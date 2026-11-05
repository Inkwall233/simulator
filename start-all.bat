@echo off
rem SkillLab 本地一键启动（需先配置根目录 .env.ai）：透传参数给 start-all.ps1
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0start-all.ps1" %*
echo.
pause
