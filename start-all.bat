@echo off
rem SkillLab 一键启动（双击运行）：透传所有参数给 start-all.ps1
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0start-all.ps1" %*
echo.
pause
