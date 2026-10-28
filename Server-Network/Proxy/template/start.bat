@echo off
title Velocity Proxy
:start
echo Starte Velocity Proxy...
java -Xms{{VECTORA_XMS}} -Xmx{{VECTORA_XMX}} -jar velocity.jar
echo Server gestoppt. Neustart in 5 Sekunden (Abbrechen mit Strg+C)...
timeout /t 5
goto start
