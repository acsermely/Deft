# Parity execution plan

How the remaining rows of [PARITY.md](PARITY.md) get built. PARITY.md says **what** and **why**; this file says **how**, item by item, precisely enough for a small model (Claude Haiku) to implement most rows without design decisions of its own.

Written 2026-11-18 against `3ca239a`.

---

## 0. How the run works

- **One item at a time, in the order of section 3.** No parallel agents.
- **Model per item** is given in each spec: **Haiku** by default; **Opus** where the item needs security design, lifecycle judgement or unclear platform behaviour. The orchestrator (the session that owns this plan) launches each agent with: section 1 (rules), section 2 (recipe) and the item's own spec.
- **Branch:** all work goes on `parity/rest`, one commit per item, message `<Item id>: <title> (PARITY <id>)` ending with the co-author line. `main` is untouched until the final review is done and the user agrees to merge.
- **Gate between items** (orchestrator, cheap): `cd android && ./gradlew :app:testDebugUnitTest :app:assembleDebug` must pass before the next item starts. If it fails, the same agent (or an Opus agent) fixes it first. This only stops breakage from piling up; it is not the review.
- **Final review** (orchestrator, after all items): read every diff against its spec and section 1, fix what is wrong (Opus subagents allowed), run `bun scripts/check.ts --device` on the emulator, do the hands-on checks listed per item on the emulator and the Galaxy A16, update PARITY.md, then report.

## 1. Rules every agent follows

1. **Never touch a device.** No `adb`, no `connectedDebugAndroidTest`, no `installDebug`, no `bun scripts/check.ts --device`. A real phone is plugged in; instrumented tests can wipe its data. Device checks belong to the orchestrator.
2. **Done means:** the item's "Done when" list is true, `./gradlew :app:testDebugUnitTest :app:assembleDebug` passes (run from `android/`), and if you touched `tools/deft-lint` or `samples/`, `bun scripts/check.ts` (no `--device`) passes too.
3. **Stay in scope.** Only change files the spec names or the recipe requires. No refactors, no renames, no formatting churn, no new dependencies unless the spec lists them (with the version you chose written in the commit message).
4. **Security defaults:** every new method goes through `BridgeService.invoke` (origin lock, capability check, `BridgeArgs` limits, grant, confirm, audit log). Never add a second entry point. Never trust `webAppId`/origin from args; use `BridgeRequest.webAppId`. Anything per app is keyed by `webAppId` (or the app's `originId` where the spec says so). No logging of user content (message bodies, file contents, clipboard, keys).
5. **Errors:** return `BridgeOutcome.Err(BridgeErrors.X, "plain message")`. Use the existing codes; add a new code to `BridgeErrors` only if the spec says so.
6. **Foreground only** unless the spec says otherwise: methods that need the app on screen look up `HostSurfaces.forApp(request.webAppId)` and fail with `UNSUPPORTED, BridgeErrors.NOT_ON_SCREEN` when it is null. The open app and the agent's Preview both register there; a window-wide control (orientation, picture-in-picture, secure, overlays, a foreground service) also refuses a surface with `isPreview` (`BridgeErrors.NOT_IN_PREVIEW`).
7. **Plain words** in all user-facing text (consent titles and descriptions, sheets, notifications): short, no jargon, say what the app can do.
8. **Commit** when done (see section 0). Do not push. Do not edit PARITY.md beyond ticking your row (add ` ✓` after the item id in its table, as Phase A/C rows show).

## 2. Recipe: adding a bridge capability

Read these first: `bridge/modules/TilesModule.kt` (small module), `bridge/modules/DisplayModule.kt` (module that acts on the app's screen), `bridge/HostSurfaces.kt`, `bridge/BridgeService.kt` (`ensureModules`, `invoke`), `catalog/Capability.kt`, `catalog/BridgeCatalog.kt`, `bridge/BridgeArgs.kt`. Paths are under `android/app/src/main/java/app/deft/` unless they start with `android/`, `docs/`, `samples/` or `tools/`.

For a new capability `foo.bar` with methods `foo.*`:

| # | File | What to add |
| --- | --- | --- |
| 1 | `catalog/Capability.kt` | Enum entry: `id`, `kind = CapabilityKind.BRIDGE` (or `STANDARD` for a plain web API), `title`, `description` (both shown on the consent screen), `defaultGrant`, `risk`, and `confirmEach` / `exclusiveGroup` if the spec says so. Place it before `HAPTICS`. |
| 2 | `catalog/BridgeCatalog.kt` | `add(Capability.FOO_BAR, "foo.method1", "foo.method2")` in `methodToCap`. |
| 3 | `android/app/src/main/assets/agent/bridge-catalog.json` | `"foo.bar": { "kind": "...", "risk": "...", "methods": [...], "events": [...], "summary": "..." }`. `BridgeCatalogTest` fails until 1 and 3 agree. |
| 4 | `bridge/BridgeArgs.kt` | An `ArgSchema` per method: `required`, `stringMax` for every string arg, `arrayMax` for every array. Irreversible methods go in `ALWAYS_CONFIRM` and get a `confirmSummary` line. |
| 5 | `bridge/modules/FooModule.kt` | `class FooModule(private val context: Context) : BridgeModule` with a `when (request.method)` and `else -> Err(UNSUPPORTED)`. Pure logic (parsing, limits, maths) goes in a separate `object` or file so it can be unit tested without Android (see `NotifySpec.kt`, `UsbTransfers.kt`). |
| 6 | `bridge/BridgeService.kt` | `modules["foo"] = FooModule(this)` in `ensureModules()`. Events: `emit(webAppId, "foo.event", payloadJson)` (queued if the app is closed). |
| 7 | `permissions/OsPermissionHelper.kt` | The `when` is exhaustive: map the new capability to its Android runtime permission(s), or to the no-permission group. |
| 8 | `android/app/src/main/AndroidManifest.xml` | `<uses-permission>`, services, receivers, activities the spec lists. Host dialogs started from a module with `FLAG_ACTIVITY_NEW_TASK` get `android:excludeFromRecents="true"` and `android:taskAffinity=""`. |
| 9 | `android/app/src/main/assets/agent/skills/js-bridge.md` | One bullet in the capability list, same style as the `tiles` / `widgets` bullets: methods, args, return shape, events. `BridgeCatalogTest.docsMentionEveryMethod` checks it. |
| 10 | `android/app/src/test/java/app/deft/...` | JVM unit tests for the pure-logic part: valid args, every rejection, limits. |
| 11 | `docs/spec-trace.md` | A row if the item adds a security rule (spec says which). |
| 12 | `docs/UNIMPLEMENTED.md` | Remove the line if the item was listed there. |

Standard web APIs that the WebView already has but must be gated (like vibrate): add a shim in `samples/deft-bridge.js` following the A3–A8 shims, and a case in `tools/deft-runtime` `bridge-shims.test.js`. Rebuild the copy: `cd android && ./gradlew :app:copyDeftBridge`.

Methods that need the app's screen: add a function to the `HostSurface` interface, implement it in `ui/host/WebViewHostActivity.kt` next to `keepScreenOn`, call it from the module on `Dispatchers.Main`.

A sample is only required where the spec says so. Samples live in `samples/<id>/` (copy `samples/platform-check` as the template: `manifest.json`, `index.html`, `app.css`, `main.js`, no inline script or style), then run `cd android && ./gradlew :app:copyBundledApps` and `bun scripts/check.ts` (update `tools/deft-lint/samples.snap.json` only if the check tells you the new ids are expected).

---

## 3. Order and specs

Order: foreground items that need no new tier first (Haiku), then the `special` tier and what it unlocks, then background running, then interop, then the cross-app powers. Each line: **id — model — depends on**.

| Step | Item | Model | Depends on |
| --- | --- | --- | --- |
| 1 | F9 Screen privacy | Haiku | — |
| 2 | F4 Torch | Haiku | — |
| 3 | D5 Picture-in-picture | Haiku | — |
| 4 | F1 Sensors | Haiku | — |
| 5 | F3 Speech and TTS | Haiku | — |
| 6 | F5a Audio volume and routing | Haiku | — |
| 7 | F2 Keystore | Haiku | — |
| 8 | F6 Media library | Haiku | — |
| 9 | E2 Outbound intents | Haiku | — |
| 10 | E5 Web Payment Request | Haiku | — |
| 11 | F7a SIM choice, blocked numbers, USSD | Haiku | — |
| 12 | P16 Bulk SMS / call-log export | Haiku | — |
| 13 | G0 `special` tier | Opus | — |
| 14 | F5b Do Not Disturb | Haiku | G0 not needed; uses system access screen |
| 15 | G1 Open network, remote images, user servers | Opus | G0 |
| 16 | G2 WebAssembly | Haiku | G1 (same CSP builder) |
| 17 | G14 Third-party scripts and embeds | Opus | G1 |
| 18 | G3 Larger apps (quota part) | Haiku | — |
| 19 | F8 / G4 Local network and sockets | Haiku | G1 (internet sockets need `network.open`) |
| 20 | G6 Wi-Fi scan and control | Haiku | — |
| 21 | G7 Apps, usage, device id | Haiku | G0 |
| 22 | G12 Clipboard watch (foreground) | Haiku | G0 |
| 23 | E3 Auth session | Haiku | — |
| 24 | D1 Background runner | Opus | — |
| 25 | D2 Triggers | Opus | D1 |
| 26 | D3 Foreground service | Opus | D1 |
| 27 | D4 Media session | Opus | D3 |
| 28 | D6 Push without Google (UnifiedPush) | Opus | D1 |
| 29 | E1 Inbound handlers | Opus | — |
| 30 | G5 Background location | Opus | D3, G0 |
| 31 | G10 Accounts | Opus | D1, G0 |
| 32 | G8 Notification listener | Opus | G0, D1 |
| 33 | G9 Accessibility service | Opus | G0 |
| 34 | G11 Roles (one commit per role) | Opus | G0 |
| 35 | F7b Call screening | Opus | G0, G11 |
| 36 | B4 Payment-category HCE | Opus | G0 |
| 37 | E4 Passkeys (research spike) | Opus | — |
| — | G13 FCM flavor | not run | needs a Firebase project from the user |

20 Haiku items, 17 Opus items, 1 blocked (G13).

### Step 1 — F9 Screen privacy — Haiku

- **Capability:** reuse `display` (low). Add methods `display.secure` and `display.hideOverlays` to `DISPLAY` in BridgeCatalog, catalog JSON (`methods` and `summary`: "Keep the screen on, orientation, print, hide from screenshots").
- **API:** `display.secure {on: "true"|"false"}` sets/clears `WindowManager.LayoutParams.FLAG_SECURE` on the host window. `display.hideOverlays {on}` calls `window.setHideOverlayWindows(on)` (API 31+; on older returns `UNSUPPORTED`). Both return `{}`.
- **Host:** add `setSecure(on: Boolean)` and `hideOverlays(on: Boolean): Boolean` to `HostSurface`; implement in `WebViewHostActivity` next to `keepScreenOn`. Clear both when the activity is destroyed (where `FLAG_KEEP_SCREEN_ON` is cleared, line ~626). `setHideOverlayWindows` needs `android.permission.HIDE_OVERLAY_WINDOWS` in the manifest.
- **Args:** `on` required, `stringMax 8`.
- **Tests:** extend an existing unit test or add `DisplayArgsTest` checking the schema rejects missing `on`.
- **Done when:** the methods are listed in js-bridge.md; `platform-check` sample gets an "F9 secure" button that toggles `display.secure` and logs `PC F9 OK secure=<bool>` (`display` is already declared in its manifest).
- **Device check (orchestrator):** screenshot of the app is black while on; Recents card blank.

### Step 2 — F4 Torch — Haiku

- **Capability:** new `TORCH` id `torch`, BRIDGE, low, `WHILE_USING`, title "Flashlight", description "Turn the camera flashlight on and off while the app is open."
- **API:** `torch.set {on: "true"|"false", level?: "1".."max"}` → `{on, level, maxLevel}`; `torch.state` → `{available, on, maxLevel}`. Use `CameraManager.setTorchMode(id, on)`; `level` uses `turnOnTorchWithStrengthLevel` on API 33+ when `maxLevel > 1`. Pick the first camera id with `FLASH_INFO_AVAILABLE` and `LENS_FACING_BACK`.
- **Foreground only** (rule 6). Turn the torch off when the app leaves the screen: the module keeps the owning `webAppId` in a small `object TorchState`; `WebViewHostActivity.onStop` calls `TorchState.offFor(appId, context)`, which turns the torch off if that app turned it on.
- **No Android permission** is needed for `setTorchMode`.
- **Pure logic for tests:** `TorchLevels.clamp(level: String?, max: Int): Int` (null → max, out of range → error). Unit test it.
- **Done when:** recipe steps 1–7, 9, 10 done; `platform-check` gets a "F4 torch" toggle button logging `PC F4 OK on=<bool>` (add `torch` to its manifest).
- **Device check:** phone flashlight turns on/off; leaving the app turns it off. (Emulator has no flash: `torch.state` → `available:false`.)

### Step 3 — D5 Picture-in-picture — Haiku

- **Capability:** new `PIP` id `pip`, BRIDGE, low, `WHILE_USING`, "Picture-in-picture", "Keep showing the app in a small window when you leave it."
- **API:** `pip.enter {aspect?: "16:9"}` enters PiP now; `pip.setAuto {on, aspect?}` enters automatically on Home press (`setAutoEnterEnabled` on API 31+, else `onUserLeaveHint`). Events: `pip.changed {inPip: bool}` from `onPictureInPictureModeChanged`.
- **Host:** `WebViewHostActivity` manifest entry gets `android:supportsPictureInPicture="true"` (it already has `configChanges` covering size). Add `enterPip(aspect): Boolean` and `setAutoPip(on, aspect)` to `HostSurface`. Aspect parse: `"w:h"` with ints 1..10000, ratio clamped to Android's allowed 1/2.39..2.39 (pure function `PipAspect.parse`, unit tested).
- **Event plumbing:** the activity reaches the service with `BridgeService.instanceOrNull()` (see its use near line 572 of `WebViewHostActivity`) and calls `emit(appId, "pip.changed", json)` from `lifecycleScope.launch`.
- **Done when:** recipe done; `platform-check` "D5 pip" button logs `PC D5 OK`.
- **Device check:** video keeps playing in a PiP window (use `three-tps` or a page with a looping canvas).

### Step 4 — F1 Sensors — Haiku

- **Capability:** new `SENSORS` id `sensors`, BRIDGE, medium, `WHILE_USING`, "Motion and environment sensors", "Read step count, light, pressure, proximity, compass and humidity while the app is open."
- **API:** `sensors.list` → `[{type, name, vendor, maxRate, maxRange, resolution}]` for types `stepCounter, stepDetector, light, pressure, proximity, magnetometer, humidity, ambientTemperature, accelerometer, gravity, linearAcceleration, rotationVector`. `sensors.subscribe {type, rateHz?}` → `{id}`; readings arrive as event `sensors.reading {id, type, values: [float], timestamp}`. `sensors.unsubscribe {id}`. Rate clamp 1..50 Hz (default 5); event emission throttled to the requested rate in the module (not in the sensor), max 4 subscriptions per app.
- **Foreground only:** unsubscribe all of an app's sensors when its host stops (same pattern as torch: `SensorsModule.stopFor(webAppId)` called from `WebViewHostActivity.onStop`).
- **Permissions:** step counter/detector need `ACTIVITY_RECOGNITION` (runtime, API 29+): add to manifest and map in `OsPermissionHelper` for `SENSORS` (it is requested only when the app subscribes to a step type: return `os_permission_missing` with message "Allow physical activity" if not granted, like other modules do; search for `OS_PERMISSION_MISSING` usage).
- **Pure logic:** `SensorSpec.parse(args)` (type names → `Sensor.TYPE_*` ints, rate clamp, limits). Unit tests.
- **Done when:** recipe done; sample `samples/pedometer` (step count + light level) with `sensors` declared.
- **Device check:** steps count up while walking with the phone; light changes when covering the sensor.

### Step 5 — F3 Speech and TTS — Haiku

- **Capability:** new `TTS` id `speech.tts`, BRIDGE, low, `WHILE_USING`, "Read text aloud", "Speak text with the phone's voice." and `SPEECH_RECOGNIZE` id `speech.recognize`, BRIDGE, medium, `WHILE_USING`, "Speech to text", "Turn what you say into text. Uses the microphone while listening." Methods prefix `speech`.
- **API:** `speech.speak {text ≤ 4000, lang?, rate?, pitch?, id?}` → `{id}`; events `speech.done {id}` / `speech.error {id, code}`. `speech.stop`. `speech.voices` → `[{name, lang, offline}]`. `speech.recognize {lang?, offline?: "true"}` → final `{text, alternatives: [text]}` (one utterance; uses `SpeechRecognizer`, `createOnDeviceSpeechRecognizer` on API 31+ when `offline` and available); `speech.cancel`. Partial results as event `speech.partial {text}`.
- **Rules:** `speech.recognize` also requires the app to hold `microphone` (check `repository.grantLevel(app, Capability.MICROPHONE)` in the module through `DeftApp.instance.repository`; fail `NOT_GRANTED` "microphone"). Recognition only in foreground (rule 6) and only right after a tap: use `UserGesture.recent(webAppId)` like `ClipboardModule`. TTS engine: one `TextToSpeech` instance owned by the module, created lazily, shut down in `BridgeService.onDestroy` (add a `shutdown()` like Bluetooth).
- **Permissions:** `RECORD_AUDIO` already declared; map `SPEECH_RECOGNIZE` to it in `OsPermissionHelper`. Order: the tap is checked before the permission, so a call with no tap returns `CANCELLED` (even when the microphone is not granted, where it used to return `OS_PERMISSION_MISSING`). The microphone dialog only follows a tap, through `OsPermissions.ensure`, which spends that tap.
- **Pure logic:** rate/pitch clamp (0.25..4.0), lang tag validation (BCP-47 shape). Unit tests.
- **Done when:** recipe done; `platform-check` gets "F3 speak" (speaks "Deft") and "F3 listen" buttons.
- **Device check:** phone speaks; dictation returns text.

### Step 6 — F5a Audio volume and routing — Haiku

- **Capability:** new `AUDIO_CONTROL` id `audio.control`, BRIDGE, medium, `WHILE_USING`, "Volume and audio output", "Read and change volumes, the ringer mode and whether sound plays on the speaker."
- **API:** `audio.getVolumes` → `{music:{value,max}, ring, alarm, notification, voiceCall}`; `audio.setVolume {stream, value}` (clamped 0..max, `FLAG_SHOW_UI`); `audio.getRingerMode` / `audio.setRingerMode {mode: normal|vibrate|silent}` (silent needs DND access on API 24+: return `os_permission_missing` "Do Not Disturb access" if `NotificationManager.isNotificationPolicyAccessGranted` is false — F5b adds the grant flow); `audio.devices` → output devices `[{id, type, name}]`; `audio.route {target: speaker|earpiece|bluetooth|default}` using `AudioManager.setCommunicationDevice` (API 31+) else `isSpeakerphoneOn`.
- **Pure logic:** stream/mode name maps and clamp. Unit tests.
- **Done when:** recipe done (no Android permission; `MODIFY_AUDIO_SETTINGS` must be added to the manifest for routing).
- **Device check:** alarm volume rises from the sample button (`platform-check` "F5 alarm vol").

### Step 7 — F2 Keystore — Haiku

- **Capability:** new `KEYSTORE` id `keystore`, BRIDGE, medium, `WHILE_USING`, "Secure keys", "Create keys that never leave this phone, optionally locked by your fingerprint or face."
- **API:** `keystore.generate {alias ≤ 64 [a-zA-Z0-9_-], alg: "EC_P256"|"AES_256", requireBiometric?: "true", validitySeconds?}` → `{alias, publicKeySpki?: base64}`; `keystore.sign {alias, base64}` → `{signature}` (EC, SHA256withECDSA); `keystore.encrypt {alias, base64}` → `{iv, ciphertext}` (AES/GCM/NoPadding); `keystore.decrypt {alias, iv, ciphertext}` → `{base64}`; `keystore.list` → `[alias]`; `keystore.delete {alias}`.
- **Namespacing:** real alias = `fl.<originId or webAppId>.<alias>`; list filters by that prefix; never expose other apps' keys. No attestation, ever (PARITY Never list).
- **Biometric:** keys with `requireBiometric` use `setUserAuthenticationRequired(true)` + `setUserAuthenticationParameters(validity or 0, AUTH_BIOMETRIC_STRONG)`. Use of such a key needs a `BiometricPrompt` with a `CryptoObject`: run it in a new small host dialog activity `ui/keystore/KeystoreAuthActivity` (a `FragmentActivity`, theme `Theme.Deft.Dialog`, `taskAffinity=""`, `excludeFromRecents`), started with `NEW_TASK` and a request id, result via a `CompletableDeferred` registry — copy the shape of `BridgePicks` / `SaveAsActivity`.
- **Pure logic:** alias validation and namespacing (`KeystoreAliases`). Unit tests incl. "alias of another app is rejected".
- **Done when:** recipe done; `samples/vault` stores a secret encrypted with a biometric-locked key and decrypts it after a fingerprint.
- **Device check:** fingerprint prompt appears on decrypt; wrong finger fails.

### Step 8 — F6 Media library — Haiku

- **Capability:** new `MEDIA_READ` id `media.read`, BRIDGE, high, `WHILE_USING`, "Photos, videos and music", "See all photos, videos and audio files on this phone." Methods prefix `media`.
- **API:** `media.query {kind: images|video|audio, since?: epochMs, limit? ≤ 500, offset?}` → `[{id, name, mime, size, width?, height?, durationMs?, dateTaken?, album?}]` newest first; `media.thumbnail {id, kind, size?: 64..512}` → `{base64 (JPEG)}` via `ContentResolver.loadThumbnail`; `media.read {id, kind}` → `{base64, mime}` capped at 7 MB (else `INVALID_ARGS` "too large; use files.* for big files").
- **Permissions:** `READ_MEDIA_IMAGES/VIDEO/AUDIO` already declared; on API 34+ partial access (`READ_MEDIA_VISUAL_USER_SELECTED`) must work: return what the system allows. Map in `OsPermissionHelper`.
- **Audit:** `media.read` is logged by `BridgeService` already; no extra.
- **Pure logic:** query args parsing, projection selection per kind. Unit tests.
- **Done when:** recipe done; `samples/gallery` shows a thumbnail grid.
- **Device check:** gallery lists the phone's photos.

### Step 9 — E2 Outbound intents — Haiku

- **Capability:** new `INTENTS_START` id `intents.start`, BRIDGE, medium, `WHILE_USING`, "Use other apps", "Ask another app to do something, like take a photo, pick a contact or add an event. You choose the app each time."
- **API:** `intents.start {action, type?, data?, extras?: {string: string}}` and `intents.startForResult {...same}` → `{resultCode: "ok"|"cancelled", data?: string, items?: [{id, name, mime, size}]}`.
- **Allow-list (only these actions):** `SEND`, `SEND_MULTIPLE` (text extras only), `VIEW`, `EDIT`, `INSERT`, `PICK`, `GET_CONTENT`, `IMAGE_CAPTURE`, `VIDEO_CAPTURE`, `SET_ALARM`, `SET_TIMER`, `SHOW_ALARMS`, `DIAL`, `SENDTO`, `WEB_SEARCH`, `INSERT_OR_EDIT`. `data` schemes: `http https mailto tel sms geo content:` is **refused** (an app must not hand out launcher URIs). Extras: max 16 keys, values strings ≤ 4096, keys must be `android.intent.extra.*` or `android.provider.*` constants. Always through `Intent.createChooser`.
- **Results:** `startForResult` runs through a new `ui/intents/IntentResultActivity` (dialog theme, `taskAffinity=""`, `excludeFromRecents`, `NEW_TASK`), which calls `registerForActivityResult(StartActivityForResult)`. Returned `content://` URIs (data + clipData) are copied into the app's `ShareInbox` (see `share/ShareInbox.kt` and how `ShareToActivity` stores items) and returned as `items`; raw URIs are never returned. `IMAGE_CAPTURE` without `EXTRA_OUTPUT`: return the thumbnail bitmap as a JPEG item.
- **Pure logic:** `IntentSpec.parse(args)` enforcing every rule above. Unit tests for each refusal.
- **spec-trace row:** "Outbound intents: allow-listed actions, chooser, no launcher URIs out, results via ShareInbox" → `IntentSpecTest`.
- **Done when:** recipe done; `platform-check` gets "E2 photo" (IMAGE_CAPTURE for result).
- **Device check:** camera app opens through a chooser; the photo comes back as an item readable with `intents.readShare`.

### Step 10 — E5 Web Payment Request — Haiku

- **Capability:** new `PAYMENTS_REQUEST` id `payments.request`, **STANDARD**, medium, `WHILE_USING`, "Pay with installed apps", "Let the app ask an installed payment app on this phone to pay."
- **Work:** in `webview/DeftWebViewConfig.kt` (or where `WebSettingsCompat` options are set), when `WebViewFeature.isFeatureSupported(WebViewFeature.PAYMENT_REQUEST)` and the app holds `payments.request`, call `WebSettingsCompat.setPaymentRequestEnabled(settings, true)`; otherwise leave it off. Add to `AndroidManifest.xml` `<queries>`: intents `org.chromium.intent.action.PAY`, `org.chromium.intent.action.IS_READY_TO_PAY`, `org.chromium.intent.action.UPDATE_PAYMENT_DETAILS`. Bump `androidx.webkit` only if 1.12.1 lacks the API (the commit message must say which version).
- **Gate:** shim in `samples/deft-bridge.js`: if `payments.request` is not granted, `window.PaymentRequest` is deleted (pattern of A3 vibrate shim).
- **Tests:** `bridge-shims.test.js` case; `WebPlatformTest` if the feature check is a pure function.
- **Done when:** recipe done (no BridgeCatalog methods for a STANDARD cap; `methods: []` in JSON).
- **Device check:** only partially possible (needs an installed payment app that supports the Chromium intents); orchestrator notes the result.

### Step 11 — F7a SIM choice, blocked numbers, USSD — Haiku

- **SIMs:** `telephony.listSims` (under `CALLS_PLACE`) → `[{subscriptionId, displayName, carrierName, slot}]` from `SubscriptionManager.getActiveSubscriptionInfoList` (needs `READ_PHONE_STATE`, declared). Optional `subscriptionId` arg on `telephony.call` (via `TelecomManager` `EXTRA_PHONE_ACCOUNT_HANDLE`) and `sms.send` (`SmsManager.getSmsManagerForSubscriptionId`). Never return IMSI/ICCID/number.
- **Blocked numbers:** new `CALLS_BLOCK` id `calls.block`, BRIDGE, high: `telephony.blockedList`, `telephony.block {e164}`, `telephony.unblock {e164}` via `BlockedNumberContract` (works only while FL is the default dialer; else `ROLE_MISSING`).
- **USSD:** new `USSD` id `telephony.ussd`, BRIDGE, high, `confirmEach = true`: `telephony.ussd {code ≤ 32, subscriptionId?}` → `{response}` via `TelephonyManager.sendUssdRequest` (needs `CALL_PHONE`). Add `confirmSummary`: "Send code <code> to your carrier".
- **Pure logic:** USSD code shape `^[*#][0-9*#]{1,30}#$`, e164 shape. Unit tests.
- **Done when:** recipe done; `samples/phone` gets a SIM picker if more than one SIM.
- **Device check:** SIM list on the A16; block/unblock round trip. USSD is sent only with a code the user names (section 4).

### Step 12 — P16 Bulk SMS / call-log export — Haiku

- **Work:** remove the "never bulk" limit in `SmsModule` / `TelephonyModule` list methods (find the row caps). Add `limit` (≤ 50 000) and `offset` args. A call with `limit > 500` must be confirmed first: in the module, load the app with `DeftApp.instance.repository.getApp(request.webAppId)` and call `GrantPrompt.ask(context, app, title = "Export messages", message = "Read up to N messages at once", biometric = false)`; anything but `ConfirmChoice.ALLOW` → `CANCELLED`. Audit metadata: row count.
- **Tests:** arg schema limits. **Done when:** js-bridge.md documents `limit`/`offset` and the confirm.

### Step 13 — G0 `special` tier — Opus

Design (decided; implement it):

- `Risk.SPECIAL` after `HIGH`. New `Capability` flag `persistentWhileActive: Boolean = false`.
- **Install:** `AppRepository` (default-grant loop near line 354) sets every SPECIAL cap to `DENIED`. `ConsentActivity` lists SPECIAL caps under a separate heading "Asks later" with no toggle and the manifest reason.
- **Granting:** dashboard app card (`DashboardAppCards.kt`) shows SPECIAL caps with a "Review" button that opens a host sheet `ui/special/SpecialGrantActivity` (dialog theme, `taskAffinity=""`). Later (see `bridge/SpecialAsk.kt`), a declared SPECIAL capability the user has not turned off also opens the same sheet at the point of use when the app asks, and the call goes on once granted; Special access is granted only through the launcher's own sheet after the screen lock: from the dashboard, or opened at the point of use when the app asks. Web code can never grant, and the consent screen never grants. app name, origin, capability title and description, the manifest `reason`, a plain warning paragraph per capability (add `warning: String` to Capability, required for SPECIAL), and two buttons: "Not now" / "Allow" (Allow requires device credential or biometric through the existing `ConfirmActivity` biometric path; `ConfirmActivity` is already a `FragmentActivity`). If the capability also needs an Android special access (accessibility, notification listener, usage access, DND access), the sheet's second step deep-links to that system screen once and reads the state back on resume.
- **Exclusive:** for SPECIAL caps with `exclusiveGroup`, granting while another app holds the group is **refused** in the sheet ("<Other app> has this. Remove it there first.") — unlike the SMS role, which replaces. Add `AppRepository.specialHolder(group)`.
- **Visible while active:** caps with `persistentWhileActive` post an ongoing notification "<App> is using <title>" on a `special_use` channel while the grant is active and the power is in use (the module that implements the power calls `SpecialUse.start(webAppId, cap)` / `stop`). G0 ships `SpecialUse` with the notification; later items call it.
- **Auto-revoke:** new column `grantedAt INTEGER` on `grants` (Room migration 8→9, default now). Daily check (on `DeftApp.onCreate` and from the existing `BootReceiver`/alarm path; no WorkManager needed): SPECIAL grants with no `usage_log` row for that cap in 30 days → reminder notification "Still want <App> to have <title>?" with "Keep" / "Remove" actions; 90 days → revoke and notify.
- **Agent:** grants made by the agent path (`AgentTools`) must refuse SPECIAL caps.
- **Tests:** `HostileTest`: `specialCannotBeGrantedFromConsent`, `secondOriginCannotTakeExclusiveSpecial`; unit test for the 30/90-day decision function. spec-trace rows for both.
- **First SPECIAL caps** (entries only, no modules yet, so later items just implement them): `apps.list`, `usage.read` (exclusive `usage`), `notifications.listen` (exclusive `notif-listener`, persistent), `accessibility` (exclusive `a11y`, persistent), `clipboard.watch`, `accounts.read`, `location.background` (persistent), `nfc.hce.payment` (exclusive `hce-payment`). They are hidden from the consent screen until their module exists: add `available: Boolean = true` to Capability and set `false` for these; parser accepts them but the dashboard shows "Not available yet".
- **Done when:** a test app declaring `apps.list` installs, shows it under "Asks later", and can only get it from the dashboard sheet.

### Step 14 — F5b Do Not Disturb — Haiku

- **Capability:** `DND` id `dnd`, BRIDGE, high, `WHILE_USING`, "Do Not Disturb", "Turn Do Not Disturb on or off and read whether it is on."
- **API:** `dnd.state` → `{filter: all|priority|alarms|none, accessGranted}`; `dnd.set {filter}`; `dnd.requestAccess` opens `Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS` (launcher-wide access; the FL grant is the per-app gate). Manifest: `ACCESS_NOTIFICATION_POLICY`.
- **Done when:** recipe done; `audio.setRingerMode silent` (F5a) works once access is granted.

### Step 15 — G1 Open network, remote images, user servers — Opus

- **CSP builder:** move CSP construction out of `LocalOriginPathHandler.cspWith` into a pure `webview/CspBuilder.kt` taking `(connectOrigins, flags: Set<CspFlag>, embedOrigins)`; `LocalOriginPathHandler` calls it. Unit test the exact output for each flag combination (this is the security-critical part).
- **P1 `network.open`** (high): `connect-src 'self' https: wss:`.
- **P2 remote images/media (all apps):** `img-src` and `media-src` gain the same origins as `connect-src` (consented origins, or `https:` with `network.open`).
- **P3 `network.user`** (medium): dashboard "Servers" section on the app card to add/remove `https://host[:port]` (same validation as `DeftManifestParser.validNetworkOrigin`), stored in `NetworkConsent` with a `source = "user"` column (migration). These count as consented origins.
- **Network log:** confirm spec §8.5 per-app log covers the new paths; if there is no log for WebView fetches, note it in PARITY instead of building one.
- **Tests:** `CspBuilderTest`; `HostileTest.undeclaredFetchIsBlocked` must still pass; new `HostileTest.openNetworkNeedsGrant`.
- **Sample:** `samples/rss` reading any feed URL the user types, with images.

### Step 16 — G2 WebAssembly — Haiku

- **Capability:** `WASM` id `wasm`, STANDARD, medium, "Run WebAssembly", "Run compiled code (WebAssembly) shipped inside the app."
- **Work:** `CspFlag.WASM` → `'wasm-unsafe-eval'` in `script-src` (G1's builder). deft-lint: find the rule that flags `.wasm` / `WebAssembly` and allow it when `wasm` is declared and the `.wasm` file is in the package or a hash-pinned dependency (D6 deps); rebuild `assets/lint/deft-lint.js` (`cd tools/deft-lint && bun run build`).
- **Tests:** `CspBuilderTest` case; deft-lint rule test both ways.
- **Sample:** `samples/wasm-add` (renamed from `samples/sqlite`) with sql.js `.wasm` as a hash-pinned dependency (or vendored file), creating a table and querying it.

### Step 17 — G14 Third-party scripts and embeds — Opus

- **Capability:** `WEB_EMBED` id `web.embed`, STANDARD, high. Manifest `embeds: ["https://js.stripe.com", "https://*.paypal.com"]` (max 16, https only, `*.` only as the leftmost label, no IPs).
- **Consent:** each origin listed on the consent screen and the dashboard; approval is per origin (user can untick some); stored like network consent with `kind = "embed"`.
- **CSP:** approved origins go into `script-src`, `frame-src`, `connect-src`, `img-src`. Investigate whether `require-trusted-types-for 'script'` breaks Stripe.js; if it does, drop Trusted Types **only** for apps with `web.embed` granted, and record that in DECISIONS.md.
- **Frames:** `frame-src` currently `'none'`; WebView must allow the 3-D Secure iframe; check `WebViewClient.shouldOverrideUrlLoading` does not kill frame navigations to approved origins.
- **Lint:** FL rule for `embeds` shape; manifest parser tests.
- **Sample:** Stripe test-mode checkout. **Needs a Stripe test publishable key from the user** (`pk_test_…`) — the agent builds the sample with a placeholder read from `samples/stripe-checkout/config.json` (gitignored); the orchestrator asks the user before the device check.

### Step 18 — G3 Larger apps (quota part) — Haiku

- **Capability:** `STORAGE_LARGE` id `storage.large`, STANDARD, low, "More storage", "Let this app store more than the usual limit, up to the free space on the phone."
- **Work:** the caps live in `workspace/DeftPaths.kt` (`MAX_*`, `*_CEILING`, `HARD_PACKAGE_BYTES`, read from `assets/config/limits.toml`) and are enforced in `DeftPackage.verify` / `MyAppsStore.prepareInstall`. When the manifest declares `storage.large` and the user granted it at install, that app's package cap becomes `min(free space − 1 GiB, 2 GiB)` and its per-binary cap 200 MiB; other apps keep the normal caps. Asset packs (download + verify) are **out of scope** for this step; note them as remaining in PARITY.
- **Tests:** `DeftPackageTest` cases for both limits.

### Step 19 — F8 / G4 Local network and sockets — Haiku

- **Capabilities:** `NET_LOCAL` id `net.local` (high): NSD + LAN sockets. `NET_SOCKETS` id `net.sockets` (high): internet sockets, also requires `network.open` granted.
- **API:** `net.discover {type: "_http._tcp"}` → events `net.found {name, type}` / `net.lost`; `net.discoverStop`; `net.resolve {name, type}` → `{host, port, txt}`; `net.tcpConnect {host, port, timeoutMs?}` → `{socketId}`; `net.tcpSend {socketId, base64}`; data arrives as `net.data {socketId, base64}`, close as `net.closed {socketId}`; `net.close {socketId}`; `net.udpSend {host, port, base64}`; `net.udpListen {port}` → `{socketId}` (port ≥ 1024). Max 8 sockets per app; 64 KiB per send.
- **LAN rule (security):** resolve the host first, then check every resolved address; connect to the checked address, not the name (defeats DNS rebinding). LAN = RFC 1918, 100.64/10 excluded, 169.254/16, fc00::/7, fe80::/10; loopback and multicast refused. Non-LAN only with `net.sockets` + `network.open`.
- **Lifecycle:** model it on the SPP part of `BluetoothModule` (reader coroutine per socket, close on app close via `BridgeService`). Sockets stay open only while the app is live (`BridgeService.isLive`); closed otherwise.
- **Pure logic:** `LanAddress.isLan(InetAddress)` and arg parsing. Unit tests for every range edge.
- **spec-trace row:** "LAN sockets connect only to checked LAN addresses" → `LanAddressTest`.
- **Sample:** `samples/lan-finder` lists `_http._tcp` services and fetches `/` from one over TCP.

### Step 20 — G6 Wi-Fi — Haiku

- **Capabilities:** `WIFI_SCAN` id `wifi.scan` (high): `wifi.scan` → `[{ssid, bssid, level, frequency, security}]`, `wifi.info` → current `{ssid, bssid, rssi, linkSpeed}`. Requires the app to hold `location` too (check in module). Android needs `ACCESS_FINE_LOCATION` and `NEARBY_WIFI_DEVICES` (API 33+, add with `usesPermissionFlags="neverForLocation"` **not** set, since we do derive location). Scans are throttled by Android (4 per 2 min): return the cached results with `{fresh: false}` when throttled.
- `WIFI_CONTROL` id `wifi.control` (high): `wifi.openPanel` (Settings panel `ACTION_WIFI`), `wifi.suggest {ssid, passphrase?}` via `WifiNetworkSuggestion` (no direct toggling: Android 10+ forbids it).
- **Sample:** `samples/wifi-analyzer`.

### Step 21 — G7 Apps, usage, device id — Haiku

- `apps.list` (SPECIAL from G0; set `available = true`): `apps.list` → `[{package, label, system}]` (launchable apps only, icon via `apps.icon {package}` → base64 PNG 96 px).
- `APPS_LAUNCH` id `apps.launch` (medium): `apps.launch {package}` starts the launch intent; refuse FL itself.
- `usage.read` (SPECIAL exclusive): `usage.stats {sinceMs, untilMs}` → `[{package, foregroundMs, lastUsed}]` via `UsageStatsManager`; needs the launcher's `PACKAGE_USAGE_STATS` special access (manifest + G0 sheet deep link to `ACTION_USAGE_ACCESS_SETTINGS`).
- `DEVICE_ID` id `device.id` (medium): `device.id` → `HMAC-SHA256(launcherSecret, origin)` hex, first 32 chars. Launcher secret: 32 random bytes stored with `security-crypto` (create once).
- **Tests:** HMAC function is stable and differs per origin. **Sample:** `samples/screen-time`.

### Step 22 — G12 Clipboard watch — Haiku

- `clipboard.watch` (SPECIAL; `available = true`): `clipboard.watchStart` / `clipboard.watchStop`; event `clipboard.changed {text}` from `ClipboardManager.addPrimaryClipChangedListener` **while the app is on screen** (Android 10+ only delivers to the focused app). Stop on host stop. IME mode comes with G11.

### Step 23 — E3 Auth session — Haiku

- **Capability:** `AUTH_SESSION` id `auth.session`, BRIDGE, medium, "Sign in with a website", "Open a sign-in page in your browser and bring the result back to this app."
- **Dependency:** `androidx.browser:browser` (latest stable).
- **API:** `auth.start {url (https only), redirectPath?}` → `{url: "<full redirect url>"}` or `CANCELLED` after 10 min / when the user closes the tab.
- **Redirect:** the app must tell the IdP `redirect_uri = app.deft.auth:/cb/<nonce>` — return `{redirectUri}` from a first call `auth.prepare` → `{nonce, redirectUri}`; `auth.start {url, nonce}` then opens the Custom Tab. New `ui/auth/AuthRedirectActivity` (exported, intent filter VIEW scheme `app.deft.auth` (debug: `app.deft.debug.auth`), `taskAffinity=""`, `excludeFromRecents`, `noHistory`) resolves the nonce → webAppId and completes that app's deferred; unknown or used nonce → finish silently. Nonces: 128-bit random, single use, bound to webAppId, expire after 10 min.
- **Pure logic:** `AuthNonces` registry (issue, consume once, expiry, binding). Unit tests incl. "nonce of app A cannot complete app B", "second use fails".
- **spec-trace row** for nonce binding.
- **Device check:** OAuth against a public test IdP (e.g. a GitHub OAuth app the user owns) — orchestrator asks the user.

### Step 24 — D1 Background runner — Opus

Design (decided):

- **Manifest:** `"background": {"module": "./bg.js", "triggers": ["alarms", "sms", "calls", "notify.action", "boot", "network", "periodic:30", "charging", "push"]}`; parser + lint validate (module same-origin path, `periodic:N` with N ≥ 15).
- **Capability:** `BACKGROUND` id `background`, BRIDGE, medium, "Run in the background", "Run short tasks when something happens, even when the app is closed (up to 30 seconds each)." Dashboard shows a per-app "Background runs" list (from the audit log) and the grant toggle is the kill switch.
- **Runner:** `background/BackgroundRunner.kt` owned by `BridgeService`: on a trigger, if the app holds `background` and declares the trigger, create an off-screen `WebView` (application context, same `DeftWebViewConfig`, same per-origin profile and CSP as the host) that loads a host page `/_deft/v1/background.html` serving `<script type="module" src="<module>">`, and gives it a bridge sink like the host's. The trigger arrives as event `background.trigger {trigger, payload}`; the module calls `deftBridge.invoke('background.done')` when finished.
- **Budget:** 30 s wall clock from load, then `destroy()`. One runner per app at a time (queue max 10 triggers, coalesce periodic); max 60 runs per app per hour. Each run is an audit row `background.run` with trigger and duration.
- **Bridge in background:** same grants; any method that would show a prompt (`confirmEach`, `ALWAYS_CONFIRM`, pickers, special sheets) fails with new error `BridgeErrors.NEEDS_FOREGROUND`. Methods needing `HostSurfaces` fail as they do today.
- **Tests:** unit tests for budget/queue logic (`RunnerQueue`); instrumented test (orchestrator runs it) that a bg module runs and is destroyed after the budget.

### Step 25 — D2 Triggers — Opus

- Wire each trigger to `BackgroundRunner.trigger(webAppId, name, payload)`: `alarms.fired` (AlarmReceiver path), `sms.received`/`sms.mmsReceived` (holder only), `telephony.incoming`, `notify.action`, `boot` (BootReceiver), `network` (ConnectivityManager callback in BridgeService), `charging` (runtime receiver for `ACTION_POWER_CONNECTED/DISCONNECTED` in BridgeService), `periodic:N` (WorkManager `PeriodicWorkRequest`, one unique work per app; dependency `androidx.work:work-runtime-ktx`).
- Events still go to the queue for the UI as today; the runner is extra.
- **Sample:** `samples/sms` gets an optional auto-reply rule run from `bg.js`.

### Step 26 — D3 Foreground service — Opus

- **Capability:** `FOREGROUND` id `foreground`, BRIDGE, high, "Keep running", "Keep running with a notification after you leave the app."
- **API:** `fgs.start {type: location|mediaPlayback|microphone|connectedDevice|dataSync, title, text?}`; `fgs.update {title?, text?}`; `fgs.stop`. Type requires the matching grant (`location`, `microphone`, a bluetooth/usb cap for `connectedDevice`).
- **Service:** `background/AppForegroundService` declared per type set (`foregroundServiceType="location|mediaPlayback|microphone|connectedDevice|dataSync"`, permissions `FOREGROUND_SERVICE_LOCATION`, `_MEDIA_PLAYBACK`, `_MICROPHONE`, `_DATA_SYNC`). Notification has a Stop action. While it runs, the app's WebView is not paused when the activity stops: `WebViewHostPauseGate` (`webview/WebViewHostRuntime.kt`, tested by `WebViewHostPauseGateTest`) gets an "fgs active" input.
- **Tests:** pause-gate unit test; type→grant mapping test.

### Step 27 — D4 Media session — Opus

- `MEDIA_SESSION` id `media.session` (medium). Shim `navigator.mediaSession` (metadata, playbackState, setActionHandler) → bridge `media.setMetadata`, `media.setState`; `MediaSessionCompat` (dependency `androidx.media:media`) in the D3 service when type is `mediaPlayback`; lock-screen, headset and Bluetooth buttons → event `media.action {action}` → shim calls the page's handler. **Sample:** podcast player that keeps playing with the screen off.

### Step 28 — D6 Push without Google — Opus

- `PUSH` id `push` (medium). Dependency: `org.unifiedpush.android:connector` (Maven Central). `push.register` → `{endpoint}` (one UnifiedPush instance per app, instance name = webAppId); `push.unregister`. Message → if the app is live, event `push.message {base64}`; else D1 trigger `push`; if the app has no background grant and the payload is JSON with `title`/`body`, post a notification on the app's default channel.
- **Device check needs a distributor** (e.g. ntfy) installed on the phone — orchestrator asks the user.

### Step 29 — E1 Inbound handlers — Opus

- Manifest `handles: {mimeTypes?: [...], schemes?: ["web+foo"], links?: ["https://host"]}` (lint + parser, max 16 each).
- Launcher declares `activity-alias` entries, disabled by default, for a fixed set: VIEW for `text/*`, `image/*`, `audio/*`, `video/*`, `application/pdf`, `text/calendar`, `text/vcard`, `application/json`; `SENDTO` `mailto`; and 8 generic `web+` slots are **not** possible (schemes are static) — instead one alias for scheme `web+deft` plus the declared mime types. An alias is enabled (`setComponentEnabledSetting`) only while at least one installed app handles it.
- A `HandlerPickerActivity` (dialog, `taskAffinity=""`) lists the apps that handle the intent, with "Always", and opens the chosen app with the content copied into its `ShareInbox` (content URIs) or the URL as data; event `intents.viewReceived {type, url?, items}`.
- https links cannot be verified App Links for an origin; they only work through the system "Open with" chooser. Record in UNIMPLEMENTED "Cannot".

### Step 30 — G5 Background location — Opus

- `location.background` (SPECIAL, persistent; `available = true`). Needs D3 `location` FGS or proximity alerts: `location.watchBackground {intervalMs ≥ 10000}` runs only inside an active `fgs` of type `location`; `location.geofenceAdd {id, lat, lng, radiusM ≥ 100}` uses `LocationManager.addProximityAlert` (no GMS) and wakes the D1 runner with trigger `geofence`. Android's own background-location permission (`ACCESS_BACKGROUND_LOCATION`) is requested from the G0 sheet. **Sample:** run tracker.

### Step 31 — G10 Accounts — Opus

- `accounts.own` (medium): launcher `AbstractAccountAuthenticator` service with one account type `app.deft.account`; per app, accounts are stored with `userData origin=<originId>`; `accounts.add {name}`, `accounts.list`, `accounts.remove`. Sync: a `SyncAdapter` that triggers the D1 runner with trigger `sync`.
- `accounts.read` (SPECIAL): `accounts.listAll` → `[{name, type}]` of all accounts visible to the launcher (`GET_ACCOUNTS` semantics on API 26+: only accounts that opted in; document that).

### Step 32 — G8 Notification listener — Opus

- `notifications.listen` (SPECIAL exclusive, persistent). Launcher `NotificationListenerService` (`BIND_NOTIFICATION_LISTENER_SERVICE`); events only to the holder: `notifications.posted {key, package, title, text, time}`, `notifications.removed {key}`; methods `notifications.active`, `notifications.dismiss {key}`, `notifications.reply {key, actionIndex, text}` (RemoteInput). G0 sheet deep-links to `ACTION_NOTIFICATION_LISTENER_SETTINGS`. Never forwards FL's own notifications. **Sample:** notification history.

### Step 33 — G9 Accessibility service — Opus

- `accessibility` (SPECIAL exclusive, persistent). Launcher `AccessibilityService` with an XML config (`canRetrieveWindowContent`, `canPerformGestures`); events to the holder: `a11y.event {type, package, text}` (throttled 10/s), methods `a11y.windowTree` (depth ≤ 30, nodes ≤ 2000), `a11y.perform {nodeId, action: click|longClick|setText|scroll}`, `a11y.gesture {path}`, `a11y.global {action: back|home|recents|notifications}`. Persistent notification is mandatory. **Sample:** automation that taps a button in another app.

### Step 34 — G11 Roles — Opus, one commit per role

Order: `role.browser` (VIEW http/https alias → holder origin opens URLs), `role.assistant` (`ROLE_ASSISTANT` + `VoiceInteractionService` stub → holder app opens), `calls.screen` (F7b), `wallpaper` (`WallpaperService` rendering the holder's page in an off-screen WebView into the wallpaper surface — large), `ime` (`InputMethodService` hosting the holder's page as the keyboard view — largest; enables G12 IME mode), `device.admin` (`DeviceAdminReceiver`: lock now, wipe refused). Each role: SPECIAL exclusive, holder chosen in the G0 sheet, `RoleManager.createRequestRoleIntent` where a role exists, revert documented in the sheet.

### Step 35 — F7b Call screening — Opus

- `calls.screen` (SPECIAL exclusive): launcher `CallScreeningService` (FL as dialer already gets screening); each incoming call → event `calls.screening {callId, number?, verified}` to the holder (live or D1 runner) which answers `calls.screenRespond {callId, action: allow|reject|silence, skipLog?}` within 4 s; default allow.

### Step 36 — B4 Payment-category HCE — Opus

- `nfc.hce.payment` (SPECIAL exclusive; `available = true`). Second `HostApduService` with a `payment` category AID group (static `apduservice_payment.xml` with a placeholder AID, real AIDs registered at runtime with `CardEmulation.registerAidsForService`); the G0 sheet sends the user to Android's default payment app setting. Same request/respond protocol as B3. Note in PARITY: cannot be verified without a payment terminal and network credentials.

### Step 37 — E4 Passkeys — Opus, research spike

- Determine whether WebAuthn can work for `https://<id>.app.deft.internal` origins: `WebSettingsCompat.setWebAuthenticationSupport` modes need the RP ID to match a domain with Digital Asset Links for app mode, and browser mode is restricted. Write findings into PARITY E4. If impossible, move E4 to UNIMPLEMENTED "Cannot" with the reason; if a Credential Manager bridge for hosted test origins is viable, write the spec for it instead of building it.

### Step 37b — E4 Passkeys bridge — Opus, not scheduled

Outcome of Step 37: WebAuthn inside the WebView stays off (see PARITY E4). This bridge is the only workable form: the app's **own backend** opts in, and the host binds each RP ID to the app origins that RP names. Schedule it only when the user has an RP domain to test with (section 4).

- **Why the binding:** Credential Manager calls from the launcher all carry the origin `android:apk-key-hash:<launcher cert>`, and the passkey provider only checks that the RP domain's `assetlinks.json` names the launcher. The RP cannot tell FL apps apart, so the host must: without the RP's own list of allowed origin ids, any FL app could use any RP that trusts the launcher.
- **Capability:** `WEBAUTHN` id `webauthn`, BRIDGE, medium, "Passkeys", "Create and use passkeys for this app's own website, after the website allows it." No `confirmEach`: the system passkey sheet asks the user every time.
- **Manifest:** `passkeys: {rpIds: ["example.com"]}` (max 4; lowercase DNS names; no IP, no `localhost`, nothing ending in `.internal`, `.local`, `.localhost`, `.test`, `.deft.internal`; max 253 chars). Parser + lint validate. The consent screen lists the RP IDs.
- **RP opt-in (both required):**
  1. `https://<rpId>/.well-known/assetlinks.json` with relation `delegate_permission/common.get_login_creds` for `app.deft` (and `app.deft.debug` for testing) and that build's signing-cert SHA-256. The provider checks this, not the host. Each signer (debug, release, F-Droid rebuild) has its own cert.
  2. `https://<rpId>/.well-known/freedom-launcher.json` = `{"passkeys": {"origins": ["<originId>", ...]}}` (max 64 ids). The host fetches it (https, no redirects, status 200, `application/json`, ≤ 64 KB, 10 s timeout, no cookies), caches it 24 h per rpId, and allows the call only if the calling app's `originId` is listed. Packaged apps have the same origin id on every device (`OriginIds.packaged`), so this list is stable for them; local apps' random ids work only per device.
- **API (all foreground; need `HostSurfaces.forApp`, Activity context for Credential Manager):**
  - `passkeys.status` → `{supported: boolean, origin: "android:apk-key-hash:<b64url>"}`. `supported` is `SDK_INT >= 34`. The origin string is what the RP server must accept as `clientDataJSON.origin`.
  - `passkeys.create {requestJson}` → `{responseJson}`. `requestJson` is the WebAuthn `PublicKeyCredentialCreationOptionsJSON` (≤ 16 KB). Host checks `rp.id` against manifest + opt-in, then `CredentialManager.createCredential(activity, CreatePublicKeyCredentialRequest(requestJson))` (no `origin` argument; never `setOrigin`).
  - `passkeys.get {requestJson}` → `{responseJson}`. `rpId` required (no default to the page host). `GetCredentialRequest(listOf(GetPublicKeyCredentialOption(requestJson)))`.
  - Errors: cancelled sheet → `CANCELLED`; no credential / no provider / API < 34 → `UNSUPPORTED` with a plain message; rpId not in manifest, not opted in, or opt-in fetch failed → `NOT_GRANTED, "This website has not allowed this app to use passkeys"`; malformed JSON, `mediation: "conditional"`, oversize → `INVALID_ARGS`.
- **Security rules:** never call `WebSettingsCompat.setWebAuthenticationSupport` with anything but `NONE`; never declare `CREDENTIAL_MANAGER_SET_ORIGIN`; never take rpId from anywhere but the parsed `requestJson`, and never from the page URL; one request in flight per app (`IN_USE`); audit row `webauthn.create` / `webauthn.get` with rpId only (no challenge, credential id, user handle or response). spec-trace row for "RP opt-in list binds rpId to origin id".
- **Dependencies:** `androidx.credentials:credentials:1.5.0` (stable, March 2025; confirm its AAR `minCompileSdk` ≤ 35 and that it builds with Kotlin 2.0.21 / AGP 8.7.2 before adding; 1.6.0 (April 2026) only fixes `isConditional` and is likely to need a newer toolchain). **Not** `credentials-play-services-auth` (GMS; would add Android 11–13 support only through Google Password Manager). Request/response stay JSON strings; no WebAuthn parsing library.
- **Pure logic:** `PasskeyPolicy` (rpId extraction from create/get JSON, rpId validation, manifest match, opt-in JSON parse and origin match, size and `mediation` checks). Unit tests: each rejection; an app whose origin id is not listed is refused even though the rpId is in its manifest; app A's opt-in does not cover app B; uppercase / IP / `.deft.internal` rpIds rejected; redirect or non-JSON opt-in response refused.
- **Shim (optional, later):** `navigator.credentials.create/get({publicKey})` in `samples/deft-bridge.js` via `PublicKeyCredential.parseCreationOptionsFromJSON` / `toJSON()`, so ordinary WebAuthn page code works.
- **Device check (orchestrator, Android 14+ phone):** a passkey provider set in Settings (Bitwarden or KeePassDX; or Google Password Manager where GMS is present); the user's RP domain serving both files for the debug cert; a test RP server that accepts the `passkeys.status` origin. Register, sign in, and confirm an unlisted app gets `NOT_GRANTED`.

### G13 FCM flavor — not run

Needs a Firebase project (`google-services.json`) and a sender. Ask the user before scheduling.

---

## 4. Things only the user can provide

| Needed for | What |
| --- | --- |
| B1–B3 hardware check, B4 | USB device or serial adapter; NFC tags; a second NFC phone for HCE; a payment terminal (B4 can't be fully verified) |
| G14 sample | Stripe test publishable key (`pk_test_…`) |
| E3 device check | An OAuth client at a test IdP (e.g. a GitHub OAuth app) |
| D6 device check | A UnifiedPush distributor app (e.g. ntfy) on the phone |
| G13 | Firebase project and decision to ship a `gms` flavor |
| F7a USSD check | Which carrier code is safe to send |
| E4 bridge (Step 37b) | A domain the user controls (serves both `.well-known` files and a test RP server) and a passkey provider app on an Android 14+ phone |
