@echo off
title End Server
:start
echo Starte End Server...
java -Xms{{VECTORA_XMS}} -Xmx{{VECTORA_XMX}} -jar paper.jar --nogui
echo Server gestoppt. Neustart in 5 Sekunden (Abbrechen mit Strg+C)...
timeout /t 5
goto start
