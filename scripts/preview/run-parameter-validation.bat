@echo off
setlocal EnableExtensions

powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0launch-cubism-parameter-validation.ps1" %*
set "EXIT_CODE=%ERRORLEVEL%"

if not "%EXIT_CODE%"=="0" (
  echo.
  echo [Turboism] Validation launch failed with exit code %EXIT_CODE%.
  echo [Turboism] Check state\host-validation-result.properties and logs\cubism-console.log.
)

exit /b %EXIT_CODE%
