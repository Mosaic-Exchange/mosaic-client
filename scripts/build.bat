@echo off
setlocal enabledelayedexpansion

:: Build the Mosaic client fat JAR for the host OS, remove any previous jar from target/,
:: and add a demo mosaic.yml in target/ when none exists yet.

:: ROOT is the parent directory of the script directory
set "SCRIPT_DIR=%~dp0"
set "ROOT=%SCRIPT_DIR%.."
cd /d "%ROOT%"

set "MAVEN=mvn"
if defined MAVEN_CMD set "MAVEN=%MAVEN_CMD%"

set "MVN_EXTRA="
if "%QUIET%"=="1" set "MVN_EXTRA=-q"

set "DEBUG_MODE=0"
set "JAVAFX_PLATFORM="

:parse_args
if "%~1"=="" goto args_done
if "%~1"=="--debug" (
    set "DEBUG_MODE=1"
    set "MVN_EXTRA=!MVN_EXTRA! -Ddebug=true"
    shift
    goto parse_args
)
if "!JAVAFX_PLATFORM!"=="" (
    echo %1 | findstr /R "^linux$ ^linux-aarch64$ ^win$ ^win-x86$ ^mac$ ^mac-aarch64$" >nul
    if !errorlevel! == 0 (
        set "JAVAFX_PLATFORM=%~1"
        shift
        goto parse_args
    )
)
:: Remaining arguments are passed to maven
set "REMAINING_ARGS=%*"
goto args_done

:args_done

if "%DEBUG%"=="1" (
    set "DEBUG_MODE=1"
    set "MVN_EXTRA=!MVN_EXTRA! -Ddebug=true"
)

if "!JAVAFX_PLATFORM!"=="" (
    :: Default to win for batch files, as they are primarily for Windows
    :: But we can try to be more specific if possible.
    :: For Windows, usually it's 'win' (x64) or 'win-x86'
    if "%PROCESSOR_ARCHITECTURE%"=="AMD64" (
        set "JAVAFX_PLATFORM=win"
    ) else if "%PROCESSOR_ARCHITEW6432%"=="AMD64" (
        set "JAVAFX_PLATFORM=win"
    ) else (
        set "JAVAFX_PLATFORM=win-x86"
    )
)

echo build.bat: removing previous packaged jar (if any)...
if exist "target\mosaic-client-1.0-SNAPSHOT.jar" del "target\mosaic-client-1.0-SNAPSHOT.jar"
if exist "target\mosaic-client-1.0-SNAPSHOT-shaded.jar" del "target\mosaic-client-1.0-SNAPSHOT-shaded.jar"

echo build.bat: building for JavaFX platform: %JAVAFX_PLATFORM%

if exist "exchange-server\pom.xml" (
    echo build.bat: installing org.rumor:rumor (exchange-server) into local Maven repo...
    call "%MAVEN%" %MVN_EXTRA% -f "exchange-server\pom.xml" install -DskipTests %REMAINING_ARGS%
    if !errorlevel! neq 0 exit /b !errorlevel!
)

echo build.bat: packaging mosaic-client...
call "%MAVEN%" %MVN_EXTRA% -f "pom.xml" package -DskipTests -Djavafx.platform=%JAVAFX_PLATFORM% %REMAINING_ARGS%
if !errorlevel! neq 0 exit /b !errorlevel!

if not exist "target\mosaic-client-1.0-SNAPSHOT.jar" (
    echo build.bat: expected jar was not produced: target\mosaic-client-1.0-SNAPSHOT.jar >&2
    exit /b 1
)

set "DEMO_YAML=target\mosaic.yml"
if not exist "%DEMO_YAML%" (
    if not exist "target" mkdir "target"
    (
        echo # Mosaic configuration
        echo # Lives next to the runnable jar ^(or project root in dev^).
        echo.
        echo # Network port this node listens on
        echo port: 7000
        echo.
        echo # Node type: basic ^| seed ^| eviction ^| master
        echo node-type: basic
        echo.
        echo # Seed node to bootstrap into the cluster ^(host:port^)
        echo # Leave empty to start as a standalone node.
        echo seed:
        echo.
        echo # Debug snapshot file ^(written periodically while running^)
        echo debug-file: mosaic-debug.txt
        echo.
        echo # Set to true to enable periodic debug snapshots
        echo debug-enabled: false
    ) > "%DEMO_YAML%"
    echo build.bat: wrote demo %DEMO_YAML%
) else (
    echo build.bat: kept existing %DEMO_YAML%
)

echo build.bat: done — target\mosaic-client-1.0-SNAPSHOT.jar
endlocal
