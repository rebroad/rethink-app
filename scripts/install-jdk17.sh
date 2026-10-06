#!/usr/bin/env bash
set -euo pipefail

VERSION=17.0.20.1
ARCHIVE="OpenJDK17U-jdk_x64_linux_hotspot_17.0.20.1_1.tar.gz"
URL="https://github.com/adoptium/temurin17-binaries/releases/download/jdk-17.0.20.1%2B1/$ARCHIVE"
SHA256=3808d1d15e3ec6bd5b84057fb5d84c33d8a1536a258146bcea2e603fc726e08e
INSTALL_ROOT=${JDK17_INSTALL_ROOT:-/opt/jdks}
DEST="$INSTALL_ROOT/temurin-$VERSION"
TMP_ROOT=${TMPDIR:-/var/tmp}

if [[ "$(uname -s)" != Linux || "$(uname -m)" != x86_64 ]]; then
  echo "This installer currently supports Linux x86_64 only." >&2
  exit 2
fi
for cmd in curl sha256sum tar; do
  command -v "$cmd" >/dev/null || { echo "Missing required command: $cmd" >&2; exit 2; }
done

if [[ -x "$DEST/bin/java" ]] && "$DEST/bin/java" -version 2>&1 | grep -Fq "version \"$VERSION\""; then
  echo "Temurin $VERSION is already installed at $DEST"
  exit 0
fi
if [[ -e "$DEST" ]]; then
  echo "Refusing to replace existing installation: $DEST" >&2
  exit 2
fi

work=$(mktemp -d "$TMP_ROOT/temurin17.XXXXXX")
trap 'rm -rf "$work"' EXIT
mkdir "$work/jdk"
curl --fail --location --retry 2 "$URL" --output "$work/$ARCHIVE"
printf '%s  %s\n' "$SHA256" "$work/$ARCHIVE" | sha256sum --check --status

tar -xzf "$work/$ARCHIVE" -C "$work/jdk" --strip-components=1
"$work/jdk/bin/java" -version 2>&1 | grep -Fq "version \"$VERSION\""

if [[ "$EUID" -eq 0 ]]; then
  install -d -m 0755 "$INSTALL_ROOT"
  cp -a "$work/jdk" "$DEST"
  ln -sfn "$DEST" "$INSTALL_ROOT/temurin-17"
elif command -v sudo >/dev/null; then
  sudo install -d -m 0755 "$INSTALL_ROOT"
  sudo cp -a "$work/jdk" "$DEST"
  sudo ln -sfn "$DEST" "$INSTALL_ROOT/temurin-17"
else
  echo "Root access is required to install into $INSTALL_ROOT; run as root or install sudo." >&2
  exit 2
fi

"$DEST/bin/java" -version
echo "Installed Temurin $VERSION at $DEST"
