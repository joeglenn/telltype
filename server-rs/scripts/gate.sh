#!/usr/bin/env bash
# Telltype Server's quality gate. Runs every check, prints one summary, exits non-zero if any failed.
#
#   scripts/gate.sh            fmt, clippy (pedantic, -D warnings), tests with line coverage
#                              (floor 85 %), cargo deny (licences, advisories, sources)
#   SLOW=1 scripts/gate.sh     also the slow real-model test (needs TELLTYPE_PARAKEET_DIR)
#
# Needs cargo-llvm-cov and cargo-deny (cargo install --locked cargo-llvm-cov cargo-deny) and the
# llvm-tools-preview rustup component. Logs of each step: target/gate/<step>.log.
set -uo pipefail

cd "$(dirname "${BASH_SOURCE[0]}")/.."
export PATH="$HOME/.cargo/bin:$PATH"
floor="${COVERAGE_FLOOR:-85}"
logs=target/gate
mkdir -p "$logs"

summary=()
failed=0
step() { # name command...
  local name=$1
  shift
  local started=$SECONDS
  if "$@" >"$logs/$name.log" 2>&1; then
    summary+=("PASS  $name ($((SECONDS - started)) s)")
  else
    summary+=("FAIL  $name ($((SECONDS - started)) s, see $logs/$name.log)")
    failed=1
  fi
}

step fmt cargo fmt --all --check
# Pedantic lints come from [workspace.lints] in Cargo.toml, so its reasoned allow-list applies;
# a command-line -W clippy::pedantic would override that allow-list.
step clippy cargo clippy --workspace --all-targets --locked -- -D warnings

test_args=(--workspace --locked)
if [ "${SLOW:-0}" = 1 ]; then
  [ -n "${TELLTYPE_PARAKEET_DIR:-}" ] || { echo "SLOW=1 needs TELLTYPE_PARAKEET_DIR" >&2; exit 2; }
  test_args+=(-- --include-ignored)
fi
cargo llvm-cov clean --workspace >/dev/null 2>&1
step tests cargo llvm-cov --no-report "${test_args[@]}"
step coverage cargo llvm-cov report --summary-only --fail-under-lines "$floor"
step deny cargo deny --locked check

echo "== Telltype Server gate =="
printf '%s\n' "${summary[@]}"
if [ -f "$logs/tests.log" ]; then
  awk '/^test result:/ { p += $4; f += $6; i += $8 } END { printf "tests: %d passed, %d failed, %d ignored\n", p, f, i }' "$logs/tests.log"
fi
if [ -f "$logs/coverage.log" ]; then
  awk '/^TOTAL/ { printf "line coverage: %s (floor %s %%), regions %s, functions %s\n", $10, floor, $4, $7 }' floor="$floor" "$logs/coverage.log"
fi
if [ -f "$logs/deny.log" ]; then
  grep -E "^(advisories|bans|licenses|sources) (ok|FAILED)" "$logs/deny.log" | paste -sd' ' | sed 's/^/deny: /'
fi
[ "$failed" = 0 ] && echo "GATE PASSED" || echo "GATE FAILED"
exit "$failed"
