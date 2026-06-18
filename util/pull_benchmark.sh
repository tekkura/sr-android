#!/bin/bash

# Configuration
DEVICE_PATH="/sdcard/Download/benchmark_results.md"
DOCS_PATH="./docs/BENCHMARK.md"
TEMP_PATH="./docs/BENCHMARK_TEMP.md"

# Ensure we are in the project root
if [ ! -d ".git" ]; then
    echo "Error: Please run this script from the project root directory."
    exit 1
fi

echo "Attempting to pull benchmark results from device public storage..."
echo "Source: $DEVICE_PATH"

adb pull "$DEVICE_PATH" "$TEMP_PATH"

if [ $? -eq 0 ]; then
    echo "" >> "$DOCS_PATH"
    echo "" >> "$DOCS_PATH"
    cat "$TEMP_PATH" >> "$DOCS_PATH"
    rm "$TEMP_PATH"
    echo "----------------------------------------------------"
    echo "Success! Results appended to: $DOCS_PATH"
    echo "----------------------------------------------------"
else
    echo "----------------------------------------------------"
    echo "Error: Failed to pull results."
    echo "1. Ensure the test passed in Android Studio."
    echo "2. Check Device Explorer under /sdcard/Download/"
    echo "----------------------------------------------------"
    exit 1
fi
