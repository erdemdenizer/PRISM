@echo off
setlocal
cd /d "%~dp0"
title PRISM - Demo & Test Launcher

echo ============================================================
echo   PRISM - Demo Workload ^& Test Launcher
echo ============================================================
echo.
echo   [1] Start Demo Workload (CPU 50%%, RAM 256MiB, 5m) [Default]
echo       - Starts calmly; press [R] + Enter anytime to spike RAM and trigger OOM Guard
echo   [2] Start Heavy Memory Demo (CPU 25%%, RAM 1GiB, 5m)
echo       - Heavy baseline; press [R] + Enter anytime to spike RAM further
echo   [3] Launch PRISM Desktop App (mvnw javafx:run)
echo   [4] Run Diagnostic Probes (HostProcessProbe ^& HostMonitoringProbe)
echo   [5] Run Full Test Suite ^& Quality Gate (mvnw verify)
echo.
set "CHOICE="
set /p "CHOICE=Select an option [1-5] (Press Enter for 1): "
if "%CHOICE%"=="" set "CHOICE=1"

if "%CHOICE%"=="1" goto :demo_standard
if "%CHOICE%"=="2" goto :demo_heavy
if "%CHOICE%"=="3" goto :run_app
if "%CHOICE%"=="4" goto :run_probes
if "%CHOICE%"=="5" goto :run_verify

echo Invalid choice: %CHOICE%
goto :end

:demo_standard
echo.
echo Compiling project...
call mvnw.cmd -q compile
if errorlevel 1 goto :fail
echo Starting DemoWorkload (CPU 50%%, RAM 256MiB, 5m) [Interactive Mode]...
echo Note the PID printed below to locate it in PRISM!
echo Tip: Press [R] + Enter while running to spike RAM and trigger OOM Guard!
echo ------------------------------------------------------------
java -Dprism.demo.interactive=true -cp target\classes prism.srm.demo.DemoWorkload --cpu 50 --memory 256MiB --duration 5m
goto :end

:demo_heavy
echo.
echo Compiling project...
call mvnw.cmd -q compile
if errorlevel 1 goto :fail
echo Starting Heavy DemoWorkload (CPU 25%%, RAM 1GiB, 5m) [Interactive Mode]...
echo Note the PID printed below to locate it in PRISM!
echo Tip: Press [R] + Enter while running to spike RAM and trigger OOM Guard!
echo ------------------------------------------------------------
java -Dprism.demo.interactive=true -cp target\classes prism.srm.demo.DemoWorkload --cpu 25 --memory 1GiB --duration 5m
goto :end

:run_app
echo.
echo Launching PRISM JavaFX application...
call mvnw.cmd javafx:run
goto :end

:run_probes
echo.
echo Running HostMonitoringProbe...
call mvnw.cmd -q test -Dtest=HostMonitoringProbe
if errorlevel 1 goto :fail
echo.
echo Running HostProcessProbe...
call mvnw.cmd -q test -Dtest=HostProcessProbe
goto :end

:run_verify
echo.
echo Running full Maven verify (Tests, Spotless, SpotBugs, JaCoCo)...
call mvnw.cmd verify
goto :end

:fail
echo.
echo [ERROR] Command failed! Make sure JAVA_HOME points to JDK 25.

:end
echo.
pause
endlocal
