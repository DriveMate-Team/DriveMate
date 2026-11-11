@echo off
REM Reset DriveMate drive log (run before re-recording a demo).
REM Stops the app, then deletes files/drive_log.json inside the app.
REM Usage: start the emulator, then double-click this file or run reset_log.bat
set ADB=%LOCALAPPDATA%\Android\Sdk\platform-tools\adb.exe
REM The car emulator runs apps as the current driver profile (usually user 10), so look it up.
for /f %%u in ('"%ADB%" shell am get-current-user') do set CURUSER=%%u
"%ADB%" shell am force-stop com.example.drivemate
"%ADB%" shell run-as com.example.drivemate --user %CURUSER% rm -f files/drive_log.json files/drive_log.json.tmp
echo DriveMate log cleared (user %CURUSER%). Launch the app again.
