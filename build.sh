#!/usr/bin/env bash
# DSHA（重构骨架）构建脚本：统一 JDK 17 + Gradle 8.5 + SDK 路径，避免各机器环境漂移。
# 用法：./build.sh :app:testDebugUnitTest   /   ./build.sh :app:assembleDebug
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$ROOT"

TOOLS="F:/DSHA/_toolchains"

export JAVA_HOME="${JAVA_HOME:-${TOOLS}/jdk-17}"
export GRADLE_USER_HOME="${GRADLE_USER_HOME:-${TOOLS}/gradle-user-home}"
export ANDROID_SDK_ROOT="${ANDROID_SDK_ROOT:-${TOOLS}/android-sdk}"
export ANDROID_HOME="${ANDROID_HOME:-${TOOLS}/android-sdk}"

GRADLE_BIN="${GRADLE_BIN:-${TOOLS}/gradle-8.5/bin/gradle}"

echo "==> JAVA_HOME=${JAVA_HOME}"
echo "==> gradle : $(command -v java >/dev/null && "$JAVA_HOME/bin/java" -version 2>&1 | head -1)"

exec "$GRADLE_BIN" "$@"
