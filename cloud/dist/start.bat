@echo off
rem Starts VectoraCloud on Windows (Command Prompt).
rem
rem   start.bat [launcher arguments]     for example: start.bat --non-interactive --setup-file setup.yml
rem
rem Environment:
rem   VECTORA_HOME    cloud home and working directory (default: the folder of this script)
rem   VECTORA_JAR     path of VectoraCloud.jar (default: next to this script)
rem   VECTORA_XMX     maximum heap of the cloud itself, e.g. 512M or 1G (default: 512M)
rem   JAVA_HOME       Java installation to use; otherwise java.exe from the PATH
rem   JDK_JAVA_OPTIONS further JVM options, read by the java launcher itself (Java 9 and later)
rem
rem Every argument is passed to VectoraCloud unchanged (--allow-root, --setup-file, --lang, ...).
rem The Java version (21 or later) is checked by VectoraCloud.jar itself. Exit codes: see
rem docs/cloud/operations.md. Do not use "Run as administrator": VectoraCloud refuses an elevated
rem start unless --allow-root is given.
rem
rem Quoting: values from the environment are read with delayed expansion and stripped of quotes,
rem so characters such as & | < > ( ) in them are never run as commands. The script's own folder
rem and the java.exe found on the PATH are read before delayed expansion is switched on, so a "!"
rem in those paths is kept. The arguments are passed on with delayed expansion off.

setlocal EnableExtensions DisableDelayedExpansion
set "VC_DIR=%~dp0"
set "VC_PATHJAVA="
for %%J in (java.exe) do set "VC_PATHJAVA=%%~$PATH:J"

setlocal EnableDelayedExpansion
if "!VC_DIR:~-1!"=="\" set "VC_DIR=!VC_DIR:~0,-1!"

rem --- VectoraCloud.jar
set "VC_JAR=!VC_DIR!\VectoraCloud.jar"
if defined VECTORA_JAR set "VC_JAR=!VECTORA_JAR:"=!"
rem A relative VECTORA_JAR is meant from the current folder; make it absolute before the cd below.
if defined VECTORA_JAR for %%F in ("!VC_JAR!") do set "VC_JAR=%%~fF"
if not exist "!VC_JAR!" (
    >&2 echo VectoraCloud: VectoraCloud.jar not found: !VC_JAR!
    >&2 echo VectoraCloud: VectoraCloud.jar nicht gefunden: !VC_JAR!
    exit /b 66
)

rem --- Heap
set "VC_XMX=512M"
if defined VECTORA_XMX set "VC_XMX=!VECTORA_XMX:"=!"
set "VC_NUM=!VC_XMX!"
set "VC_LAST=!VC_NUM:~-1!"
for %%U in (k K m M g G) do if "!VC_LAST!"=="%%U" set "VC_NUM=!VC_NUM:~0,-1!"
set "VC_BAD="
if not defined VC_NUM set "VC_BAD=1"
if defined VC_NUM for /f "delims=0123456789" %%D in ("!VC_NUM!") do set "VC_BAD=1"
if defined VC_BAD (
    >&2 echo VectoraCloud: VECTORA_XMX must be a size such as 512M or 2G, not: !VC_XMX!
    >&2 echo VectoraCloud: VECTORA_XMX muss eine Groesse wie 512M oder 2G sein, nicht: !VC_XMX!
    exit /b 64
)

rem --- Java: JAVA_HOME first, then the PATH
set "VC_JAVA="
if defined JAVA_HOME (
    set "VC_JH=!JAVA_HOME:"=!"
    if "!VC_JH:~-1!"=="\" set "VC_JH=!VC_JH:~0,-1!"
    if exist "!VC_JH!\bin\java.exe" (
        set "VC_JAVA=!VC_JH!\bin\java.exe"
    ) else (
        >&2 echo VectoraCloud: JAVA_HOME [!VC_JH!] has no bin\java.exe, trying java.exe from the PATH.
        >&2 echo VectoraCloud: JAVA_HOME [!VC_JH!] enthaelt kein bin\java.exe, versuche java.exe aus dem PATH.
    )
)
if not defined VC_JAVA set "VC_JAVA=!VC_PATHJAVA!"
if not defined VC_JAVA (
    >&2 echo VectoraCloud: No Java found. Install Java 21 or later, e.g. https://adoptium.net/temurin/releases/ and put it on the PATH or set JAVA_HOME.
    >&2 echo VectoraCloud: Kein Java gefunden. Installiere Java 21 oder neuer, z. B. https://adoptium.net/de/temurin/releases/ und nimm es in den PATH auf oder setze JAVA_HOME.
    exit /b 3
)

rem --- Home
set "VC_HOME=!VC_DIR!"
if defined VECTORA_HOME set "VC_HOME=!VECTORA_HOME:"=!"
cd /d "!VC_HOME!" 2>nul || (
    >&2 echo VectoraCloud: Cannot change to the cloud home: !VC_HOME!
    >&2 echo VectoraCloud: Kann nicht in das Cloud-Home wechseln: !VC_HOME!
    exit /b 66
)

rem --- Start. Delayed expansion off, so a "!" in an argument stays as typed.
rem UTF-8 for the console and for what Java writes to it (non-ASCII characters in the console output).
chcp 65001 >nul
setlocal DisableDelayedExpansion
"%VC_JAVA%" "-Xmx%VC_XMX%" -Dstdout.encoding=UTF-8 -Dstderr.encoding=UTF-8 -jar "%VC_JAR%" %*
exit /b %ERRORLEVEL%
