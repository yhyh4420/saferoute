#!/bin/sh
# Copy the phone's opt-in field-test log (contains locations) to pipeline/out/ (gitignored).
set -eu
cd "$(dirname "$0")/.."
ADB="${ADB:-$HOME/Library/Android/sdk/platform-tools/adb}"
OUT="pipeline/out/fieldlog_$(date +%Y%m%d_%H%M).jsonl"
"$ADB" exec-out run-as com.saferoute.app cat files/fieldlog.jsonl > "$OUT"
echo "$(wc -l < "$OUT") lines -> $OUT"
