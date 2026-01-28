#!/usr/bin/env bash
set -euo pipefail

# Build sherpa-onnx AAR from source for arm64-v8a
#
# Usage:
#   ./build-sherpa-onnx-aar.sh                    # clones sherpa-onnx
#   ./build-sherpa-onnx-aar.sh /path/to/sherpa-onnx  # uses existing checkout

SHERPA_ONNX_VERSION="1.12.23"
AAR_OUTPUT="app/build/tmp/sherpa-onnx-${SHERPA_ONNX_VERSION}.aar"

# Determine sherpa-onnx source directory
if [ -n "${1:-}" ]; then
    SHERPA_DIR="$(cd "$1" && pwd)"
    echo "Using existing sherpa-onnx at: $SHERPA_DIR"
else
    SHERPA_DIR="${PWD}/sherpa-onnx-src"
    if [ ! -d "$SHERPA_DIR" ]; then
        echo "Cloning sherpa-onnx v${SHERPA_ONNX_VERSION}..."
        git clone --depth 1 --branch "v${SHERPA_ONNX_VERSION}" \
            https://github.com/k2-fsa/sherpa-onnx.git "$SHERPA_DIR"
    else
        echo "Using cached sherpa-onnx at: $SHERPA_DIR"
    fi
fi

# Find Android NDK
if [ -z "${ANDROID_NDK:-}" ]; then
    # Try standard SDK location
    SDK_DIR="${ANDROID_HOME:-${HOME}/Android/Sdk}"
    NDK_DIR=$(find "${SDK_DIR}/ndk" -maxdepth 1 -name '27.*' -type d 2>/dev/null | sort -V | tail -1)
    if [ -z "$NDK_DIR" ]; then
        NDK_DIR=$(find "${SDK_DIR}/ndk" -maxdepth 1 -type d 2>/dev/null | sort -V | tail -1)
    fi
    if [ -z "$NDK_DIR" ] || [ "$NDK_DIR" = "${SDK_DIR}/ndk" ]; then
        echo "ERROR: Android NDK not found. Set ANDROID_NDK or install via sdkmanager."
        exit 1
    fi
    export ANDROID_NDK="$NDK_DIR"
fi
echo "Using NDK: $ANDROID_NDK"

# Step 1: Build native libraries
echo "Building native libraries for arm64-v8a..."
cd "$SHERPA_DIR"
export SHERPA_ONNX_ENABLE_TTS=OFF
export SHERPA_ONNX_ENABLE_SPEAKER_DIARIZATION=OFF
./build-android-arm64-v8a.sh

# Step 2: Copy .so files into AAR module
JNILIBS_DIR="android/SherpaOnnxAar/sherpa_onnx/src/main/jniLibs/arm64-v8a"
mkdir -p "$JNILIBS_DIR"
cp build-android-arm64-v8a/install/lib/lib*.so "$JNILIBS_DIR/"
echo "Copied .so files to $JNILIBS_DIR:"
ls -la "$JNILIBS_DIR/"

# Step 3: Assemble AAR
echo "Assembling AAR..."
cd android/SherpaOnnxAar
chmod +x gradlew
./gradlew :sherpa_onnx:assembleRelease

# Step 4: Copy AAR to output location
cd "$OLDPWD"
cd "$(dirname "$0")"  # back to translander repo root
mkdir -p "$(dirname "$AAR_OUTPUT")"
cp "${SHERPA_DIR}/android/SherpaOnnxAar/sherpa_onnx/build/outputs/aar/sherpa_onnx-release.aar" \
   "$AAR_OUTPUT"
echo "AAR built successfully: $AAR_OUTPUT"
ls -la "$AAR_OUTPUT"
