#!/usr/bin/env bash
# Release-APKs mit dem eigenen, geheimen Schlüssel signieren (Schlüsselwechsel, APK Signature Scheme v3):
# - v1/v2 bleiben mit dem bisherigen Schlüssel (Geräte unter Android 9 kennen keinen Wechsel),
# - ab Android 9 gilt der neue Schlüssel; die Kette alt → neu („lineage“) lässt bestehende Installationen das Update
#   annehmen. Danach nimmt ein solches Gerät nichts mehr an, was nur mit dem alten (öffentlichen) Schlüssel signiert ist.
# Der Schlüssel liegt verschlüsselt im Repo (signing/release.p12.enc), das Passwort nur im Secret RELEASE_KEY_PASSWORD.
set -euo pipefail
enc=signing/release.p12.enc
if [ ! -f "$enc" ] || [ -z "${RELEASE_KEY_PASSWORD:-}" ]; then
  echo "::warning::Kein eigener Signaturschlüssel (signing/release.p12.enc oder Secret RELEASE_KEY_PASSWORD fehlt) – APKs bleiben mit dem bisherigen Schlüssel signiert"
  exit 0
fi
tmp=$(mktemp -d)
trap 'rm -rf "$tmp"' EXIT
openssl enc -d -aes-256-cbc -pbkdf2 -iter 600000 -in "$enc" -out "$tmp/release.p12" -pass env:RELEASE_KEY_PASSWORD
bt=$(ls -d "$ANDROID_HOME"/build-tools/* | sort -V | tail -1)
apksigner="$bt/apksigner"
old=(--ks signing/ayutv.keystore --ks-key-alias androiddebugkey --ks-pass pass:android --key-pass pass:android)
new=(--ks "$tmp/release.p12" --ks-key-alias dna --ks-pass env:RELEASE_KEY_PASSWORD --key-pass env:RELEASE_KEY_PASSWORD)
"$apksigner" rotate --out "$tmp/lineage" --old-signer "${old[@]}" --new-signer "${new[@]}"
for apk in "$@"; do
  [ -f "$apk" ] || continue
  "$apksigner" sign "${old[@]}" --next-signer "${new[@]}" --lineage "$tmp/lineage" \
    --rotation-min-sdk-version 28 --v4-signing-enabled false "$apk"
  echo "== $apk"
  "$apksigner" verify --verbose --print-certs "$apk" | grep -E "^Verified using|^Signer|certificate SHA-256|lineage" || true
done
