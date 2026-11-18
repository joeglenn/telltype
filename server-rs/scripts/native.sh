#!/usr/bin/env bash
# Download the prebuilt sherpa-onnx static library for this machine, check it against the pinned
# SHA-256 in native/sherpa-onnx.sha256 and unpack it to target/native/sherpa-onnx, where
# .cargo/config.toml points the build (SHERPA_ONNX_LIB_DIR). Idempotent; a bad download is
# deleted and the script exits non-zero.
#
#   scripts/native.sh                 # needs curl, sha256sum, tar with bzip2
#   SHERPA_ONNX_ARCHIVE_DIR=/dir      # use an archive already downloaded there (still checked)
set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")/.."

case "$(uname -m)" in
  x86_64) arch=x64 ;;
  aarch64 | arm64) arch=aarch64 ;;
  *) echo "native.sh: no prebuilt sherpa-onnx for $(uname -m)" >&2; exit 2 ;;
esac
version=$(awk '/^name = "sherpa-onnx-sys"$/ { getline; gsub(/[^0-9.]/, ""); print }' Cargo.lock)
archive="sherpa-onnx-v${version}-linux-${arch}-static-lib.tar.bz2"
expected=$(awk -v a="$archive" '$2 == a { print $1 }' native/sherpa-onnx.sha256)
[ -n "$expected" ] || { echo "native.sh: no pinned SHA-256 for $archive in native/sherpa-onnx.sha256" >&2; exit 2; }

dest=target/native
lib="$dest/sherpa-onnx"
mkdir -p "$dest"
if [ "$(cat "$lib/.sha256" 2>/dev/null)" = "$expected" ] && [ -f "$lib/lib/libsherpa-onnx-c-api.a" ]; then
  echo "native.sh: $archive ok (sha256 $expected)"
  exit 0
fi
file="$dest/$archive"
if [ ! -f "$file" ]; then
  if [ -n "${SHERPA_ONNX_ARCHIVE_DIR:-}" ]; then
    cp "$SHERPA_ONNX_ARCHIVE_DIR/$archive" "$file.part"
  else
    curl -fsSL --retry 3 -o "$file.part" "https://github.com/k2-fsa/sherpa-onnx/releases/download/v${version}/${archive}"
  fi
  mv "$file.part" "$file"
fi
actual=$(sha256sum "$file" | cut -d' ' -f1)
if [ "$actual" != "$expected" ]; then
  rm -f "$file"
  echo "native.sh: SHA-256 mismatch for $archive: got $actual, pinned $expected (download deleted)" >&2
  exit 1
fi
rm -rf "$lib" "$dest/unpack"
mkdir -p "$dest/unpack"
tar -xjf "$file" -C "$dest/unpack"
mv "$dest/unpack/${archive%.tar.bz2}" "$lib"
rmdir "$dest/unpack"
echo "$expected" >"$lib/.sha256"
echo "native.sh: $archive checked and unpacked to $lib (sha256 $expected)"
