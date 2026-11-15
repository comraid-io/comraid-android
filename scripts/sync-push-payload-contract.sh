#!/usr/bin/env bash
# Sync the Push payload contract + goldens from planning (ADR 0017).
#
# Source: sibling ../planning/contracts/push-payload, or COMRAID_PLANNING_ROOT.
# Destination (committed): contracts/push-payload/
#
#   ./scripts/sync-push-payload-contract.sh           # refresh committed copy
#   ./scripts/sync-push-payload-contract.sh --check   # fail if committed copy drifts
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
DEST="$ROOT/contracts/push-payload"

resolve_source() {
  if [[ -n "${COMRAID_PLANNING_ROOT:-}" ]]; then
    echo "${COMRAID_PLANNING_ROOT%/}/contracts/push-payload"
    return
  fi
  local sibling="$ROOT/../planning/contracts/push-payload"
  if [[ -d "$sibling" ]]; then
    echo "$sibling"
    return
  fi
  echo ""
}

SRC="$(resolve_source)"
if [[ -z "$SRC" || ! -d "$SRC" ]]; then
  echo "Push payload contract: planning source not found." >&2
  echo "Set COMRAID_PLANNING_ROOT or clone sibling planning/ beside this repo." >&2
  exit 1
fi

if [[ ! -f "$SRC/contract.json" ]]; then
  echo "Push payload contract: missing $SRC/contract.json" >&2
  exit 1
fi

CHECK=0
if [[ "${1:-}" == "--check" ]]; then
  CHECK=1
elif [[ -n "${1:-}" ]]; then
  echo "Usage: $0 [--check]" >&2
  exit 2
fi

if [[ "$CHECK" -eq 1 ]]; then
  if [[ ! -d "$DEST" ]]; then
    echo "Push payload contract: committed copy missing at $DEST (run sync)." >&2
    exit 1
  fi
  if ! diff -qr "$SRC" "$DEST" >/dev/null; then
    echo "Push payload contract: committed copy drifts from $SRC" >&2
    diff -ur "$SRC" "$DEST" >&2 || true
    echo "Run: ./scripts/sync-push-payload-contract.sh" >&2
    exit 1
  fi
  echo "Push payload contract: committed copy matches $SRC"
  exit 0
fi

rm -rf "$DEST"
mkdir -p "$DEST"
# Byte-for-byte tree copy (contract.json, README.md, goldens/).
cp -a "$SRC/." "$DEST/"
echo "Push payload contract: synced $SRC → $DEST"
