#!/usr/bin/env bash
# Push the OpenRouter key into a DEBUG build on a USB-connected phone.
#
# The launcher keeps the key in EncryptedSharedPreferences, bound to the phone's hardware key for
# that install. A reinstall (or a build signed with another debug keystore) loses it; run this
# again afterwards. Debug builds import the file on next start and delete it.
#
# Key source, first found wins:
#   1. $OPENROUTER_API_KEY
#   2. ~/.config/freedom-launcher/openrouter.key   (chmod 600, outside the repo)
# The key goes over stdin, never on a command line.
set -euo pipefail

PKG="${PKG:-app.deft.debug}"
ADB="${ADB:-$(command -v adb || echo "$HOME/Android/Sdk/platform-tools/adb")}"
KEY_FILE="${KEY_FILE:-$HOME/.config/freedom-launcher/openrouter.key}"

if [[ -n "${OPENROUTER_API_KEY:-}" ]]; then
  key_input() { printf '%s' "$OPENROUTER_API_KEY"; }
elif [[ -f "$KEY_FILE" ]]; then
  key_input() { tr -d '\r\n' < "$KEY_FILE"; }
else
  echo "No key: set OPENROUTER_API_KEY or create $KEY_FILE (chmod 600)." >&2
  exit 1
fi

"$ADB" get-state >/dev/null
key_input | "$ADB" exec-in run-as "$PKG" sh -c 'umask 077; cat > files/agent-key.import'
# Restart so the app imports it now.
"$ADB" shell am force-stop "$PKG"
"$ADB" shell monkey -p "$PKG" -c android.intent.category.HOME 1 >/dev/null 2>&1 || true
sleep 3
if "$ADB" shell run-as "$PKG" ls files/agent-key.import >/dev/null 2>&1; then
  echo "Pushed, but the app has not imported it yet (open the launcher once)."
else
  echo "Key imported into $PKG."
fi
