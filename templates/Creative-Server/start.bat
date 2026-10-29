@echo off
title Creative Server
:start
echo Starte Creative Server...
java -Xms{{VECTORA_XMS}} -Xmx{{VECTORA_XMX}} -jar paper.jar --nogui
echo Server gestoppt. Neustart in 5 Sekunden (Abbrechen mit Strg+C)...
timeout /t 5
goto start
