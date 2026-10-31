#!/bin/sh
# Put META_API_KEY from the repo-root .env onto a connected test phone, without printing it.
# Works on debug builds only (run-as). The app re-encrypts it with the Android Keystore on next
# start and deletes this plaintext copy. Release APKs never contain or accept a key this way.
set -eu
cd "$(dirname "$0")/.."
ADB="${ADB:-$HOME/Library/Android/sdk/platform-tools/adb}"
KEY=$(sed -n 's/^META_API_KEY=//p' .env | head -1)
[ -n "$KEY" ] || { echo "META_API_KEY missing in .env" >&2; exit 1; }
printf '%s' "$KEY" | "$ADB" shell "run-as com.saferoute.app sh -c 'mkdir -p no_backup && umask 077 && cat > no_backup/meta_api_key.import'"
"$ADB" shell am force-stop com.saferoute.app
echo "provisioned (${#KEY} chars); start the app once to seal it"
