#!/bin/bash
# DSHA_ADB_SCRIPT_VERSION=20
# 确认只由 Python 入口处理；直调脚本与包装器共用一次确认及真实退出码。
exec python3 /root/.dsh/adb-shell.py "$@"
