#!/usr/bin/env bash
set -euo pipefail

ROOT=$(cd "$(dirname "$0")/.." && pwd)
if [[ "$ROOT" == /mnt/kingston/@home/* ]] && command -v cpto >/dev/null 2>&1; then
  SOURCE_REL=${ROOT#/mnt/kingston/@home/}
  BUILD_ROOT=${BUILD_ROOT:-/mnt/kingston/builds/${SOURCE_REL}.build}
  cpto --lngit "$ROOT" "$BUILD_ROOT"
  cd "$BUILD_ROOT"
  exec "$BUILD_ROOT/scripts/build-android.sh" "$@"
fi
JAVA_HOME=${JAVA_HOME:-/opt/jdks/temurin-17}
ANDROID_HOME=${ANDROID_HOME:-${ANDROID_SDK_ROOT:-"$HOME/Android/Sdk"}}
TMPDIR=${TMPDIR:-/var/tmp/rethink-app-build-tmp}
mkdir -p "$TMPDIR"
export TMPDIR
if [[ -z ${JAVA_TOOL_OPTIONS:-} ]]; then
  export JAVA_TOOL_OPTIONS="-Djava.io.tmpdir=$TMPDIR"
else
  export JAVA_TOOL_OPTIONS="$JAVA_TOOL_OPTIONS -Djava.io.tmpdir=$TMPDIR"
fi
INSTALL=0

for arg in "$@"; do
  case "$arg" in
    --install) INSTALL=1 ;;
    -h|--help)
      echo "Usage: $0 [--install]"
      echo "Build and test the F-Droid debug APK; --install also installs it on the ADB-connected device via ssh flip7."
      exit 0
      ;;
    *) echo "Unknown option: $arg" >&2; exit 2 ;;
  esac
done

if [[ ! -x "$JAVA_HOME/bin/java" ]]; then
  echo "JDK 17 not found at $JAVA_HOME; run scripts/install-jdk17.sh first or set JAVA_HOME." >&2
  exit 2
fi
if [[ ! -f "$ANDROID_HOME/platform-tools/adb" && ! -d "$ANDROID_HOME/platforms" ]]; then
  echo "Android SDK not found at $ANDROID_HOME; set ANDROID_HOME." >&2
  exit 2
fi

export JAVA_HOME ANDROID_HOME ANDROID_SDK_ROOT="$ANDROID_HOME"
export PATH="$JAVA_HOME/bin:$PATH"
SDK_AAR="$ROOT/app/build/generated/zerotier-sdk/zerotier-sdk.aar"
SDK_BUILD_LOG="$TMPDIR/zerotier-sdk-build.log"
if ! bash "$ROOT/scripts/build-zerotier-sdk.sh" >"$SDK_BUILD_LOG" 2>&1; then
  tail -n 80 "$SDK_BUILD_LOG" >&2
  exit 1
fi
CLI_BINARY=${ZEROTIER_CLI_BINARY:-$ROOT/app/build/generated/zerotier-cli/zerotier-cli}
CLI_BUILD_LOG="$TMPDIR/zerotier-cli-build.log"
if [[ -z ${ZEROTIER_CLI_BINARY:-} ]]; then
  if ! bash "$ROOT/scripts/build-zerotier-cli.sh" >"$CLI_BUILD_LOG" 2>&1; then
    tail -n 80 "$CLI_BUILD_LOG" >&2
    exit 1
  fi
fi
if [[ ! -f "$CLI_BINARY" ]]; then
  echo "ZeroTier CLI binary not found: $CLI_BINARY" >&2
  exit 2
fi

GRADLE_ZEROTIER_ARGS=(-PzerotierSdkAar="$SDK_AAR")
FIRESTACK_SOURCE=${FIRESTACK_SOURCE_DIR:-/mnt/kingston/@home/rebroad/src/firestack}
FIRESTACK_BUILD=${FIRESTACK_BUILD_DIR:-/mnt/kingston/builds/rebroad/src/firestack.build}
FIRESTACK_AAR=${FIRESTACK_AAR:-$FIRESTACK_BUILD/build/intra/tun2socks.aar}
FIRESTACK_PIN=$(sed -n 's/^firestackCommit=//p' "$ROOT/gradle.properties" | head -n 1)
if [[ -f "$FIRESTACK_AAR" && -e "$FIRESTACK_SOURCE/.git" ]]; then
  FIRESTACK_HEAD=$(git -C "$FIRESTACK_SOURCE" rev-parse --short HEAD)
  if [[ "$FIRESTACK_HEAD" == "$FIRESTACK_PIN"* ]]; then
    GRADLE_ZEROTIER_ARGS+=("-PfirestackRepo=local" "-PfirestackAar=$FIRESTACK_AAR")
  fi
fi

"$ROOT/gradlew" :app:assembleFdroidFullDebug "${GRADLE_ZEROTIER_ARGS[@]}" --no-daemon --no-configuration-cache --console=plain

TEST_STATUS=0
"$ROOT/gradlew" :app:testFdroidFullDebugUnitTest \
  --tests 'com.celzero.bravedns.zerotier.*' \
  --tests 'com.celzero.bravedns.adapter.SummaryStatsEndpointLabelTest' \
  "${GRADLE_ZEROTIER_ARGS[@]}" --no-daemon --no-configuration-cache --max-workers=1 --console=plain || TEST_STATUS=$?

if (( INSTALL )); then
  ANDROID_ABI=${ANDROID_ABI:-arm64-v8a}
  APK="$ROOT/app/build/outputs/apk/fdroidFull/debug/app-fdroid-full-${ANDROID_ABI}-debug.apk"
  if [[ ! -f "$APK" ]]; then
    echo "Expected APK not found: $APK" >&2
    exit 1
  fi
  ADB_SSH_TARGET=${ADB_SSH_TARGET:-flip7}
  ADB_SSH_HOST=${ADB_SSH_HOST:-10.16.78.64}
  # Keep using flip7's pre-existing SSH host-key entry while forcing the TCP destination to its LAN IP.
  ADB_SSH_HOSTKEY_ALIAS=${ADB_SSH_HOSTKEY_ALIAS:-'[192.168.192.7]:8022'}
  ADB_SSH_CONFIG=${ADB_SSH_CONFIG:-$HOME/.ssh/config}
  SSH_OPTIONS=()
  if [[ -f "$ADB_SSH_CONFIG" ]]; then SSH_OPTIONS=(-F "$ADB_SSH_CONFIG"); fi
  REMOTE_APK=${REMOTE_APK:-/data/data/com.termux/files/usr/tmp/rethink-fdroid-full-debug.apk}
  REMOTE_CLI=${REMOTE_CLI:-/data/data/com.termux/files/usr/tmp/zerotier-cli}
  scp "${SSH_OPTIONS[@]}" -o "HostName=$ADB_SSH_HOST" -o "HostKeyAlias=$ADB_SSH_HOSTKEY_ALIAS" \
    "$CLI_BINARY" "$ADB_SSH_TARGET:$REMOTE_CLI"
  echo "Installing $APK on $ADB_SSH_TARGET via LAN $ADB_SSH_HOST..."
  scp "${SSH_OPTIONS[@]}" -o "HostName=$ADB_SSH_HOST" -o "HostKeyAlias=$ADB_SSH_HOSTKEY_ALIAS" "$APK" "$ADB_SSH_TARGET:$REMOTE_APK"
  ssh "${SSH_OPTIONS[@]}" -o "HostName=$ADB_SSH_HOST" -o "HostKeyAlias=$ADB_SSH_HOSTKEY_ALIAS" "$ADB_SSH_TARGET" \
    "install -Dm755 '$REMOTE_CLI' \"\$PREFIX/bin/zerotier-cli\" && \"\$PREFIX/bin/zerotier-cli\" -v && \
     adb install -r '$REMOTE_APK' && adb shell pm path com.celzero.bravedns"
fi

exit "$TEST_STATUS"
