#!/usr/bin/env bash
# Upload the assembled APK to R2 so the Cloudflare Pages landing can serve it.
# Pages will not host a 25 MB+ file as a static asset.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

RELEASE="$ROOT/android/app/build/outputs/apk/release/app-release.apk"
DEBUG="$ROOT/android/app/build/outputs/apk/debug/app-debug.apk"

if [[ -f "$RELEASE" ]]; then
  FILE="$RELEASE"
elif [[ -f "$DEBUG" ]]; then
  FILE="$DEBUG"
  echo "Using debug APK (no release build found): $FILE"
else
  echo "No APK found. Build one first:" >&2
  echo "  cd android && ./gradlew :app:assembleDebug" >&2
  exit 1
fi

BUCKET="deft-apk"
KEY="deft.apk"

cd "$ROOT/web"

echo "Ensuring R2 bucket $BUCKET exists…"
bunx wrangler r2 bucket create "$BUCKET" 2>/dev/null || true

echo "Uploading $(basename "$FILE") → r2://$BUCKET/$KEY"
bunx wrangler r2 object put "$BUCKET/$KEY" --file "$FILE" --remote

echo "Done. The landing page serves it at /deft.apk"
