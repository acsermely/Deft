# Recipes: manifest, bridge, special pages

## manifest.json

```json
{
  "deft": 1,
  "id": "screen-time",
  "name": "Screen Time",
  "version": "1.0.0",
  "profile": "myapp",
  "start_url": "./index.html",
  "capabilities": [
    { "id": "usage.read", "reason": "Show how long you used each app today" }
  ],
  "network": []
}
```

`capabilities` lists only what the app calls, each with a plain-language `reason` the user reads on the consent screen. Ids come from `skill.read name=js-bridge`; never invent one. A restyle never changes the manifest.

## Reading skill files

On the device, `skill.read name=deft-design/references/recipes/bridge.md`. In Claude Code and other external harnesses, read the same files from `.claude/skills/deft-design/` (or `skill/deft-design/` in the repo). Same content, two ways in.

## Bridge calls (FL200) during a restyle

Old pages use `window.deftBridge`; lint warns FL200. The new form is in `skill.read name=js-bridge`:

| Old | New (`import { ready, call, on, has } from '/_deft/v1/bridge.js'`) |
|---|---|
| `await deftBridge.ready` | `await ready()` |
| `deftBridge.invoke('m', args)` | `call('m', args)` |
| `window.__deftOnBack = () => closed()` | `back(() => closed())` from `/_deft/v1/app.js`. Return exactly `true` when you handled Back; keep the returned `off()` if the handler is temporary. |
| `const id = deftBridge.subscribe('e', fn)` | `const off = on('e', fn)`; call `off()` to stop. There is no subscription id. |

`js-bridge.md` and `runtime.md` (read them with `skill.read name=js-bridge` and `name=runtime`; in Claude Code they are `agent/skills/` in the APK assets) are part of the harness context. They hold the method list, arg shapes and the store and bridge APIs this skill only points at.

`call(m, a)` is `deftBridge.invoke(m, a || {})` and `on(e, fn)` is `deftBridge.subscribe(e, fn)`: same promise, same payload, same event names. So a one-to-one swap changes nothing. Outside the launcher `call()` rejects with `code: 'bridge_not_ready'` (it also prints "Open in Deft") and `on()` returns a no-op.

Presence guard without FL200: `inLauncher()` (the bridge exists) or `has('sms')` (a granted method starts with `sms.`), both from `bridge.js`. `if (!inLauncher()) { showDemo(); return; }`. FL200 is fine to leave when the guard picks a different code path, for example a page that also runs in a plain browser: `if (!window.deftBridge) { showDemo(); return; }`. Replace the guard when it only asks "can I call?": use `has('contacts')` or `has('contacts.list')` (`has(prefix)` is true when a granted method equals the prefix or starts with `prefix.`; it takes method names, not capability ids, so `has('contacts.read')` is always false) or catch the rejection. A small helper keeps call sites clean:

```js
import { call } from '/_deft/v1/bridge.js';
// Returns the result, or the fallback when the bridge is missing or the call fails.
export const tryCall = (m, a, fallback = null) => call(m, a).catch(() => fallback);
```

Is migrating in scope for a restyle? Yes when it is mechanical (a one-to-one swap, same method and arguments) and lint points at it. If the code stores subscription ids, polls for the bridge or branches on it being missing, leave the warning and say so. Warnings do not fail lint. Never change what a call does while restyling.

## Wallpaper pages (manifest `wallpaper.page`)

A wallpaper is a canvas the launcher runs behind the home screen. Use the same tokens as an app (`--deft-bg`, `--deft-label`, `--deft-accent`: read them with `getComputedStyle` for canvas drawing and redraw on theme change), but:

- Motion is minimal or none. No `data-deft-enter`, no `motion.js`, no springs. Redraw only when something changed (once a second for a clock, not every frame) and avoid a free-running `requestAnimationFrame` loop. The launcher runs the page only while the home or lock screen is visible.
- Battery first: no blur or large shadows, one canvas, no polling.
- Folder layout. FL003 rejects `..` in a path, so a module the page shares with the main page lives in the wallpaper's folder. Main page `index.html` may import `./wallpaper/clock.js` (downwards is fine); `wallpaper/main.js` imports `./clock.js`. Never `../clock.js`.

```
index.html  main.js      imports ./wallpaper/clock.js
wallpaper/index.html  main.js  clock.js   (clock.js has the shared drawing code)
```

## IME keyboard pages (manifest `keyboard.page`)

The launcher shows this page as the phone's keyboard, in a fixed-height window. It is not an app screen.

- No `.deft-app` shell, no `.deft-page`, no title, no scroll. Put `<div class="keys">` straight in `<body>`; set `html, body { height: 100%; margin: 0; overflow: hidden }` and `user-select: none`.
- Rows are flex rows that fill the width: `.row { display: flex; flex: 1; min-height: 0; gap: 4px }`, keys `flex: 1; min-width: 0`. Wide keys use `flex: 1.5`, space `flex: 4`. Keys are `<button class="deft-btn key">`.
- The 48px tap rule yields here: `.key { min-height: 0; padding: 0 }`. The window height (`ime.setHeight`, 240 dp) divided by the rows is the key height; keep four rows at 240 dp so each key stays near 56 dp.
- No press haptics unless the user's Android "haptic feedback on keypress" says so: put `data-deft-haptic="off"` on `<html>`. It silences `deft.haptic()` and the automatic press haptic. Do not call `deft.haptic` yourself on the IME page.
- Type on `pointerdown` with `preventDefault()` so the field keeps focus. Selected state (shift on): `background: var(--deft-label); color: var(--deft-bg)`.
- The app page (the one that explains how to enable the keyboard) is a normal app page.
