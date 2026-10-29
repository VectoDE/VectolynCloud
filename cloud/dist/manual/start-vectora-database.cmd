@echo off
rem Transitional launcher until VectoraCloud (stage 2) manages the database.
rem Starts the local MariaDB from <network folder>\Database\runtime; copy this file into the
rem network folder next to start-network.bat.
call "%~dp0Database\runtime\start-vectora-database.cmd"
