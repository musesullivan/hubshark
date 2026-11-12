@echo off
REM Double-click this to log in to GitHub and publish this folder to your account.
REM It just hands off to publish-to-github.ps1 (which contains all the logic).
cd /d "%~dp0"
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0publish-to-github.ps1"
