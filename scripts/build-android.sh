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
      echo "Build and test the F-Droid debug APK; --install installs it via USB ADB and copies zerotier-cli to Termux through a USB-forwarded SSH connection."
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
  ADB_BIN=${ADB_BIN:-$ANDROID_HOME/platform-tools/adb}
  if [[ ! -x "$ADB_BIN" ]]; then ADB_BIN=$(command -v adb || true); fi
  if [[ -z "$ADB_BIN" || ! -x "$ADB_BIN" ]]; then
    echo "ADB not found; install Android platform-tools or set ADB_BIN." >&2
    exit 2
  fi

  USB_SERIALS=()
  while read -r serial state details; do
    if [[ "$state" == "device" && "$details" == *usb:* ]]; then
      USB_SERIALS+=("$serial")
    fi
  done < <("$ADB_BIN" devices -l | tail -n +2)

  ADB_SERIAL=${ADB_SERIAL:-}
  if [[ -n "$ADB_SERIAL" ]]; then
    found_usb_serial=0
    for serial in "${USB_SERIALS[@]}"; do
      if [[ "$serial" == "$ADB_SERIAL" ]]; then found_usb_serial=1; break; fi
    done
    if (( ! found_usb_serial )); then
      echo "ADB_SERIAL=$ADB_SERIAL is not a connected USB ADB device." >&2
      exit 2
    fi
  elif (( ${#USB_SERIALS[@]} == 1 )); then
    ADB_SERIAL=${USB_SERIALS[0]}
  elif (( ${#USB_SERIALS[@]} == 0 )); then
    echo "No USB ADB device found. Connect Flip7 over USB debugging, or set ADB_SERIAL to a connected USB device." >&2
    exit 2
  else
    printf 'Multiple USB ADB devices found (%s); set ADB_SERIAL to the intended device.\n' "${USB_SERIALS[*]}" >&2
    exit 2
  fi

  ADB_SSH_TARGET=${ADB_SSH_TARGET:-flip7}
  # This identifies the known host key only; SSH traffic uses USB ADB forwarding.
  ADB_SSH_HOSTKEY_ALIAS=${ADB_SSH_HOSTKEY_ALIAS:-'[192.168.192.7]:8022'}
  ADB_SSH_CONFIG=${ADB_SSH_CONFIG:-$HOME/.ssh/config}
  SSH_OPTIONS=()
  if [[ -f "$ADB_SSH_CONFIG" ]]; then SSH_OPTIONS=(-F "$ADB_SSH_CONFIG"); fi
  REMOTE_CLI=${REMOTE_CLI:-/data/data/com.termux/files/usr/tmp/zerotier-cli}

  echo "Installing $APK over USB ADB ($ADB_SERIAL)..."
  "$ADB_BIN" -s "$ADB_SERIAL" install -r "$APK"
  "$ADB_BIN" -s "$ADB_SERIAL" shell pm path com.celzero.bravedns

  ADB_FORWARD_PORT=$("$ADB_BIN" -s "$ADB_SERIAL" forward tcp:0 tcp:8022)
  if [[ ! "$ADB_FORWARD_PORT" =~ ^[0-9]+$ ]]; then
    echo "ADB returned an invalid USB-forwarded port: $ADB_FORWARD_PORT" >&2
    exit 2
  fi
  cleanup_usb_forward() {
    "$ADB_BIN" -s "$ADB_SERIAL" forward --remove "tcp:$ADB_FORWARD_PORT" >/dev/null 2>&1 || true
  }
  trap cleanup_usb_forward EXIT

  USB_SSH_OPTIONS=(
    "${SSH_OPTIONS[@]}"
    -o HostName=127.0.0.1
    -o "HostKeyAlias=$ADB_SSH_HOSTKEY_ALIAS"
    -o BatchMode=yes
    -o ConnectTimeout=10
  )
  echo "Copying zerotier-cli to Termux over the USB ADB tunnel..."
  scp "${USB_SSH_OPTIONS[@]}" -P "$ADB_FORWARD_PORT" \
    "$CLI_BINARY" "$ADB_SSH_TARGET:$REMOTE_CLI"
  ssh "${USB_SSH_OPTIONS[@]}" -p "$ADB_FORWARD_PORT" "$ADB_SSH_TARGET" \
    "install -Dm755 '$REMOTE_CLI' \"\$PREFIX/bin/zerotier-cli\" && \"\$PREFIX/bin/zerotier-cli\" -v"
fi

exit "$TEST_STATUS"
