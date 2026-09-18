@echo off
REM =====================================================================
REM  Run the Spark offline analysis job (ETL pipeline).
REM  Usage:  scripts\run-etl.cmd  [--mode=both|df|rdd] [--generate] [--no-ods]
REM  Default mode is "both": run DataFrame and RDD implementations for
REM  the performance comparison required by the "improvement" level.
REM =====================================================================
setlocal enabledelayedexpansion

set "ROOT=%~dp0.."
cd /d "%ROOT%"

if "%JAVA_HOME%"=="" set "JAVA_HOME=D:\Java\jdk1.8.0_212"
if "%HADOOP_HOME%"=="" set "HADOOP_HOME=C:\Users\19351\hadoop"
if "%MAVEN_HOME%"=="" set "MAVEN_HOME=D:\maven\apache-maven-3.8.8"
set "PATH=%HADOOP_HOME%\bin;%PATH%"

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
