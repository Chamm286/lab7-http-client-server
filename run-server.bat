@echo off
setlocal
cd /d "%~dp0"
if not defined JAVA_HOME (
    echo JAVA_HOME must point to a JDK installation.
    exit /b 1
)
if not exist out mkdir out
"%JAVA_HOME%\bin\javac.exe" -encoding UTF-8 -d out src\core\AuditStore.java src\core\HttpServerCore.java src\server\ServerMain.java
if errorlevel 1 exit /b 1
"%JAVA_HOME%\bin\java.exe" -cp out server.ServerMain %*
