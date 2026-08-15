@echo off
setlocal EnableExtensions EnableDelayedExpansion
cd /d "%~dp0"

set "BUILDER_PAUSE=1"
set "BUILDER_ARGS=%*"
if /i "%~1"=="--no-pause" (
    set "BUILDER_PAUSE=0"
    set "BUILDER_ARGS=!BUILDER_ARGS:*--no-pause=!"
)

call :find_java_8
if not defined BUILDER_JAVA_HOME (
    echo [BuilderMod] ERROR: JDK 8 was not found.
    echo [BuilderMod] Install JDK 8 or set JAVA_HOME to a JDK 8 directory.
    set "BUILDER_EXIT=1"
    goto :finish
)

set "JAVA_HOME=%BUILDER_JAVA_HOME%"
set "PATH=%JAVA_HOME%\bin;%PATH%"

echo [BuilderMod] Java: %JAVA_HOME%
echo [BuilderMod] Running clean build...
echo.

call "%~dp0gradlew.bat" clean build --no-daemon --console=plain !BUILDER_ARGS!
set "BUILDER_EXIT=%ERRORLEVEL%"

echo.
if not "%BUILDER_EXIT%"=="0" (
    echo [BuilderMod] BUILD FAILED with exit code %BUILDER_EXIT%.
    goto :finish
)

echo [BuilderMod] BUILD SUCCESSFUL
echo [BuilderMod] Output:
for %%F in ("%~dp0build\libs\buildermod-*.jar") do echo   %%~fF

:finish
echo.
if "%BUILDER_PAUSE%"=="1" pause
exit /b %BUILDER_EXIT%

:find_java_8
set "BUILDER_JAVA_HOME="
if defined JAVA_HOME call :accept_java_8 "%JAVA_HOME%"
if defined BUILDER_JAVA_HOME exit /b 0

for /d %%D in (
    "C:\Program Files\OpenLogic\jdk-8*"
    "C:\Program Files\Eclipse Adoptium\jdk-8*"
    "C:\Program Files\Amazon Corretto\jdk1.8*"
    "%USERPROFILE%\.jdks\*1.8*"
) do (
    if not defined BUILDER_JAVA_HOME call :accept_java_8 "%%~fD"
)
exit /b 0

:accept_java_8
if not exist "%~1\bin\java.exe" exit /b 0
"%~1\bin\java.exe" -version 2>&1 | findstr /c:"1.8.0" >nul
if errorlevel 1 exit /b 0
set "BUILDER_JAVA_HOME=%~1"
exit /b 0
