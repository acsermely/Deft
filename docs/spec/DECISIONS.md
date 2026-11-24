# Spec decisions

Recorded for the implementing agent. Defaults from spec section 16 apply unless overridden here.

## Project decisions (D1–D5)

| Id | Decision | Choice |
| --- | --- | --- |
| D1 | URL-installed remote web apps | Remove now. Samples become bundled, read-only APK assets. Copy-to-my-apps makes a local copy. |
| D2 | Local-app storage | Move to app-private `filesDir/deft/` with a one-time migration from `Documents/myapps`. Zip export/import and (later) Dev link replace USB/MTP editing. |
| D3 | Capability ids | Hard split. Old coarse ids are rejected. Existing local manifests are rewritten during migration. |
| D4 | CI | None. The gate is `bun scripts/check.ts` (unit tests always; instrumented tests only with `--device`, because a run can wipe the debug app's data). |
| D5 | Hosting | Keep the Cloudflare `freedom-samples` Worker and R2. From M5 it serves the signed index and `.deftpkg` files. Nothing on the device installs from it after PR2. |
| D6 | Third-party libraries | Apps import CDN URLs directly (no build step, no package manager). The manifest pins each URL with an SRI hash; the launcher fetches it once after the user confirms, stores it by hash, serves it same-origin through a host-inserted import map, and sweeps unreferenced files (spec 10.10). `script-src` stays `'self'`. |
| D7 | Native parity (2026-11-17) | Full feature parity with sideloaded native Android apps is a goal, not a v1 aspiration. Everything an Android app can get through a permission or special access becomes a capability: declared in the manifest, granted by the user, enforced and audited by the host. Former "Never (tracking)" refusals are lifted; what stays out is only what Android or the missing GMS makes impossible. Highest-risk powers use the `special` tier (granted in the dashboard, not the consent screen). Plan: [`docs/PARITY.md`](../PARITY.md). Overrides spec §8.5 network default, §16 #3–5 and 16.1 #1–4, #7, #9–10. |

## Spec section 16

| # | Decision | Default | Status |
| --- | --- | --- | --- |
| 1 | `MIN_WEBVIEW_MAJOR` | 124 | Kept. A16 WebView is 156, above the floor. |
| 2 | App origin domain | `<id>.app.deft.internal` | Kept. Spike on the A16: secure context, and interception sees fetch, modules, workers and images. |
| 3 | Network default | No network unless declared | Decided. |
| 4 | Remote images | Blocked (`img-src 'self' data: blob:`) | Revisit in M1 if reference apps suffer. |
| 5 | WebAssembly | Allowed behind the `wasm` capability (PARITY G2): `'wasm-unsafe-eval'` only for apps with the grant; `.wasm` in the package or as a hash-pinned dependency | Decided (2026-11-19). |
| 6 | Runtime/lint license | MIT | M1. |
| 7 | Default-source hosting | Cloudflare Worker + R2 (override; not Codeberg) | D5. |
| 8 | Key and signature format | Raw ed25519 + JCS | M4. |
| 9 | Play Store flavor | Not at launch | Deferred with M7. |
| 10 | Project name | "Deft" pending a name check | Before any public announcement. |
| 11 | Per-app WebView profiles | Use `ProfileStore` where androidx.webkit supports it | A16 reports `MULTI_PROFILE=true`. `DeftWebViewConfig` creates a profile per origin id and calls `WebViewCompat.setProfile`. |
| 12 | Component format | Template functions + CSS; no shadow DOM | M4b. |
| 13 | Component dependencies | None; flat | Decided. App-level CDN dependencies are D6 (spec 10.10). |
| 14 | Core components in the runtime | CSS classes in `base.css` only | M4b. |

## Spike results (PR0)

Recorded on the Samsung A16 (`SM-A165F`, Android 16) by `SpikeTest` on 2026-11-03. An earlier FAIL was a bad measurement: the probe JSON was double-encoded, and fetch/worker were started from an inline script that CSP correctly blocked. The corrected run:

```
SPIKE WebView versionName=156.0.8078.47 major=156
SPIKE MULTI_PROFILE=true DOCUMENT_START_SCRIPT=true WEB_MESSAGE_LISTENER=true
SPIKE assumption1_csp_inject=PASS probe={"docStart":true,"evalJs":true,"isSecureContext":true,"hasJsBridge":true,"mod":true,"workerErr":""}
SPIKE assumption2_secure=true fetch=true module=true worker=true image=true
```

| Assumption | Result |
| --- | --- |
| 1. `addDocumentStartJavaScript` / `evaluateJavascript` / `jsBridge` still work under `Content-Security-Policy: script-src 'self'` | PASS. No nonce. Host scripts are not page scripts. |
| 2. `https://x.app.deft.internal` is a secure context; `shouldInterceptRequest` sees fetch, module imports, workers and images | PASS. All four resource kinds were intercepted. |
| 3. `WebViewFeature.MULTI_PROFILE` | true. Per-app profiles are applied. |
| 4. Installed WebView major version | 156 (`156.0.8078.47`). Floor stays 124. |

## Runtime budget

`repeat()` recreates nodes instead of reordering keyed ones. Keyed reorder did not fit in the 8 KB uncompressed JS budget (section 14.2), which is the release gate. The modules stay at 8192 bytes. Focus and caret restore stay in `render()`. Motion stays in `base.css` for the same reason. The `base.css` ceiling is 16 KB.

## Spec 16.1 triage

Updated for D7 on 2026-11-17.

| # | Question | When |
| --- | --- | --- |
| 1 | User-provided network origins / open-network capability | Both planned (D7): `network.user`, `network.open` ([PARITY.md](../PARITY.md) P1, P3). |
| 2 | `img-src` / `media-src` for declared origins | Planned (D7): follow `connect-src` (PARITY P2). |
| 3 | Background tasks / foreground service | Planned (D7): headless runner, triggers, typed foreground services (PARITY Phase D). Alarms cover what shipped. |
| 4 | Push: UnifiedPush only | UnifiedPush in the default build (PARITY D6); FCM only in an optional `gms` flavor (P19). |
| 5 | Host-routed intents | Shipped: `intents.open`, `share.receive`. Inbound handlers and outbound actions planned (PARITY E1–E2). |
| 6 | Auth session / in-app browser | Planned: `auth.session` (PARITY E3). |
| 7 | Asset packs | Planned (D7): `storage.large` with hash-verified packs (PARITY P5, G3). |
| 8 | File and storage capabilities | Now. Split into `files.pick`, `files.read`, `files.write`. |
| 9 | Media session | Planned (PARITY D4, D5). |
| 10 | Native APIs WebView lacks | Shipped: BLE, NFC read, USB open, SMS, telephony, contacts, calendar. The rest (USB transfers, NFC write/HCE, Keystore, sensors, tiles, notification actions…) is PARITY Phases B, C, F. |
| 11 | Per-app data export/import | M3 (history) plus the zip export in PR1. |
| 12 | Manifest translations | Later. |
| 13 | Donation link | M5. |

## Parity decisions

| Id | Decision | Choice |
| --- | --- | --- |
| G14-TT | Trusted Types for apps with third-party scripts (`web.embed`, PARITY P20) | **Kept on, with one extra policy.** Stripe's CSP guide says that under Trusted Types Stripe.js must be allowed to load further scripts from `https://js.stripe.com` and `https://*.js.stripe.com` through a `default` policy, so Stripe.js breaks under the plain `require-trusted-types-for 'script'; trusted-types deft` (no page may create `default`). Instead of dropping Trusted Types, an app whose `web.embed` is granted **and** has at least one approved origin gets `trusted-types deft default`, and the host's document-start script (app origin only, before any page script) creates that `default` policy with only `createScriptURL`, passing `https:` URLs on approved origins (`*.` = any subdomain, as in CSP) and refusing everything else. HTML and script sinks stay closed; page code cannot create its own `default`. Every other app keeps `trusted-types deft`. Pinned in `CspBuilderTest` (`twoApprovedEmbedOrigins`, `embedsWithEveryFlagCombination`, `embedScriptPolicyPassesOnlyApprovedScriptUrls`) and `HostileTest.unapprovedEmbedScriptIsBlocked`. Not yet run against live Stripe on a device: if a provider writes HTML through a sink (`innerHTML`, `document.write` into its own `about:blank` frame, as zoid-based SDKs such as PayPal do), it will still be blocked, and the fallback is to drop Trusted Types for `web.embed` apps only. |
| G14-frames | Frame navigations of embed apps | `frame-src` lists the approved origins; deeper frames (3-D Secure bank pages inside Stripe's frame) follow their own site's CSP. The host's `shouldOverrideUrlLoading` lets any `https:` navigation with `isForMainFrame == false` through **only** in an app with approved embeds (`DeftNavigation.allowSubframe`), so a frame is never turned into an "open in browser" sheet; top-level rules are unchanged. |
| G14-store | Where embed approvals live | The `network_consent` table (no migration): `source = "embed"` for approved origins, `"embed-refused"` for unticked ones, so a refusal is not asked again but a newly listed origin is. Origins dropped from the manifest are forgotten. |
