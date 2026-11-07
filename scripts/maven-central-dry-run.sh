#!/usr/bin/env bash
# Local Maven Central dry-run for com.comraid:comraid.
# - Rejects alpha/deprecated pins in the release classpath
# - publishToMavenLocal
# - Signed Central-shaped bundle validation under comraid/build/repo
#
# Credentials for a real Central upload (never committed):
#   ORG_GRADLE_PROJECT_mavenCentralUsername / ORG_GRADLE_PROJECT_mavenCentralPassword
#   ORG_GRADLE_PROJECT_signingInMemoryKey[+Password]
#
# Usage: ./scripts/maven-central-dry-run.sh
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

echo "==> Checking release classpath for alpha pins"
DEPS="$(./gradlew :comraid:dependencies --configuration releaseRuntimeClasspath --quiet)"
if echo "$DEPS" | grep -E ':[^ ]+-alpha[0-9]*'; then
  echo "FAIL: alpha dependency on releaseRuntimeClasspath" >&2
  exit 1
fi
if ! echo "$DEPS" | grep -q 'security-crypto:1.0.0'; then
  echo "FAIL: expected androidx.security:security-crypto:1.0.0" >&2
  exit 1
fi

run_publish() {
  if [[ -n "${SIGNING_KEY:-}" ]]; then
    ./gradlew "$@" -PsigningInMemoryKey="$SIGNING_KEY"
  else
    ./gradlew "$@"
  fi
}

if command -v gpg >/dev/null 2>&1; then
  TMP_GNUPG="$(mktemp -d)"
  cleanup() { rm -rf "$TMP_GNUPG"; }
  trap cleanup EXIT
  export GNUPGHOME="$TMP_GNUPG"
  chmod 700 "$GNUPGHOME"

  echo "==> Generating ephemeral GPG key for signed dry-run (not for release)"
  gpg --batch --pinentry-mode loopback --passphrase '' --quick-generate-key \
    'ComRaid Dry Run <dry-run@comraid.io>' default default never >/dev/null 2>&1
  KEY_ID="$(gpg --list-secret-keys --with-colons | awk -F: '/^sec:/ {print $5; exit}')"
  SIGNING_KEY="$(gpg --batch --pinentry-mode loopback --passphrase '' --export-secret-keys --armor "$KEY_ID")"
else
  echo "==> gpg not found; unsigned bundle validation only (install gnupg for signed dry-run)"
  SIGNING_KEY=""
fi

echo "==> publishToMavenLocal"
run_publish :comraid:publishToMavenLocal --quiet

echo "==> validateMavenCentralBundle (localTest repo)"
run_publish :comraid:validateMavenCentralBundle

echo "OK: Maven Central dry-run passed for com.comraid:comraid:0.1.0"
