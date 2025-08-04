#!/bin/bash

# Script to download and set up whisper.cpp for Android build
# Run this script from the app/src/main/cpp directory

set -e

WHISPER_VERSION="v1.6.2"  # Use a stable version
WHISPER_DIR="whisper.cpp"

echo "Setting up whisper.cpp for Android..."

# Check if whisper.cpp already exists
if [ -d "$WHISPER_DIR" ]; then
    echo "whisper.cpp directory already exists. Removing it..."
    rm -rf "$WHISPER_DIR"
fi

# Clone whisper.cpp
echo "Cloning whisper.cpp $WHISPER_VERSION..."
git clone --depth 1 --branch "$WHISPER_VERSION" https://github.com/ggerganov/whisper.cpp.git "$WHISPER_DIR"

# Navigate to whisper.cpp directory
cd "$WHISPER_DIR"

echo "whisper.cpp has been downloaded successfully!"
echo ""
echo "Next steps:"
echo "1. Download a whisper model (e.g., ggml-base.en.bin) from https://huggingface.co/ggerganov/whisper.cpp"
echo "2. Place the model file in app/src/main/assets/"
echo "3. Build the project with: ./gradlew assembleDebug"
echo ""
echo "Available models:"
echo "- ggml-tiny.bin (39 MB) - Fast but less accurate"
echo "- ggml-base.bin (142 MB) - Good balance"
echo "- ggml-small.bin (244 MB) - Better accuracy"
echo "- ggml-medium.bin (769 MB) - High accuracy"
echo "- ggml-large.bin (1550 MB) - Best accuracy"
echo ""
echo "English-only versions (smaller and faster):"
echo "- ggml-tiny.en.bin"
echo "- ggml-base.en.bin"
echo "- ggml-small.en.bin"
echo "- ggml-medium.en.bin"
