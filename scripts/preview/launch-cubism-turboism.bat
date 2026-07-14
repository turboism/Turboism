@echo off
setlocal EnableExtensions

powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0launch-cubism-turboism.ps1" %*
set "EXIT_CODE=%ERRORLEVEL%"

if not "%EXIT_CODE%"=="0" (
  echo.
  echo [Turboism] Launch failed with exit code %EXIT_CODE%.
  echo [Turboism] Check the message above and logs\turboism.log if it exists.
  pause
)

exit /b %EXIT_CODE%
