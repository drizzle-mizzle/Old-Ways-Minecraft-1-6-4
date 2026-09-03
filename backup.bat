@echo off
cd /d "%~dp0"
set STAMP=%date:~-4%%date:~3,2%%date:~0,2%-%time:~0,2%%time:~3,2%
set STAMP=%STAMP: =0%
if not exist backups mkdir backups
powershell -NoProfile -Command "Compress-Archive -Path world,world_nether,world_the_end,plugins,server.properties,ops.txt,white-list.txt -DestinationPath backups\srv-%STAMP%.zip -Force"
echo Backup: backups\srv-%STAMP%.zip
pause
