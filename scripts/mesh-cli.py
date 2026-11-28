#!/usr/bin/env python3
"""BLE central that speaks the Mesh sample protocol (GATT write/notify JSON)."""

from __future__ import annotations

import argparse
import asyncio
import json
import sys
from uuid import UUID, uuid4

try:
    from bleak import BleakClient, BleakScanner
    from bleak.exc import BleakError
except ImportError:
    sys.stderr.write("Install bleak in the wrapper venv: ./scripts/mesh-cli scan\n")
    raise

SERVICE = UUID("0000f1ee-0000-1000-8000-00805f9b34fb")
CHAR = UUID("0000f1e1-0000-1000-8000-00805f9b34fb")
SERVICE_S = str(SERVICE).lower()


def looks_like_mesh(service_uuids: list[str] | None) -> bool:
    if not service_uuids:
        return False
    return any(str(u).lower() == SERVICE_S for u in service_uuids)


def encode_msg(nick: str, text: str) -> bytes:
    return json.dumps(
        {
            "v": 1,
            "id": uuid4().hex[:12],
            "ttl": 7,
            "n": nick[:24],
            "t": text[:80],
        },
        separators=(",", ":"),
    ).encode()


SEEN_CAP = 250
_seen: set[str] = set()


def from_peer(data: bytearray | bytes, our_nick: str) -> str | None:
    """Pretty line for a remote flood packet, or None to hide echoes/dupes."""
    raw = bytes(data).decode("utf-8", errors="replace")
    try:
        o = json.loads(raw)
    except json.JSONDecodeError:
        return f"(raw) {raw[:120]}"
    if not isinstance(o, dict) or "t" not in o:
        return f"(raw) {raw[:120]}"
    pid = str(o.get("id") or "")
    if pid:
        if pid in _seen:
            return None
        _seen.add(pid)
        if len(_seen) > SEEN_CAP:
            _seen.pop()
    n = str(o.get("n") or "peer")[:24]
    if n == our_nick[:24]:
        return None
    return f"{n}: {str(o.get('t'))[:80]}"


async def collect(seconds: float) -> dict[str, tuple]:
    found: dict[str, tuple] = {}

    def on_adv(device, adv) -> None:
        uuids = list(adv.service_uuids or [])
        if uuids and not looks_like_mesh(uuids):
            return
        if not uuids:
            return
        found[device.address] = (device, adv.rssi, adv.local_name)

    async with BleakScanner(detection_callback=on_adv, service_uuids=[SERVICE_S]) as scanner:
        await asyncio.sleep(seconds)
        # Also keep anything the scanner still holds (macOS sometimes fills this late).
        for d, adv in scanner.discovered_devices_and_advertisement_data.values():
            uuids = list(adv.service_uuids or [])
            if looks_like_mesh(uuids):
                found[d.address] = (d, adv.rssi, adv.local_name)
    return found


def print_found(found: dict[str, tuple]) -> None:
    if not found:
        print("No Mesh advertisers. On the phone: open Mesh → Go live. Bluetooth on, phone unlocked.")
        return
    print(f"{len(found)} Mesh device(s):")
    for addr, (dev, rssi, name) in found.items():
        label = name or dev.name or "unnamed"
        print(f"  {addr}  rssi={rssi}  {label}")


async def cmd_scan(seconds: float) -> int:
    print(f"Scanning {seconds:.0f}s for {SERVICE_S} …")
    found = await collect(seconds)
    print_found(found)
    return 0 if found else 1


async def cmd_chat(nick: str, seconds: float, address: str | None) -> int:
    stop = asyncio.Event()
    outgoing: asyncio.Queue[str] = asyncio.Queue()
    links: dict[str, BleakClient] = {}
    holding: set[str] = set()

    async def read_stdin() -> None:
        while not stop.is_set():
            line = await asyncio.to_thread(sys.stdin.readline)
            if line == "":
                stop.set()
                return
            text = line.strip()
            if not text:
                print(f"{nick}> ", end="", flush=True)
                continue
            if text in ("/quit", "/exit"):
                stop.set()
                return
            await outgoing.put(text)

    def prompt_status() -> None:
        n = len(links)
        print(f"On air · {n} radio" + ("" if n == 1 else "s") + f". Nick={nick}")
        print(f"{nick}> ", end="", flush=True)

    async def hold(device) -> None:
        addr = device.address
        if addr in holding:
            return
        holding.add(addr)

        def on_notify(_handle: int, data: bytearray) -> None:
            line = from_peer(data, nick)
            if line is None:
                return
            print(f"\r{line}")
            print(f"{nick}> ", end="", flush=True)

        try:
            async with BleakClient(device) as client:
                ch = client.services.get_characteristic(CHAR)
                if ch is None:
                    return
                await asyncio.sleep(0.35)
                await client.start_notify(CHAR, on_notify)
                links[addr] = client
                prompt_status()
                while not stop.is_set() and client.is_connected:
                    await asyncio.sleep(0.4)
        except BleakError as e:
            print(f"radio {addr} dropped ({e})")
        finally:
            links.pop(addr, None)
            holding.discard(addr)
            if not stop.is_set():
                prompt_status()

    async def discover() -> None:
        while not stop.is_set():
            found = await collect(seconds)
            print_found(found)
            for addr, (dev, _rssi, _name) in found.items():
                if address and addr != address:
                    continue
                if addr not in holding:
                    asyncio.create_task(hold(dev))
            try:
                await asyncio.wait_for(stop.wait(), timeout=2.0)
            except asyncio.TimeoutError:
                pass

    async def sender() -> None:
        while not stop.is_set():
            try:
                text = await asyncio.wait_for(outgoing.get(), timeout=0.4)
            except asyncio.TimeoutError:
                continue
            body = encode_msg(nick, text)
            _seen.add(json.loads(body.decode())["id"])
            if not links:
                print("On air locally, no radios yet.")
            for addr, client in list(links.items()):
                try:
                    props = []
                    ch = client.services.get_characteristic(CHAR)
                    if ch is not None:
                        props = [str(p).lower() for p in (ch.properties or [])]
                    no_rsp = any("write-without-response" in p or "write_without_response" in p for p in props)
                    await client.write_gatt_char(CHAR, body, response=not no_rsp)
                except BleakError as e:
                    print(f"write {addr} failed ({e})")
            print(f"{nick}> ", end="", flush=True)

    print("Flood CLI: same packets as Mesh (id + ttl 7). Phone → Go live. Ctrl-D /quit.")
    print(f"{nick}> ", end="", flush=True)
    stdin_task = asyncio.create_task(read_stdin())
    disc_task = asyncio.create_task(discover())
    send_task = asyncio.create_task(sender())
    try:
        await stop.wait()
    finally:
        stop.set()
        for t in (stdin_task, disc_task, send_task):
            t.cancel()
    return 0


def main() -> int:
    p = argparse.ArgumentParser(
        description="Flood the Deft Mesh sample (same JSON as the phone: id + ttl 7). Phone → Go live.",
    )
    p.add_argument("command", nargs="?", default="chat", choices=("scan", "chat"))
    p.add_argument("--nick", default="Mac", help="Nickname in JSON messages (default Mac)")
    p.add_argument("--seconds", type=float, default=8, help="Scan window")
    p.add_argument("--address", help="Connect this BLE address/UUID instead of the first hit")
    args = p.parse_args()
    try:
        if args.command == "scan":
            return asyncio.run(cmd_scan(args.seconds))
        return asyncio.run(cmd_chat(args.nick, args.seconds, args.address))
    except KeyboardInterrupt:
        print()
        return 130


if __name__ == "__main__":
    raise SystemExit(main())
