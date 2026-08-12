@echo off
setlocal
set DIR=%~dp0
if exist "%DIR%gradle\wrapper\gradle-wrapper.jar" (
  java -jar "%DIR%gradle\wrapper\gradle-wrapper.jar" %*
  exit /b %ERRORLEVEL%
)
echo Gradle Wrapper JAR is missing.
echo Open this project in Android Studio and use Gradle Sync, or restore gradle-wrapper.jar.
exit /b 1
