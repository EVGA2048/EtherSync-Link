@echo off
setlocal
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0setup-arclight.ps1" %*
exit /b %ERRORLEVEL%
