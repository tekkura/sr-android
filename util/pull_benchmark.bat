@echo off
set DEVICE_PATH=/sdcard/Download/benchmark_results.md
set DOCS_PATH=docs\BENCHMARK.md
set TEMP_PATH=docs\BENCHMARK_TEMP.md

if not exist ".git" (
    echo Error: Please run this script from the project root directory.
    exit /b 1
)

echo Attempting to pull benchmark results from device public storage...
echo Source: %DEVICE_PATH%

adb pull "%DEVICE_PATH%" "%TEMP_PATH%"

if %ERRORLEVEL% EQU 0 (
    echo "----------------------------------------------------"
    echo Success! Results pulled to: %TEMP_PATH%
    echo "----------------------------------------------------"
) else (
    echo "----------------------------------------------------"
    echo "Error: Failed to pull results."
    echo "1. Ensure the test passed in Android Studio."
    echo "2. Check Device Explorer under /sdcard/Download/"
    echo "3. Check if your device/emulator is connected (adb devices)."
    echo "----------------------------------------------------"
    exit /b 1
)

:: Append results to BENCHMARK.md
echo: >> "%DOCS_PATH%"
echo: >> "%DOCS_PATH%"
type "%TEMP_PATH%" >> "%DOCS_PATH%"
del "%TEMP_PATH%"

echo ----------------------------------------------------
echo Success! Results appended to: %DOCS_PATH%
echo ----------------------------------------------------

endlocal
