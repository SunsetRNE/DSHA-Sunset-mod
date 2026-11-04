@echo off
setlocal
where bash >nul 2>nul
if errorlevel 1 (
  >&2 echo Git Bash is required for this local build launcher.
  exit /b 1
)
bash "%~dp0gradle-local.sh" %*
exit /b %ERRORLEVEL%
