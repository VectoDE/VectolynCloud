@echo off
rem Transitional launcher until VectoraCloud (stage 2) manages the database.
rem Starts the local MariaDB from Server-Network\Database\runtime.
call "%~dp0Database\runtime\start-vectora-database.cmd"
