#!/usr/bin/env bash
# Optional ADB debloat helper. Dry-run unless --apply is passed.
# Does not unlock the bootloader, does not root, does not claim to stop OEM/baseband telemetry.
set -euo pipefail

APPLY=0
if [[ "${1:-}" == "--apply" ]]; then
  APPLY=1
fi

PACKAGES=(
  com.google.android.gms
  com.google.android.gsf
  com.android.vending
  com.google.android.googlequicksearchbox
  com.google.android.gm
)

echo "Target device:"
adb devices

for pkg in "${PACKAGES[@]}"; do
  if [[ "$APPLY" -eq 1 ]]; then
    echo "disable-user $pkg"
    adb shell pm disable-user --user 0 "$pkg" || adb shell pm uninstall --user 0 "$pkg" || true
  else
    echo "DRY-RUN would: pm disable-user --user 0 $pkg   (or uninstall --user 0)"
  fi
done

if [[ "$APPLY" -eq 0 ]]; then
  echo
  echo "Re-run with --apply to execute. Some OEM apps will break. GMS telemetry is otherwise outside Deft."
fi
