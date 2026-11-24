#!/usr/bin/env bash
# Serve the sample PWAs on one Mac. Ctrl+C stops all of them.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

if ! command -v python3 >/dev/null 2>&1; then
  echo "Need python3 (macOS includes it)." >&2
  exit 1
fi

LAN="$(ipconfig getifaddr en0 2>/dev/null || ipconfig getifaddr en1 2>/dev/null || echo "(no Wi-Fi IP)")"

pids=()
cleanup() {
  trap - INT TERM EXIT
  echo
  echo "Stopping sample servers…"
  for pid in "${pids[@]+"${pids[@]}"}"; do
    kill "$pid" 2>/dev/null || true
  done
}
trap cleanup INT TERM EXIT

start_one() {
  local port="$1"
  local dir="$2"
  python3 -m http.server "$port" --bind 0.0.0.0 --directory "$dir" &
  pids+=($!)
}

echo "Serving samples from $ROOT (python3 -m http.server)"
echo
echo "  Contacts    http://127.0.0.1:3001/"
echo "  Files       http://127.0.0.1:3002/"
echo "  Messages    http://127.0.0.1:3003/"
echo "  Calendar    http://127.0.0.1:3004/"
echo "  Clock       http://127.0.0.1:3005/"
echo "  Mesh        http://127.0.0.1:3006/"
echo "  Phone       http://127.0.0.1:3007/"
echo "  Three TPS   http://127.0.0.1:3008/"
echo
echo "Paste into Deft (trailing slash required):"
echo "  Emulator:  http://10.0.2.2:3001/ … http://10.0.2.2:3008/"
echo "  Phone:     http://${LAN}:3001/ … http://${LAN}:3008/"
echo
echo "Ctrl+C to stop."
echo

start_one 3001 "$ROOT/samples/contacts"
start_one 3002 "$ROOT/samples/files"
start_one 3003 "$ROOT/samples/sms"
start_one 3004 "$ROOT/samples/calendar"
start_one 3005 "$ROOT/samples/clock"
start_one 3006 "$ROOT/samples/mesh"
start_one 3007 "$ROOT/samples/phone"
start_one 3008 "$ROOT/samples/three-tps"

for port in 3001 3002 3003 3004 3005 3006 3007 3008; do
  ok=0
  for _ in 1 2 3 4 5 6 7 8 9 10; do
    if curl -sf -o /dev/null "http://127.0.0.1:${port}/"; then
      ok=1
      break
    fi
    sleep 0.2
  done
  if [[ "$ok" -ne 1 ]]; then
    echo "Port ${port} did not come up. Is something else using it? Try: lsof -i :${port}" >&2
    exit 1
  fi
done

echo "All eight are up."
wait
