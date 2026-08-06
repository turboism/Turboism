@echo off
setlocal EnableExtensions

rem Turboism 非侵入启动器（双击运行；等价 scripts/preview/launch-cubism-turboism.bat）
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0launch-cubism-turboism.ps1" %*
set "EXIT_CODE=%ERRORLEVEL%"

if not "%EXIT_CODE%"=="0" (
  echo.
  echo [Turboism] Launch failed with exit code %EXIT_CODE%.
  echo [Turboism] Check the message above and the logs\runtime\YYYY-MM-DD\turboism-*.log if it exists.
  pause
)

exit /b %EXIT_CODE%
