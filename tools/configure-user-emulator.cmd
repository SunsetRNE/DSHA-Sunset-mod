@echo off
setlocal
set "ROOT=%~dp0.."
rem 默认 mod 身份的模拟器包名；用 -Pdsha.identity=upstream 构建时请改成 dsh.android.client.emulator
set "PKG=io.github.sunsetrne.dsha.emulator"
set "ADB=%ROOT%\.android-sdk\platform-tools\adb.exe"
set "ANDROID_ADB_SERVER_PORT=5041"
"%ADB%" -P 5041 wait-for-device
"%ADB%" -P 5041 -s emulator-5580 shell settings put system system_locales zh-CN
"%ADB%" -P 5041 -s emulator-5580 shell wm size 1080x1920
"%ADB%" -P 5041 -s emulator-5580 shell wm density 420
"%ADB%" -P 5041 -s emulator-5580 shell settings put system peak_refresh_rate 120.0
"%ADB%" -P 5041 -s emulator-5580 shell settings put system min_refresh_rate 120.0
"%ADB%" -P 5041 -s emulator-5580 shell settings put system user_rotation 0
"%ADB%" -P 5041 -s emulator-5580 shell am force-stop %PKG%
"%ADB%" -P 5041 -s emulator-5580 shell am start -n %PKG%/com.deepseekharness.app.ui.MainActivity
"%ADB%" -P 5041 -s emulator-5580 shell wm size
"%ADB%" -P 5041 -s emulator-5580 shell settings get system system_locales
"%ADB%" -P 5041 -s emulator-5580 shell settings get system peak_refresh_rate
