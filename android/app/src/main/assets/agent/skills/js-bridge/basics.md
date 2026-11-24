# JS bridge: basics, errors, events

App code uses `call()` from `/_deft/v1/bridge.js`:

```js
import { ready, call, on, has } from '/_deft/v1/bridge.js';

await ready();
await call('contacts.list', { limit: 50 });
const off = on('sms.received', (payload) => {});
off();
if (has('contacts')) { /* some contacts.* method is allowed */ }
```

`has(prefix)` is true when a granted method equals `prefix` or starts with `prefix.` (`has('contacts')`, `has('contacts.list')`). It takes method names, not capability ids: `has('contacts.read')` is always false.

`call(method, args)` returns a Promise. The method must be granted (see `bridge-catalog.json`). The host sends the allowed method list in `ready`. Events need the matching capability (`sms.received` needs `sms.read`).

Raw `window.deftBridge` is the legacy fallback. The launcher injects it at document start. Await `ready` (do not poll). In a normal browser the object is missing.

```
if (!window.deftBridge) { /* Open in Deft */ }
await deftBridge.ready
await deftBridge.invoke('contacts.list', { limit: 50 })
const off = deftBridge.subscribe('sms.received', (payload) => {})
off()
```

**Web APIs, not bridge:** `location` → `navigator.geolocation` (indoors GPS can be tens of metres off: use `coords.accuracy`, and do not count small moves as distance); origin storage is always on; `camera` / `microphone` → `getUserMedia`. There is no `geolocation.*` bridge method; `location.*` (`location.background`) is only for location after the user leaves the app. Web Notification API is denied; use `notify.post` (`notifications.post`).

**Bridge capability ids:** `contacts.read`, `contacts.write`, `sms.read`, `sms.write`, `sms.send`, `calls.place`, `calls.control`, `calls.block`, `telephony.ussd`, `calllog.read`, `calllog.write`, `files.pick`, `files.read`, `files.write`, `calendar.read`, `calendar.write`, `alarms`, `notifications.post`, `intents.open`, `intents.start`, `share.receive`, `nfc.read`, `usb`, `bluetooth.serial`, `bluetooth.ble.central`, `bluetooth.ble.peripheral`, `net.local`, `wifi.scan`, `wifi.control`, `apps.list`, `apps.launch`, `usage.read`, `notifications.listen`, `accessibility`, `device.id`, `clipboard.watch`, `location.background`, `auth.session`, `background`, `foreground`, `media.session`, `push`, `accounts.own`, `accounts.read`, `role.browser`, `role.assistant`, `device.admin`, `wallpaper`, `ime`.

Grants come from the consent screen; there is no separate first-use prompt. Call `sms.send`, `sms.sendMms` and `telephony.call` directly from a tap handler (the user's Send or Call button): a call within 5 seconds of a genuine tap goes straight out, one per tap. Scrolls, long-presses and pinches are not taps. Calls from timers, radio events or loops show a launcher confirmation every time.

**Standard ids:** `location`, `camera`, `microphone`.

Only one web app at a time can hold the `sms` or `calls` groups. Deft must be the default SMS app / default dialer for those roles. OS permission dialogs still appear. GATT clients may overlap. Scan, advertise, GATT server, and bonding are one web app at a time (`in_use`).

Agent tools `files.*`, `alarms.schedule`, and `notify.post` act on **launcher config / my-app source**. In app JS, phone files are `call('files.listDir', …)`.

**Error shape.** A failed `call()` rejects with a JS `Error`: `err.message` is the host's text (e.g. `"Not granted"`) and `err.code` is one string. Catch it and read `err.code`, never parse the message:

```js
try { await call('sensors.stepsToday'); }
catch (err) { if (err.code === 'os_permission_missing') { /* show "Open the app to allow activity access" */ } }
```

Codes: `not_granted` (method or origin not allowed; also sent by the page itself when the method is not in the granted list), `unsupported` (unknown method, a response that cannot be used, e.g. `as: 'json'` on invalid JSON, or a method that needs the app on screen called from elsewhere: message `The app is not on screen …`. On screen means open or shown in the agent's Preview; `apps.verify` is not. Window-wide controls (orientation, picture-in-picture, secure, overlays, `fgs.start`) need the open app. Show `e.message` instead of a generic "not available"), `invalid_args`, `os_permission_missing`, `role_missing`, `origin_mismatch`, `cancelled`, `bridge_not_ready`, `in_use`, `rate_limited`, `network_error`, `needs_foreground` (background runs and widgets only). Outside the launcher (plain browser) `call()` rejects with `bridge_not_ready` and the message `Open in Deft`. An HTTP error status from `net.fetch` is not an error: it resolves with `status`. `in_use` is generic (no other-app identity). `bleState` includes `radioOwner: true` only for the locking origin. `bleBonded` is the full adapter list only for the radio owner; other apps see only MACs they already connected. `bleCreateBond` / `bleRemoveBond` require the radio lock or a GATT claim on that MAC.

## subscribe

`sms.received`, `sms.mmsReceived`, `sms.composeRequested`, `telephony.incoming`, `telephony.state` `{state}`, `telephony.ended`, `contacts.changed`, `intents.shareReceived`, `intents.viewReceived`, `assist.invoked`, `alarms.fired`, `clipboard.changed`, `net.found`, `net.lost`, `net.data`, `net.closed`, `bluetooth.sppData`, `bluetooth.sppConnection`, `bluetooth.bleScanResult`, `bluetooth.bleConnection`, `bluetooth.bleCharacteristic`, `bluetooth.bleMtu`, `bluetooth.blePhy`, `bluetooth.bleAdapter`, `bluetooth.bleBond`, `net.found`, `net.lost`, `net.data`, `net.closed`, `bluetooth.bleAdvertise`, `bluetooth.bleServerConnection`, `bluetooth.bleServerService`, `bluetooth.bleServerReadRequest`, `bluetooth.bleServerWriteRequest`.

Optional: `window.__deftOnBack` returning true if you handled system back (close a sheet).

Examples: HTML-only app → `"capabilities": []`. GPS compass → `"capabilities": [{ "id": "location", "reason": "Show heading on the map" }]` and `navigator.geolocation`. Reminders → `alarms` plus `notifications.post`, then `alarms.schedule` / `notify.post`.
