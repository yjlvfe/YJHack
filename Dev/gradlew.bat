@echo off
setlocal

set "APP_HOME=%~dp0"
set "WRAPPER_JAR=%APP_HOME%gradle\wrapper\gradle-wrapper.jar"

if exist "%WRAPPER_JAR%" (
  if defined JAVA_HOME (
    set "JAVACMD=%JAVA_HOME%\bin\java.exe"
  ) else (
    set "JAVACMD=java.exe"
  )
  "%JAVACMD%" -Xmx64m -Xms64m -Dorg.gradle.appname=gradlew -classpath "%WRAPPER_JAR%" org.gradle.wrapper.GradleWrapperMain %*
  exit /b %ERRORLEVEL%
)

where gradle >nul 2>nul
if %ERRORLEVEL% equ 0 (
  gradle %*
  exit /b %ERRORLEVEL%
)

set "VERSION=8.14.4"
set "SHA256=f1771298a70f6db5a29daf62378c4e18a17fc33c9ba6b14362e0cdf40610380d"
if defined GRADLE_USER_HOME (
  set "CACHE_ROOT=%GRADLE_USER_HOME%\yjhack-wrapper"
) else (
  set "CACHE_ROOT=%USERPROFILE%\.gradle\yjhack-wrapper"
)
set "DIST_DIR=%CACHE_ROOT%\gradle-%VERSION%"
set "ZIP=%CACHE_ROOT%\gradle-%VERSION%-bin.zip"
set "URL=https://services.gradle.org/distributions/gradle-%VERSION%-bin.zip"

if not exist "%DIST_DIR%\bin\gradle.bat" (
  if not exist "%CACHE_ROOT%" mkdir "%CACHE_ROOT%"
  powershell -NoProfile -ExecutionPolicy Bypass -Command ^
    "$ErrorActionPreference='Stop'; $ProgressPreference='SilentlyContinue';" ^
    "Invoke-WebRequest -Uri '%URL%' -OutFile '%ZIP%.tmp';" ^
    "$actual=(Get-FileHash -Algorithm SHA256 '%ZIP%.tmp').Hash.ToLowerInvariant();" ^
    "if($actual -ne '%SHA256%'){Remove-Item '%ZIP%.tmp' -Force; throw 'Gradle checksum mismatch'};" ^
    "if(Test-Path '%DIST_DIR%'){Remove-Item '%DIST_DIR%' -Recurse -Force};" ^
    "Expand-Archive -Path '%ZIP%.tmp' -DestinationPath '%CACHE_ROOT%' -Force;" ^
    "Move-Item '%ZIP%.tmp' '%ZIP%' -Force"
  if errorlevel 1 exit /b 1
)

call "%DIST_DIR%\bin\gradle.bat" %*
exit /b %ERRORLEVEL%
