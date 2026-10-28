@echo off
setlocal
title Vectora MariaDB - close this window to stop the database
rem Runs from Database\runtime. basedir and datadir are resolved relative to this script.
set "DB=%~dp0"
set "DB=%DB:~0,-1%"
if not exist "%DB%\mariadb-11.8.9-winx64\bin\mariadbd.exe" (
  echo MariaDB was not found in "%DB%\mariadb-11.8.9-winx64".
  echo Unpack mariadb-11.8.9-winx64 there first.
  pause
  exit /b 1
)
cd /d "%DB%"
echo Starting Vectora MariaDB on 127.0.0.1:{{VECTORA_DB_PORT}}...
echo Closing this window stops the local rank database.
"%DB%\mariadb-11.8.9-winx64\bin\mariadbd.exe" --defaults-file="%DB%\vectora-mariadb.ini" --basedir="%DB%\mariadb-11.8.9-winx64" --datadir="%DB%\data" --console
endlocal
