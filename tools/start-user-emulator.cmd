@echo off
setlocal
set "ROOT=%~dp0.."
set "ANDROID_HOME=%ROOT%\.android-sdk"
set "ANDROID_SDK_ROOT=%ROOT%\.android-sdk"
set "ANDROID_AVD_HOME=%ROOT%\.android-avd"
set "ANDROID_USER_HOME=%ROOT%\.android-user"
set "ANDROID_EMULATOR_HOME=%ROOT%\.android-user"
set "ANDROID_ADB_SERVER_PORT=5041"
set "TEMP=%ROOT%\build\verify\delivery-audit\android-tmp"
set "TMP=%TEMP%"
"%ANDROID_HOME%\emulator\emulator.exe" -avd dsha_test -port 5580 -no-audio -no-snapshot -gpu host -vsync-rate 120
