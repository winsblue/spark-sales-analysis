@echo off
REM =====================================================================
REM  Build everything: frontend (Vite) + backend (Spring Boot) + Spark job.
REM  Frontend build output is copied into the backend static resources so
REM  that the single backend jar serves both API and web UI.
REM =====================================================================
setlocal

set "ROOT=%~dp0.."
cd /d "%ROOT%"

if "%JAVA_HOME%"=="" set "JAVA_HOME=D:\Java\jdk1.8.0_212"
if "%MAVEN_HOME%"=="" set "MAVEN_HOME=D:\maven\apache-maven-3.8.8"
set "NPM_CMD=npm"

echo [build-all] ================ 1/3 build frontend ================
cd /d "%ROOT%\web"
if not exist node_modules (
  echo [build-all] installing npm dependencies ...
  call %NPM_CMD% install --no-audit --no-fund
  if errorlevel 1 exit /b 1
)
call %NPM_CMD% run build
if errorlevel 1 exit /b 1

echo [build-all] ================ 2/3 copy web dist to server static ================
if exist "%ROOT%\server\src\main\resources\static" rd /s /q "%ROOT%\server\src\main\resources\static"
mkdir "%ROOT%\server\src\main\resources\static"
xcopy /E /Y /I /Q "%ROOT%\web\dist\*" "%ROOT%\server\src\main\resources\static\" >nul
if errorlevel 1 exit /b 1

echo [build-all] ================ 3/3 build maven modules ================
cd /d "%ROOT%"
call "%MAVEN_HOME%\bin\mvn.cmd" -B -f pom.xml clean package -DskipTests
if errorlevel 1 exit /b 1

echo.
echo [build-all] done.
echo   spark job jar  : spark-job\target\spark-job.jar
echo   backend jar    : server\target\sales-analysis-server.jar
echo   frontend dist  : web\dist
