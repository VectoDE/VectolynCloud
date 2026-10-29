@echo off
title Velocity Legacy Proxy
:start
echo Starte Velocity Legacy Proxy...
java -Xms{{VECTORA_XMS}} -Xmx{{VECTORA_XMX}} -jar velocity.jar
echo Server gestoppt. Neustart in 5 Sekunden (Abbrechen mit Strg+C)...
timeout /t 5
goto start
