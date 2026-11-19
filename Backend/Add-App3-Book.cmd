@echo off
powershell.exe -NoLogo -NoProfile -ExecutionPolicy Bypass -File "%~dp0Add-App3-Book.ps1" %*
exit /b %ERRORLEVEL%
