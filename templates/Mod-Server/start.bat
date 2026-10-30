@echo off
title Mod Server (Fabric)
:start
echo Starte Fabric Mod-Server...
java -Xms{{VECTOLYN_XMS}} -Xmx{{VECTOLYN_XMX}} -jar fabric-server.jar --nogui
echo Server gestoppt. Neustart in 5 Sekunden (Abbrechen mit Strg+C)...
timeout /t 5
goto start
