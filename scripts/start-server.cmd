@echo off
REM =====================================================================
REM  Start the Spring Boot backend service (port 8080).
REM  Make sure the offline job has been executed first:
REM      scripts\run-etl.cmd --mode=both
REM =====================================================================
setlocal

set "ROOT=%~dp0.."
cd /d "%ROOT%"

if "%JAVA_HOME%"=="" set "JAVA_HOME=D:\Java\jdk1.8.0_212"
if "%MAVEN_HOME%"=="" set "MAVEN_HOME=D:\maven\apache-maven-3.8.8"

echo [start-server] building backend jar ...
call "%MAVEN_HOME%\bin\mvn.cmd" -B -q -f server\pom.xml clean package -DskipTests
if errorlevel 1 (
  echo [start-server] build failed.
  exit /b 1
)

echo [start-server] starting on http://localhost:8080
echo [start-server] press Ctrl+C to stop
echo.
"%JAVA_HOME%\bin\java.exe" -Xmx512m -Dfile.encoding=UTF-8 -jar "%ROOT%\server\target\sales-analysis-server.jar"
