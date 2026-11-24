#!/usr/bin/env bash
# Pull on-device agent session logs (JSONL) for bug hunting. Does not start or reset chats.
set -euo pipefail

PKG="${PKG:-app.deft.debug}"
ADB="${ADB:-$HOME/Library/Android/sdk/platform-tools/adb}"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
OUT="${1:-$ROOT/agent-pull}"

if [[ ! -x "$ADB" ]]; then
  ADB="$(command -v adb)"
fi

serial="${ANDROID_SERIAL:-}"
if [[ -z "$serial" ]]; then
  serial="$("$ADB" devices | awk '/^R[0-9A-Z]+[[:space:]]+device/{print $1; exit}')"
fi
if [[ -z "$serial" ]]; then
  serial="$("$ADB" devices | awk '/[[:space:]]device$/{print $1; exit}')"
fi
if [[ -z "$serial" ]]; then
  echo "No adb device. Plug in the phone and enable USB debugging." >&2
  exit 1
fi

echo "device=$serial package=$PKG"
mkdir -p "$OUT"
CURRENT="$("$ADB" -s "$serial" shell "run-as $PKG cat files/agent/current" 2>/dev/null | tr -d '\r' || true)"
echo "current_session=${CURRENT:-"(none)"}"
echo "$CURRENT" > "$OUT/current"

SESS="$("$ADB" -s "$serial" shell "run-as $PKG ls files/agent/sessions" 2>/dev/null | tr -d '\r' || true)"
if [[ -z "$SESS" ]]; then
  echo "No sessions under files/agent/sessions." >&2
  exit 0
fi

while IFS= read -r id; do
  [[ -z "$id" ]] && continue
  dest="$OUT/$id"
  mkdir -p "$dest"
  "$ADB" -s "$serial" exec-out run-as "$PKG" cat "files/agent/sessions/$id/meta.json" > "$dest/meta.json" 2>/dev/null || true
  "$ADB" -s "$serial" exec-out run-as "$PKG" cat "files/agent/sessions/$id/events.jsonl" > "$dest/events.jsonl" 2>/dev/null || true
  echo "pulled $id ($(wc -l < "$dest/events.jsonl" | tr -d ' ') events)"
done <<< "$SESS"

if [[ -n "$CURRENT" && -f "$OUT/$CURRENT/events.jsonl" ]]; then
  echo "active log: $OUT/$CURRENT/events.jsonl"
fi
