#!/bin/bash
# Managed gh entrypoint for the DSHA proroot runtime.
# The CLI uses raw syscalls, so host-visible config, CA, and a libc CONNECT
# proxy must be supplied explicitly. Login still uses gh auth login --web;
# credentials remain in the gh hosts.yml until native credential storage lands.
set -u

ROOT_HOST="${DSHA_GH_HOST_ROOT:-}"
REAL_GH="${DSHA_GH_BIN:-/opt/gh/bin/gh}"
PROXY_JS="${DSHA_GH_PROXY_SCRIPT:-/usr/local/share/dsha/gh-proxy.mjs}"
STATE_DIR="${DSHA_GH_PROXY_STATE_DIR:-/tmp/dsha-gh-proxy}"
REQUESTED_PORT="${DSHA_GH_PROXY_PORT:-7891}"
PID_FILE="$STATE_DIR/pid"
PORT_FILE="$STATE_DIR/port"
LOG_FILE="$STATE_DIR/proxy.log"
LOCK_DIR="$STATE_DIR/lock"

if [ ! -x "$REAL_GH" ]; then
    echo "gh: managed GitHub CLI is unavailable ($REAL_GH); install the bundled gh binary first" >&2
    exit 127
fi

if [ "${DSHA_GH_IDENTITY_NOTICE:-1}" = "1" ]; then
    echo "gh: requests use the SunsetRNE GitHub identity configured in this DSHA environment" >&2
fi

proxy_alive() {
    [ -s "$PID_FILE" ] || return 1
    [ -s "$PORT_FILE" ] || return 1
    local pid port
    pid="$(tr -dc '0-9' < "$PID_FILE")"
    port="$(tr -dc '0-9' < "$PORT_FILE")"
    [ -n "$pid" ] && [ -n "$port" ] || return 1
    kill -0 "$pid" 2>/dev/null || return 1
    if [ -r "/proc/$pid/cmdline" ]; then
        case "$(tr '\0' ' ' < "/proc/$pid/cmdline" 2>/dev/null)" in
            *gh-proxy.mjs*) ;;
            *) return 1 ;;
        esac
    fi
    (exec 3<>"/dev/tcp/127.0.0.1/$port") 2>/dev/null
}

release_lock() {
    rm -f "$LOCK_DIR/owner" 2>/dev/null || true
    rmdir "$LOCK_DIR" 2>/dev/null || true
}

acquire_lock() {
    mkdir -p "$STATE_DIR" 2>/dev/null || return 1
    local i=0 owner
    while ! mkdir "$LOCK_DIR" 2>/dev/null; do
        owner=""
        [ -s "$LOCK_DIR/owner" ] && owner="$(tr -dc '0-9' < "$LOCK_DIR/owner")"
        if [ -z "$owner" ]; then
            # mkdir is the atomic lock; give the owner a short window to publish
            # its PID before deciding that an interrupted writer left a stale lock.
            i=$((i + 1))
            [ "$i" -lt 100 ] || { rmdir "$LOCK_DIR" 2>/dev/null || true; return 1; }
            sleep 0.05
            continue
        fi
        if ! kill -0 "$owner" 2>/dev/null; then
            rm -f "$LOCK_DIR/owner" 2>/dev/null || true
            rmdir "$LOCK_DIR" 2>/dev/null || true
            continue
        fi
        i=$((i + 1))
        [ "$i" -lt 100 ] || return 1
        sleep 0.05
    done
    printf '%s\n' "$$" >"$LOCK_DIR/owner"
    return 0
}

ensure_proxy() {
    proxy_alive && return 0
    acquire_lock || return 1
    if proxy_alive; then
        release_lock
        return 0
    fi
    rm -f "$PORT_FILE" "$PID_FILE"
    if ! command -v node >/dev/null 2>&1; then
        release_lock
        return 1
    fi
    nohup node "$PROXY_JS" --port "$REQUESTED_PORT" --state-dir "$STATE_DIR" \
        >>"$LOG_FILE" 2>&1 </dev/null &
    local pid=$!
    printf '%s\n' "$pid" >"$PID_FILE"
    local i=0
    while [ "$i" -lt 100 ]; do
        if proxy_alive; then
            release_lock
            return 0
        fi
        if ! kill -0 "$pid" 2>/dev/null; then
            break
        fi
        sleep 0.05
        i=$((i + 1))
    done
    release_lock
    return 1
}

if ensure_proxy; then
    PROXY_PORT="$(tr -dc '0-9' < "$PORT_FILE")"
    export HTTPS_PROXY="http://127.0.0.1:${PROXY_PORT}"
    export HTTP_PROXY="http://127.0.0.1:${PROXY_PORT}"
    export https_proxy="$HTTPS_PROXY"
    export http_proxy="$HTTP_PROXY"
    export NO_PROXY="${NO_PROXY:-localhost,127.0.0.1}"
    export no_proxy="$NO_PROXY"
else
    echo "gh: local GitHub proxy could not start; see $LOG_FILE" >&2
fi

if [ -z "${GH_CONFIG_DIR:-}" ]; then
    if [ -n "$ROOT_HOST" ]; then
        export GH_CONFIG_DIR="$ROOT_HOST/root/.config/gh"
    else
        export GH_CONFIG_DIR="/root/.config/gh"
    fi
elif [ -n "$ROOT_HOST" ] && [ "$GH_CONFIG_DIR" = "/root/.config/gh" ]; then
    export GH_CONFIG_DIR="$ROOT_HOST/root/.config/gh"
fi

if [ -n "$ROOT_HOST" ]; then
    CA_FILE="${DSHA_GH_CA:-$ROOT_HOST/usr/local/share/dsha/ca-certificates.crt}"
    if [ ! -f "$CA_FILE" ]; then
        CA_FILE="$ROOT_HOST/etc/ssl/certs/ca-certificates.crt"
    fi
    [ -f "$CA_FILE" ] && export SSL_CERT_FILE="$CA_FILE"
fi

host_path() {
    local p="$1"
    [ -n "$ROOT_HOST" ] || { printf '%s' "$p"; return; }
    case "$p" in
        "$ROOT_HOST"/*) printf '%s' "$p"; return ;;
        /*) p="$(realpath -m -- "$p")" ;;
        *) p="$(realpath -m -- "$PWD/$p")" ;;
    esac
    printf '%s%s' "$ROOT_HOST" "$p"
}

rewrite_arg() {
    local a="$1"
    [ -n "$ROOT_HOST" ] || { printf '%s' "$a"; return; }
    case "$a" in
        "$ROOT_HOST"/*|*://*|-*|'') printf '%s' "$a"; return ;;
        *=@*)
            local k="${a%%=@*}" f="${a#*=@}"
            if [ -f "$f" ]; then printf '%s=@%s' "$k" "$(host_path "$f")"; return; fi
            ;;
        @*)
            local f="${a#@}"
            if [ -f "$f" ]; then printf '@%s' "$(host_path "$f")"; return; fi
            ;;
    esac
    local base="${a%%#*}" label=""
    [ "$base" != "$a" ] && label="#${a#*#}"
    if [ -f "$base" ]; then printf '%s%s' "$(host_path "$base")" "$label"; return; fi
    printf '%s' "$a"
}

if [ "${DSHA_GH_RAW:-0}" != "1" ]; then
    args=()
    has_repo=0
    for a in "$@"; do
        case "$a" in -R|--repo|--repo=*|-R?*) has_repo=1 ;; esac
        args+=("$(rewrite_arg "$a")")
    done
    case "${1:-}" in
        pr)
            if [ "${2:-}" = "checkout" ]; then
                echo "gh: pr checkout needs git, which cannot run through this proroot adapter; use the host git checkout flow" >&2
                exit 2
            fi
            ;;
        repo)
            if [ "${2:-}" = "clone" ]; then
                echo "gh: repo clone needs git, which cannot run through this proroot adapter; use git clone from the host-visible workspace" >&2
                exit 2
            fi
            ;;
        release|run|issue|workflow|cache|secret|variable|label|ruleset|attestation|browse)
            if [ "$has_repo" = 0 ] && [ -z "${GH_REPO:-}" ] && command -v git >/dev/null 2>&1; then
                url="$(git remote get-url origin 2>/dev/null || true)"
                slug="$(printf '%s' "$url" | sed -nE 's#^(git@github\.com:|https://([^@/]+@)?github\.com/)([^/]+/[^/]+)/?$#\3#p' | sed -E 's#\.git$##')"
                [ -n "$slug" ] && export GH_REPO="$slug"
            fi
            ;;
    esac
    exec "$REAL_GH" "${args[@]}"
fi

exec "$REAL_GH" "$@"
