@echo off
setlocal EnableExtensions
chcp 65001 >nul
cd /d "%~dp0"

rem =====================================================================
rem  Lab 7 - Network Web Server and Client
rem  Build 5 Java files and run JavaFX application.
rem  Requires JDK 21+ and JavaFX SDK 21 in libs.
rem =====================================================================

if defined JAVA_HOME (
    set "JAVAC=%JAVA_HOME%\bin\javac.exe"
    set "JAVA=%JAVA_HOME%\bin\java.exe"
) else (
    set "JAVAC=javac"
    set "JAVA=java"
)

set "JAVAFX=libs\javafx-sdk-21.0.5\lib"

if not exist "%JAVAFX%\javafx.controls.jar" (
    echo [LOI] Khong tim thay JavaFX SDK tai %JAVAFX%
    echo       Tai JavaFX SDK 21.0.5 va giai nen vao thu muc libs.
    pause
    exit /b 1
)
if not exist "libs\mysql-connector-j-9.4.0.jar" (
    echo [LOI] Thieu libs\mysql-connector-j-9.4.0.jar
    pause
    exit /b 1
)
if not exist "libs\gson-2.11.0.jar" (
    echo [LOI] Thieu libs\gson-2.11.0.jar
    pause
    exit /b 1
)
if not exist "libs\ikonli-core-12.3.1.jar" (
    echo [LOI] Thieu libs\ikonli-core-12.3.1.jar
    pause
    exit /b 1
)
if not exist "libs\ikonli-javafx-12.3.1.jar" (
    echo [LOI] Thieu libs\ikonli-javafx-12.3.1.jar
    pause
    exit /b 1
)
if not exist "libs\ikonli-fontawesome5-pack-12.3.1.jar" (
    echo [LOI] Thieu libs\ikonli-fontawesome5-pack-12.3.1.jar
    pause
    exit /b 1
)

if exist out rmdir /s /q out
mkdir out
if not exist out\ui mkdir out\ui

echo [1/2] Dang compile 5 file Java...
"%JAVAC%" -encoding UTF-8 -d out -cp "libs/*" ^
    --module-path "%JAVAFX%" --add-modules javafx.controls,javafx.web ^
    src\App.java ^
    src\core\NetworkServer.java ^
    src\core\NetworkClient.java ^
    src\core\DatabaseManager.java ^
    src\ui\MainView.java

if errorlevel 1 (
    echo.
    echo [LOI] Compile that bai. Xem thong bao phia tren.
    pause
    exit /b 1
)

copy /Y "src\ui\style.css" "out\ui\style.css" >nul
echo [2/2] Dang khoi dong ung dung...
echo.
echo     Server mac dinh : http://localhost:8080
echo     Web Client      : http://localhost:8080/  (sau khi bam Bat server)
echo     MySQL           : localhost:3307 / root / mat khau rong
echo.

"%JAVA%" -Dfile.encoding=UTF-8 -Dsun.jnu.encoding=UTF-8 -Dstdout.encoding=UTF-8 -Dstderr.encoding=UTF-8 --module-path "%JAVAFX%" --add-modules javafx.controls,javafx.web -cp "out;libs/*" App

endlocal
