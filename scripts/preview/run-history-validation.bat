@echo off
setlocal EnableExtensions
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0launch-cubism-history-validation.ps1" %*
exit /b %ERRORLEVEL%
