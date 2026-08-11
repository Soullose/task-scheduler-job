@echo off
rem ============================================
rem  task-scheduler startup script (Windows)
rem  Uses the Java runtime under scheduler\jdk
rem ============================================
rem Switch console to UTF-8 so Chinese log output is not garbled
chcp 65001 >nul
setlocal

set "BASE_DIR=%~dp0"
set "JAVA_EXE=%BASE_DIR%jdk\bin\java.exe"
set "JAR_PATH=%BASE_DIR%server\task-scheduler.jar"

if not exist "%JAVA_EXE%" (
    echo [ERROR] Java runtime not found: %JAVA_EXE%
    echo Please put a JDK/JRE into the "jdk" folder next to this script.
    exit /b 1
)

if not exist "%JAR_PATH%" (
    echo [ERROR] Application jar not found: %JAR_PATH%
    echo Please run "mvn package" first to generate the scheduler folder.
    exit /b 1
)

echo Using Java: "%JAVA_EXE%"
"%JAVA_EXE%" %JAVA_OPTS% -Dfile.encoding=UTF-8 -Dstdout.encoding=UTF-8 -Dstderr.encoding=UTF-8 -jar "%JAR_PATH%" --scheduler.task-config-location=file:%BASE_DIR%conf\scheduler\tasks.yaml %*
