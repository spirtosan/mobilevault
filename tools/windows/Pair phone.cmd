@echo off
rem Mobile Vault: pair a phone with the backup server (shows the QR code full screen).
rem Settings: %USERPROFILE%\mobilevault-secrets\pair-phone.json (see pair-phone.example.json).
powershell.exe -NoProfile -ExecutionPolicy Bypass -STA -File "%~dp0pair-phone.ps1"
