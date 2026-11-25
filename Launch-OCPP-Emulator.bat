@echo off
setlocal
powershell.exe -NoLogo -NoProfile -ExecutionPolicy Bypass -File "%~dp0Launch-OCPP-Emulator.ps1" %*
set "launcher_exit=%ERRORLEVEL%"
if not "%launcher_exit%"=="0" pause
exit /b %launcher_exit%
