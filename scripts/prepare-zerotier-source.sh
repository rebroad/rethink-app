#!/usr/bin/env bash
set -euo pipefail

SOURCE_REPO=${ZEROTIER_SOURCE_REPO:-https://github.com/rebroad/ZeroTierOne.git}
SOURCE_COMMIT=${ZEROTIER_SOURCE_COMMIT:-052cb0085e81368a294a3a9b94bc8c19244eb139}

if [[ -n ${ZEROTIER_SOURCE_DIR:-} ]]; then
  SOURCE_DIR=$ZEROTIER_SOURCE_DIR
elif [[ -d /mnt/kingston/@home/rebroad/src/ZeroTierOne/.git ]]; then
  SOURCE_DIR=/mnt/kingston/@home/rebroad/src/ZeroTierOne
else
  SOURCE_DIR=$HOME/src/ZeroTierOne
fi

if ! command -v git >/dev/null 2>&1; then
  echo "git is required to prepare the separate ZeroTierOne checkout." >&2
  exit 2
fi

if ! git -C "$SOURCE_DIR" rev-parse --is-inside-work-tree >/dev/null 2>&1; then
  if [[ -e "$SOURCE_DIR" ]]; then
    echo "ZeroTier source path exists but is not a Git checkout: $SOURCE_DIR" >&2
    exit 2
  fi
  mkdir -p "$(dirname "$SOURCE_DIR")"
  git clone --no-checkout "$SOURCE_REPO" "$SOURCE_DIR"
  if ! git -C "$SOURCE_DIR" checkout --detach "$SOURCE_COMMIT"; then
    echo "Could not check out ZeroTierOne revision $SOURCE_COMMIT from $SOURCE_REPO." >&2
    exit 2
  fi
fi

actual_commit=$(git -C "$SOURCE_DIR" rev-parse HEAD 2>/dev/null || true)
if [[ "$actual_commit" != "$SOURCE_COMMIT" ]]; then
  echo "ZeroTierOne checkout at $SOURCE_DIR is $actual_commit; expected $SOURCE_COMMIT." >&2
  echo "Set ZEROTIER_SOURCE_COMMIT to intentionally build another revision." >&2
  exit 2
fi
if ! git -C "$SOURCE_DIR" diff --quiet HEAD --; then
  echo "ZeroTierOne has tracked local changes at $SOURCE_DIR; refusing a non-reproducible build." >&2
  exit 2
fi

for required in java/src node osdep ext/prometheus-cpp-lite-1.0; do
  if [[ ! -d "$SOURCE_DIR/$required" ]]; then
    echo "Required ZeroTierOne source path is missing: $SOURCE_DIR/$required" >&2
    exit 2
  fi
done

printf '%s\n' "$SOURCE_DIR"
