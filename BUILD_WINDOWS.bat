
@echo off
setlocal EnableExtensions
title Build 144P Windows App

cd /d "%~dp0"

set "SQLITE_JAR=sqlite-jdbc-3.36.0.3.jar"
set "APP_NAME=144P"
set "MAIN_CLASS=Client"

echo.
echo ==========================================
echo          144P - Windows App Builder
echo ==========================================
echo.

where javac >nul 2>nul || (echo ERROR: JDK not found.& pause & exit /b 1)
where jar >nul 2>nul || (echo ERROR: jar command not found.& pause & exit /b 1)
where jpackage >nul 2>nul || (echo ERROR: jpackage not found.& pause & exit /b 1)

if not exist "%SQLITE_JAR%" (
    echo ERROR: Cannot find %SQLITE_JAR%
    pause
    exit /b 1
)

if not exist "Client.java" (
    echo ERROR: Client.java not found.
    pause
    exit /b 1
)

if not exist "144p_logo.png" (
    echo ERROR: 144p_logo.png not found.
    pause
    exit /b 1
)

if exist "build" rmdir /s /q "build"
if exist "package-input" rmdir /s /q "package-input"
if exist "dist" rmdir /s /q "dist"

mkdir "build"
mkdir "package-input"
mkdir "dist"

echo [1/4] Compiling Java files...

javac -encoding UTF-8 -cp ".;%SQLITE_JAR%" -d "build" *.java

if errorlevel 1 (
    echo Compilation failed.
    pause
    exit /b 1
)

echo [2/4] Adding logo and creating JAR...

copy /y "144p_logo.png" "build\144p_logo.png" >nul

(
    echo Manifest-Version: 1.0
    echo Main-Class: %MAIN_CLASS%
    echo Class-Path: %SQLITE_JAR%
    echo.
) > manifest.txt

jar cfm "package-input\144P.jar" manifest.txt -C build .

if errorlevel 1 (
    echo JAR creation failed.
    pause
    exit /b 1
)

copy /y "%SQLITE_JAR%" "package-input\%SQLITE_JAR%" >nul

echo [3/4] Creating Windows EXE installer...

jpackage --type exe --name "%APP_NAME%" --input "package-input" --main-jar "144P.jar" --main-class "%MAIN_CLASS%" --dest "dist" --app-version "1.0.0" --vendor "144P"

if errorlevel 1 (
    echo EXE creation failed.
    pause
    exit /b 1
)

echo.
echo [4/4] Build completed!
echo.
echo Installer location:
echo %CD%\dist
echo.
pause
