@echo off
setlocal
cd /d "%~dp0"
title PRISM - Portable Fast-JAR ^& App Builder

echo ============================================================
echo   PRISM - Portable Fast-JAR ^& App Builder
echo ============================================================
echo.
echo   [1] Build Fast Portable Fat-JAR (dist\PRISM-Portable\prism-portable.jar) [Default]
echo       - Single portable JAR + AppCDS fast-startup launcher (opens in ^<1s)
echo   [2] Build Standalone Windows Portable EXE Image (PRISM.exe + bundled JRE)
echo       - Runs on any Windows 10/11 PC even without Java installed
echo.
set "MODE="
set /p "MODE=Select build mode [1-2] (Press Enter for 1): "
if "%MODE%"=="" set "MODE=1"

if "%MODE%"=="2" goto :build_exe

echo.
echo [1/2] Building single portable Fat-JAR (prism-portable.jar)...
call mvnw.cmd -q -Pportable-jar -DskipTests package
if errorlevel 1 goto :fail

echo [2/2] Preparing portable distribution folder (dist\PRISM-Portable)...
if not exist "dist\PRISM-Portable" mkdir "dist\PRISM-Portable"
copy /Y "target\prism-portable.jar" "dist\PRISM-Portable\prism-portable.jar" >nul
for %%F in ("target\prism-*-demo.jar") do copy /Y "%%F" "dist\PRISM-Portable\prism-demo.jar" >nul

(
echo @echo off
echo setlocal
echo cd /d "%%~dp0"
echo set "JVM_FAST_FLAGS=--enable-native-access=ALL-UNNAMED -XX:+TieredCompilation -XX:TieredStopAtLevel=1 -XX:+UseG1GC -Xms64m -Xmx512m"
echo if exist "prism-appcds.jsa" ^(
echo     start "" javaw %%JVM_FAST_FLAGS%% -XX:SharedArchiveFile=prism-appcds.jsa -jar prism-portable.jar
echo ^) else ^(
echo     start "" javaw %%JVM_FAST_FLAGS%% -XX:ArchiveClassesAtExit=prism-appcds.jsa -jar prism-portable.jar
echo ^)
) > "dist\PRISM-Portable\start-prism.bat"

echo.
echo ============================================================
echo   SUCCESS! Portable JAR bundle is ready at:
echo     dist\PRISM-Portable\prism-portable.jar
echo     dist\PRISM-Portable\start-prism.bat  (Instant-start launcher)
echo     dist\PRISM-Portable\prism-demo.jar   (Demo workload JAR)
echo ============================================================
goto :end

:build_exe
echo.
echo Building standalone Windows Portable App-Image (PRISM.exe + custom JRE)...
call mvnw.cmd -Ppackage-image -Dprism.package.unverifiedJdk=true -DskipTests package
if errorlevel 1 goto :fail
echo.
echo ============================================================
echo   SUCCESS! Standalone Windows App-Image is ready at:
echo     target\package\image\PRISM\PRISM.exe
echo ============================================================
goto :end

:fail
echo.
echo [ERROR] Build failed! Ensure JAVA_HOME points to JDK 25.

:end
echo.
pause
endlocal
