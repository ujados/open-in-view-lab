@REM Maven Wrapper script for Windows
@echo off
setlocal

set "MAVEN_PROJECTBASEDIR=%~dp0"
set "MAVEN_WRAPPER_PROPERTIES=%MAVEN_PROJECTBASEDIR%.mvn\wrapper\maven-wrapper.properties"

for /f "tokens=2 delims==" %%a in ('findstr "distributionUrl" "%MAVEN_WRAPPER_PROPERTIES%"') do set "MAVEN_URL=%%a"

set "MAVEN_HOME=%USERPROFILE%\.m2\wrapper\dists\apache-maven-3.9.9"

if not exist "%MAVEN_HOME%\bin\mvn.cmd" (
    echo Downloading Maven...
    mkdir "%MAVEN_HOME%" 2>nul
    powershell -Command "Invoke-WebRequest -Uri '%MAVEN_URL%' -OutFile '%TEMP%\maven.zip'"
    powershell -Command "Expand-Archive -Path '%TEMP%\maven.zip' -DestinationPath '%USERPROFILE%\.m2\wrapper\dists\' -Force"
    del "%TEMP%\maven.zip"
)

"%MAVEN_HOME%\bin\mvn.cmd" %*
