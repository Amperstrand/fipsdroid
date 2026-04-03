#!/bin/bash
set -e

# Prerequisites:
# 1. Install Rust Android targets: rustup target add aarch64-linux-android armv7-linux-androideabi x86_64-linux-android
# 2. Install cargo-ndk: cargo install cargo-ndk
# 3. Install Android NDK: via Android Studio or sdkmanager "ndk;25.2.9519653"

# Check for NDK
if [ -z "$ANDROID_NDK_HOME" ]; then
    echo "ERROR: ANDROID_NDK_HOME not set"
    echo "Please set it to your NDK installation path, e.g.:"
    echo "  export ANDROID_NDK_HOME=/path/to/ndk"
    exit 1
fi

# Build for arm64 (primary target)
cargo ndk -t aarch64-linux-android -o android/app/src/main/jniLibs build --release

# Generate UniFFI bindings (if uniffi is set up)
# Uncomment the following lines if UniFFI is properly configured:
# cargo run --features uniffi-cli -- generate --library target/aarch64-linux-android/release/libfipsdroid_core.so --language kotlin --out-dir android/app/src/main/java/

echo "Build complete. .so files in android/app/src/main/jniLibs/arm64-v8a/"
