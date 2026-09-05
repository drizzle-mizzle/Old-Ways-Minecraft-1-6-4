@echo off
rem  Old Ways: build the launcher exe and drop it into this folder.
rem  Only ASCII here on purpose -- cmd.exe is picky about encodings;
rem  everything worth reading is printed by tools/publish_exe.py.
chcp 65001 >nul
setlocal

set "REPO=%OW_REPO%"
if "%REPO%"=="" set "REPO=C:\Users\flower\Desktop\Server"

if not exist "%REPO%\tools\publish_exe.py" (
    echo Repository not found: %REPO%
    echo Set OW_REPO to the project folder and run this again.
    echo.
    pause
    exit /b 1
)

set "PY="
where py >nul 2>nul && set "PY=py -3"
if not defined PY where python >nul 2>nul && set "PY=python"
if not defined PY (
    echo Python 3 not found in PATH -- install it from https://python.org
    echo.
    pause
    exit /b 1
)

%PY% "%REPO%\tools\publish_exe.py" --to "%~dp0."
set "CODE=%ERRORLEVEL%"

echo.
pause
exit /b %CODE%
