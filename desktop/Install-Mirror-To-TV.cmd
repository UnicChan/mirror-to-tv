@echo off
setlocal EnableExtensions EnableDelayedExpansion
cd /d "%~dp0"

set "ADB=%~dp0tools\adb.exe"
set "APK=%~dp0Mirror-To-TV.apk"
set "TVIP=%~1"
set "TVPORT=%~2"
set "EXIT_CODE=1"

set "MIRROR_TO_TV_INSTALL_CONFIG=%~dp0mirror-to-tv.config.json"
if not defined TVIP if exist "%MIRROR_TO_TV_INSTALL_CONFIG%" for /f "tokens=1,* delims==" %%A in ('powershell.exe -NoProfile -Command "try { $config = ConvertFrom-Json (Get-Content -Raw -LiteralPath $env:MIRROR_TO_TV_INSTALL_CONFIG); 'TVIP=' + $config.tvIp; 'TVPORT=' + $config.adbPort } catch {}"') do (
  if /i "%%A"=="TVIP" set "TVIP=%%B"
  if /i "%%A"=="TVPORT" set "TVPORT=%%B"
)

echo.
echo mirror-to-tv 1.0 installer
echo ==========================
echo.

if not exist "%ADB%" (
  echo ERROR: tools\adb.exe was not found.
  echo Extract the complete release ZIP before running this installer.
  goto finish
)

if not exist "%APK%" (
  echo ERROR: Mirror-To-TV.apk was not found.
  echo Extract the complete release ZIP before running this installer.
  goto finish
)

if not defined TVIP set /p "TVIP=TV IPv4 address: "
if not defined TVPORT set /p "TVPORT=ADB port [5555]: "
if not defined TVPORT set "TVPORT=5555"

set "MIRROR_TO_TV_INSTALL_IP=%TVIP%"
set "MIRROR_TO_TV_INSTALL_PORT=%TVPORT%"
powershell.exe -NoProfile -Command "$ip = $null; $port = 0; if (-not [Net.IPAddress]::TryParse($env:MIRROR_TO_TV_INSTALL_IP, [ref]$ip) -or $ip.AddressFamily -ne [Net.Sockets.AddressFamily]::InterNetwork -or -not [int]::TryParse($env:MIRROR_TO_TV_INSTALL_PORT, [ref]$port) -or $port -lt 1 -or $port -gt 65535) { exit 1 }"
if errorlevel 1 (
  echo ERROR: Enter a valid IPv4 address and a port from 1 to 65535.
  goto finish
)

set "SERIAL=%TVIP%:%TVPORT%"
echo.
echo Connecting to %SERIAL%...
"%ADB%" connect "%SERIAL%"
echo If the TV asks whether to allow ADB debugging, choose Allow.

set "MIRROR_TO_TV_INSTALL_ADB=%ADB%"
set "MIRROR_TO_TV_INSTALL_SERIAL=%SERIAL%"
powershell.exe -NoProfile -Command "for ($attempt = 0; $attempt -lt 45; $attempt++) { $state = (& $env:MIRROR_TO_TV_INSTALL_ADB -s $env:MIRROR_TO_TV_INSTALL_SERIAL get-state 2^>$null | Out-String).Trim(); if ($LASTEXITCODE -eq 0 -and $state -eq 'device') { exit 0 }; Start-Sleep -Seconds 1 }; exit 1"
if errorlevel 1 (
  echo ERROR: The TV did not authorize the ADB connection within 45 seconds.
  echo Check ADB debugging and confirm the authorization dialog on the TV.
  goto disconnect
)

echo Installing the receiver...
set "INSTALL_LOG=%TEMP%\mirror-to-tv-install-%RANDOM%-%RANDOM%.log"
"%ADB%" -s "%SERIAL%" install -r "%APK%" >"!INSTALL_LOG!" 2>&1
set "INSTALL_RESULT=!ERRORLEVEL!"
type "!INSTALL_LOG!"
if "!INSTALL_RESULT!"=="0" (
  del /q "!INSTALL_LOG!" >nul 2>&1
  goto install_complete
)

findstr /c:"INSTALL_FAILED_UPDATE_INCOMPATIBLE" "!INSTALL_LOG!" >nul
if not errorlevel 1 goto signature_mismatch
del /q "!INSTALL_LOG!" >nul 2>&1
echo ERROR: APK installation failed.
goto disconnect

:signature_mismatch
del /q "!INSTALL_LOG!" >nul 2>&1
echo.
choice /c YN /n /m "The installed app has a different signature. Completely reinstall the app? This removes its existing app data. [Y/N] "
if errorlevel 2 (
  echo Installation cancelled. The installed app was not changed.
  set "EXIT_CODE=2"
  set "MIRROR_TO_TV_NO_PAUSE=1"
  goto disconnect
)

echo Removing the installed app...
"%ADB%" -s "%SERIAL%" uninstall local.lanoverlay.tv
if errorlevel 1 (
  echo ERROR: The installed app could not be removed.
  goto disconnect
)

echo Installing the fresh receiver...
"%ADB%" -s "%SERIAL%" install "%APK%"
if errorlevel 1 (
  echo ERROR: The old app was removed, but the fresh APK installation failed.
  goto disconnect
)

:install_complete

echo Granting permission to display over other apps...
"%ADB%" -s "%SERIAL%" shell appops set local.lanoverlay.tv SYSTEM_ALERT_WINDOW allow
if errorlevel 1 (
  echo ERROR: The overlay permission could not be granted.
  goto disconnect
)

echo Starting the TV receiver...
"%ADB%" -s "%SERIAL%" shell am broadcast -a local.lanoverlay.tv.START -n local.lanoverlay.tv/.BootReceiver >nul
if errorlevel 1 (
  echo ERROR: The TV receiver could not be started.
  goto disconnect
)

powershell.exe -NoProfile -Command "Start-Sleep -Seconds 1"
"%ADB%" -s "%SERIAL%" shell dumpsys activity services local.lanoverlay.tv 2>nul | findstr /c:"local.lanoverlay.tv/.OverlayService" >nul
if errorlevel 1 (
  echo ERROR: The receiver did not report a running service.
  echo Open mirror-to-tv once from the TV app list and allow display over other apps.
  goto disconnect
)

set "MIRROR_TO_TV_INSTALL_DIR=%~dp0"
powershell.exe -NoProfile -Command "$path = Join-Path $env:MIRROR_TO_TV_INSTALL_DIR 'mirror-to-tv.config.json'; if (Test-Path -LiteralPath $path) { try { $config = Get-Content -Raw -LiteralPath $path | ConvertFrom-Json } catch { $config = [pscustomobject]@{} } } else { $config = [pscustomobject]@{} }; $config | Add-Member NoteProperty tvIp $env:MIRROR_TO_TV_INSTALL_IP -Force; $config | Add-Member NoteProperty adbPort ([int]$env:MIRROR_TO_TV_INSTALL_PORT) -Force; $config | ConvertTo-Json | Set-Content -LiteralPath $path -Encoding UTF8"
if errorlevel 1 (
  echo WARNING: Installation succeeded, but the desktop connection settings were not saved.
)

echo.
echo Installation complete. The receiver is running and the address was saved.
set "EXIT_CODE=0"

:disconnect
"%ADB%" disconnect "%SERIAL%" >nul 2>&1
if "%EXIT_CODE%"=="0" if /i not "%MIRROR_TO_TV_NO_LAUNCH%"=="1" start "" "%~dp0Start-Mirror-To-TV.cmd"

:finish
echo.
if /i not "%MIRROR_TO_TV_NO_PAUSE%"=="1" pause
exit /b %EXIT_CODE%
