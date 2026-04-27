@echo off
setlocal enabledelayedexpansion

:: Build and run the Mosaic client using javafx:run, avoiding fat JAR packaging.
:: Ensures dependencies are compiled and available.

set "SCRIPT_DIR=%~dp0"
set "ROOT=%SCRIPT_DIR%.."
cd /d "%ROOT%"

set "MAVEN=mvn"
if defined MAVEN_CMD set "MAVEN=%MAVEN_CMD%"

set "MVN_EXTRA="
if "%QUIET%"=="1" set "MVN_EXTRA=-q"

set "GOAL=javafx:run"

:parse_args
if "%~1"=="" goto args_done
if "%~1"=="--debug" (
    set "GOAL=javafx:run@debug"
    shift
    goto parse_args
)
if "%~1"=="--frontend" (
    set "GOAL=javafx:run@run-frontend"
    shift
    goto parse_args
)
if "%~1"=="--config" (
    if "%~2"=="" (
        echo Error: --config requires a file path
        exit /b 1
    )
    set "CONFIG_PATH=%~2"
    :: Check if path is absolute (rough check for Windows)
    echo !CONFIG_PATH! | findstr /R "^[a-zA-Z]:\\" >nul
    if !errorlevel! neq 0 (
        set "CONFIG_PATH=%CD%\!CONFIG_PATH!"
    )
    set "MVN_EXTRA=!MVN_EXTRA! "-Djavafx.args=--config !CONFIG_PATH!""
    shift
    shift
    goto parse_args
)

:: Remaining arguments are passed to maven
set "REMAINING_ARGS=%*"
goto args_done

:args_done

echo run_simple.bat: ensuring dependencies are ready...

if exist "exchange-server\pom.xml" (
    echo run_simple.bat: installing org.rumor:rumor (exchange-server) into local Maven repo...
    call "%MAVEN%" %MVN_EXTRA% -f "exchange-server\pom.xml" install -DskipTests
    if !errorlevel! neq 0 exit /b !errorlevel!
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
    echo run_simple.bat: wrote demo %DEMO_YAML%
)

echo run_simple.bat: executing %GOAL%...
call "%MAVEN%" %MVN_EXTRA% -f test-pom.xml compile %GOAL% %REMAINING_ARGS%
endlocal
