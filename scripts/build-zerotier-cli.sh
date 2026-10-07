#!/usr/bin/env bash
set -euo pipefail

ROOT=$(cd "$(dirname "$0")/.." && pwd)
if [[ "$ROOT" == /mnt/kingston/@home/* ]] && command -v cpto >/dev/null 2>&1; then
  SOURCE_REL=${ROOT#/mnt/kingston/@home/}
  BUILD_ROOT=${BUILD_ROOT:-/mnt/kingston/builds/${SOURCE_REL}.build}
  cpto --lngit "$ROOT" "$BUILD_ROOT"
  cd "$BUILD_ROOT"
  exec bash "$BUILD_ROOT/scripts/build-zerotier-cli.sh" "$@"
fi

ZT_SOURCE_DIR=$(bash "$ROOT/scripts/prepare-zerotier-source.sh")
ZT_BUILD_ROOT=${ZEROTIER_BUILD_ROOT:-/mnt/kingston/builds/rebroad/src/ZeroTierOne.build}
mkdir -p "$ZT_BUILD_ROOT"
ZT_BUILD_ROOT=$(cd "$ZT_BUILD_ROOT" && pwd -P)
CLI_BUILD_DIR="$ZT_BUILD_ROOT/termux-cli-build"
OUTPUT_DIR=${ZEROTIER_CLI_OUTPUT_DIR:-$ROOT/app/build/generated/zerotier-cli}
if command -v cpto >/dev/null 2>&1; then
  CLI_SOURCE_DIR="$ZT_BUILD_ROOT/termux-cli-source"
  USE_CPTO=1
else
  # Preserve the established no-cpto fallback: compile from the source tree.
  CLI_SOURCE_DIR=$ZT_SOURCE_DIR
  USE_CPTO=0
fi
ANDROID_HOME=${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/Android/Sdk}}
ANDROID_NDK_VERSION=${ANDROID_NDK_VERSION:-28.2.13676358}
ANDROID_NDK_HOME=${ANDROID_NDK_HOME:-$ANDROID_HOME/ndk/$ANDROID_NDK_VERSION}
ANDROID_API=${ANDROID_API:-24}
ANDROID_BUILD_JOBS=${ANDROID_BUILD_JOBS:-4}

for path in service nonfree/controller include ext/cpp-httplib ext/http-parser ext/inja \
  ext/nlohmann ext/miniupnpc ext/libnatpmp ext/opentelemetry-cpp-api-only; do
  if [[ ! -d "$ZT_SOURCE_DIR/$path" ]]; then
    echo "Required ZeroTierOne source path is missing: $ZT_SOURCE_DIR/$path" >&2
    exit 2
  fi
done
NDK_BIN="$ANDROID_NDK_HOME/toolchains/llvm/prebuilt/linux-x86_64/bin"
if [[ ! -x "$NDK_BIN/aarch64-linux-android${ANDROID_API}-clang++" ]]; then
  echo "Android NDK $ANDROID_NDK_VERSION not found under $ANDROID_HOME; set ANDROID_NDK_HOME." >&2
  exit 2
fi

mkdir -p "$CLI_BUILD_DIR" "$OUTPUT_DIR"
if (( USE_CPTO )); then
  mkdir -p "$CLI_SOURCE_DIR/node" "$CLI_SOURCE_DIR/osdep" "$CLI_SOURCE_DIR/service" \
    "$CLI_SOURCE_DIR/nonfree/controller" "$CLI_SOURCE_DIR/include" \
    "$CLI_SOURCE_DIR/ext/cpp-httplib" "$CLI_SOURCE_DIR/ext/http-parser" \
    "$CLI_SOURCE_DIR/ext/inja" "$CLI_SOURCE_DIR/ext/nlohmann" \
    "$CLI_SOURCE_DIR/ext/miniupnpc" "$CLI_SOURCE_DIR/ext/libnatpmp" \
    "$CLI_SOURCE_DIR/ext/prometheus-cpp-lite-1.0" \
    "$CLI_SOURCE_DIR/ext/opentelemetry-cpp-api-only" "$CLI_SOURCE_DIR/rustybits/target"
  SYNC_LOG=${TMPDIR:-/var/tmp}/zerotier-cli-source-sync.log
  : >"$SYNC_LOG"
  for path in node osdep service nonfree/controller include ext/cpp-httplib ext/http-parser ext/inja \
    ext/nlohmann ext/miniupnpc ext/libnatpmp ext/prometheus-cpp-lite-1.0 ext/opentelemetry-cpp-api-only; do
    cpto --no-lngit "$ZT_SOURCE_DIR/$path" "$CLI_SOURCE_DIR/$path" >>"$SYNC_LOG" 2>&1
  done
  for path in Makefile make-linux.mk objects.mk one.cpp version.h; do
    install -m 0644 "$ZT_SOURCE_DIR/$path" "$CLI_SOURCE_DIR/$path"
  done
fi

# Cross-compiler objects are never reused across configurations or source changes.
if (( USE_CPTO )); then
  find "$CLI_SOURCE_DIR/node" "$CLI_SOURCE_DIR/osdep" "$CLI_SOURCE_DIR/service" \
    "$CLI_SOURCE_DIR/ext/http-parser" "$CLI_SOURCE_DIR/ext/miniupnpc" \
    "$CLI_SOURCE_DIR/ext/libnatpmp" -type f \( -name '*.o' -o -name '*.d' \) -delete
fi
cat >"$CLI_BUILD_DIR/termux-pthread-affinity.h" <<'EOF'
#include <errno.h>
/* Linux-only thread affinity APIs are optional to this Android CLI build. */
#define pthread_setaffinity_np(thread, size, cpuset) (ENOSYS)
#define pthread_setattr_default_np(attr) (0)
EOF
MAKE_REBUILD=()
if (( ! USE_CPTO )); then MAKE_REBUILD=(-B); fi

CLI_LOG=${TMPDIR:-/var/tmp}/zerotier-cli-build.log
if ! (
  cd "$CLI_SOURCE_DIR"
  RUSTC_WRAPPER= make -j"$ANDROID_BUILD_JOBS" "${MAKE_REBUILD[@]}" zerotier-one \
    CC="$NDK_BIN/aarch64-linux-android${ANDROID_API}-clang" \
    CXX="$NDK_BIN/aarch64-linux-android${ANDROID_API}-clang++" \
    CXXFLAGS="-include $CLI_BUILD_DIR/termux-pthread-affinity.h" \
    INCLUDES='-Irustybits/target -isystem ext -Iext/libnatpmp -Iext/prometheus-cpp-lite-1.0/core/include -Iext/prometheus-cpp-lite-1.0/3rdparty/http-client-lite/include -Iext/prometheus-cpp-lite-1.0/simpleapi/include' \
    LDFLAGS=-static-libstdc++ \
    ZT_NATIVE=0 ZT_EMBEDDED=1 ZT_SSO_SUPPORTED=0 ZT_OTEL=0 MMDB_PKG= LDLIBS=-llog
) >"$CLI_LOG" 2>&1; then
  tail -n 80 "$CLI_LOG" >&2
  exit 1
fi
if [[ ! -s "$CLI_SOURCE_DIR/zerotier-one" ]]; then
  echo "ZeroTier Android CLI build completed without producing zerotier-one." >&2
  tail -n 80 "$CLI_LOG" >&2
  exit 1
fi

install -m 0755 "$CLI_SOURCE_DIR/zerotier-one" "$OUTPUT_DIR/zerotier-cli"
printf 'ZeroTierOne source revision: %s\n' "$(git -C "$ZT_SOURCE_DIR" rev-parse --short HEAD 2>/dev/null || printf unknown)" \
  >"$OUTPUT_DIR/BUILD-INFO.txt"
printf '%s\n' "$OUTPUT_DIR/zerotier-cli"
