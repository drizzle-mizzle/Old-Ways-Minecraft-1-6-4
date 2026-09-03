@echo off
title MC 1.6.4 - Towny + Heroes
cd /d "%~dp0"
.\jre8\bin\java.exe -Xms2G -Xmx4G -XX:+UseConcMarkSweepGC -XX:+UseParNewGC -jar spigot-1.6.4-R2.1.jar nogui
echo.
echo Server stopped.
pause
