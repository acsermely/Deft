# Deft

Stock-Android HOME launcher that hosts ordinary web apps (HTML/JS) as first-class apps. The launcher holds OS permissions and roles. Web apps call `window.deftBridge` only for capabilities the web platform does not provide (SMS, contacts, calls, files, calendar, alarms, NFC/BT/USB, native notify, intents). Camera, mic, and GPS stay as WebView APIs.

This is **not** a custom ROM, not root, and not a first-party native messenger/dialer/file manager. Sideload-only: Play will not accept default SMS without a special program.

Landing page (SvelteKit, Cloudflare Pages): **[web/](web/)** — `cd web && npm run dev`, then `npm run deploy`. Publish the APK with `./scripts/publish-apk.sh`.

**New to Android Studio?** Start with **[docs/GETTING_STARTED.md](docs/GETTING_STARTED.md)**: open the `android` folder, install SDK 35, run on an emulator or phone, set as Home, open a bundled sample.

**Direction.** The goal is native parity: a web app in the launcher should be able to do anything a sideloaded Android app can, as long as it declares it and the user grants it (decision D7). The plan is **[docs/PARITY.md](docs/PARITY.md)**.

## What is implemented

- HOME with web apps and the Android app drawer, Settings, a transparency dashboard (grants and usage log), and an on-device agent that writes and edits web apps
- Sample web apps bundled in the APK (read-only; **Copy to my apps** to edit): Contacts, Files, Messages, Calendar, Clock, Phone, Mesh, Three TPS and more. There is no install-by-URL
- Signed zip import/export for local apps; per-app origins `https://<id>.app.deft.internal` with CSP and per-app WebView profiles
- Consent screen (standard web API vs launcher bridge), per-capability grants, confirm sheets for sends and calls, optional biometric confirm
- `WebViewHost` per app, origin-locked bridge, `BridgeService` foreground service with an honest persistent notification
- Bridge modules: contacts, SMS/MMS (`ROLE_SMS`), telephony + in-call (`ROLE_DIALER`), files (SAF), calendar, alarms, notify, intents, share-in, NFC read, Bluetooth Classic + full BLE, USB (open only)
- Camera, mic and geolocation through the normal web APIs behind the install-time grant
- `deft-lint` (bundled into the APK) and the `/_deft/v1/` runtime

## JS API

```js
import { ready, call, on } from '/_deft/v1/bridge.js';

await ready();
const people = await call('contacts.list', { limit: 50 });
const off = on('sms.received', (msg) => {});
```

The method must be declared in `manifest.json` and granted by the user. Full method and event list: [android/app/src/main/assets/agent/skills/js-bridge.md](android/app/src/main/assets/agent/skills/js-bridge.md). `window.deftBridge.invoke(...)` is the legacy fallback.

Location: `navigator.geolocation` only. Camera/mic: `navigator.mediaDevices.getUserMedia` only. There is no `geolocation` or `camera` bridge method.

## Docs

- [docs/GETTING_STARTED.md](docs/GETTING_STARTED.md): build, emulator or phone, samples
- [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md): how the pieces fit
- [docs/PARITY.md](docs/PARITY.md): **the forward plan**: hardening, then native-parity gaps and phases
- [docs/UNIMPLEMENTED.md](docs/UNIMPLEMENTED.md): what Android makes impossible, and rejected designs
- [docs/spec/](docs/spec/): app platform spec ([PLATFORM.md](docs/spec/PLATFORM.md)), decisions ([DECISIONS.md](docs/spec/DECISIONS.md)), platform roadmap ([ROADMAP.md](docs/spec/ROADMAP.md))
- [docs/SIDELOAD.md](docs/SIDELOAD.md), [docs/GMS.md](docs/GMS.md), [docs/REPRODUCIBLE.md](docs/REPRODUCIBLE.md), [docs/spec-trace.md](docs/spec-trace.md)
- [scripts/debloat.sh](scripts/debloat.sh): ADB helper, dry-run by default, **not** in the launcher UI

Local gate: `bun scripts/check.ts` (add `--device` for instrumented tests on a connected phone).
