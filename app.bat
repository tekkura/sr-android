@echo off
setlocal

set "ROOT_DIR=%~dp0"

where py >nul 2>nul
if %ERRORLEVEL%==0 (
    py -3 "%ROOT_DIR%scripts\app_cli.py" %*
    exit /b %ERRORLEVEL%
)

where python >nul 2>nul
if %ERRORLEVEL%==0 (
    python "%ROOT_DIR%scripts\app_cli.py" %*
    exit /b %ERRORLEVEL%
)

echo Unable to find Python. The app CLI requires Python 3.9 or newer. 1>&2
echo See docs\getting-started.md for setup instructions. 1>&2
exit /b 1
