# Native parity — gap analysis and plan

**Goal.** A web app running in Deft (WebView + `deftBridge`) can do what a sideloaded native Android app can do, with the same rule as the rest of the platform: **declared in the manifest, granted by the user, enforced by the host** ([spec §1, Parity goal](spec/PLATFORM.md)).

**Status (2026-11-19).** Phases H, A, B (B1–B4), C, D, E (E1–E3, E5), F, G done on branch `parity/rest`, not yet merged; E4 is research-only (bridge spec in PARITY_EXECUTION.md Step 37b); G13 not started. Device checks pending. Bridge: 39 namespaces, 222 methods, 80 capability ids. Host: `WebViewHostActivity`, `DeftWebViewConfig`, `LocalOriginPathHandler`, `CspBuilder`.

**How to use this file.** Each row becomes one PR. The step-by-step build plan, with a spec per row, is [PARITY_EXECUTION.md](PARITY_EXECUTION.md). This is the single forward plan for the launcher. When a row ships, move it out of here and update [UNIMPLEMENTED.md](UNIMPLEMENTED.md) if it listed the item, the bridge reference `assets/agent/skills/js-bridge.md`, `assets/agent/bridge-catalog.json` and `Capability.kt` in the same PR.

---

## 1. Where the gaps are

What a native app has, compared with what an FL app has today.

| Area | Native app | FL app today | Gap size |
| --- | --- | --- | --- |
| **Running when closed** | WorkManager, foreground services, broadcast receivers, `JobScheduler` | No JS runs unless the WebView is on screen. Events are queued in Room and delivered on next open (`BridgeService.emit`). Only `alarms` and SMS post notifications while closed. | **Largest** |
| **Notifications** | Channels, actions, inline reply, progress, ongoing, grouping, update/cancel, full-screen intent | `notify.post {title, body, url?}` only | Large |
| **Intents in/out** | Any intent filter (links, MIME types, custom actions); start any activity; `startActivityForResult` | Inbound: share-in only. Outbound: `intents.open` with 6 URL schemes. No results back. | Large |
| **System surfaces** | Home widgets, app shortcuts, Quick Settings tiles, PiP, media session / lock-screen controls | None | Large |
| **Network** | Open network, raw TCP/UDP, mDNS/NSD, local network | `connect-src` limited to manifest origins; remote images blocked; no sockets; no discovery | Large |
| **Hardware: USB** | Bulk/control/interrupt transfers, serial | List, permission, open, close. **No data transfer.** | Medium (unfinished) |
| **Hardware: NFC** | Read/write NDEF, IsoDep/APDU, NfcA/B/F/V, host card emulation | `nfc.read` only | Medium (unfinished) |
| **Hardware: other** | Sensors (step counter, light, pressure…), torch, audio routing/volume/ringer, DND, Keystore keys, speech recognizer, TTS, print | Only what the web exposes (DeviceMotion/Orientation, `getUserMedia`, `crypto.subtle`) | Medium |
| **Web APIs the host drops** | n/a | `<input type="file">` (no `onShowFileChooser`), downloads (no-op listener), fullscreen video (no `onShowCustomView`), `navigator.vibrate` (no `VIBRATE` permission in the manifest), DRM (`PROTECTED_MEDIA_ID` ignored), MIDI ignored, wake lock / Web Share unverified | Small each, many of them |
| **Media library** | `MediaStore` queries (gallery apps) | Picker only (`files.pickMedia`) | Medium |
| **Auth** | OAuth via Custom Tab + redirect, `AccountManager`, passkeys | None | Medium |
| **Size and code** | 100+ MB APK, native code (NDK) | 25 MiB package cap, 2 MiB per binary, no WebAssembly (CSP) | Medium |
| **Cross-app powers** | Accessibility, notification listener, usage stats, app list, accounts, Wi-Fi scan, background location, extra roles (IME, browser, assistant) | Refused as tracking | Large (now planned, section 2) |
| **Telephony extras** | Call screening, blocked numbers, dual-SIM choice, visual voicemail | Place/answer/reject/hold/DTMF/call log | Small–medium |

Already at or near parity, so not in this plan (apart from hardening in Phase H): SMS/MMS, contacts, calendar, files (SAF), alarms, Bluetooth Classic + BLE (central and peripheral), camera/mic/geolocation (foreground), in-call control.

---

## 2. Former refusals — decided: allowed as capabilities

**Decision D7 (2026-11-17, [DECISIONS.md](spec/DECISIONS.md)).** Everything is opt-in and permission-gated, so the former refusals become capabilities like any other. The only things that stay out are those Android or the lack of GMS make impossible for a sideloaded app (section 2.3).

### 2.1 The `special` risk tier

Mirrors Android's own "special app access" (accessibility, notification listener, usage access). It is the guard rail that replaces refusal.

- New `Risk.SPECIAL` above `HIGH` in `Capability.kt`.
- **Never granted from the install consent screen.** The consent screen lists it as "asks later". A DENIED row is the user's final no only with the user-denial flag (the dashboard's off switch, or "Don't ask again"); without it the app may ask later. Special access is granted only through the launcher's own sheet after the screen lock: from the dashboard, or opened at the point of use when the app asks. Web code can never grant, and the consent screen never grants. The sheet names the app and shows the manifest `reason`.
- If Android has its own special-access toggle (accessibility, notification listener, usage access, DND access), the launcher holds it and the dashboard deep-links to the system screen once; per-origin access is the FL grant on top.
- **One holder at a time** for cross-app surveillance powers (`exclusiveGroup`), like the SMS role.
- **Visible while active:** a persistent "<App> is using <power>" notification for background location, notification listener, accessibility, open network in background.
- **Audit** in plain words on the dashboard (already exists for bridge calls), plus a monthly "still want this?" reminder for unused `special` grants that auto-revokes after 90 days of no use (like Android's permission auto-reset).
- Agent-created apps never get `special` grants without the user doing it in the dashboard (spec §8.6 already says this for all grants).
- **Plain grants at the point of use.** A declared plain capability the user has never decided (no grant row) opens one consent screen for the plain capabilities the call or web request needs (a camera and microphone request gets one screen, each ticked by default); unticked ones are recorded as "not now" (denied, but the app may ask later). At the point of use the sheet offers Allow, "Not now" (asks later, after a 30 s cooldown) and "Don't ask again" (a user denial, never asked again); only the dashboard's off switch and "Don't ask again" are user denials. Limit: a remote or catalog-installed app (neither workspace nor bundled) gets the hinted error instead of an ask.

### 2.2 Items moved from refused to planned

| # | Ability | Capability (risk) | Notes |
| --- | --- | --- | --- |
| P1 | Open network: any HTTPS origin, WebSocket, EventSource | `network.open` (high) | `connect-src https: wss:`; per-app network log and "Block network" switch stay (spec §8.5) |
| P2 | Remote images and media | follows `connect-src` | `img-src`/`media-src` gain the same origins as `connect-src` |
| P3 | User-entered server origins | `network.user` (medium) | Host screen to add a self-hosted server URL; narrower than P1 |
| P4 | WebAssembly | `wasm` (medium) | `'wasm-unsafe-eval'` for that app only; `.wasm` must be a hash-pinned dependency (D6) or a file in the package. Spec principle 3 is relaxed: shipping a prebuilt `.wasm` is allowed, building it is the author's business |
| P5 | Larger apps / asset packs | `storage.large` (low) | Per-app cap up to the device's free space with a consent line; asset packs hash-verified and covered by the package signature |
| P6 | Raw sockets, mDNS/NSD, LAN | `net.local` (high), `net.sockets` (high) | LAN-only first; internet sockets need `network.open` too |
| P7 | Background location, geofences, GNSS measurements | `location.background` (special) | Requires D3 foreground service or geofence wake; Android's own background-location prompt still applies |
| P8 | Wi-Fi scan, SSID/BSSID, Wi-Fi state control | `wifi.scan` (high), `wifi.control` (high) | Implies location; needs `location` granted too, as Android does |
| P9 | Installed-app list, launch other apps | `apps.list` (special), `apps.launch` (medium) | `QUERY_ALL_PACKAGES` is already effectively held by the HOME drawer |
| P10 | Usage stats | `usage.read` (special, exclusive) | `PACKAGE_USAGE_STATS` system toggle |
| P11 | Notification listener (read/dismiss/reply to other apps' notifications) | `notifications.listen` (special, exclusive) | `NotificationListenerService` in the launcher, events to the holder origin only |
| P12 | Accessibility service | `accessibility` (special, exclusive) | `AccessibilityService` in the launcher; holder gets window content events and can perform actions. Persistent notification |
| P13 | Clipboard observer | `clipboard.watch` (special) | Android 10+ only delivers clipboard to the focused app or IME; works while the FL app is in front, or if FL is the default IME (see P17) |
| P14 | `AccountManager` | `accounts.read` (special), `accounts.own` (medium) | `accounts.own`: the app registers its own account type through a launcher authenticator, enabling sync adapters |
| P15 | Stable per-app device id | `device.id` (medium) | Derived per origin (`HMAC(launcher secret, origin)`), like Android's per-signing-key `ANDROID_ID`; resets on reinstall |
| P16 ✓ | Full SMS / call-log export | existing `sms.read`, `calllog.read` | Drop the "never bulk" rule; keep the audit log and add a size-based confirm (e.g. >500 rows) |
| P17 | More roles: browser, assistant, IME, call screening, wallpaper, device admin | `role.browser`, `role.assistant`, `ime`, `calls.screen`, `wallpaper`, `device.admin` (special, exclusive each) | Launcher declares the components; the holder origin implements them in JS (an IME as a WebView keyboard surface, live wallpaper as a WebView) |
| P18 | App-to-app calls | `intents.*` (Phase E) | Stays host-routed (A → host → B with chooser); this is the Android model too |
| P19 | FCM push | `push` (medium), separate `gms` build flavor | UnifiedPush stays the default; an optional flavor adds FCM for users who keep GMS. [GMS.md](GMS.md) gets a section on it |
| P20 | Third-party scripts and embeds (payment SDKs, maps, video, sign-in widgets) | `web.embed` (high) | Per app, the user approves specific origins (e.g. `js.stripe.com`, `*.paypal.com`). The host adds them to `script-src`, `frame-src` and `connect-src` for that app only; code from them runs with the app's grants, which the consent screen says. Lint FL1xx/FL2xx learn the declared list. Required by Stripe, PayPal, Adyen and 3-D Secure, which forbid self-hosting and use iframes. Added 2026-11-18 after the real-world review |

### 2.3 Still impossible (stays in UNIMPLEMENTED "Cannot")

Not policy: Android blocks these for any sideloaded, non-privileged app, or they need Google.

- IMEI, IMSI, ICCID, serial, real hardware MAC (Android 10+ returns placeholders or throws for third-party apps).
- eSIM `EuiccManager`, baseband, OEM privileged APIs.
- Advertising ID, Play Integrity / SafetyNet (need GMS; the `gms` flavor could expose Advertising ID later if wanted).
- Separate UID / signature per origin; dynamic verified App Links; RCS public API; other apps' private databases.

## 3. Plan

Ordered by value per effort and by dependency. Each phase is shippable on its own. Risk levels follow spec §8.4.

### Phase H — Hardening (carried over from RETHINK)

Security fixes that make the existing grant model hold. Parity adds powers, so these come first: a new capability is only as safe as the consent and install path in front of it.

**Done (2026-11-17):** H1–H6, marked ✓ below. H7 was dropped (2026-11-18). Phase H is complete.

| Item | Problem today | Target | Done when |
| --- | --- | --- | --- |
| H1 Send/call confirmation ✓ | Any `ACTION_UP` (even the end of a scroll) counts as the tap that skips the sheet for `sms.send`, `sms.sendMms`, `telephony.call` ([BridgeService.kt:195](../android/app/src/main/java/app/deft/bridge/BridgeService.kt:195)). `confirmBiometric` is read from a stale app object | **Decided: real taps only.** `UserGesture.track` counts one finger, within touch slop, released before the long-press timeout; `confirmBiometric` is read fresh in `invoke`. Tests: `UserGestureTest` | A synthesized scroll followed by `sms.send` shows the sheet; enabling biometric mid-session applies to the next send |
| H2 One origin id per app ✓ | Origin and WebView profile derive from the slug; a local app can collide with a bundled app's profile; delete leaves overlay, key and profile behind | New rows get a random 20-char base32 origin id (`OriginIds.local`), or the spec hash for packages; existing rows keep theirs so no storage is lost. `deleteProject` removes project, overlay, key, version, rollback; `uninstall` also deletes the WebView profile. Tests: `OriginIdsTest` | Same slug twice gets two origins; recreate after delete reuses nothing |
| H3 Serve one tree ✓ | Host uses overlay + base; preview, verifier and lint use the base only; `connect-src` reads the base manifest | `MergedAppTree` is used by the host, the agent preview (now with the consented `connect-src`), the verifier, lint (`collect`/`collectOne`) and the network allow-list. Tests: `MergedAppTreeTest` | An overlay edit shows up identically in all of them |
| H4 Permissions are not source edits ✓ | Agent edit to `manifest.json` `network` takes effect on next launch without consent; parser accepts IP literals and `localhost` ([DeftManifest.kt:46](../android/app/src/main/java/app/deft/registry/DeftManifest.kt:46)) | Served allow-list = manifest ∩ consented set (`network_consent` table, `NetworkConsent`); a new origin opens the consent screen from the host; shrinking revokes; apps installed before this adopt their manifest once. Parser and lint FL704 share one rule and also reject undotted hosts and `*.deft.internal`. Tests: `NetworkConsentTest`, `DeftManifestTest`, lint FL704 | Agent-added origin changes nothing until Allow |
| H5 Install is one path ✓ | Import verifies the signature but writes into the live tree; lint runs after install; no downgrade check | `prepareInstall` (signature, same key, no downgrade via `deft/keys/<slug>.version`, path rules and quotas) → lint in memory (an error aborts) → `commitInstall` (extract to `deft/staging`, swap into place). Consent for new caps/origins/libraries runs on next open (H4 and existing gates); binding the Allow tap to a manifest hash is not done. Unsigned zip import still writes directly. Tests: `DeftPackageTest` | A bad package leaves the old app byte-for-byte in place |
| H6 Shares stay inside the grant ✓ | `ShareToActivity` copies any stream (even `file://` launcher paths) into `cacheDir/share/<app>`, which `files.read` can browse (`FilesModule.shareRoot`) | `content://` only, copied into a private `ShareInbox` (the sender's grant ends with the share sheet), event carries `items[{id,name,mime,size}]`, `intents.readShare {id}` reads once, 24 h expiry; `shareRoot` removed; `file_paths.xml` narrowed to Downloads and `cache/mms/`. Tests: `ShareInboxTest` | A `file://` share writes nothing; a share can't be listed later |
| H7 Bluetooth device picker — dropped | Proposed forcing a device picker before `bleConnect`/`sppConnect` | **Dropped (2026-11-18).** Under D7 an app with the Bluetooth grant can scan and connect like a native app; a forced picker would hide nothing (scan stays granted) and would break automatic peer linking in Mesh. An opt-in `bluetooth.pick` for "choose your device" flows can be added in Phase F if an app needs it | — |

### Phase A — Host quick wins (no new bridge modules)

**Done (2026-11-18), checked on the API 37 emulator and the Galaxy A16:** A1–A9. Standard APIs are routed by shims in the injected client (`samples/deft-bridge.js`), so web code needs no changes. Tests: `bridge-shims.test.js`, `WebPlatformTest`, `UserGestureTest`; hands-on sample `samples/platform-check` (one button per item, results logged as `PC <item> OK|FAIL` in the console).

**Fixed in that check (2026-11-18):** host dialogs started from a bridge call with `FLAG_ACTIVITY_NEW_TASK` (`ConfirmActivity`, `SaveAsActivity`, `TreePickActivity`, `MediaPickActivity`, `UsbPermissionActivity`, `BluetoothEnableActivity`, `NfcReadActivity`) opened in the launcher's Home task, so closing them left the user on Home instead of in the app. They now have `taskAffinity=""` and `excludeFromRecents`, so each runs in its own short task and closing it returns to the app. Retested with Save as (A2) and the Files folder picker on the emulator (FL as the default Home app), and with Save as on the Galaxy A16.

Small fixes in `WebViewHostActivity` / `DeftWebViewConfig`. These make ordinary web code behave like it does in Chrome.

| Item | Work | Capability | Done when |
| --- | --- | --- | --- |
| A1 File input ✓ | Implement `onShowFileChooser` → SAF `ACTION_OPEN_DOCUMENT` (respect `accept`, `multiple`, `capture`) | none (user picks) | `<input type="file">` and `capture="environment"` work in a sample |
| A2 Downloads ✓ | Replace no-op `setDownloadListener` with `ACTION_CREATE_DOCUMENT` save, also handle `blob:`/`data:` `<a download>` | `files.write` (spec §8.3 already says so) | Sample exports a CSV through the system save dialog |
| A3 Vibration ✓ | Add `android.permission.VIBRATE`; verify `navigator.vibrate` | `haptics` (low) — already named in spec §8.4 | Vibration works; revoked grant makes it a no-op |
| A4 Fullscreen & orientation ✓ | `onShowCustomView`/`onHideCustomView`; immersive mode; map Screen Orientation `lock()` to `requestedOrientation` via a small `display.*` bridge if WebView lacks it | none | Video fullscreen works; game sample locks landscape |
| A5 Wake lock ✓ | Verify Screen Wake Lock API; fallback `display.keepOn` → `FLAG_KEEP_SCREEN_ON` while visible | none (low) | Screen stays on in a timer sample |
| A6 Web Share out ✓ | Verify `navigator.share`; if missing, polyfill through host `ACTION_SEND` chooser for text/URL/files | none | Share text and a blob image to another app |
| A7 Clipboard read ✓ | `clipboard.read` bridge, user-gesture gated | `clipboard.read` (medium, in spec) | Paste button works; no background reads |
| A8 Print ✓ | `print.page` → `PrintManager` with the WebView's `createPrintDocumentAdapter` | none | Sample prints / saves PDF |
| A9 DRM & MIDI ✓ | Grant `PROTECTED_MEDIA_ID` / `MIDI_SYSEX` behind capabilities | `media.drm`, `midi` (medium) | Widevine test stream plays; MIDI keyboard works |

### Phase B — Finish half-built hardware modules

**Done (2026-11-18), not yet checked on hardware:** B1–B3. Tests: `UsbTransfersTest`, `NfcPayloadsTest`.

| Item | Work | Capability | Done when |
| --- | --- | --- | --- |
| B1 USB transfers ✓ | `usb.claimInterface`, `usb.bulkTransfer`, `usb.controlTransfer`, `usb.interruptTransfer` (base64 in/out, size limits, timeouts), `usb.releaseInterface`; events for async reads. Optional `usb.serial*` helpers for CDC-ACM / common chips | `usb` (high) | Talk to an Arduino over CDC-ACM from a sample |
| B2 NFC write & tech ✓ | `nfc.scanStart`/`scanStop` (reader mode in the app's own screen, `nfc.tag` events), `nfc.writeNdef` (formats blank tags), `nfc.makeReadOnly` (always confirmed), `nfc.transceive` for IsoDep/NfcA/B/F/V/MIFARE | split `nfc.read` / `nfc.write` / `nfc.tech` | Write a URL tag; read a transit card's ISO-DEP response |
| B3 NFC HCE ✓ | `DeftHostApduService`; AIDs registered at runtime for the one holder (`nfc.hceRegister`); each APDU is an `nfc.hceCommand` event answered with `nfc.hceRespond` within 1.5 s, else 6F00. Works only while the holder app is open until Phase D adds background execution | `nfc.hce` (high, exclusive) | Phone emulates a test tag to another reader |
| B4 Payment-category HCE ✓ | Second `HostApduService` with a `payment` AID group so the holder can become Android's default contactless-payment app (system setting, user-chosen); AIDs per payment network registered at runtime. Built: `DeftPaymentApduService` (disabled until a holder exists, `requireDeviceUnlock`), `nfc.paymentRegister/paymentRespond`, `nfc.paymentCommand` events answered within 1.5 s else 6F00 (B3's routing, shared in `ApduRouter`/`HcePending`); the G0 sheet asks Android (`ACTION_CHANGE_DEFAULT`, or the wallet role where the phone has it) and reads back the default; revoking disables the service and drops the AIDs. Only an open holder page answers: a background run cannot start within 1.5 s. Unit tests only (`HceRoutingTest`, `SpecialReviewTest`): **cannot be verified without a payment terminal**, and real payments also need network-issued card credentials and tokenisation that a sideloaded app cannot obtain | `nfc.hce.payment` (special, exclusive) | A test terminal selects the payment AID and gets the app's answer. Real card payments still need network-certified tokens, which no launcher can provide |

### Phase C — Rich notifications and system surfaces

**Done (2026-11-18), checked on the Galaxy A16:** C1–C5. C4 shipped as native data cards (title, text, value, tap opens the app) instead of live pages on Home. Tests: `NotifySpecTest`, `WidgetStoreTest`, `DeftManifestTest`, lint FL708.

The launcher is HOME, so several surfaces native apps can't get easily are cheap here.

| Item | Work | Capability | Done when |
| --- | --- | --- | --- |
| C1 Notification channels ✓ | Per-app channels (`notify.createChannel {id, name, importance}`), namespaced by origin | `notifications.post` | Two channels show in system settings under FL with app-name prefix |
| C2 Rich notify ✓ | `notify.post` gains `id`, `actions[]`, `reply`, `progress`, `ongoing`, `group`, `image`; add `notify.update`, `notify.cancel`, `notify.list` (own only). Taps/actions/replies emit `notify.action` events | `notifications.post` | Messaging sample replies inline from the shade |
| C3 App shortcuts ✓ | Manifest `shortcuts[]` (max 8, same-origin paths) → long-press menu on Home (list and dock); lint FL708 | none (manifest) | Long-press an app icon shows its shortcuts |
| C4 Home widgets ✓ | `widgets.update {id, title, text, value, url}`: up to 3 native cards per app above the dock; no app code on Home. Live mini-pages or a real `AppWidgetProvider` can follow if needed | `widgets` (low) | Clock/weather widget on the home screen updates every N minutes |
| C5 Quick Settings tile ✓ | Three `TileService` slots; `tiles.request` claims one and shows Android's "Add tile?" prompt (13+); `tiles.update` sets label/state; tap emits `tile.clicked` and opens the app if it is closed | `tiles` (low) | Toggle tile flips state in an app |

### Phase D — Running when closed (the big one)

Today no app code runs off-screen. This phase adds host-scheduled, budgeted background JS, which spec §1 already allows ("only as host-scheduled, budgeted tasks").

| Item | Work | Capability | Done when |
| --- | --- | --- | --- |
| D1 Headless runner ✓ | `BackgroundRunner`: an off-screen WebView (or one per app, pooled) that loads the app's declared `background` module on its origin with the same CSP/profile, exposes the same bridge, and is torn down after a time/CPU budget (e.g. 30 s) | `background` (medium) | A task runs after the app process is killed and is visible in the audit log |
| D2 Triggers ✓ | Wake the runner on: `alarms.fired`, `sms.received`, `telephony.incoming`, `notify.action`, boot, connectivity change, periodic (`WorkManager`, ≥15 min), charging. Manifest declares which | per trigger, existing caps | SMS auto-reply sample works with the app closed |
| D3 Foreground service ✓ | `fgs.start {type, title}` → typed FGS (`location`, `mediaPlayback`, `microphone`, `connectedDevice`, `dataSync`) holding the app's WebView alive with a mandatory notification; `fgs.stop` | `foreground` (high), plus the matching sensor cap | Workout sample records GPS track with the screen off |
| D4 Media session ✓ | Background audio via D3 `mediaPlayback`; map Media Session API (or `media.*` bridge) to `MediaSessionCompat`: lock-screen controls, headset buttons, Bluetooth AVRCP | `media.session` (medium) | Podcast sample keeps playing and is controllable from lock screen |
| D5 Picture-in-picture ✓ | `enterPictureInPictureMode` for the app task on request or on home press | `pip` (low) | Video sample continues in PiP |
| D6 Push without Google ✓ | UnifiedPush distributor client in the launcher; `push.register` returns an endpoint per app; message → D1 runner or `notify.post` | `push` (medium) | ntfy-backed sample receives a push with the app closed |

### Phase E — Intents, interop and auth

| Item | Work | Capability | Done when |
| --- | --- | --- | --- |
| E1 Inbound handlers ✓ | Manifest `handles: {links?, mimeTypes?, schemes?}`. Launcher declares broad static filters (`VIEW` for common MIME types, `SENDTO`, custom `web+` schemes) and routes to the app the user chooses (host picker, remember choice). Https links follow the existing "not a default browser" rule | none (manifest `handles`, files need `share.receive`) | "Open with → PDF viewer app" from the Files app opens the FL app with the content URI readable via the bridge |
| E2 Outbound intents ✓ | `intents.start {action, type?, data?, extras?}` with an allow-listed action set (`SEND`, `VIEW`, `EDIT`, `INSERT`, `IMAGE_CAPTURE`, `PICK`, `SET_ALARM`, …), always through a chooser; `intents.startForResult` returns result data/URIs | `intents.open` split → `intents.start` (medium) | Sample opens the system camera via `IMAGE_CAPTURE` and gets the photo back |
| E3 Auth session ✓ | `auth.start {url, redirect}` → Custom Tab; redirect captured by a launcher scheme and returned to the calling origin only | `auth.session` (medium) | OAuth login against a test IdP completes |
| E4 Passkeys | Researched 2026-11-19 (Step 37). **WebAuthn inside FL WebViews: cannot.** `WEB_AUTHENTICATION_SUPPORT_FOR_APP` sends the origin `android:apk-key-hash:<launcher cert>` (the same for every FL app) and needs the RP domain's `assetlinks.json` to name the launcher's package and cert; no third-party site does, and `<id>.app.deft.internal` has no public DNS for asset links. `_FOR_BROWSER` passes the page origin through Credential Manager, but each passkey provider decides which browsers it trusts (Google Password Manager by application, Bitwarden by its list or a manual trust), and an RP ID under `.deft.internal` is owned by nobody, so it gives no phishing protection. WebAuthn stays off (`NONE`, the default) in FL WebViews ([UNIMPLEMENTED.md](UNIMPLEMENTED.md)). **Works today:** passkeys on real sites through E3 `auth.start` (the user's browser does WebAuthn for the site's own origin). **Possible with conditions:** a Credential Manager bridge for RPs that opt in by listing the launcher cert and the app's origin id (Android 14+, a passkey provider app; [PARITY_EXECUTION.md](PARITY_EXECUTION.md) Step 37b, not scheduled) | `webauthn` (medium) | See Step 37b in PARITY_EXECUTION.md |
| E5 Web Payment Request ✓ | Enable the WebView Payment Request API (androidx.webkit `PAYMENT_REQUEST` where supported) so `new PaymentRequest(...)` can launch installed Android payment apps; no Google Pay without GMS | `payments.request` (medium) | A sample pays through a test payment app on the device |

### Phase F — Hardware and system depth

| Item | Work | Capability | Done when |
| --- | --- | --- | --- |
| F1 Sensors ✓ | `sensors.list`, `sensors.subscribe {type, rateHz}` for step counter/detector, light, pressure, proximity, magnetometer, humidity; rate limits; foreground-only unless D3 | `sensors` (medium); step counter needs `ACTIVITY_RECOGNITION` | Pedometer sample counts steps |
| F2 Keystore ✓ | `keystore.generate {alias, alg, requireBiometric?}`, `sign`, `encrypt/decrypt`, `delete`; keys namespaced per origin, **no attestation** (Never list) | `keystore` (medium) | Password-manager sample stores a biometric-locked secret |
| F3 Speech & TTS ✓ | `speech.recognize` → `SpeechRecognizer` (on-device where possible); `tts.speak` → `TextToSpeech` if Web Speech is unusable in WebView | `microphone` / none | Voice note to text works offline on supported devices |
| F4 Torch & camera extras (torch done; RAW/manual controls, zoom/focus, barcode pending) | `camera.torch {on}`; Camera2 RAW/manual controls via a `camera.capture` bridge if `getUserMedia` is not enough; verify `getUserMedia` zoom/focus constraints; barcode via `BarcodeDetector` if present, else bridge | `camera` | QR scanner sample with torch |
| F5 Audio & DND ✓ | `audio.getVolumes/setVolume`, ringer mode, `audio.route` (speaker/earpiece/BT), DND read/set (needs notification policy access) | `audio.control` (medium), `dnd` (high) | Alarm-clock sample raises alarm volume |
| F6 Media library ✓ `media.query {kind, since?, limit}` → `MediaStore` images/video/audio with thumbnails; separate from the picker | `media.read` (high) | Gallery sample lists all photos |
| F7 Telephony extras ✓ (visual voicemail not built) | `CallScreeningService` holder, blocked numbers (`BlockedNumberContract`, needs dialer role), SIM choice for `telephony.call`/`sms.send` via `SubscriptionManager` (display names only, no IMSI/ICCID); USSD via `TelephonyManager.sendUssdRequest` (confirm-each; today `*`/`#` are refused); visual voicemail | `calls.screen`, existing caps | Spam-blocker sample rejects a listed number |
| F8 Local network ✓ | `nsd.discover/resolve`, `net.tcpConnect/udpSend` to LAN addresses only | `net.local` (high) | Home-automation sample finds and talks to a device on Wi-Fi |
| F9 Screen privacy ✓ | `display.secure {on}` sets `FLAG_SECURE` on the app's window (no screenshots, blank in Recents and screen sharing); `display.hideOverlays` uses `setHideOverlayWindows` against tapjacking on sensitive screens | `display` (low) | Screenshots of a banking-style screen come out black; an overlay app cannot draw over it |

### Phase G — Former refusals (section 2.2)

Start with the `special` tier itself, then the items in roughly this order (value first, cross-app surveillance powers last because they need the most host UI):

| Item | Work | Done when |
| --- | --- | --- |
| G0 `special` tier ✓ | `Risk.SPECIAL`, dashboard grant sheet, "asks later" consent row, persistent-use notification, 90-day auto-revoke, exclusive holders | A `special` cap can't be granted from the consent screen; HostileTest proves it |
| G1 Network ✓ | P1–P3: per-app CSP built from grants; network log covers sockets too. **Gap (2026-11-18):** the spec §8.5 per-app network log and "Block network" switch do not exist for WebView requests (fetch, XHR, sockets, images); G1 did not build them | RSS reader sample fetches arbitrary feeds and shows remote images |
| G2 WebAssembly ✓ | P4: per-app CSP flag, deft-lint rule update, dependency pinning for `.wasm`. **Gap (2026-11-18):** the sample was `samples/sqlite`, now `samples/wasm-add`, and runs a hand-made `add.wasm`, not sql.js: no network access in the run. No hash-pinned `.wasm` dependency was fetched or run on a device | sql.js sample runs SQLite in WASM |
| G3 Size (quota done; asset packs pending) | P5: per-app quotas in `limits.toml` overridable by grant; asset pack download + verify | 200 MB offline-maps sample installs |
| G4 Local network & sockets ✓ | P6 = F8 | see F8 |
| G5 Background location ✓ | P7 on top of D3 | Run tracker records with the screen off; notification visible throughout |
| G6 Wi-Fi ✓ | P8 | Wi-Fi analyzer sample lists networks |
| G7 Apps & usage ✓ | P9, P10, P15 | Screen-time sample shows per-app usage |
| G8 Notification listener ✓ | P11 | Notification-history sample; only the holder gets events |
| G9 Accessibility ✓ | P12 | Automation sample taps a button in another app |
| G10 Accounts ✓ | P14 | App registers its own account and a sync job runs (needs D1) |
| G11 Roles ✓ (browser, assistant, device admin, wallpaper, IME; call screening is F7b) | P17, one role per PR (IME and wallpaper are the largest) | Each role can be granted to one origin and reverted in system settings |
| G12 Clipboard watch ✓ (foreground; IME mode done with G11) | P13 | Clipboard manager sample records copies while focused or as IME |
| G13 FCM flavor | P19 | `gms` flavor build receives an FCM message |
| G14 Third-party scripts and embeds ✓ | P20: per-app CSP from approved origins, consent row listing them, lint support, a Stripe test-mode sample | Stripe test-mode checkout (card form and 3-D Secure challenge) completes inside an FL app |

---

## 4. Cross-cutting work for every new capability

- `Capability.kt` entry with `risk`, `defaultGrant`, `confirmEach`, `exclusiveGroup`.
- `assets/agent/bridge-catalog.json` method schema with size limits (feeds argument validation, deft-lint FL2xx and the agent).
- Consent copy (Launcher bridge vs Standard web API) and dashboard audit wording.
- Rebuild `assets/lint/deft-lint.js`; update `tools/deft-lint/samples.snap.json`.
- A sample under `samples/` and a `HostileTest` case (wrong origin, revoked grant, oversized args, no user gesture).
- The bridge reference `assets/agent/skills/js-bridge.md` (the only copy) and this file.
- `bun scripts/check.ts` (with `--device` for anything touching hardware).
- For `special` caps: a HostileTest that the consent screen cannot grant it and that a second origin cannot become holder while one exists.

## 5. Suggested order

0. **H**, **A**, **C** — done and checked on a device. **B1–B4**, **D**, **E1–E3, E5**, **F**, **G** (not G13) — built on `parity/rest`; device checks pending (USB device or serial adapter, NFC tags, a second phone for HCE).
1. **D1–D3** — background runner and foreground services; biggest capability jump, needs the most security thought (budget, audit, kill switch).
2. **E1–E2**, then **D4–D6**, then **F** as specific apps need them.
3. **G0** can start in parallel; **G1–G2** (network, WASM) are high value and small once G0 exists. The cross-app powers (G8–G12) come last. **B4** needs G0.

**Real-world track** (shopping, payments, games, banking; added 2026-11-18). If those are the goal, pull these forward, in this order:
1. **G0 → G14 → G1/P2 → G2**: the special tier, then third-party scripts and embeds, remote images, WebAssembly. Unlocks checkout SDKs, product images and engine-built games.
2. **E3, E4, F2**: OAuth sessions, passkeys, biometric-locked Keystore keys (sign-in and banking-grade auth).
3. **F9**: screenshot and overlay protection for sensitive screens.
4. **D6, D1–D3**: push and background running (order updates, 2FA, card emulation while closed).
5. **E5, B4**: Payment Request and payment-category card emulation.

Still out of reach whatever we build: Play Integrity attestation (most bank backends require it), Google Pay and Play Billing (need GMS), and payment-network tokens for real tap-to-pay.
