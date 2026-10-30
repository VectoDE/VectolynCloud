@echo off
title Legacy Server (PandaSpigot 1.8.8)
:start
echo Starte Legacy Server...
java -Xms{{VECTOLYN_XMS}} -Xmx{{VECTOLYN_XMX}} -jar pandaspigot.jar nogui
echo Server gestoppt. Neustart in 5 Sekunden (Abbrechen mit Strg+C)...
timeout /t 5
goto start
