@echo off
cd /d "%~dp0"
set "PATH=%PATH%;C:\Program Files\apache-maven-3.9.16-bin\apache-maven-3.9.16\bin;C:\Windows\System32"
echo Starting in: %CD%
echo Open http://localhost:8080 when you see "Started CesopApplication". Press Ctrl+C to stop.
mvn spring-boot:run
pause
