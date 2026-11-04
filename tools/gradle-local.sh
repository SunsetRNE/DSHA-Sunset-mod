#!/usr/bin/env bash
# 只设置当前构建进程，不修改用户或系统环境。
set -euo pipefail
project_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
export DSHA_PYTHON="${DSHA_PYTHON:-C:/Users/Administrator/AppData/Local/Programs/Python/Python314/python.exe}"
if [[ ! -f "$DSHA_PYTHON" ]]; then
  printf '%s\n' 'Python executable missing. Set DSHA_PYTHON to an installed Python executable.' >&2
  exit 1
fi
cd -- "$project_dir"
exec ./gradlew.bat "$@"
