#!/bin/bash
set -euo pipefail

# Cross-compile hush.cpp for iOS (device + simulator)
# Uses Xcode's built-in iOS SDK toolchain (no external dependencies)

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
HUSH_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"

echo "=== Hush! iOS Build ==="
echo "Hush root: $HUSH_ROOT"

# Find iOS SDKs
DEVICE_SDK=$(xcrun --sdk iphoneos --show-sdk-path)
SIM_SDK=$(xcrun --sdk iphonesimulator --show-sdk-path)

if [ ! -d "$DEVICE_SDK" ]; then
    echo "ERROR: iOS device SDK not found. Is Xcode installed?"
    exit 1
fi
if [ ! -d "$SIM_SDK" ]; then
    echo "ERROR: iOS simulator SDK not found. Is Xcode installed?"
    exit 1
fi
echo "Device SDK: $DEVICE_SDK"
echo "Sim SDK:    $SIM_SDK"

OUTPUT_DEVICE="$SCRIPT_DIR/Libs/ios-arm64"
OUTPUT_SIM="$SCRIPT_DIR/Libs/ios-arm64-simulator"

# Clean previous builds
rm -rf "$SCRIPT_DIR/build-ios-arm64" "$SCRIPT_DIR/build-ios-arm64-simulator"
rm -rf "$OUTPUT_DEVICE" "$OUTPUT_SIM"
mkdir -p "$OUTPUT_DEVICE" "$OUTPUT_SIM"

# ─── Build for DEVICE (arm64) ───────────────────────────────────
echo ""
echo "=== Building for iOS Device (arm64) ==="
cmake "$SCRIPT_DIR" \
    -DCMAKE_BUILD_TYPE=Release \
    -DCMAKE_SYSTEM_NAME=iOS \
    -DCMAKE_OSX_DEPLOYMENT_TARGET=16.0 \
    -DCMAKE_OSX_ARCHITECTURES=arm64 \
    -DCMAKE_OSX_SYSROOT="$DEVICE_SDK" \
    -B "$SCRIPT_DIR/build-ios-arm64"

cmake --build "$SCRIPT_DIR/build-ios-arm64" --config Release -j$(sysctl -n hw.ncpu)

cp "$SCRIPT_DIR/build-ios-arm64/libhush_core.a" "$OUTPUT_DEVICE/"
cp "$SCRIPT_DIR/build-ios-arm64/libhush_ffi.a" "$OUTPUT_DEVICE/"

# ─── Build for SIMULATOR (arm64) ────────────────────────────────
echo ""
echo "=== Building for iOS Simulator (arm64) ==="
cmake "$SCRIPT_DIR" \
    -DCMAKE_BUILD_TYPE=Release \
    -DCMAKE_SYSTEM_NAME=iOS \
    -DCMAKE_OSX_DEPLOYMENT_TARGET=16.0 \
    -DCMAKE_OSX_ARCHITECTURES=arm64 \
    -DCMAKE_OSX_SYSROOT="$SIM_SDK" \
    -DCMAKE_OSX_SYSROOT_FALLBACK="$SIM_SDK" \
    -B "$SCRIPT_DIR/build-ios-arm64-simulator"

cmake --build "$SCRIPT_DIR/build-ios-arm64-simulator" --config Release -j$(sysctl -n hw.ncpu)

cp "$SCRIPT_DIR/build-ios-arm64-simulator/libhush_core.a" "$OUTPUT_SIM/"
cp "$SCRIPT_DIR/build-ios-arm64-simulator/libhush_ffi.a" "$OUTPUT_SIM/"

echo ""
echo "=== Build Complete ==="
echo ""
echo "Device libraries:"
ls -la "$OUTPUT_DEVICE"/*.a
echo ""
echo "Simulator libraries:"
ls -la "$OUTPUT_SIM"/*.a
echo ""
echo "Xcode will automatically pick the right architecture based on the build destination."
