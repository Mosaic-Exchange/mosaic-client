@echo off
setlocal enabledelayedexpansion

:: Run the packaged fat JAR from target/ (build first with scripts/build.bat).

set "SCRIPT_DIR=%~dp0"
set "ROOT=%SCRIPT_DIR%.."
set "JAR=%ROOT%\target\mosaic-client-1.0-SNAPSHOT.jar"

set "JAVA_OPTS="
if "%~1"=="--debug" (
    set "JAVA_OPTS=-agentlib:jdwp=transport=dt_socket,server=y,suspend=y,address=*:5005"
    shift
)

if not exist "%JAR%" (
    echo run.bat: jar not found: %JAR% >&2
    echo run.bat: run %ROOT%\scripts\build.bat first. >&2
    exit /b 1
)

cd /d "%ROOT%"
java %JAVA_OPTS% -jar "%JAR%" %*
endlocal
