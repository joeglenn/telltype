#!/usr/bin/env bash
# Install Telltype Server as a systemd user unit: the prebuilt binary in bin/ when run from a release
# archive, otherwise built from source. Safe to run again.
#
#   scripts/install.sh              build, install the binary, config (first run), credentials, unit;
#                                   start the unit, or restart it if the binary or unit changed
#   scripts/install.sh --no-start   install the files only; do not touch systemd (no reload, enable, start)
#
# Environment: PREFIX (default ~/.local; the binary goes to $PREFIX/bin), TELLTYPE_CONFIG_DIR
# (default ~/.config/telltype), UNIT_DIR (default ~/.config/systemd/user). Never needs sudo.
# Models are not downloaded here: scripts/download_models.sh.
set -euo pipefail

server_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
prefix="${PREFIX:-$HOME/.local}"
config_dir="${TELLTYPE_CONFIG_DIR:-$HOME/.config/telltype}"
unit_dir="${UNIT_DIR:-$HOME/.config/systemd/user}"
config="$config_dir/server.toml"
binary="$prefix/bin/telltype-server"
unit=telltype-server
start=1
[ "${1:-}" = "--no-start" ] && start=0
export PATH="$HOME/.cargo/bin:$PATH"

fail() { echo "install: $*" >&2; exit 1; }

if [ -x "$server_dir/bin/telltype-server" ]; then
  # A release archive: the binary is prebuilt (and its archive checked with its .sha256 file).
  built="$server_dir/bin/telltype-server"
else
  command -v cargo >/dev/null || fail "cargo not found (install Rust from https://rustup.rs, or use a release archive)"
  # The native library only from the copy checked against its pinned SHA-256 (see scripts/native.sh).
  bash "$server_dir/scripts/native.sh"
  export SHERPA_ONNX_LIB_DIR="${SHERPA_ONNX_LIB_DIR:-$server_dir/target/native/sherpa-onnx/lib}"
  cargo build --release --locked --manifest-path "$server_dir/Cargo.toml"
  built="$server_dir/target/release/telltype-server"
fi
changed=0
if ! cmp -s "$built" "$binary"; then
  install -D -m 0755 "$built" "$binary"
  changed=1
  echo "installed $binary"
fi

mkdir -p "$config_dir"
chmod 0700 "$config_dir"
if [ ! -f "$config" ]; then
  install -m 0600 "$server_dir/examples/server.toml" "$config"
  echo "wrote $config from examples/server.toml; edit the paths, then run this script again"
  exit 0
fi
"$binary" check-config --config "$config"
"$binary" credentials --config "$config"

# The listening port must be free or held by our own unit.
port="$(sed -nE 's/^listen *= *"[^"]*:([0-9]+)".*/\1/p' "$config" | head -1)"
if [ -n "$port" ] && ss -ltnH "sport = :$port" | grep -q . && ! systemctl --user is-active --quiet "$unit"; then
  fail "port $port is taken by something other than $unit; not touching it"
fi

mkdir -p "$unit_dir"
rendered="$(sed -e "s#%h/.config/telltype/server.toml#$config#" -e "s#%h/.local/bin/telltype-server#$binary#" \
  "$server_dir/systemd/$unit.service")"
if [ "$rendered" != "$(cat "$unit_dir/$unit.service" 2>/dev/null)" ]; then
  printf '%s\n' "$rendered" >"$unit_dir/$unit.service"
  chmod 0644 "$unit_dir/$unit.service"
  changed=1
  echo "installed $unit_dir/$unit.service"
fi
[ "$start" = 1 ] || { echo "files installed; systemd not touched (--no-start)"; exit 0; }
systemctl --user daemon-reload
systemctl --user enable --quiet "$unit"
if [ "$changed" = 1 ] && systemctl --user is-active --quiet "$unit"; then
  systemctl --user restart "$unit"
  echo "restarted $unit"
elif ! systemctl --user is-active --quiet "$unit"; then
  systemctl --user start "$unit"
  echo "started $unit"
else
  echo "unchanged $unit"
fi
systemctl --user is-active "$unit"
