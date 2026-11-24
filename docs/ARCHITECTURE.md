# Architecture

Stock Android (kernel, HAL, framework) is unmodified. Deft is one APK that is the HOME app and holds the OS permissions and roles. Web apps never hold them; they get a mediated bridge after per-origin consent.

```
HOME (web apps + Android app drawer + Settings + home canvas)
  -> WebViewHostActivity (one task per app, origin https://<id>.app.deft.internal)
       -> LocalOriginPathHandler (serves app files + /_deft/v1/ runtime, adds CSP)
       -> DeftJsBridge (addWebMessageListener, locked to the app origin)
  -> BridgeService (foreground service)
       -> Contacts / Calendar / Telephony / Files / Sms / Intents / Notify
       -> Alarms / Nfc / Bluetooth (SPP + BLE) / Usb
  -> Dashboard (grants, usage log, lint results)
  -> Agent panel (Kotlin, native tools, OpenRouter-compatible API)
```

**Apps.** Bundled samples are read-only APK assets (`assets/deft-apps/`, copied from `samples/` at build). Local apps live in `filesDir/deft/`. Packages are signed zips (ed25519). Each app has its own origin, CSP, and WebView profile.

**Bridge.** `BridgeService.invoke` checks origin, then capability, then the stored grant, then validates arguments against the catalog (`assets/agent/bridge-catalog.json`). Sends and calls go through a confirm sheet unless they follow a real tap (see [PARITY.md](PARITY.md) H1). Every call is written to the per-app audit log. Events (`sms.received`, `alarms.fired`, …) go to live views; if the app is not open they are queued and delivered on next open. No app JS runs in the background today ([PARITY.md](PARITY.md) Phase D).

**Standard web APIs** stay in the WebView: geolocation and `getUserMedia` behind install-time grants, storage per origin. In-page Web Notifications are denied; apps use `notify.post`.

**Roles.** The launcher is the one default SMS app and optionally the default dialer. At most one web origin holds `sms` and one holds `calls`. Incoming `SMS_DELIVER` is stored in `content://sms` even with no holder, then routed to the holder. USSD (`*`/`#`) is refused on place-call.

**Files.** A persistable SAF tree per origin, or Downloads when no folder is picked. The media picker is single-item.

**Navigation.** Off-origin links open another installed web app (matched by origin) or a system VIEW chooser.

**Look and feel** comes from `filesDir/config/*.toml` via `ConfigStore` (defaults in `assets/config/`), applied to the launcher UI and injected into apps as `--deft-*` variables.

**Agent.** Kotlin in the launcher process, not a web origin. Model and endpoint are in `assets/config/agent.toml`; the API key is in EncryptedSharedPreferences. Writes to config and app source go through Apply; grants are never set by the agent. App edits are linted by `DeftLintEngine` (bundled `tools/deft-lint`).

More: platform spec [spec/PLATFORM.md](spec/PLATFORM.md), forward plan [PARITY.md](PARITY.md), what can't be done [UNIMPLEMENTED.md](UNIMPLEMENTED.md).
