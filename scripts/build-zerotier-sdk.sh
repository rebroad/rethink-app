#!/usr/bin/env bash
set -euo pipefail

ROOT=$(cd "$(dirname "$0")/.." && pwd)
ZT_SOURCE_DIR=${ZEROTIER_SOURCE_DIR:-/mnt/kingston/@home/rebroad/src/ZeroTierOne}
ZT_BUILD_ROOT=${ZEROTIER_BUILD_ROOT:-/mnt/kingston/builds/rebroad/src/ZeroTierOne.build}
ANDROID_HOME=${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/Android/Sdk}}
JAVA_HOME=${JAVA_HOME:-/opt/jdks/temurin-17}
NDK_VERSION=${ANDROID_NDK_VERSION:-28.2.13676358}
NDK=${ANDROID_NDK_HOME:-$ANDROID_HOME/ndk/$NDK_VERSION}
ANDROID_API=${ANDROID_API:-23}
OUTPUT_AAR=${ZEROTIER_SDK_AAR:-$ROOT/app/build/generated/zerotier-sdk/zerotier-sdk.aar}
ABIS=(arm64-v8a armeabi-v7a x86 x86_64)

for required in "$ZT_SOURCE_DIR/java/src" "$ZT_SOURCE_DIR/node" "$ZT_SOURCE_DIR/osdep" "$ZT_SOURCE_DIR/ext/prometheus-cpp-lite-1.0"; do
    if [[ ! -d "$required" ]]; then
        echo "Required ZeroTierOne source path is missing: $required" >&2
        exit 2
    fi
done
if [[ ! -f "$NDK/build/cmake/android.toolchain.cmake" ]]; then
    echo "Android NDK $NDK_VERSION not found under $ANDROID_HOME; set ANDROID_NDK_HOME." >&2
    exit 2
fi
if [[ ! -x "$JAVA_HOME/bin/javac" ]]; then
    echo "JDK 17 not found at $JAVA_HOME; set JAVA_HOME." >&2
    exit 2
fi

SDK_MIRROR="$ZT_BUILD_ROOT/rethink-sdk-source"
if command -v cpto >/dev/null 2>&1; then
    mkdir -p "$SDK_MIRROR"
    for relative in java node osdep include ext/http-parser ext/nlohmann ext/prometheus-cpp-lite-1.0; do
        mkdir -p "$SDK_MIRROR/$relative"
        cpto --no-lngit "$ZT_SOURCE_DIR/$relative" "$SDK_MIRROR/$relative"
    done
    install -m 0644 "$ZT_SOURCE_DIR/version.h" "$SDK_MIRROR/version.h"
    SDK_SOURCE_DIR=$SDK_MIRROR
else
    SDK_SOURCE_DIR=$ZT_SOURCE_DIR
fi

BUILD_DIR="$ZT_BUILD_ROOT/java/rethink-android-sdk"
STAGE="$BUILD_DIR/package"
CLASSES="$BUILD_DIR/classes"
mkdir -p "$STAGE/jni" "$STAGE/META-INF/licenses" "$CLASSES" "$(dirname "$OUTPUT_AAR")"
rm -rf "$CLASSES" "$STAGE/jni" "$STAGE/META-INF" "$STAGE/classes.jar"
mkdir -p "$STAGE/jni" "$STAGE/META-INF/licenses" "$CLASSES"

for abi in "${ABIS[@]}"; do
    abi_build="$BUILD_DIR/cmake/$abi"
    cmake -S "$ROOT/scripts/zerotier-sdk" -B "$abi_build" \
        -DCMAKE_TOOLCHAIN_FILE="$NDK/build/cmake/android.toolchain.cmake" \
        -DANDROID_ABI="$abi" \
        -DANDROID_PLATFORM="android-$ANDROID_API" \
        -DANDROID_STL=c++_shared \
        -DZT_SOURCE_DIR="$SDK_SOURCE_DIR" \
        -DCMAKE_BUILD_TYPE=Release
    cmake --build "$abi_build" --parallel "${ANDROID_BUILD_JOBS:-4}"
    mkdir -p "$STAGE/jni/$abi"
    install -m 0644 "$abi_build/libZeroTierOneJNI.so" "$STAGE/jni/$abi/libZeroTierOneJNI.so"
    case "$abi" in
        arm64-v8a) triple=aarch64-linux-android ;;
        armeabi-v7a) triple=arm-linux-androideabi ;;
        x86) triple=i686-linux-android ;;
        x86_64) triple=x86_64-linux-android ;;
    esac
    install -m 0644 "$NDK/toolchains/llvm/prebuilt/linux-x86_64/sysroot/usr/lib/$triple/libc++_shared.so" \
        "$STAGE/jni/$abi/libc++_shared.so"
done

ANDROID_JAR=$(find "$ANDROID_HOME/platforms" -maxdepth 2 -name android.jar -print | sort -V | tail -1)
if [[ -z "$ANDROID_JAR" ]]; then
    echo "No Android platform android.jar found under $ANDROID_HOME/platforms." >&2
    exit 2
fi
mapfile -t JAVA_SOURCES < <(find "$SDK_SOURCE_DIR/java/src" -name '*.java' -print | sort)
"$JAVA_HOME/bin/javac" -source 8 -target 8 -bootclasspath "$ANDROID_JAR" -d "$CLASSES" "${JAVA_SOURCES[@]}"
"$JAVA_HOME/bin/jar" cf "$STAGE/classes.jar" -C "$CLASSES" .
cat > "$STAGE/AndroidManifest.xml" <<'EOF'
<manifest xmlns:android="http://schemas.android.com/apk/res/android" package="com.zerotier.sdk" />
EOF
cp "$ROOT/app/src/main/assets/licenses/GPL-3.0.txt" "$STAGE/META-INF/licenses/GPL-3.0.txt"
cp "$ROOT/app/src/main/assets/licenses/MPL-2.0.txt" "$STAGE/META-INF/licenses/MPL-2.0.txt"
cp "$ROOT/app/src/main/assets/THIRD_PARTY_NOTICES.txt" "$STAGE/META-INF/THIRD_PARTY_NOTICES.txt"
source_revision=$(git -C "$ZT_SOURCE_DIR" rev-parse --short HEAD 2>/dev/null || printf unknown)
printf 'ZeroTierOne source revision: %s\n' "$source_revision" > "$STAGE/META-INF/BUILD-INFO.txt"
"$JAVA_HOME/bin/jar" cf "$OUTPUT_AAR" -C "$STAGE" .
printf '%s\n' "$OUTPUT_AAR"
