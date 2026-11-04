#!/usr/bin/env bash
# Envia a chave de release (de keystore.properties) para os secrets do GitHub,
# usados pelo workflow .github/workflows/android.yml ao publicar uma versão.
set -euo pipefail
cd "$(dirname "$0")/.."
[ -f keystore.properties ] || { echo "keystore.properties não encontrado"; exit 1; }
set -a; . ./keystore.properties; set +a
repo=$(gh repo view --json nameWithOwner -q .nameWithOwner)
base64 -w0 "$storeFile"    | gh secret set KEYSTORE_BASE64   -R "$repo"
printf '%s' "$storePassword" | gh secret set KEYSTORE_PASSWORD -R "$repo"
printf '%s' "$keyAlias"      | gh secret set KEY_ALIAS         -R "$repo"
printf '%s' "$keyPassword"   | gh secret set KEY_PASSWORD      -R "$repo"
gh secret list -R "$repo"
