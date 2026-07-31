#!/bin/bash

# Define variables
PLUGIN_NAME="DownloadDataset"
PLUGIN_DIR="./${PLUGIN_NAME}"
SRC_DIR="${PLUGIN_DIR}/src/com/bytezone/plugins"
OUT_DIR="${PLUGIN_DIR}/out"
JAR_NAME="${PLUGIN_NAME}.jar"
CP_JAR="dm3270-1.0.0-SNAPSHOT-all.jar"

echo "Building plugin: ${PLUGIN_NAME}..."

# Clean previous build
echo "Cleaning old build files..."
rm -rf "${OUT_DIR}"
rm -f "${JAR_NAME}"

# Create output directory
mkdir -p "${OUT_DIR}"

# Compile Java files
echo "Compiling Java sources..."
javac -cp "${CP_JAR}" -d "${OUT_DIR}" "${SRC_DIR}"/*.java

if [ $? -eq 0 ]; then
    echo "Compilation successful."
    
    # Package into JAR
    echo "Packaging into ${JAR_NAME}..."
    jar cvf "${JAR_NAME}" -C "${OUT_DIR}" .
    
    if [ $? -eq 0 ]; then
        echo "Build completed successfully! The JAR file is ready: ${JAR_NAME}"
    else
        echo "Error: Failed to package JAR."
        exit 1
    fi
else
    echo "Error: Compilation failed."
    exit 1
fi
