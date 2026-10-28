@echo off
title Vectora Netzwerk Steuerzentrum
chcp 65001 > nul
rem Transitional launcher until VectoraCloud (stage 2) takes over.
rem Every server runs from Server-Network\<Server>\runtime.
set "SN=%~dp0"
cls

:menu
echo ===================================================
echo       VECTORA NETZWERK STEUERZENTRUM
echo ===================================================
echo.
echo  [1] Alles starten (Datenbank + Proxy + Backends)
echo  [2] Nur Datenbank starten
echo  [3] Nur Proxy starten
echo  [4] Nur Backend-Server starten (Lobby, Survival, Mod, Creative, End)
echo  [5] Beenden (Fenster schliessen)
echo.
echo ===================================================
set /p choice="Bitte wähle eine Option (1-5): "

if "%choice%"=="1" goto start_all
if "%choice%"=="2" goto start_db
if "%choice%"=="3" goto start_proxy
if "%choice%"=="4" goto start_backend
if "%choice%"=="5" exit
goto menu

:start_all
call :db
echo Warte 5 Sekunden auf die Datenbank...
timeout /t 5 > nul
call :proxy
call :backend
echo.
echo [OK] Datenbank und alle 6 Server-Prozesse wurden in eigenen Fenstern gestartet.
pause
goto menu

:start_db
call :db
echo [OK] Datenbank gestartet.
pause
goto menu

:start_proxy
call :proxy
echo [OK] Proxy gestartet.
pause
goto menu

:start_backend
call :backend
echo [OK] Alle Backend-Server gestartet.
pause
goto menu

:db
echo Starte Datenbank...
start "Vectora MariaDB" /D "%SN%Database\runtime" cmd /c start-vectora-database.cmd
exit /b 0

:proxy
echo Starte Proxy...
start "Proxy (Velocity)" /D "%SN%Proxy\runtime" start.bat
exit /b 0

:backend
echo Starte Backend-Server...
start "Lobby-Server" /D "%SN%Lobby-Server\runtime" start.bat
start "Survival-Server" /D "%SN%Survival-Server\runtime" start.bat
start "Mod-Server" /D "%SN%Mod-Server\runtime" start.bat
start "Creative-Server" /D "%SN%Creative-Server\runtime" start.bat
start "End-Server" /D "%SN%End-Server\runtime" start.bat
exit /b 0
