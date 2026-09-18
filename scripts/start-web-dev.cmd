@echo off
REM =====================================================================
REM  Start frontend dev server (Vite, port 5173) with /api proxy to 8080.
REM  Use this in development; for production run scripts\build-all.cmd and
REM  serve everything from the backend jar.
REM =====================================================================
setlocal
set "ROOT=%~dp0.."
cd /d "%ROOT%\web"

if not exist node_modules (
  echo [web-dev] installing npm dependencies ...
  call npm install --no-audit --no-fund
  if errorlevel 1 exit /b 1
)
echo [web-dev] starting Vite dev server on http://127.0.0.1:5173
call npm run dev
