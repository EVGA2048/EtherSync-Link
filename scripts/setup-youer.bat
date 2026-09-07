@echo off
setlocal
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0setup-youer.ps1" %*
exit /b %ERRORLEVEL%
