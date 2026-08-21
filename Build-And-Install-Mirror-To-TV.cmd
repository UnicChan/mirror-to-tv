@echo off
setlocal EnableExtensions
cd /d "%~dp0"

set "BUILD_ONLY=0"
if /i "%~1"=="--build-only" set "BUILD_ONLY=1"

set "BUILD_JAVA_HOME="
if defined JAVA_HOME if exist "%JAVA_HOME%\bin\javac.exe" set "BUILD_JAVA_HOME=%JAVA_HOME%"
if not defined BUILD_JAVA_HOME if exist "C:\Tools\jdk-17\bin\javac.exe" set "BUILD_JAVA_HOME=C:\Tools\jdk-17"
if not defined BUILD_JAVA_HOME for /d %%D in ("%ProgramFiles%\Microsoft\jdk-17*") do if exist "%%~fD\bin\javac.exe" set "BUILD_JAVA_HOME=%%~fD"
if not defined BUILD_JAVA_HOME for /d %%D in ("%ProgramFiles%\Eclipse Adoptium\jdk-17*") do if exist "%%~fD\bin\javac.exe" set "BUILD_JAVA_HOME=%%~fD"
if not defined BUILD_JAVA_HOME goto missing_java

set "BUILD_FFMPEG="
if defined MIRROR_TO_TV_FFMPEG if exist "%MIRROR_TO_TV_FFMPEG%" set "BUILD_FFMPEG=%MIRROR_TO_TV_FFMPEG%"
if not defined BUILD_FFMPEG if exist "C:\Tools\ffmpeg\bin\ffmpeg.exe" set "BUILD_FFMPEG=C:\Tools\ffmpeg\bin\ffmpeg.exe"
if not defined BUILD_FFMPEG if exist "%~dp0.private\ffmpeg" for /r "%~dp0.private\ffmpeg" %%F in (ffmpeg.exe) do if not defined BUILD_FFMPEG if exist "%%~fF" set "BUILD_FFMPEG=%%~fF"
if not defined BUILD_FFMPEG goto missing_ffmpeg

set "TVIP="
set "TVPORT="
set "MIRROR_TO_TV_SAVED_CONFIG=%~dp0dist\mirror-to-tv\mirror-to-tv.config.json"
if exist "%MIRROR_TO_TV_SAVED_CONFIG%" for /f "tokens=1,* delims==" %%A in ('powershell.exe -NoProfile -Command "try { $config = ConvertFrom-Json (Get-Content -Raw -LiteralPath $env:MIRROR_TO_TV_SAVED_CONFIG); 'TVIP=' + $config.tvIp; 'TVPORT=' + $config.adbPort } catch {}"') do (
  if /i "%%A"=="TVIP" set "TVIP=%%B"
  if /i "%%A"=="TVPORT" set "TVPORT=%%B"
)

set "JAVA_HOME=%BUILD_JAVA_HOME%"
set "MIRROR_TO_TV_FFMPEG=%BUILD_FFMPEG%"

echo.
echo Building mirror-to-tv...
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0build.ps1"
if errorlevel 1 goto build_failed

if "%BUILD_ONLY%"=="1" goto build_complete

echo.
echo Installing or updating the TV receiver...
set "MIRROR_TO_TV_NO_PAUSE=1"
if defined TVIP (
  call "%~dp0dist\mirror-to-tv\Install-Mirror-To-TV.cmd" "%TVIP%" "%TVPORT%"
) else (
  call "%~dp0dist\mirror-to-tv\Install-Mirror-To-TV.cmd"
)
if errorlevel 1 goto install_failed

echo.
echo Build and TV update complete.
goto finish_success

:build_complete
echo.
echo Build complete: dist\mirror-to-tv-1.0.zip
goto finish_success

:missing_java
echo.
echo ERROR: JDK 17 was not found.
echo Install or extract it to C:\Tools\jdk-17 and run this file again.
goto finish_error

:missing_ffmpeg
echo.
echo ERROR: ffmpeg.exe was not found.
echo Extract FFmpeg to C:\Tools\ffmpeg so that C:\Tools\ffmpeg\bin\ffmpeg.exe exists.
goto finish_error

:build_failed
echo.
echo ERROR: The build failed. See the message above.
goto finish_error

:install_failed
echo.
echo ERROR: The APK was built, but the TV update failed. See the message above.
goto finish_error

:finish_success
echo.
pause
exit /b 0

:finish_error
echo.
pause
exit /b 1
