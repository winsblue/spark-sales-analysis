@echo off
REM =====================================================================
REM  Run the Spark offline analysis job (ETL pipeline).
REM
REM  Usage:
REM     scripts\run-etl.cmd                     run both implementations
REM     scripts\run-etl.cmd --mode=df           DataFrame only
REM     scripts\run-etl.cmd --generate          generate mock data then exit
REM     scripts\run-etl.cmd --import-real       convert the real dataset to ODS
REM     scripts\run-etl.cmd --no-ods            skip ODS loading
REM
REM  Required environment (set them, or keep the default paths below):
REM     JAVA_HOME    JDK 8 / 11 / 17
REM     MAVEN_HOME   Maven 3.6+
REM     HADOOP_HOME  folder containing bin\winutils.exe (Windows only)
REM =====================================================================
setlocal enabledelayedexpansion

set "ROOT=%~dp0.."
cd /d "%ROOT%"

REM ---- resolve tool paths: prefer environment variables, fall back to defaults ----
if "%JAVA_HOME%"=="" if exist "D:\Java\jdk1.8.0_212\bin\java.exe" set "JAVA_HOME=D:\Java\jdk1.8.0_212"
if "%MAVEN_HOME%"=="" if exist "D:\maven\apache-maven-3.8.8\bin\mvn.cmd" set "MAVEN_HOME=D:\maven\apache-maven-3.8.8"
if "%HADOOP_HOME%"=="" if exist "C:\Users\19351\hadoop\bin\winutils.exe" set "HADOOP_HOME=C:\Users\19351\hadoop"

if "%JAVA_HOME%"=="" (
  echo [run-etl][ERROR] JAVA_HOME is not set and the default path does not exist.
  echo [run-etl][ERROR] Please install JDK 8+ and run:  set JAVA_HOME=C:\path\to\jdk
  exit /b 1
)
if "%MAVEN_HOME%"=="" (
  echo [run-etl][ERROR] MAVEN_HOME is not set and the default path does not exist.
  echo [run-etl][ERROR] Please install Maven and run:  set MAVEN_HOME=C:\path\to\maven
  exit /b 1
)
if "%HADOOP_HOME%"=="" echo [run-etl][WARN] HADOOP_HOME not found - Spark on Windows needs winutils.exe; set HADOOP_HOME if the job fails.
set "PATH=%HADOOP_HOME%\bin;%PATH%;%JAVA_HOME%\bin"

echo [run-etl] JAVA_HOME   = %JAVA_HOME%
echo [run-etl] HADOOP_HOME = %HADOOP_HOME%
echo [run-etl] project dir = %ROOT%

REM ---- 1. compile and export dependency classpath ----
call "%MAVEN_HOME%\bin\mvn.cmd" -B -q -f spark-job\pom.xml clean compile dependency:build-classpath -Dmdep.outputFile="%ROOT%\spark-job\target\classpath.txt"
if errorlevel 1 (
  echo [run-etl] Maven build failed.
  exit /b 1
)

set /p CP=<"%ROOT%\spark-job\target\classpath.txt"

REM ---- 2. run the job ----
echo [run-etl] starting Spark job ...
echo.
"%JAVA_HOME%\bin\java.exe" -Xmx2g -cp "%ROOT%\spark-job\target\classes;%CP%" com.sales.SalesAnalysisApplication %*
set "RC=%ERRORLEVEL%"
echo.
echo [run-etl] exit code = %RC%
exit /b %RC%
