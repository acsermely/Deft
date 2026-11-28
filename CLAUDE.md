# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this is

Deft is a stock-Android HOME launcher (one APK, no root, no custom ROM) that runs ordinary HTML/JS web apps as first-class apps. The launcher holds the OS permissions and roles. Web apps get them only through `window.deftBridge` / `/_deft/v1/bridge.js`, per capability, after the user consents. Camera, mic and geolocation stay standard WebView APIs. The goal is native parity: a web app should be able to do anything a sideloaded Android app can, once declared and granted.

Docs are for people: `README.md`, `docs/GETTING_STARTED.md`, `docs/how-it-works.md`, `docs/writing-apps.md`. Keep them plain and true to the code, with no plan ids, step numbers or status lines; update them in the same change that changes the behaviour they describe. There is no plan document. Record why a piece of code is the way it is in a comment next to it. Code that only a real phone can prove gets a `DEVICE-UNCHECKED (<area>): ...` note saying what to check (`grep -rn DEVICE-UNCHECKED android/`). Do not announce the name "Deft" publicly: the trademark check isn't done.

## Commands

Tooling is **Bun** for every repo script and JS tool. Gradle needs **JDK 17 or 21** (newer JDKs can break Gradle 8.9) and `android/local.properties` with `sdk.dir=` (git-ignored).

```bash
bun scripts/check.ts            # the local gate: Kotlin unit tests, deft-lint tests, runtime tests, generated-file checks, sample manifests, lint bundle + sample lint snapshot
bun scripts/check.ts --device   # also instrumented tests on the connected phone (can wipe app.deft.debug data)
```

Android (run from `android/`):

```bash
./gradlew :app:assembleDebug
./gradlew installDebug                                   # debug package is app.deft.debug
./gradlew :app:testDebugUnitTest --tests 'app.deft.bridge.KeystoreAliasesTest'
./gradlew connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=app.deft.webview.HostileTest
```

JS tools (each has its own `bun test`; pass a file path or `-t <name>` to run one test):

```bash
cd tools/deft-lint && bun test                 # lint rules (test/rules.test.js)
cd tools/deft-runtime && bun test              # /_deft/v1 runtime tests
cd tools/deft-design && bun test
bun tools/deft-lint/cli.js samples/clock [--profile app|canvas] [--json]   # lint one app like the device does
```

Regenerators (`check.ts` runs each with `--check` and fails if output is stale):

```bash
bun tools/deft-design/build.js        # design/tokens/*.json -> tokens.css, Tokens.kt, docs/design/tokens.md, skill tokens.md + APK skill copy
bun scripts/build-bridge-docs.ts      # bridge-catalog.json -> agent/skills/js-bridge.md and generated blocks in js-bridge/<group>.md
cd tools/deft-lint && bun run build   # -> android/app/src/main/assets/lint/deft-lint.js (bundles components.css)
```

Web:

```bash
cd web && bun run dev / bun run deploy                     # SvelteKit landing page on Cloudflare Pages (project deft)
./scripts/publish-apk.sh                                    # uploads the built APK to R2 bucket deft-apk
bun scripts/build-samples-site.ts && bunx --bun wrangler deploy  # samples + runtime -> Worker deft-samples (*.app.acsermely.hu)
```

Node and `npx` are not required. Run wrangler as `bunx --bun wrangler …`, or `bun --bun web/node_modules/wrangler/bin/wrangler.js …` from the repo root for the samples Worker. The Worker serves non-HTML assets with a 7-day `max-age` (`workers/samples.js`), so runtime CSS changes need a Cloudflare cache purge to show up right away.

## Sources of truth (do not edit the copies)

| Edit | Generated or copied into |
|---|---|
| `samples/<app>/` | `android/app/src/main/assets/deft-apps/` (Gradle `copyBundledApps` on preBuild; `hostile/` excluded) |
| `samples/deft-bridge.js` | `assets/bridge/deft-bridge.js` (git-ignored) |
| `skill/deft-design/` (also symlinked as `.claude/skills/deft-design`) | `assets/agent/skills/deft-design/` via `tools/deft-design/build.js` |
| `design/tokens/*.json` | `deft-runtime/v1/tokens.css`, `ui/theme/Tokens.kt`, token docs |
| `assets/agent/bridge-catalog.json` | the bridge method docs the on-device agent reads |
| `deft-runtime/v1/components.css`, `tools/deft-lint/src` | `assets/lint/deft-lint.js` (committed bundle; must match a fresh build) |

## Architecture

```
HomeActivity (web apps + Android app drawer + Settings + home canvas)
  -> WebViewHostActivity: one task per app, origin https://<id>.app.deft.internal, own WebView profile
       -> LocalOriginPathHandler: serves app files and the /_deft/v1/ runtime, adds CSP
       -> DeftJsBridge: addWebMessageListener locked to the app origin
  -> BridgeService (foreground service) -> bridge modules (contacts, sms, telephony, files, calendar, alarms, notify, intents, nfc, bluetooth, usb, ...)
  -> Dashboard (grants, audit log, lint results)
  -> Agent (Kotlin, in-process, OpenRouter-compatible API) that writes and edits web apps
```

- **Bridge call path:** `BridgeService.invoke` checks origin, then capability, then the stored grant, then validates arguments against `bridge-catalog.json`. Sends and calls need a confirm sheet unless they follow a real tap. Every call goes to the per-app audit log. Events for apps that aren't open are queued until the next open. No app JS runs in the background yet.
- **Apps** declare `capabilities` (with a user-facing `reason`) and `network` origins in `manifest.json`; `scripts/check.ts` validates every sample manifest against the catalog. Bundled samples are read-only; user apps live in `filesDir/deft/` and import/export as ed25519-signed zips (`scripts/deft.ts pack|sign|verify`).
- **Roles:** the launcher is the one default SMS app (and optionally the default dialer). At most one web origin holds `sms` and one holds `calls`.
- **Theming:** `filesDir/config/*.toml` via `ConfigStore` (defaults in `assets/config/`) styles the launcher UI. `config/Palette.kt` injects it into apps as `--deft-*` variables (`theme.css`).
- **Agent:** endpoint and model in `assets/config/agent.toml`, API key in EncryptedSharedPreferences. It never sets grants. App edits go through Apply and are linted on device by `DeftLintEngine`, which runs the bundled `deft-lint.js`. Its instructions are `assets/agent/SKILL.md` and `assets/agent/skills/`.
- Kotlin code is in `android/app/src/main/java/app/deft/`, one package per area (`bridge/`, `webview/`, `agent/`, `sms/`, `telephony/`, `ui/`, ...). Debug-only test hosts and receivers are in `src/debug/`.

## Runtime (`/_deft/v1/`) and design system

- CSS cascade layers are declared in `base.css`: `deft.reset, deft.theme, deft.base, deft.layout, deft.components, deft.utilities`. Apps must link `base.css, tokens.css, components.css, theme.css` in that order (lint FL603). App CSS that isn't in a layer beats every runtime layer.
- Size caps enforced by `tools/deft-runtime/core.test.js`: `components.css` ≤ 32 KB (it sits close to the cap) and `base.css` ≤ 16 KB.
- In app code, lint forbids raw `@keyframes`, `cubic-bezier()`, `steps()` and millisecond literals (FL627/628/631); use the motion tokens and `motion.js`. The full rules are in `skill/deft-design/` (read it before building app UI or CSS).
- Press feedback: the WebView tap highlight is off for the whole page. Runtime controls and `[data-deft-press]` draw a state layer through the registered `--deft-press-layer` property as a `background-image`, so component rules must not set the `background` shorthand at press time.
