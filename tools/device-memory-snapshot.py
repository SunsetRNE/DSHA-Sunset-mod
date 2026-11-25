#!/usr/bin/env python3
"""Read one DSHA app, Node web runtime and bound WebView renderer memory snapshot via ADB."""

import argparse
from dataclasses import dataclass
from datetime import datetime, timezone
import json
import re
import subprocess
import time


PACKAGE = "dsh.android.client"
ENTRY = "/usr/local/lib/node_modules/@deepseek-ai/dsh/lib/bin.js"
NODE_EXECUTABLES = {"node", "nodejs"}
MAX_EXIT_RECORDS = 8


@dataclass(frozen=True)
class Process:
    pid: int
    uid: int
    name: str
    args: tuple[str, ...]


def processes(raw):
    for line in raw.splitlines():
        fields = line.split(maxsplit=3)
        if len(fields) >= 3 and fields[0].isdigit() and fields[1].isdigit():
            args = tuple(fields[3].split()) if len(fields) == 4 else ()
            yield Process(int(fields[0]), int(fields[1]), fields[2], args)


def is_web_node(process):
    args = process.args
    if process.name == "libproroot-bridge.so":
        names = tuple(field.rsplit("/", 1)[-1] for field in args)
        if names[:2] != ("libproroot-bridge.so", "libproroot-linker.so"):
            return False
        index = 4 if names[2:4] == ("--preload", "libdsha-proroot-compat.so") else 2
        bridge = names[index:index + 5]
        if (len(bridge) != 5 or bridge[0] != "--argv0" or bridge[1] not in NODE_EXECUTABLES
                or bridge[2:4] != ("--preload", "libproroot-runtime.so")):
            return False
        index += 4
        if names[index:index + 2] == ("--preload", "libgcc_s.so.1"):
            index += 2
        args = args[index:]
    elif process.name not in NODE_EXECUTABLES:
        return False

    if not args or args[0].rsplit("/", 1)[-1] not in NODE_EXECUTABLES:
        return False
    rest = args[2:] if args[1:2] == ("--expose-internals",) else args[1:]
    return (len(rest) >= 7 and rest[0] in {ENTRY, "/usr/local/bin/dsh"}
            and rest[1:6] == ("web", "--no-open", "--host", "127.0.0.1", "--port")
            and re.fullmatch(r"[0-9]{1,5}", rest[6]) is not None and int(rest[6]) <= 65535)


def renderer_pids(services, app_pid):
    package = re.escape(PACKAGE)
    component = rf"\bu\d+ {package}/org\.chromium\.content\.app\.SandboxedProcessService\d+(?=[:\s}}])"
    owner = rf"(?m)^\s*\* Client AppBindRecord\{{\S+ ProcessRecord\{{\S+ {app_pid}:{package}/"
    renderers = set()
    for record in re.split(r"(?m)^\s*\* ServiceRecord\{", services)[1:]:
        header = record.splitlines()[0]
        if not re.search(component, header) or not re.search(owner, record):
            continue
        match = re.search(r"(?m)^\s*app=ProcessRecord\{\S+ ([1-9]\d*):[^\s/]+:sandboxed_process", record)
        if match:
            renderers.add(int(match[1]))
    return renderers


def start_ticks(line, pid):
    if not line.startswith(f"{pid} (") or ") " not in line:
        return None
    fields = line.rsplit(") ", 1)[1].split()
    # /proc/PID/stat starttime is field 22. The remaining fields start at field 3.
    return int(fields[19]) if len(fields) > 19 and fields[19].isdigit() else None


def summary_value(raw, label):
    match = re.search(rf"\bTOTAL {label}:\s+([\d,]+)", raw)
    return int(match[1].replace(",", "")) if match else None


def memory_for(read, process):
    pid, uid = process.pid, process.uid
    # Collect the memory table in system_server, without a dump callback in the sampled process.
    raw = read(f"""sample_uid=$(ps -p {pid} -o UID=)
[ "$sample_uid" -eq {uid} ] || exit 1
cat /proc/{pid}/stat || exit 1
dumpsys -t 4 meminfo -s --local {pid}
cat /proc/{pid}/stat""")
    row = {"pid": pid, "uid": uid, "pssKiB": None, "rssKiB": None}
    lines = raw.splitlines()
    if len(lines) < 2:
        return {**row, "status": "unavailable"}
    before = start_ticks(lines[0], pid)
    after = start_ticks(lines[-1], pid)
    if before is None or after != before:
        return {**row, "status": "process-identity-changed-or-unavailable"}
    header = rf"(?m)^\s*\*\* MEMINFO in pid {pid} \[.*?\] \*\*"
    if not re.search(header, raw):
        return {**row, "status": "meminfo-unavailable", "startTicks": before}
    pss, rss = (summary_value(raw, name) for name in ("PSS", "RSS"))
    return {**row, "status": "sampled" if pss is not None or rss is not None else "meminfo-unavailable",
            "startTicks": before, "pssKiB": pss, "rssKiB": rss}


def recent_exits(raw, uid):
    if "ACTIVITY MANAGER PROCESS EXIT INFO" not in raw:
        return {"status": "unavailable", "records": []}
    result = []
    for record in re.split(r"(?m)^\s*ApplicationExitInfo #\d+:", raw)[1:]:
        identity = re.search(r"(?m)^\s*timestamp=(\d{4}-\d\d-\d\d \d\d:\d\d:\d\d(?:\.\d+)?)"
                             r"\s+pid=(\d+)\s+realUid=(\d+)\s+packageUid=(\d+)", record)
        outcome = re.search(r"(?m)^\s*process=(\S+)\s+reason=(\d+) \(([A-Z ()_-]+)\)"
                            r"\s+subreason=(\d+) \(([A-Z ()_-]+)\)\s+status=(-?\d+)", record)
        if not identity or not outcome or int(identity[4]) != uid:
            continue
        name, reason, rationale, subreason, detail, status = outcome.groups()
        role = "app" if name == PACKAGE else "package-process"
        if ":sandboxed_process" in name:
            role = "sandboxed-renderer"
        result.append({"pid": int(identity[2]), "uid": int(identity[3]), "role": role,
                       "deviceLocalTimestamp": identity[1], "reasonCode": int(reason),
                       "reason": rationale, "subreasonCode": int(subreason),
                       "subreason": detail, "status": int(status)})
        if len(result) == MAX_EXIT_RECORDS:
            break
    return {"status": "read" if result else "no-matching-records", "records": result}


def snapshot(read, include_exits):
    package = re.escape(PACKAGE)
    matches = re.findall(rf"(?m)^package:{package} uid:(\d+)\s*$",
                         read("cmd", "package", "list", "packages", "-U", "--user", "current", PACKAGE))
    if len(matches) != 1:
        raise ValueError("Current user's DSHA package UID could not be verified.")
    uid = int(matches[0])
    owned_processes = read("ps", "-w", "-u", str(uid), "-o", "PID,UID,NAME,ARGS")
    candidates = [process for process in processes(owned_processes) if process.uid == uid]
    apps = [candidate for candidate in candidates if candidate.name == PACKAGE]
    nodes = [candidate for candidate in candidates if is_web_node(candidate)]
    renderers = []
    renderer_count = 0
    if len(apps) == 1:
        pids = renderer_pids(read("dumpsys", "-t", "4", "activity", "services", PACKAGE), apps[0].pid)
        renderer_count = len(pids)
        if renderer_count == 1:
            pid, = pids
            selection = read("ps", "-p", str(pid), "-o", "PID,UID,NAME")
            renderers = [candidate for candidate in processes(selection)
                         if candidate.pid == pid and candidate.uid != uid
                         and candidate.uid % 100000 >= 90000 and ":sandboxed_process" in candidate.name]

    result = {"package": PACKAGE, "uid": uid}
    for role, selected in (("app", apps), ("nodeWeb", nodes), ("webViewRenderer", renderers)):
        if len(selected) == 1:
            result[role] = memory_for(read, selected[0])
        else:
            result[role] = {"status": "ambiguous" if selected else "unavailable"}
    if renderer_count > 1:
        result["webViewRenderer"] = {"status": "ambiguous", "boundCount": renderer_count}
    if include_exits:
        result["exitInfo"] = recent_exits(read("dumpsys", "-t", "4", "activity", "exit-info", PACKAGE), uid)
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--adb", default="adb")
    parser.add_argument("--serial", required=True)
    parser.add_argument("--include-exits", action="store_true", help="Include bounded, package-scoped exit reasons.")
    args = parser.parse_args()

    def read(*command):
        try:
            result = subprocess.run([args.adb, "-s", args.serial, "shell", *command],
                                    capture_output=True, encoding="utf-8", errors="replace", timeout=6)
        except (OSError, subprocess.TimeoutExpired):
            return ""
        return result.stdout if result.returncode == 0 else ""

    start = time.monotonic()
    when = datetime.now(timezone.utc).isoformat()
    try:
        result = snapshot(read, args.include_exits)
    except ValueError as error:
        parser.exit(1, f"{error}\n")
    print(json.dumps({"sampledAtUtc": when, "elapsedMs": round((time.monotonic() - start) * 1000),
                      **result}, indent=2))


if __name__ == "__main__":
    main()
