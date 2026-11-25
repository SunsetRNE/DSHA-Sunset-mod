#!/usr/bin/env bash
set -euo pipefail
role=${1:?npm input role required}
[[ "$role" == a || "$role" == b ]]
src=/mnt/f/DSHA_RESTART/source/build377-local
raw=/root/dsha-build374-runtime/npm-arm64-$role
round=${2:?attempt required}
[[ "$round" =~ ^r[0-9]+$ ]]
out=/mnt/f/DSHA_RESTART/artifacts/build377-local/runtime/$round/npm-arm64-$role
[[ -d "$out" && ! -e "$out/dsh-runtime.bin" ]]
for file in package.json package-lock.json; do
    cmp -- "$raw/$file" "$src/tools/dsh-runtime/$file"
done
printf 'start=%s\n' "$(date --iso-8601=ns)" > "$out/build-timing.txt"
python3 -B "$src/tools/build-dsh-runtime.py" --source "$raw/node_modules" --output "$out/dsh-runtime.bin" > "$out/build.json" 2> "$out/build.stderr.txt"
printf 'completed=%s\n' "$(date --iso-8601=ns)" >> "$out/build-timing.txt"
