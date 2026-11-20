@REM ----------------------------------------------------------------------------
@REM Apache Maven Wrapper Startup Batch Script for Windows
@REM ----------------------------------------------------------------------------

@IF "%DEBUG%" == "" @ECHO OFF
@SETLOCAL EnableExtensions EnableDelayedExpansion

SET "BASEDIR=%~dp0"
SET "WRAPPER_DIR=%BASEDIR%.mvn\wrapper"
SET "WRAPPER_PROPERTIES=%WRAPPER_DIR%\maven-wrapper.properties"

SET "DIST_URL="
IF EXIST "%WRAPPER_PROPERTIES%" (
    FOR /F "tokens=1,* delims==" %%A IN ('findstr /R "^distributionUrl=" "%WRAPPER_PROPERTIES%"') DO (
        SET "DIST_URL=%%B"
    )
)

IF "%DIST_URL%"=="" (
    SET "DIST_URL=https://repo.maven.apache.org/maven2/org/apache/maven/apache-maven/3.9.9/apache-maven-3.9.9-bin.zip"
)

REM Compute hash folder name for storage
SET "MVN_DIR=%USERPROFILE%\.m2\wrapper\dists\apache-maven-3.9.9"

IF NOT EXIST "%MVN_DIR%" (
    echo Downloading Maven from %DIST_URL%...
    mkdir "%MVN_DIR%" 2>NUL
    powershell -NoProfile -ExecutionPolicy Bypass -Command "$ErrorActionPreference = 'Stop'; [Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12; (New-Object Net.WebClient).DownloadFile('%DIST_URL%', '%MVN_DIR%\maven.zip'); Expand-Archive -Path '%MVN_DIR%\maven.zip' -DestinationPath '%MVN_DIR%' -Force; Remove-Item '%MVN_DIR%\maven.zip' -Force"
)

FOR /R "%MVN_DIR%" %%F IN (mvn.cmd) DO (
    IF EXIST "%%F" (
        SET "MVN_CMD=%%F"
        GOTO :run
    )
)

:run
IF DEFINED MVN_CMD (
    CALL "%MVN_CMD%" %*
) ELSE (
    echo Error: Could not locate mvn.cmd in %MVN_DIR%
    exit /b 1
)
