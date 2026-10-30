@echo off
title Velocity Legacy Proxy
:start
echo Starte Velocity Legacy Proxy...
java -Xms{{VECTOLYN_XMS}} -Xmx{{VECTOLYN_XMX}} -jar velocity.jar
echo Server gestoppt. Neustart in 5 Sekunden (Abbrechen mit Strg+C)...
timeout /t 5
goto start
