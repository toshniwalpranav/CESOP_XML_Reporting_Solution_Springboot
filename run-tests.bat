@echo off
cd /d "%~dp0"
set "PATH=%PATH%;C:\Program Files\apache-maven-3.9.16-bin\apache-maven-3.9.16\bin;C:\Windows\System32"
echo Running in: %CD%
mvn test
pause
