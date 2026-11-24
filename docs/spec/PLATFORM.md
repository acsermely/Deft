# Deft — App Platform & Distribution Spec

Nov 2, 2026 · @Ati

## Amendments (repo, Nov 2026)

These override the body of the spec where it disagrees with the current repository or with recorded project decisions. See also [`DECISIONS.md`](DECISIONS.md), [`ROADMAP.md`](ROADMAP.md) and [`../PARITY.md`](../PARITY.md).

- `js-bridge.md` lives at `android/app/src/main/assets/agent/skills/js-bridge.md` and is the only copy. `docs/js-bridge.md` points to it.
- `deft-bridge.js` lives in `samples/` and is copied into APK assets by Gradle (`android/app/build.gradle.kts`).
- `HOOK_JS` (`WebViewFindings.kt`) only catches errors and unhandled rejections. Trusted Types and CSP reporting are added in M0/M1.
- `AppVerifier` is a runtime smoke check (console, HTTP, layout). It does not verify signatures or hashes.
- The local-app scaffold currently writes `styles.css`. It is renamed to `app.css` in M1.
- Capability ids in sections 3.2 and 8.4 are replaced by the catalog in `assets/agent/bridge-catalog.json` (M0 PR3). Coarse ids (`contacts`, `sms`, …) are rejected.
- URL-installed remote web apps are a non-goal and are removed in M0 PR2. Samples ship as bundled APK assets.
- Local apps live in app-private storage (`filesDir/deft/`), not `Documents/myapps`.
- Gates run locally through `bun scripts/check.ts`, not CI on an emulator. No CI until the project goes public.
- Tooling (7.1, 11.9, 12.2) uses Bun (`bun build`, `bun test`) instead of Node, esbuild and vitest.
- Decision 16 #7 (default-source hosting): the existing Cloudflare Worker (`freedom-samples`) plus R2, not Codeberg.
- Sections 12.4–12.6, 13 and milestone M7 are deferred until a public launch. Milestone M8 is deferred; `scripts/debloat.sh` covers the personal case.
- Motion lives in `base.css` (6.4). The `base.css` ceiling in 14.2 is 16 KB. Apps use the runtime curves and the single shared name `deft-morph`. They do not author their own durations or `view-transition-name` values.
- 8.4 tap rule (H1, 2026-11-17): only a genuine tap counts: one finger, within the touch slop, released before the long-press timeout. The end of a scroll, a long-press or a pinch is not a tap. The biometric flag is read fresh on every confirm-each call.
- **Native parity (D7, 2026-11-17).** The Parity goal in section 1 is in force now. Restrictions in sections 4.3, 8.2, 8.5 and 16 (network allow-list default, remote images, WebAssembly, background tasks, push, size caps) become per-app capabilities instead of fixed limits. A new `special` risk tier sits above `high`. See [`../PARITY.md`](../PARITY.md).
- App quotas live in `filesDir/config/limits.toml` and reload with the rest of the launcher config. The host clamps `max_text_bytes` to 512 KiB–4 MiB, `max_binary_bytes` to 2–8 MiB, `max_files` to 256–1024, and `max_package_bytes` to 10–64 MiB. Defaults are 512 KiB, 2 MiB, 256 files, and 25 MiB. `DeftPaths` constants remain the floors (the package floor is 10 MiB; its default is the previous 25 MiB import cap).

## 1. Purpose and principles

Deft (FL) ships a platform for small, user-owned web apps on Android: plain HTML/CSS/JS that people and agents write, edit and share on the device, with no compiler, bundler, package registry or JS toolchain anywhere in the loop. This spec defines the app format, the authoring rules, the runtime, the theme system, the security model and the distribution system. It is written for an implementing agent with full repo access; normative words MUST / SHOULD / MAY follow RFC 2119.

### Product thesis

Omarchy showed that an opinionated system made of plain text files becomes malleable once an agent with file access can edit it. Android apps are the opposite: compiled, signed, opaque. FL brings the Omarchy loop to the phone: *describe it, edit a file, see it a second later, share it*. That loop only works if apps are source, the stack is fixed, and the rules are checked by machines rather than by documentation.

### Principles (in priority order; when two conflict, the higher wins)

1. **User sovereignty.** The user owns the device, the apps and the data. Every app is readable source. Every permission is visible and revocable. Nothing is installed, updated or phoned home without the user's consent. No account is ever required.
2. **Security by construction, not by review.** Safety comes from what the platform makes impossible (CSP, Trusted Types, capability-gated bridge, local origin), then from deterministic checks (deft-lint), and only last from human curation. A malicious app that passes lint must still be unable to exceed its declared capabilities.
3. **No build step, ever.** What is on disk is what runs. No transpile, bundle, minify or code generation for apps, themes or the runtime. Tooling may be built in the FL repo (the linter bundle), but nothing a contributor or the agent writes needs a build. Development happens on the phone: writing, previewing, debugging and publishing all run inside the launcher.
4. **One fixed stack.** One runtime (`/_deft/v1/`), one theme contract, one linter. Fewer choices make generated apps better and reviews cheaper. Escape hatch: plain DOM JS is always allowed, inside the same security limits.
5. **Allow-lists over block-lists.** Capabilities, network origins, file types and runtime exports are enumerated. Anything not listed is denied.
6. **Every error teaches.** Each lint rule and runtime error carries a stable id, a location and the concrete replacement, so a human or a model fixes it in one round.
7. **Decentralized by default.** Anyone can publish; anyone can run an index; the default index is a convenience, not a gate. The platform never depends on a server the project operates.
8. **Boring, auditable tech.** Git, JSON, ed25519, SHA-256, zip. No blockchain, no proprietary formats, no telemetry.

**Parity goal.** FL apps should be able to reach feature parity with native Android and iOS apps. Restrictions in this spec are v1 defaults, not permanent limits: a new power is acceptable when it is declared in the manifest, granted by the user and enforced by the host. Candidate extensions are listed as questions in 16.1.

### Non-goals

- Running arbitrary existing websites or PWAs as FL apps (they lack the local-origin and capability model).
- Native performance for heavy 3D games; canvas 2D and modest WebGL are fine.
- Unmediated background JS. If background work is added, it runs only as host-scheduled, budgeted tasks (see 16.1).
- Monetization, ads, accounts, analytics. If added later, they are separate opt-in components, never part of the core.
- Hosts other than the Android launcher. iOS cannot support this model; apps are written, previewed, debugged and run only inside the launcher on Android.

### How to read this spec

Sections 2–10 define the app platform (section 10 covers shared components and recipes); section 11 defines distribution; sections 12–13 cover contributors and governance; sections 14–15 cover measurement and the build order; section 16 lists open decisions. Identifiers that already exist in the repo (`AgentTools.kt`, `MyAppsStore`, `HomeCanvasStore`, `AppVerifier.kt`, `LocalOriginPathHandler.kt`, `Capability.kt`, `js-bridge.md`, `deft-bridge.js`, `WebViewFindings.HOOK_JS`, `AgentLog.kt`) are referenced by name; the implementer MUST read them before changing them.

## 2. System overview

FL has four layers: **packages** (source folders, signed for distribution), the **host** (Kotlin: stores, loader, bridge, lint engine, installer), the **runtime** (`/_deft/v1/`, plain ES modules served from APK assets) and **sources** (static JSON indexes on any HTTPS host or git forge). The agent harness is one more author that writes files through the same stores and the same lint gate as a human. The architecture diagram at the end of this section shows how they connect.

### Glossary

| Term | Meaning |
| --- | --- |
| App | A folder with `manifest.json`, `index.html`, JS/CSS modules and assets. Profile `myapp` (bridge allowed) or `canvas` (no bridge, no network). |
| Canvas | A home-screen surface (widget-like) using the `canvas` profile. Same format as an app. |
| Theme | Data-only package: `palette.json` + optional wallpapers. Never contains CSS or JS. |
| Package | A zip of an app or theme folder plus `deft-package.json` (hashes) and a detached signature. |
| Base | The read-only installed copy of a package version. |
| Overlay | The user's/agent's edits to an installed app, stored separately and layered over the base. |
| Local app | An app created on the device (by user or agent), with no upstream. |
| Source | A URL to a signed `index.json` listing packages. Users may add any number. |
| Author key | An ed25519 key pair that signs packages. Identity = key, not account. |
| Capability | A named bridge permission from `Capability.kt` (e.g. `contacts.read`). |
| deft-lint | The deterministic validator, run on device (WebView) and in contributor CI (Node or browser). |
| Runtime | `/_deft/v1/*.js` + `/_deft/v1/*.css`, frozen per major version. |
| Bridge | `deft-bridge.js` host injection plus `/_deft/v1/bridge.js` wrapper. Only path to device APIs. |
| Component | Reusable UI source (code + recipe + checks) that apps copy into components/\<id>/ and own. May declare capabilities. No dependencies on other components. |
| Recipe | Structured text describing a pattern for the agent to implement. Never declares capabilities. |
| Provenance header | First-line comment in a copied component file naming component, version, author and original hash. |

### Runtime layout on device

```
<app-private-storage>/deft/
  apps/<app-id>/base/<version>/...      read-only, verified package contents
  apps/<app-id>/overlay/...             user/agent edits (sparse)
  apps/<app-id>/data/                   origin storage is per app (see 8.3)
  local/<app-id>/...                    locally created apps (no base)
  themes/<theme-id>/<version>/...
  sources/<source-id>.json              cached signed indexes
  keys/trusted.json                     pinned author keys (TOFU) + user-trusted keys
  revocations/<source-id>.json
```

Each app is served from its own origin (see 8.3) by `LocalOriginPathHandler`, which resolves `overlay → base` per path and serves `/_deft/` from APK assets.

&#91;embedded content: FL architecture: authoring, distribution, lint gate, serving, bridge\]

Local edits and installed packages both pass deft-lint before they count. Everything an app runs is served by one handler that applies the per-app CSP, and device access only goes through the host-checked bridge.

## 3. Package format and manifest

An app is a folder whose root holds `manifest.json` and `index.html`; the same folder is the git repo, the on-device source and (zipped, with hashes and a signature) the distributed package. There is exactly one format for local apps, canvases and published apps.

### 3.1 Folder rules

- Allowed extensions: `.html .js .css .json .svg .png .webp .jpg .jpeg .gif .avif .woff2 .wasm .txt .md`, plus `LICENSE`/`COPYING` without extension. Everything else is rejected (lint FL700).
- Paths: lowercase `[a-z0-9._-]` segments, `/` separated, max depth 6, max 256 files. No leading dot, no `..`, no symlinks, no empty segments. Zip entries violating this abort install (zip-slip defence).
- Size: per file ≤ `MyAppsStore.MAX_BYTES` for text, ≤ 2 MB per binary asset, ≤ 10 MB per package (hard cap 25 MB, needs `"large": true` and shows a size warning at install).
- `_deft/` is reserved at every level: stores reject writes, installer rejects packages containing it. The components/\<id>/ folder is reserved for copied components (section 10).
- Required: `manifest.json`, `index.html`, `main.js`, `LICENSE` (for published packages only).

### 3.2 manifest.json

```json
{
  "deft": 1,
  "id": "notes",
  "name": "Notes",
  "version": "1.4.0",
  "description": "Plain-text notes with tags.",
  "profile": "myapp",
  "start_url": "./index.html",
  "icon": "./icon.svg",
  "license": "GPL-3.0-or-later",
  "source": "https://codeberg.org/alice/deft-notes",
  "authors": ["Alice Example"],
  "capabilities": [
    { "id": "share.receive", "reason": "Save text shared from other apps as a note" }
  ],
  "network": [],
  "dependencies": {
    "https://cdn.jsdelivr.net/npm/three@0.160.0/build/three.module.min.js": "sha256-PmkKx9GA…"
  },
  "categories": ["productivity"],
  "forked_from": null
}
```

| Field | Rule |
| --- | --- |
| `fl` | Required integer. Selects runtime major and lint rule set. Only `1` exists. |
| `id` | Required slug `[a-z0-9-]{2,40}`. **Not globally unique.** Canonical identity is `(author key fingerprint, id)`; see 11.2. |
| `name` | Required, 1–30 chars, no URLs, no emoji-only names. Displayed with the author's verified name/fingerprint, never alone in install/consent UI. |
| `version` | Required semver. Updates MUST increase it. |
| `profile` | `myapp` or `canvas`. Canvas forbids `capabilities` and `network`. |
| `license` | SPDX expression. Required to publish. The default index accepts only OSI- or FSF-approved licenses (11.6). |
| `source` | HTTPS URL of the public source repo. Required to publish to the default index. |
| `capabilities` | Array of `{id, reason}`; `id` MUST exist in `bridge-catalog.json`; `reason` 10–140 chars, shown verbatim in consent UI. |
| `network` | Array of `https://host[:port]` origins. No wildcards, no paths, no `http:`, no IP literals, no `localhost`. Empty = offline app. |
| `dependencies` | Object of exact `https:` CDN file URL → SRI hash (`sha256-`/`sha384-`/`sha512-`), or `{integrity, as}` to add a bare-name alias. Required hashes to publish; local apps may use `null` until the first confirmed download pins it. Canvas forbids it. See 10.10. |
| `forked_from` | `null` or `{author, id, version}`; set automatically by fork (9.3). |
| `min_host` | Optional host version code if the app needs a newer bridge capability. |

Unknown fields are a lint warning (FL705), not an error, so later spec versions stay forward compatible.

### 3.3 Distribution envelope

A published package is `<id>-<version>.deftpkg` (a zip) containing the app folder plus two files at the root:

- `deft-package.json`: `{ "format": 1, "kind": "app"|"theme", "id", "version", "author_key": "ed25519:<base64>", "created": "<RFC 3339>", "files": { "<path>": "sha256:<hex>", ... } }`. Every file in the zip except the two envelope files MUST be listed; any extra or missing file aborts install.
- `deft-package.sig`: detached ed25519 signature over the RFC 8785 (JCS) canonical bytes of `deft-package.json`.

The zip container itself is not signed, so zip metadata (timestamps, order, compression) never matters and packages are reproducible: the same git tree always yields the same `deft-package.json` except `created`. Verifiers recompute every file hash from the extracted bytes.

Themes use the same envelope with `kind: "theme"` and the theme folder rules in 6.2.

## 4. Authoring profiles (FL-JS)

FL-JS is standard modern JavaScript, HTML and CSS with a small set of bans and one fixed runtime; there is no new syntax, so models keep their training priors and humans need no new tooling. Two profiles exist: `myapp` (full apps, bridge allowed) and `canvas` (home-screen surfaces, no bridge, no network).

### 4.1 Target engine

- The only runtime target is Android System WebView (Chromium). The host defines `MIN_WEBVIEW_MAJOR` (initial value **124**) in one Kotlin constant mirrored into `bridge-catalog.json`.
- Below that version the host shows a one-time screen explaining how to update WebView and refuses to run FL apps (local editing still works). It MUST NOT silently run with missing features.
- Lint group FL8xx (7.3) rejects platform features not available at `MIN_WEBVIEW_MAJOR`, using `web-features` / BCD `chrome_android` data baked into the linter at build time. Raising the constant is a one-line change plus a linter rebuild.

### 4.2 Required page shape

```html
<!doctype html>
<html lang="en">
<head>
  <meta charset="utf-8">
  <meta name="viewport" content="width=device-width, initial-scale=1, viewport-fit=cover">
  <title>Notes</title>
  <link rel="stylesheet" href="/_deft/v1/base.css">
  <link rel="stylesheet" href="/_deft/v1/theme.css">
  <link rel="stylesheet" href="./app.css">
  <script type="module" src="./main.js"></script>
</head>
<body>
  <main id="app" class="deft-stack"></main>
</body>
</html>
```

- Exactly one module entry `./main.js`. No classic scripts, no inline `<script>`, no inline `<style>`, no `on*=` attributes.
- Stylesheets: only `/_deft/v1/*.css` and same-folder relative files. `base.css` and `theme.css` are required, in that order, before app CSS.
- JS imports: relative specifiers ending in `.js`, or exact exports of `/_deft/v1/<module>.js`. No bare specifiers, no import maps, no remote URLs, no dynamic `import()` of computed strings.
- No TypeScript, JSX, decorators or other syntax requiring a build. The parser runs `ecmaVersion: latest, sourceType: module`.

### 4.3 Capability matrix

| Platform surface | `myapp` | `canvas` | Enforced by |
| --- | --- | --- | --- |
| DOM, CSS, canvas 2D, WebGL, Web Audio | yes | yes | — |
| `/_deft/v1/` runtime (except `bridge.js`) | yes | yes | lint FL0xx |
| `/_deft/v1/bridge.js` | yes, declared caps only | no | lint FL2xx + host |
| `fetch` / `WebSocket` / `EventSource` | manifest `network` origins only | no | CSP `connect-src` + lint |
| `localStorage`, IndexedDB | yes, per-app origin | yes, per-canvas origin | origin isolation (8.3) |
| Dedicated module workers (same folder) | yes | no | CSP `worker-src` |
| Service workers, SharedWorker | no | no | CSP + host denies registration |
| Camera, mic, geolocation via web APIs | only if matching capability declared | no | host `onPermissionRequest` |
| Clipboard write (user gesture) | yes | no | host |
| Clipboard read, notifications, contacts, SMS, files, BLE, alarms | bridge capability only | no | bridge |
| `iframe`, `object`, `embed` | no | no | CSP + lint |
| `window.open`, external links | opens system browser after confirm | no | host `shouldOverrideUrlLoading` |
| `alert`/`confirm`/`prompt` | no (use runtime `dialog()`) | no | lint FL105 + host no-op |
| `eval`, `new Function`, string timers | no | no | CSP + lint FL101 |
| `innerHTML` and other HTML sinks | no | no | Trusted Types + lint FL100 |

### 4.4 Escape hatch

Plain DOM APIs (`createElement`, `textContent`, `addEventListener`, CSSOM) are always allowed; the runtime is a recommendation, not a requirement. The suppression comment `// deft-allow FL4xx: <reason>` silences one warning-level finding on the next line. It can never silence error-level rules, and never anything in FL1xx (security), FL2xx (bridge/network) or FL7xx (manifest). Each suppression is counted and shown on the app's detail page ("3 lint suppressions"), so reviewers see them.

## 5. Runtime `/_deft/v1/`

The runtime is a set of hand-written, commented ES modules (target ≤ 8 KB total uncompressed JS, excluding CSS) in `android/app/src/main/assets/deft-runtime/v1/`, served at `/_deft/v1/` on every app origin. Its APIs deliberately mimic lit-html and Preact signals so models already know them. It is frozen once shipped: fixes may land, but no export is removed or changes meaning; breaking changes go to `/_deft/v2/`, and both are served side by side for at least two years.

### 5.1 Modules

| Module | Exports | Notes |
| --- | --- | --- |
| `core.js` | `signal(v)`, `computed(fn)`, `effect(fn) → dispose`, `batch(fn)`, `untracked(fn)` | `.value` get/set, `.peek()`. Effects run synchronously after batch, microtask-coalesced. |
| `dom.js` | `html` tag, `render(target, tpl)`, `repeat(items, keyFn, tplFn)`, `ref()`, `classes(obj)` | Auto-escaping. Attribute, property (`.value=`), boolean (`?disabled=`) and event (`@click=`) bindings. No `raw()`/`unsafeHTML` export, by design. |
| `events.js` | `on(target, type, selectorOrFn, fn?) → off`, `scope() → {on, interval, effect, dispose}` | Delegated events; `scope` groups cleanup for screens. |
| `store.js` | `store(key, initial, {version, migrate}) → signal` | Persists to localStorage under `fl:<key>`; JSON in try/catch; debounced 250 ms writes; quota errors → `console.warn` + `toast`. |
| `app.js` | `interval(fn, ms)`, `frame(fn)`, `back(fn)`, `dialog({title, body, actions}) → Promise`, `toast(text, {action})`, `share(data)` | Timers pause on `visibilitychange`. `back` wraps host `__deftOnBack`. `dialog` uses `<dialog>`; `toast` uses `popover`. |
| `route.js` | `routes({ '/': Home, '/note/:id': Note }, target)`, `go(path)`, `current` (signal), `link(path)` | Hash-based. Integrates with `back()`. Uses same-document View Transitions when available, no-op otherwise. |
| `bridge.js` | `ready()`, `call(method, args) → Promise`, `on(event, fn) → off`, `has(cap)` | `myapp` only. Errors normalized to `{code, message}`. When run outside FL, renders an "Open in Deft" notice instead of throwing. |
| `index.js` | re-exports all but `bridge.js` | Canvas code imports this. |
| `base.css` | reset, tokens fallback, layout primitives, form styles, safe-area utilities | See 6.4. |
| `theme.css` | generated per active theme | See 6.3. Not a file in assets: served dynamically by the host. |

### 5.2 `html` and `render` requirements

- Templates are parsed once per call site (cached by the template strings array) into a `<template>` element. The only HTML sink used is `template.innerHTML` inside the Trusted Types policy `fl` (see 8.2); interpolated values are never part of that string.
- `render()` morphs in place: it MUST preserve the focused element, its selection/caret and scroll position of scroll containers when the same template re-renders.
- `repeat()` reorders keyed nodes instead of re-creating them; unkeyed arrays are allowed but lint warns when a `.map()` returning a template is passed to a list longer than one (FL402).
- `style` bindings in templates (`style=${{color: x}}` or `style=${'--hue:' + h}`) are applied via CSSOM (`el.style.setProperty`), never via the `style` attribute, so the CSP can omit `'unsafe-inline'` for style attributes (8.2). Static `style="..."` in templates is a lint error (FL106).
- URL attributes (`href`, `src`, `action`, `formaction`) reject `javascript:` and `data:` (except `data:image/*` on `img src`) at bind time.

### 5.3 Example

```js
// main.js — Notes: list + editor screens
import { signal, computed, html, render, repeat, store, routes, on } from '/_deft/v1/index.js';

const notes = store('notes', [], { version: 1 });
const query = signal('');
const visible = computed(() => notes.value.filter(n => n.text.includes(query.value)));

const Home = () => html`
  <header class="deft-cluster"><h1>Notes</h1><a class="deft-btn deft-btn-primary" href="#/new">New</a></header>
  <input type="search" aria-label="Search notes" .value=${query.value} @input=${e => query.value = e.target.value}>
  <ul class="deft-stack">${repeat(visible.value, n => n.id, n => html`<li><a href="#/note/${n.id}">${n.text}</a></li>`)}</ul>`;

routes({ '/': Home, '/new': () => html`...`, '/note/:id': ({ id }) => html`...` }, document.getElementById('app'));
```

### 5.4 Documentation shipped with it

`assets/agent/skills/runtime.md` (≤ 60 lines) gives one example per module and is loaded by `skill.read name=runtime`. The same file is published on the project website. Every module file starts with a header comment listing its exports, so a model reading the source sees the API immediately.

## 6. Theme system

One palette themes every app at once: a theme is a small data file, the host turns it into `/_deft/v1/theme.css`, and apps may only reference its tokens, so a theme switch retints the whole system live with no per-app work. This is Omarchy's `colors.toml` model; the web makes it simpler because there is exactly one consumer format, CSS custom properties.

### 6.1 Rules

- Apps MUST NOT hard-code colors, font families or radii outside `:root`-scoped custom properties of their own; they reference `var(--deft-*)` tokens (lint FL610–FL615).
- Apps MAY define private tokens derived from FL tokens (`--note-tag: color-mix(in oklch, var(--deft-color-primary) 30%, var(--deft-color-base-100))`).
- Themes are data only. A theme package containing any `.css`, `.js`, `.html` or `.svg` file is rejected (SVG can script). This mirrors Omarchy stripping executable files from git-installed themes.
- The active theme applies to the launcher UI, all apps and all canvases. A per-app override ("always light") is a launcher setting, never an app capability.
- Color naming follows daisyUI's paired model (every surface color has a `-content` color for text on it), because it is battle-tested and familiar to developers and models. FL keeps its own `deft-` prefix and needs no Tailwind or build step.

### 6.2 Theme package

```
my-theme/
  palette.json         required
  wallpapers/*.webp    optional, ≤ 4 files, ≤ 2 MB each
  preview.webp         optional, shown in theme picker
  LICENSE              required to publish
  manifest.json        { "deft": 1, "kind": "theme", "id", "name", "version", "license", "authors", "source" }
```

`palette.json`:

```json
{
  "modes": ["light", "dark"],
  "light": {
    "base-100": "#fbf8f3", "base-200": "#f1ece4", "base-300": "#e6dfd4", "base-content": "#1f1b16",
    "primary": "#8a4b2a", "primary-content": "#ffffff",
    "secondary": "#3f6b5c", "secondary-content": "#ffffff",
    "accent": "#b5832b", "accent-content": "#1f1b16",
    "neutral": "#3a332b", "neutral-content": "#f1ece4",
    "info": "#2a5d8a", "info-content": "#ffffff",
    "success": "#2e7d32", "success-content": "#ffffff",
    "warning": "#a15c00", "warning-content": "#ffffff",
    "error": "#b3261e", "error-content": "#ffffff"
  },
  "dark": { "...": "same 20 keys" },
  "font": { "ui": "system", "mono": "system-mono" },
  "shape": { "radius-box": "m", "radius-field": "s", "radius-selector": "full", "border": 1, "density": "regular" }
}
```

- Keys: `base-100/200/300` (surfaces, lightest to darkest step), `base-content` (text on surfaces), and seven roles (`primary`, `secondary`, `accent`, `neutral`, `info`, `success`, `warning`, `error`), each with its `-content` pair. Derived tokens (border, focus ring, selection, muted text) are computed in `base.css` with `color-mix()`, so themes stay small.
- Colors: `#rrggbb` or `oklch(L C H)` only. `modes` is `["light"]`, `["dark"]` or both.
- Fonts: names from a host-bundled allow-list (`system`, `system-mono`, plus a few bundled OFL fonts). No font files in themes in v1, so themes cannot smuggle font exploits or bloat.
- `shape`: radii for boxes (cards, dialogs), fields (inputs, buttons) and selectors (toggles, checkboxes) from `none | s | m | l | full`; `border` width 0–2 px; `density` `compact | regular | comfortable`.
- Validation at install and in lint (FL72x): every key present; contrast ≥ 4.5:1 for each role and its `-content`, and for `base-content` on each `base-*` (WCAG 2.2). Failing contrast is an error for the default index and a warning for local themes.
- **Theme gallery:** daisyUI's built-in themes are MIT-licensed; convert them to `palette.json` with attribution in each theme's manifest, so FL ships with a broad gallery on day one.

### 6.3 Generated `theme.css`

The host serves `/_deft/v1/theme.css` dynamically (`Cache-Control: no-store`). Shape:

```css
@layer deft.theme {
  :root, [data-theme] {
    color-scheme: light dark;            /* or 'light' / 'dark' per modes + user setting */
    --deft-color-base-100: light-dark(#fbf8f3, #16130f);
    --deft-color-primary: light-dark(#8a4b2a, #e0a07a);
    --deft-color-primary-content: light-dark(#ffffff, #241208);
    /* ...one line per palette key... */
    --deft-font-ui: system-ui, sans-serif;
    --deft-font-mono: ui-monospace, monospace;
    --deft-radius-box: 0.5rem; --deft-radius-field: 0.25rem; --deft-radius-selector: 999px;
    --deft-border: 1px;
    --deft-density: 1;                     /* 0.85 compact, 1.15 comfortable */
  }
  [data-theme="light"] { color-scheme: light; }
  [data-theme="dark"]  { color-scheme: dark; }
}
```

- **Dynamic theme:** a built-in theme `system` maps Android 12+ dynamic colors (Material You) into the palette keys, so FL apps follow the wallpaper the way native apps do.
- **Font scale:** the host sets the WebView text zoom from the Android font-scale setting; tokens use `rem`, so apps scale correctly.
- **Live switch:** on theme change the host calls `evaluateJavascript` on every live app WebView with a fixed snippet from `HOOK_JS` that re-fetches the `theme.css` link (cache-busting query). This works for apps that do not use the runtime. It dispatches `deft:theme` on `window` so apps with canvas drawing can repaint.
- **Scoped themes (`data-theme`):** `data-theme="light"` or `"dark"` on any element forces that mode of the active theme for its subtree (e.g. an always-dark media viewer). The launcher can also load `/_deft/v1/themes/<id>.css`, which emits `[data-theme="<id>"]` blocks, for theme previews and the theme picker; apps link only `theme.css`.

### 6.4 `base.css` and layout primitives

`base.css` is organized in cascade layers so app CSS (unlayered) always wins without `!important` or specificity tricks:

```css
@layer deft.reset, deft.theme, deft.base, deft.layout, deft.components, deft.utilities;
```

| Layer | Contents |
| --- | --- |
| `deft.reset` | Modern reset; `box-sizing`; media elements block; `text-size-adjust`; `prefers-reduced-motion` disables animations and collapses `::view-transition-*`. |
| `deft.base` | Element defaults from tokens: body `base-100`/`base-content`, headings type scale, links, `:focus-visible` ring (derived from `primary`), form controls, tables, `::selection`. Tap targets ≥ 48 px for buttons, inputs, links in nav. |
| `deft.layout` | Intrinsic primitives (Every Layout style): `.deft-stack`, `.deft-cluster`, `.deft-grid` (auto-fit minmax), `.deft-sidebar`, `.deft-switcher`, `.deft-center`, `.deft-cover`, `.deft-scroll` (overflow container with scroll snap opt-in). All configured by custom properties (`--deft-gap`, `--deft-min`). |
| `deft.components` | Core classes following the grammar in 6.5: `deft-btn`, `deft-card`, `deft-badge`, `deft-input`, `deft-select`, `deft-textarea`, `deft-toggle`, `deft-checkbox`, `deft-tabs`/`deft-tab`, `deft-menu`, `deft-alert`, `deft-list`, `deft-appbar` and `deft-tabbar` (safe-area aware), `deft-empty`, plus `dialog` and `[popover]` styling. CSS-only where the platform allows (`<dialog>`, `popover`, `<details>`, `:has()`). |
| `deft.utilities` | `.deft-safe-top/-bottom/-inline`, `.deft-visually-hidden`, `.deft-truncate`. |

Token scales (all in `base.css`, independent of the theme): fluid space `--deft-space-3xs` … `--deft-space-2xl` and fluid type `--deft-text-xs` … `--deft-text-2xl` generated with Utopia-style `clamp()` between 320 px and 840 px viewports and multiplied by `--deft-density`; `--deft-tap: 48px`; `--deft-duration-xs` 100ms, `--deft-duration-s` 120ms, `--deft-duration-m` 220ms, `--deft-duration-l` 320ms; `--deft-ease` `cubic-bezier(0.2, 0, 0, 1)`, `--deft-ease-enter` `cubic-bezier(0.05, 0.7, 0.1, 1)`, `--deft-ease-exit` `cubic-bezier(0.3, 0, 0.8, 0.15)`.

Motion. `base.css` animates button press (`transform: scale(0.97)`, not on `.deft-row`), row and list highlight, `dialog.deft-dialog` (fade and scale), `dialog.deft-sheet` and non-dialog `.deft-sheet` panels (rise), toasts and popovers, and the same-document view transition `routes()` starts. `.deft-morph { view-transition-name: deft-morph }` is the only shared name: one source and its matching hero. The group moves and resizes over `--deft-duration-l` / `--deft-ease-enter` while the contents crossfade. Apps MUST NOT set any other `view-transition-name`. Custom motion MUST transition only `transform` and `opacity` and MUST use the `--deft-duration-*` and `--deft-ease*` tokens. `prefers-reduced-motion: reduce` disables animations and transitions, including `::view-transition-*`.

Result: an app that uses semantic HTML plus two or three primitives looks native to FL, is responsive from 320 px phones to foldables/tablets, supports light/dark and follows the user's theme without writing a media query or a color.

### 6.5 Class grammar

Every component class (core in `base.css` and shared components in section 10) uses one predictable grammar, modelled on daisyUI so people and agents can guess class names:

| Part | Pattern | Values |
| --- | --- | --- |
| Component | `deft-<name>` | `deft-btn`, `deft-card`, `deft-badge`, … |
| Color | `deft-<name>-<role>` | `primary`, `secondary`, `accent`, `neutral`, `info`, `success`, `warning`, `error` |
| Size | `deft-<name>-<size>` | `xs`, `sm`, `md` (default), `lg`, `xl` |
| Style | `deft-<name>-<style>` | `outline`, `soft`, `ghost`, `dash`, `link` |
| Part | `deft-<name>-<part>` | e.g. `deft-card-body`, `deft-card-actions` |
| State | native attributes first | `disabled`, `aria-selected`, `aria-current`, `:checked`, `[open]` instead of state classes |

Example: `<button class="deft-btn deft-btn-primary deft-btn-sm deft-btn-outline">`. Utility-class markup is not part of FL; layout comes from the primitives in 6.4. Lint FL302 recognizes the grammar, so unknown modifiers (`deft-btn-blue`) are reported.

## 7. deft-lint

deft-lint is the single deterministic validator for apps, canvases and themes; the same bundle runs in the on-device WebView, in contributor CI under Node, and in a static web page, so "passes for me" means "installs for everyone". Error-level findings block verify, install and publish; warnings are shown and counted; infos appear only in full verify.

### 7.1 Package and build

- Source: `tools/deft-lint/` (Node, esbuild, vitest). Output: one committed file `android/app/src/main/assets/lint/deft-lint.js` (IIFE exposing `globalThis.deftLint`), same pattern as the committed `deft-bridge.js`. CI fails if the committed bundle differs from a fresh build (reproducibility check).
- Dependencies, pinned and vendored: `acorn`, `acorn-walk`, `eslint-scope` (scope analysis for undeclared identifiers and floating promises), `htmlparser2`, `postcss-value-parser` + a small hand-written CSS tokenizer (no full CSS engine), `@noble/ed25519` + `@noble/hashes` (shared with the packer for signature checks), compat data generated from `web-features`/BCD at build time.
- Budget: bundle ≤ 500 KB; incremental lint of one changed file in a 20-file app ≤ 150 ms on a mid-range phone; full project ≤ 600 ms.
- Deterministic: no clock, no randomness, no network; output sorted by path, line, col, id.

### 7.2 API

```js
deftLint.run({
  files: { 'main.js': '...', 'index.html': '...', 'manifest.json': '...' },  // text files only
  profile: 'myapp' | 'canvas' | 'theme',
  mode: 'local' | 'publish',      // publish adds FL707, FL112 and stricter theme contrast
  changed: 'main.js',             // optional: limit per-file rules, keep cross-file rules touching it
  catalog,                        // bridge-catalog.json
  minWebView: 124
}) → Diagnostic[]

// Diagnostic
{ id: 'FL100', severity: 'error'|'warn'|'info', path, line, col, endLine, endCol,
  message: 'innerHTML assigns raw HTML', fix: 'use html`...` + render() from /_deft/v1/dom.js',
  docs: 'https://<project-site>/lint/FL100' }
```

Rule ids are stable forever; a retired rule's id is never reused. Each rule lives in `tools/deft-lint/src/rules/<id>.js` with `bad/` and `good/` fixtures; `rules/index.js` is the catalog. The rule set is selected by manifest `fl` (v1 apps keep v1 rules).

### 7.3 Rule catalog

E = error, W = warn, I = info. "Locked" = cannot be suppressed by `deft-allow`.

**FL0xx syntax and modules**

| Id | Sev | Finding | Fix shown |
| --- | --- | --- | --- |
| FL000 | E | Parse error | Plain JS modules only; no TypeScript/JSX |
| FL001 | E | Remote import not in `dependencies`, or bare specifier without an `as` alias | `./file.js`, `/_deft/v1/<module>.js`, or declare the URL in `dependencies` (10.10) |
| FL002 | E | Relative import without `.js` | Add `.js` |
| FL003 | E | Import target missing | Create the file or fix the path |
| FL004 | E | Imported name not exported by target | List of the target's exports |
| FL005 | E | Unknown `/_deft/v1/` module or export | List of runtime exports |
| FL006 | E | Assignment to undeclared identifier | Declare with `let`/`const` |
| FL007 | E | `import()` with non-literal specifier | Static import |
| FL008 | W | Reference to unknown global | Typo? Known globals list |

**FL1xx security** (all E, all locked)

| Id | Finding | Fix shown |
| --- | --- | --- |
| FL100 | `innerHTML`, `outerHTML`, `insertAdjacentHTML`, `document.write(ln)`, `DOMParser.parseFromString`, `createContextualFragment`, `srcdoc` | `html` + `render()` |
| FL101 | `eval`, `new Function`, `Function(...)`, string `setTimeout`/`setInterval`, `.constructor.constructor` | Use functions |
| FL102 | `on*=` attributes in HTML or templates | `@click=${fn}` or `on()` |
| FL103 | Inline `<script>` or `<style>` | `./main.js`, `./app.css` |
| FL104 | Remote URL in `src`/`href`/CSS `url()`/`@import`/font | Bundle the asset in the app |
| FL105 | `alert`/`confirm`/`prompt` | `dialog()` |
| FL106 | Static `style="..."` attribute | Class, or `style=${{...}}` binding |
| FL107 | `javascript:` URL | Event handler |
| FL108 | `iframe`, `object`, `embed`, `frame`, `base`, `meta http-equiv` | Not supported |
| FL109 | Service worker registration, `SharedWorker` | Not supported; app is already offline |
| FL110 | `window.open` | Plain `<a href>`; host opens browser |
| FL111 | Obfuscation: computed access on `window`/`globalThis`/`document`/`self` with non-literal key; `String.fromCharCode` or `atob` results reaching calls; escaped identifiers | Write the property name literally |
| FL112 | Minified or generated code (any line > 400 chars, or file entropy heuristic) in `publish` mode | Ship readable source |
| FL113 | Access to host internals (`__deft*`, `deftBridge` private members) | Runtime APIs |
| FL114 | SVG containing `<script>`, `foreignObject`, `on*`, external `href` | Plain vector SVG |

**FL2xx bridge and network**

| Id | Sev | Finding | Fix shown |
| --- | --- | --- | --- |
| FL200 | W | Direct `window.deftBridge` | `call()` from `bridge.js` |
| FL201 | E locked | Unknown bridge method or event | Catalog list |
| FL202 | E locked | Capability used but not declared | Add to `manifest.capabilities` with `reason` |
| FL203 | W | Capability declared but unused | Remove it |
| FL204 | E locked | Bridge import or network API in `canvas` | Canvas is offline and bridge-free |
| FL205 | E locked | Network URL literal to undeclared origin (skipped with `network.open`) | Add origin to `manifest.network` |
| FL206 | I | Network URL not statically known | CSP will enforce `manifest.network` |
| FL207 | E locked | `call()` with non-literal method name | Literal method names |

**FL3xx cross-file**

| Id | Sev | Finding |
| --- | --- | --- |
| FL300 | W | `getElementById`/`querySelector('#x')` id not in HTML or templates |
| FL301 | E | Duplicate static ids |
| FL302 | I | `classList` literal class not defined in app CSS or `base.css` |
| FL303 | W | File unreachable from `index.html` |

**FL4xx async, lifecycle, correctness** (W)

| Id | Finding | Fix shown |
| --- | --- | --- |
| FL400 | `async` callback in `forEach` | `for...of` + `await` |
| FL401 | Floating promise from `call()`, `dialog()`, `fetch` | `await` or `.catch()` |
| FL402 | `.map()` of templates without key | `repeat(items, key, tpl)` |
| FL403 | `JSON.parse` outside try; raw `localStorage` | `store()` |
| FL404 | `setInterval`/`requestAnimationFrame` without clear | `interval()`/`frame()` |
| FL405 | `addEventListener` on `window`/`document` never removed | `on()` + `scope()` |
| FL406 | `effect()` created inside a template function | Create effects once per screen |

**FL5xx structure** (W)

FL500 `var`; FL501 `==`/`!=`; FL502 file > 200 lines or `main.js` > 80 lines; FL503 missing header comment (one line describing the file); FL504 (I) leftover `console.log`.

**FL6xx HTML, CSS, theme, accessibility**

| Id | Sev | Finding | Fix shown |
| --- | --- | --- | --- |
| FL600 | E | Viewport meta missing or without `viewport-fit=cover` | Canonical meta |
| FL601 | E | `user-scalable=no` or `maximum-scale` | Remove; zoom is an accessibility need |
| FL602 | E | Missing `<script type="module" src="./main.js">` | Canonical head |
| FL603 | E | `base.css`/`theme.css` missing or after app CSS | Canonical head |
| FL604 | E | `@import`, remote `url()`, remote `@font-face` | Local files |
| FL605 | W | `position: fixed/sticky` without `env(safe-area-inset-*)` | `.deft-appbar`, `.deft-tabbar`, `.deft-safe-*` |
| FL606 | W | `!important` | Layers make it unnecessary |
| FL607 | W | `100vh` | `100dvh` |
| FL608 | W | `100vw` | `100%` |
| FL609 | W | Fixed `width`/`min-width` > 480px | `max-width`, `.deft-center`, `.deft-grid` |
| FL610 | W | Color literal outside a custom property definition | `var(--deft-color-*)` |
| FL611 | W | `font-family` literal | `var(--deft-font-ui)` |
| FL612 | I | `px` font size | `var(--deft-text-*)` or `rem` |
| FL613 | W | `border-radius` literal | `var(--deft-radius-*)` |
| FL614 | W | App redefines a `--deft-*` token | Define a private token |
| FL615 | W | `outline: none` without `:focus-visible` style | Keep the focus ring |
| FL620 | E | `<img>` without `alt` | `alt=""` if decorative |
| FL621 | E | `<button>` without accessible name | Text or `aria-label` |
| FL622 | E | Form control without label | `<label>` or `aria-label` |
| FL623 | W | Click handler on `div`/`span` | `<button>` |
| FL624 | W | `<html>` without `lang` | `lang="en"` |
| FL625 | W | Skipped heading level | Next level |
| FL626 | W | Positive `tabindex` | `0` or DOM order |

**FL7xx manifest, package, theme**

FL700 E disallowed file type or path; FL701 E manifest invalid JSON/schema; FL702 E unknown capability; FL703 E missing required field; FL704 E invalid `network` entry; FL705 W unknown field; FL706 E canvas declares capabilities or network; FL707 E (publish) missing `LICENSE`, `license` or `source`; FL708 E capability `reason` missing or out of length; FL710 E invalid `dependencies` entry (`https:` URL without query, SRI hash, `as` name; hash required in publish mode); FL711 W dependency URL has no version; FL709 E size limits exceeded; FL720 E palette schema; FL721 E/W palette contrast (6.2); FL722 E forbidden file in theme. All FL7xx are locked.

**FL8xx platform**

FL800 E feature (CSS property/value/at-rule/selector, JS API, HTML element/attribute) not supported at `MIN_WEBVIEW_MAJOR`, with the version that adds it. FL801 W feature not in any stable Chromium (flag or origin trial only).

**FL9xx components and recipes** (section 10)

| Id | Sev | Finding | Fix shown |
| --- | --- | --- | --- |
| FL900 | E locked | Code under `components/<id>/` uses a capability or origin its `component.json` does not declare | Declare it in the component, or move the call to app code |
| FL901 | E | App manifest lacks a capability or origin a bundled component declares | Add it to `manifest.json` with a reason |
| FL902 | I | Provenance hash differs: modified copy | None; shown as "modified" |
| FL903 | I | Newer component version available | `components.update` |
| FL904 | W / E (publish) | Bundled component is revoked | Remove or replace it |
| FL905 | E | Component imports another component | Components stay flat; copy what you need |
| FL906 | I | Provenance header removed | Files are now plain app code |
| FL907 | E | Recipe structure invalid, over 150 lines, or capability/network instructions outside frontmatter | Follow 10.3 |
| FL908 | E | Standalone recipe declares capabilities or network | Recipes are text only |

### 7.4 Suppression and false positives

- `// deft-allow FL4xx: reason` (JS) and `/* deft-allow FL6xx: reason */` (CSS) on the previous line suppress one non-locked warning. A suppression without a reason is itself FL009 (W).
- `samples/` holds every scaffold and reference app; CI snapshots lint output over all of them. Any new finding on a reference app fails CI, which keeps false positives from shipping.
- The rule docs page for each id shows bad/good fixtures, generated from the same files the tests use.

### 7.5 Linting without a toolchain

Contributors without Node use a static page (`lint.<project-site>`) that loads the same `deft-lint.js` and lints a dropped folder entirely in the browser; nothing is uploaded. The launcher's app detail screen has a "Lint report" action for any installed app.

### 7.6 Bridge catalog (shared data)

`android/app/src/main/assets/agent/bridge-catalog.json` is the single machine-readable list of capabilities: `{ "minWebView": 124, "capabilities": { "<id>": { "kind": "read|write|sensor|system", "risk": "low|medium|high", "methods": [...], "events": [...], "summary": "..." } } }`. It is consumed by the linter (FL2xx), the consent UI (risk badges, summaries), the agent skills and the website docs. A JVM test asserts catalog ids == `Capability.entries` ids, every method appears in `js-bridge.md`, and every `Capability` has a `risk`. Drift fails CI.

### 7.7 On-device engine

- `webview/DeftLintEngine.kt`: one warm, hidden WebView (pattern from `AppVerifier.kt`), loading `assets/lint/index.html` + `deft-lint.js` through `WebViewAssetLoader`. Network blocked (`blockNetworkLoads = true`), no bridge injected, JS only.
- Calls are `Mutex`-guarded; payload passed as JSON via `evaluateJavascript`; 5 s timeout returns a synthetic `FL999 lint timeout` warning rather than hanging the harness.
- Idle-destroy after 60 s; re-created on demand (cold start budget ≤ 400 ms).
- Reads only text files (`.js .html .css .json .svg .md .txt`) within `MyAppsStore.MAX_BYTES`; binary assets are listed by path only, for FL003/FL303 checks.
- `webview/DeftLintFormat.kt` renders diagnostics for the model as compact lines: `main.js:12:5 FL100 error innerHTML assigns raw HTML -> use html\`...\` + render()` . Cap at 12 lines +  `N more (run apps.verify for all)` . Unit-tested like  `SafeAreaLayoutTest.kt\`.
- The same engine is used by the installer (11.4) and the app detail "Lint report".

### 7.8 Agent harness wiring (`AgentTools.kt`)

- `files.write`, `files.edit`, `canvas.write`, `canvas.edit`: perform the write, then lint with `changed=path` (profile `canvas` when `isHome`). Return `wrote <path>` + diagnostics for that file + cross-file findings touching it. The write is never rolled back by lint; the model fixes forward.
- `apps.verify`, `canvas.verify`: full-project lint first; if any error, report `FAIL` with all errors (uncapped up to 50) and still run the existing runtime verify (console errors, CSP violations, layout checks) so the model sees everything in one round.
- Writes under `_deft/` are rejected by `MyAppsStore` and `HomeCanvasStore` with a message naming the runtime.
- When the agent edits an installed app, writes go to the overlay (9.2), never the base.
- `AgentLog.kt` per session: lint errors per round, warnings per round, verify fails, rounds to first green verify, suppressions added, total tokens. Local only; exported only if the user shares a log.

### 7.9 Prompt and scaffold

- Rewrite the `my-app` and `canvas` sections of `SKILL.md`: remove prose now enforced by lint (inline scripts, `.js` imports, safe-area detail); add a 10-line "Use `/_deft/v1/`, use `--deft-*` tokens and layout primitives, fix FL diagnostics before stopping" summary.
- New `assets/agent/skills/runtime.md` (5.4) and `assets/agent/skills/theme.md` (token list, primitives, one example screen).
- `js-bridge.md`: show `call()` first, raw `deftBridge` as legacy fallback.
- `MyAppsStore.create` scaffold and `HomeCanvasDefaults.kt` produce starters that pass lint with zero warnings and use the runtime, `base.css`, `theme.css` and one primitive. A CI test lints every scaffold.

## 8. Security model

The security boundary is the per-app origin plus CSP plus the host-checked bridge; lint and curation reduce risk but are never relied on. A hostile app that passes lint, is signed, and is installed MUST still be unable to read another app's data, run injected script, reach undeclared network origins, or use a capability the user has not granted.

### 8.1 Threat model

| Threat | Primary mitigation | Secondary |
| --- | --- | --- |
| Malicious app author | Capability grants checked host-side; CSP; origin isolation | Lint FL1xx/FL2xx, readable-source rule (FL112), curation, revocation |
| App exfiltrates data | `connect-src` = declared origins only; per-app network log | Consent screen lists origins; offline badge for apps with none |
| XSS inside an app (bad data rendered) | `script-src 'self'`, no inline script, so injected markup cannot execute | Trusted Types; auto-escaping `html` |
| One app attacks another | Separate origin per app; bridge bound to its origin; no shared storage | Separate WebView profile where supported |
| Typosquatting / impersonation | Identity = author key + id; name never shown without author; similar-name warning | Index policy, verified-author badges via forge proof |
| Stolen or malicious update | Same-key rule for updates; capability escalation requires consent | Revocation lists; key rotation statements |
| Compromised source / index host | Index signed by source key; package hashes + author signature checked independently | Multiple sources; client pins source key |
| Rogue default-index maintainers | Index is a transparency log in a public git repo; anyone can mirror or fork | Users can remove the default source entirely |
| Network attacker | HTTPS only, plus signatures on everything installed | No HTTP fallback, no TOFU on transport |
| Malicious theme | Data-only format; strict schema | No CSS/JS/SVG/fonts in themes |
| Prompt injection into the agent | Installs, grants, sources and keys require a host UI tap, never agent-only | Agent sees untrusted content labelled as such |
| Compromised FL release | Reproducible builds; committed runtime/linter bundles checked in CI | Signed releases, SBOM, external audit before 1.0 |
| Malicious or expanding component | Copy-in (no runtime fetch); component-scoped capability lint FL900; app author must accept new capabilities | Provenance hashes, component revocation flags every bundling app |
| Prompt injection via recipe text | Recipes cannot authorize; generated code still passes lint and consent | Recipe lint FL907/FL908, curation |

### 8.2 Content Security Policy

`LocalOriginPathHandler` adds this header to **every** response from an app origin (HTML, SVG and all others, so a directly opened SVG cannot script). `CspBuilder` (`webview/CspBuilder.kt`) builds it per app from the manifest and the user's grants, so it changes only when a grant changes (the page reloads). Without any grant, the header is:

```
default-src 'none';
script-src 'self';
style-src 'self';
img-src 'self' data: blob: <consented origins>;
font-src 'self';
media-src 'self' blob: mediastream: <consented origins>;
connect-src 'self' <consented origins>;
worker-src 'self';
manifest-src 'none'; object-src 'none'; frame-src 'none'; child-src 'none';
frame-ancestors 'none'; base-uri 'none'; form-action 'none';
require-trusted-types-for 'script';
trusted-types deft;
```

- **Consented origins** are the https origins of the manifest `network` list that the user approved. They go into `connect-src`, `img-src` and `media-src`. The Home canvas (no network) gets `connect-src 'none'` and `worker-src 'none'`, and no origins.
- **`network.open`** replaces the origin lists with `https:` in `img-src` and `media-src`, and `'self' https: wss:` in `connect-src`.
- **`wasm`** (PARITY G2) adds `'wasm-unsafe-eval'` to `script-src`, and nothing else. The deft-lint rule accepts `.wasm` files only in the package or as hash-pinned dependencies.
- **`web.embed`** (PARITY G14) adds the approved embed origins (https, `*.` only as the leftmost label and never over a public suffix such as `*.github.io` or `*.co.uk`) to `script-src` and `frame-src`, and to `connect-src` and `img-src` unless `network.open` is granted. Trusted Types also allows a `default` policy, which `EmbedScriptPolicy` defines to accept script URLs from approved origins only.
- No `'unsafe-inline'` and no `'unsafe-eval'` in any case. `'wasm-unsafe-eval'` appears only with the `wasm` grant.
- `style-src` without `'unsafe-inline'` works because the runtime applies dynamic styles through CSSOM (5.2), which CSP does not block.
- Trusted Types: the host's document-start script (`HOOK_JS` via `addDocumentStartJavaScript`) creates the single `fl` policy before any page script runs and passes it privately to `dom.js` through a one-shot handshake. Because policy names cannot be reused, an app cannot create its own permissive policy. This is defence in depth: even a bypass yields markup only, since script execution is already blocked by `script-src`.
- Apps with `dependencies` get one inline import map, inserted by the host into served HTML. Its `sha256-` hash is added to that response's `script-src`. No other inline script is allowed, and page code cannot change the map.
- `HOOK_JS` installs a `securitypolicyviolation` listener that reports violations to `apps.verify` / `apps.debug` as `kind=csp`, and to the per-app log.

### 8.3 Origin isolation and WebView hardening

- Every app gets its own HTTPS origin, intercepted by the host and never resolved on the network: `https://<origin-id>.app.deft.internal`. `origin-id` = first 20 base32 chars of `SHA-256(author_key || "/" || id)` for installed apps, a random id for local apps. Forks get a new origin (data is not shared; the fork flow offers a one-time data copy).
- Overlays keep the base's origin (same app, same data). The launcher UI and canvases each get their own origins.
- WebView settings for app views: `allowFileAccess=false`, `allowContentAccess=false`, `setSupportMultipleWindows(false)`, `javaScriptCanOpenWindowsAutomatically=false`, `mixedContentMode=NEVER_ALLOW`, `setGeolocationEnabled` only when the location capability is granted, Safe Browsing disabled for app origins (local content; avoids sending URLs to a third party).
- `shouldOverrideUrlLoading` keeps the view on its origin. An `https:` link elsewhere opens a confirmation sheet showing the full URL, then a Custom Tab or the default browser. All other schemes are dropped, except `tel:`/`mailto:`/`sms:`, which go through an intent chooser.
- Use the androidx.webkit multi-profile API (`ProfileStore`) to give each app a separate cookie/storage/cache profile where the installed WebView supports it; fall back to origin separation otherwise.
- Downloads are blocked unless the app holds the `files.write` capability, and then go through the system file picker.

### 8.4 Bridge and capabilities

- The bridge is exposed with `WebViewCompat.addWebMessageListener` restricted by `allowedOriginRules` to the app's exact origin. `addJavascriptInterface` MUST NOT be used for app views, because it is visible to every frame and origin.
- **Declared ≠ granted.** The manifest declares; the user grants. The host checks every call against the grant set, never against the manifest alone.
- Risk levels from the catalog drive consent:
  - `low` (e.g. `share.receive`, `haptics`): granted at install with the consent screen.
  - `medium` (e.g. `notifications.post`, `alarms`, `clipboard.read`): granted at install, revocable.
  - `high` (contacts, SMS, call log, location, camera, microphone, files, BLE): shown on the consent screen with the manifest `reason`, checked by default. A ticked box plus Allow is the grant; there is no second, first-use prompt. Unticked capabilities are stored as denied and can be allowed later in the dashboard.
- Actions that spend money or speak as the user (`sms.send`, `sms.sendMms`, `telephony.call`) need a real tap. If the call arrives within 5 seconds of a native tap on that app's WebView (seen by the host; page code cannot fake it), it goes straight out. Each tap covers one action. Otherwise (timers, radio events, wakeups, a burst after one tap) the host shows a confirmation naming the app, with the recipient and full content. Apps cannot suppress or style it. When the user turns on biometric confirmation for an app, every such action is confirmed.
- Arguments are validated against the catalog's JSON schema per method, with size limits. Unknown methods are rejected even if a capability is granted.
- Every bridge call is written to a local per-app audit log (time, capability, method, count). The app settings page shows it in plain words: "Read contacts 3 times today". The log is never sent anywhere.
- Revoking a capability takes effect immediately for live views (the next call fails with `{code: 'denied'}`).

### 8.5 Network

- Only manifest origins, HTTPS only, enforced by CSP. The host keeps a per-app network log (origin, request count, bytes) and offers a per-app "Block network" switch.
- Apps with an empty `network` list carry an "Offline" badge in listings and the consent screen; this is the default and should be the common case.

### 8.6 Agent safety

- Content the agent reads from apps, web pages, notifications or messages is untrusted data. The harness labels it, and the system prompt states it cannot authorize actions.
- These actions are never performed by the agent alone: install, update with new capabilities, grant or revoke a capability, add or remove a source, trust a key, publish or sign. The agent can prepare them; the user confirms in a host-rendered sheet outside the chat.
- Agent-created apps are local-tier and get no capabilities until the user grants them through the normal consent flow.
- The agent can read other apps' source (it is public) but not their data directories, unless the user grants per-app data access for that session.

### 8.7 Privacy commitments (normative)

- No telemetry, analytics, crash upload or account in the core. Diagnostics stay on device unless the user explicitly shares them.
- Index and package fetches carry no user identifier, no install list and no cookies. Update checks run at randomized times, fetch whole indexes rather than per-app queries, and go through the system proxy (Orbot works).
- Listings show popularity only from public sources (forge stars) or opt-in signals; the project never collects install counts.

## 9. Personalization: overlays, forks, history

Every installed app is editable by its user or their agent, edits survive upstream updates, and every change can be undone. This is the Omarchy promise (user files override shipped defaults) applied to apps.

### 9.1 Overlay model

- Installed app = read-only `base/<version>/` + sparse `overlay/` + `overlay.json` (stored beside, not inside, the served tree).
- `LocalOriginPathHandler` resolves each request: overlay file → base file → 404. Paths listed in `overlay.json.deleted` return 404 even if the base has them.
- `overlay.json`: `{ "base_version": "1.4.0", "files": { "<path>": { "base_sha256": "<hash of the base file when first overlaid, or null if new>" } }, "deleted": ["<path>"] }`.
- Lint and verify always run on the merged view.
- Overlays may change any file, including `manifest.json`, with three exceptions: `id` and author cannot change; additions to `capabilities` or `network` are treated as a capability request and go through consent; `fl` cannot change.
- UI: an app with an overlay shows a "Modified by you" badge and a diff view per file. "Reset to original" works per file and for the whole app.
- The base signature stays verified; the overlay is local and trusted only by this device.

### 9.2 Updates with overlays

For every overlaid path on update:

1. Upstream unchanged (new base hash == recorded `base_sha256`): keep the overlay, no prompt.
2. Upstream changed: mark a conflict. The app keeps running with the user's version. The update screen offers, per file, *Keep mine*, *Take upstream*, or *Merge with agent*: the agent receives old base, new base and overlay, and writes a merged overlay that is linted before it replaces the old one.
3. A path the user deleted but upstream changed: ask once.

After merging, `base_sha256` values are updated to the new base. If the merged view has lint errors, the update still installs, the app is marked "Needs attention", and one tap reverts the overlay.

### 9.3 Forks

"Fork" copies the merged view into a new local app: new origin, `manifest.forked_from = {author, id, version}`, author = the user's key once they publish. A one-time data copy is offered. Forks have no upstream; "Compare with original" shows a diff against the latest installed version of the source.

### 9.4 Local history and undo

- Every write to a local app, canvas or overlay (by user editor or agent) is recorded in a content-addressed history: blobs by SHA-256 plus a log `{time, actor: "user"|"agent", session_id, paths}`.
- "Undo agent session" reverts every change made in one agent session in one step. This is the main safety net that makes letting an agent edit apps acceptable.
- Retention: last 200 entries or 50 MB per app, oldest pruned first; never prunes the entry matching the current published version.
- Export: zip of the app plus `history.json`. Git-bundle export MAY come later.

### 9.5 Personal themes and canvases

The agent can create local themes (`palette.json` only), validated by FL72x, from prompts such as "warm, low contrast, like old paper". Canvases follow the same overlay, fork and history rules as apps.

## 10. Components and recipes

Shared building blocks are distributed as source that apps copy in and own, in the style of shadcn/ui, not as dependencies resolved at install or run time. There is no resolver, no dependency tree and no runtime fetch. A copied component is part of the app: linted with it, signed with it, editable like any other file.

### 10.1 Kinds

| Kind | Contains | May declare capabilities/network | Copied into apps |
| --- | --- | --- | --- |
| Component | Reference code + `RECIPE.md` + example + acceptance checks | Yes, in `component.json` | Yes, under `components/<id>/` |
| Recipe | Text only: a pattern such as an onboarding flow, settings screen or list + detail | **No** | No; the agent reads it and writes app code |
| Theme | Palette data (section 6) | No | No |

Components MUST NOT depend on other components (v1 rule, kept flat on purpose). A component that needs a calendar ships its own; shared logic goes into the runtime instead, via RFC.

### 10.2 Component folder

```
calendar/
  component.json     { "deft": 1, "kind": "component", "id": "calendar", "name": "Calendar", "version": "1.3.0",
                       "license": "MIT", "authors": [...], "source": "https://...",
                       "capabilities": [], "network": [],
                       "exports": ["calendar"], "tokens": ["--deft-color-primary", "--deft-color-base-200"] }
  calendar.js        exports calendar({ value, onSelect, weekStart, ... }) → html template
  calendar.css       uses only --deft-* tokens and the deft- class grammar (6.5)
  RECIPE.md          10.3
  example.html       a page rendering every state; used by checks and the docs gallery
  checks/*.json      acceptance checks (10.6)
  LICENSE
```

- Format: a plain function returning an `html` template, taking values or signals and callbacks, plus one CSS file. Light-DOM custom elements are allowed as an alternative when portability outside FL matters; shadow DOM is not used in v1.
- Same file rules as apps (3.1). Only `calendar.js`, `calendar.css` and other code files are copied into apps; `RECIPE.md`, `example.html` and `checks/` stay in the catalog.
- Prefer platform elements: components wrap `<dialog>`, `popover`, `<details>`, `<input type="date">` and `<select>` where those suffice, and add only what the platform lacks.

### 10.3 Recipe format

`RECIPE.md` (for components) and standalone recipes use the same structure: YAML frontmatter (`id`, `version`, `kind`, `license`, and for components `capabilities` and `network`), then these sections in order, free text inside each:

1. **Purpose:** one paragraph.
2. **API:** props, signals and events, as a code block.
3. **Behavior and states:** empty, loading, error, selection and edge cases.
4. **Accessibility:** keyboard map, roles, focus behavior.
5. **Theming:** tokens and class modifiers used; what may be customized.
6. **Adaptation notes:** what is safe to change and what must stay.
7. **Acceptance checks:** each line maps to a check in `checks/` where possible.

Limits: ≤ 150 lines. Lint (FL9xx) checks the structure, the length, and that capability or network instructions appear only in frontmatter. Recipe text is untrusted input to the agent (8.6): it can guide code, never authorize anything.

### 10.4 Copy-in and provenance

- `components.add <author>/<id>@<version>` (agent tool and launcher UI) verifies the component package like any package (11.4 steps 2–7), then copies its code files into `components/<id>/` of the target app.
- Each copied file starts with a provenance header:

```js
// deft-component calendar@1.3.0 author=fl1q9x7m2kd4ha3 sha256=<hash of the original file>
```

- The linter uses the header to: detect modified copies (hash differs, shown as "modified"), report available updates, attribute capability use to the component (10.5), and flag revoked components.
- Removing the header turns the files into ordinary app code: allowed, but the component then loses update notices and its capability scoping, and lint reports it once (FL906, info).

### 10.5 Permission model

The component author declares, the app author accepts and answers for it, the user grants to the app.

- **Declare:** `component.json` lists capabilities (with a reason) and network origins.
- **Scope:** code in `components/<id>/` may use only what that component declares, even if the app holds more (FL900, locked error). This protects app authors from component updates that quietly expand access.
- **Accept:** the app manifest MUST include every capability and origin its components declare (FL901, error, with an "add to manifest.json" fix). The app author writes the `reason` in the app's own terms.
- **Grant:** users see and grant permissions per app only, exactly as in 8.4 and 11.5. Component names never appear in consent prompts. The app's details page lists bundled components (name, version, author) for those who want to look.
- **Accountability follows the signature:** the app author signs the package that contains the component code, so a misbehaving bundled component leads to the app's revocation as well as the component's.
- App code outside `components/` may use a capability a component declared only if the app manifest declares it too (it always must, by FL901).

### 10.6 Updates and acceptance checks

- Lint reports newer versions (FL903, info). Adopting one shows a three-way diff (old upstream, new upstream, the app's possibly modified copy) and merges with the same choices as overlays (9.2): keep mine, take upstream, merge with agent.
- If the new version declares new capabilities or origins, adoption stops until the app author adds them to the manifest; the resulting app update then needs user approval (11.7).
- Acceptance checks in `checks/*.json` are declarative steps run by `AppVerifier` against `example.html` or the host app: render states, keyboard sequences with expected focus, required roles and labels, no horizontal overflow at 360 px, theme-follow check. The agent runs them after adapting a component; curated components must pass them.

### 10.7 Revocation

A revoked component (11.8) produces FL904: a warning in local mode and an error in publish mode, in every app that bundles it, plus an index notice for installed apps that contain it. For `malware` revocations, the default source also re-reviews affected apps.

### 10.8 Agent use

- Tools: `components.search(query)` over the index (name, summary, recipe Purpose), `components.read(id)` returning `RECIPE.md`, `components.add`, `components.update`.
- Flow: search before writing a common widget; read the recipe; add; adapt within the Adaptation notes; run acceptance checks; fix lint.
- The agent cannot accept new capabilities on the user's behalf: adding a capability-bearing component to an app prepares the manifest change, which the app author (the user, for local apps) confirms in a host sheet.

### 10.9 Curation

- Start with one blessed component per slot (calendar view, date-range picker, virtual list, bottom sheet, swipe actions, tabs, simple charts, carousel, search with suggestions), maintained by the core team and extracted from the reference apps.
- Curated components require: zero lint warnings, passing acceptance checks, an accessibility review against a published checklist (keyboard, screen reader, focus, contrast in all bundled themes), `example.html`, and an MIT, Apache-2.0 or other permissive free license so they can go into apps under any free license.
- Community components follow the same automated checks as community apps (11.6).

### 10.10 CDN dependencies

Apps may import libraries straight from a CDN URL, as they would in any browser, with no build step and no package manager (principle 3). The launcher keeps that code fixed.

**Rule.** Every byte of executable code is fixed by a hash that the app's signature covers. A CDN file is fetched **once**, after the user confirms, checked against its hash and served locally from then on. The page never talks to the CDN, and code is never fetched while an app runs.

- **Declare.** `manifest.dependencies` maps an exact `https:` file URL to its [SRI](https://www.w3.org/TR/SRI/) hash (`sha256-`, `sha384-` or `sha512-`): `"https://cdn.jsdelivr.net/npm/three@0.160.0/build/three.module.min.js": "sha256-…"`. The long form `{ "integrity": "…", "as": "three" }` also adds a bare-name alias. Local apps may write `null` instead of a hash; the first confirmed download pins it. Publishing requires every hash. URLs must not carry a query or fragment and should contain a version (FL711 warns otherwise). CDN hosts do not go in `network`: dependencies are not `connect-src`.
- **App code stays plain.** `import * as THREE from "https://cdn.jsdelivr.net/npm/three@0.160.0/build/three.module.min.js"` runs unchanged in a desktop browser and in the launcher. With an `as` alias, `import * as THREE from "three"` works in the launcher.
- **Store.** One content-addressed file per hash, `filesDir/deft/deps/<algo>-<hex>`, shared by every app that declares it. Only JavaScript (`.js`, `.mjs`, or no extension) and `.json` are accepted. Limits: 16 MB per file, 512 MB for the whole store.
- **Sources of bytes.** The hash is checked whichever way the bytes arrive:
  1. Inside a signed `.deftpkg` as `deps/<algo>-<hex>`, listed in the package file list like any file. Installed into the store at package install. Default for distributed apps: offline install, self-contained.
  2. Downloaded once from the declared URL for local, bundled or agent-built apps, after the user confirms the consent screen (11.5), whose Libraries section lists every missing URL next to any capabilities, so one tap covers both. When only libraries are missing (a version change, or the agent adding one mid-session) the same screen opens with just that section. Nothing is downloaded or granted if the user cancels, and a failed download changes nothing. Redirects are refused (the error shows the final URL to declare instead). For a `null` hash on a local app, the host computes `sha384` of the downloaded bytes and writes it into `manifest.json` (trust on first use, like a lockfile).
- **Serve.** `https://<host>/<path>` is served on each app's own origin at `/_deft/deps/<host>/<path>`, resolved through **that app's** manifest only. An app cannot load files it does not declare, so it cannot probe what other apps have installed. Bytes are read-only: overlays and agent edits cannot change them.
- **Import map.** The host inserts one import map into served HTML: `"https://<host>/"` → `"/_deft/deps/<host>/"` for each declared host, plus each `as` alias. Its `sha256-` hash is added to that response's `script-src` (8.2). Because served paths mirror the CDN paths, relative imports inside a library (`./chunk.js`, `../x.js`) resolve to other `/_deft/deps/` paths; each of those files must be declared too, or it fails with 404.
- **Known limits.**
  - Only `import` / `import()` specifiers go through the import map. `<script src="https://…">`, `<link href="https://…">` and `fetch()` to a CDN stay blocked by CSP. Load CDN code from a local module; copy CSS into the app.
  - Root-relative imports inside CDN files (`/npm/x@1/+esm`, `/v135/…` as emitted by esm.sh and jsDelivr `+esm`) resolve against the app origin and fail. Use files whose imports are relative, full URLs, or single-file builds (`*.module.min.js`, `dist/*.esm.js`).
  - Bare imports inside a library (`import "three"` in three's addons) need an `as` alias for that name.
- **Clean up.** Mark and sweep, not stored counters. After every package install, update, uninstall, rollback and confirmed download, and at startup, the host collects every hash referenced by installed, local and bundled apps, overlays and rollback backups, then deletes store files nobody references. An update stores the new hash before the manifest switches, so the sweep never removes a version still in use.
- **Agent.** The agent may write `dependencies` into a local manifest. The download is an install action and needs the consent screen (8.1, 11.5); the agent can never trigger or approve it.
- **Revocation.** A known-bad hash can be flagged in every app that declares it.

## 11. Distribution

Distribution is decentralized and verifiable. Authors sign packages with their own keys. Anyone can run a source, which is a signed static index. The default source is a public git repo that rebuilds every package from tagged source and proves the result matches. The device independently checks hashes, signatures and lint before asking the user for consent. No part of it requires a server the project operates, an account, or trust in a single party.

### 11.1 Design lineage

This borrows deliberately from systems the free-software community already trusts: F-Droid (build from source, anti-feature labels, multiple repos), Omarchy and Homebrew taps (install from a git URL, add third-party sources), TUF (signed metadata with expiry and rollback protection, simplified), and APK signing (same key for updates).

### 11.2 Identity and keys

- An author is an ed25519 key. Encoding: `ed25519:<base64 public key>`. Displayed fingerprint: `fl1` + first 12 chars of base32(SHA-256(pubkey)), e.g. `fl1q9x7m2kd4ha3`.
- Canonical package identity is `(author fingerprint, id)`. Two authors can both publish `notes`. The UI always shows the name together with the author (verified handle or fingerprint); the name is never the identity.
- **Verified handles (optional):** an author proves control of a forge account or domain by publishing their public key at a location tied to it: a file `deft-author.txt` in a repo they own, a DNS TXT record `_fl-author.<domain>`, or `https://<domain>/.well-known/deft-author.txt`. Sources record the proof URL; the client re-checks it on demand ("Verify now") and shows `codeberg.org/alice` instead of the fingerprint.
- **Key storage on device:** the private key is stored encrypted with an Android Keystore AES key (hardware-backed where available); signing happens in the host, never in a WebView. Export and backup are offered at creation with a clear warning.
- **Rotation:** `{"type":"rotate","old":...,"new":...,"date":...}` signed by the old key and countersigned by the new key, shipped in the index and in later packages. Clients that see a valid rotation accept updates from the new key.
- **Lost key:** updates cannot continue under the same identity. A new key publishes the app as a new package; the old listing can point to it, but the client treats it as a new app (new consent, data migration offered by the user, never automatic).
- **Compromised key:** the author publishes a signed revocation `{"type":"revoke","key":...,"after":<date>}`, or a source revokes the key. Clients block updates signed by that key after the date and flag installed versions released after it.

### 11.3 Sources and the index format

A source is `https://<host>/<path>/index.json` + `index.json.sig` (ed25519 by the source key). The client pins the source key when the source is added (via QR, link or settings, always showing the key fingerprint).

```json
{
  "format": 1,
  "source": { "id": "deft-main", "name": "Deft Main", "key": "ed25519:...",
              "policy": "https://.../POLICY.md", "contact": "security@..." },
  "serial": 18342,
  "generated": "2026-11-02T10:00:00Z",
  "expires": "2026-11-16T10:00:00Z",
  "packages": [{
    "kind": "app", "author": "ed25519:...", "id": "notes", "name": "Notes",
    "summary": "Plain-text notes with tags.", "license": "GPL-3.0-or-later",
    "repo": "https://codeberg.org/alice/deft-notes", "handles": ["codeberg.org/alice"],
    "tier": "curated", "anti_features": [], "categories": ["productivity"],
    "icon": { "url": "...", "sha256": "..." },
    "versions": [{
      "version": "1.4.0", "released": "2026-10-30", "deft": 1,
      "urls": ["https://codeberg.org/alice/deft-notes/releases/download/v1.4.0/notes-1.4.0.deftpkg", "https://mirror.example/..."],
      "sha256": "...", "size": 48213, "git": { "tag": "v1.4.0", "commit": "<40-hex>" },
      "capabilities": ["share.receive"], "network": [], "reproduced": true
    }]
  }],
  "revoked": [{ "author": "ed25519:...", "id": "x", "versions": "*", "reason": "malware", "date": "...", "notice": "https://..." }],
  "key_events": [ /* rotations and revocations */ ]
}
```

- **Rollback protection:** reject an index whose `serial` is lower than the cached one.
- **Freeze protection:** past `expires`, show "Source not updated since …" and stop auto-updates from it (manual installs still allowed, with a warning).
- **Index cannot lie about permissions:** at install, the package's manifest `capabilities` and `network` MUST equal the index entry's, or install aborts. Listings therefore show true permissions before download.
- Packages are fetched from any listed URL; the hash makes mirrors trustless.
- Components and recipes use the same index (kinds component and recipe; component entries list capabilities and network like apps), and themes use it with `kind: "theme"`.

### 11.4 Install pipeline (normative order)

1. **Resolve** the package from an index entry, an `deftpkg` URL, or a deep link `freedomlauncher://install?url=…&sha256=…&author=…`. Deep links and raw URLs show a "Not from one of your sources" warning.
2. **Download** over HTTPS into a quarantine directory, aborting if bytes exceed the declared `size`.
3. **Check** the SHA-256 against the index or link.
4. **Extract** with path rules (3.1); reject zip-slip, symlinks, duplicates and disallowed files.
5. **Verify signature:** `deft-package.sig` over the JCS bytes of `deft-package.json` with `author_key`. The key must equal the index entry's author and, for an update, the pinned key of the installed app (or a valid rotation from it).
6. **Verify files:** every extracted file is listed with a matching hash; no unlisted files.
7. **Check consistency:** manifest `id`/`version` match `deft-package.json`; version > installed; `capabilities`/`network` equal the index entry; `fl` supported; `min_host` satisfied.
8. **Lint** in `publish` mode on device. Any error rejects the install and shows the report.
9. **Consent screen** (11.5).
10. **Commit:** atomic move to `base/<version>/`, pointer switch, previous version kept for one-tap rollback. Record in the local install log.

Any failure leaves the device exactly as before and deletes the quarantine directory.

### 11.5 Consent screen

Shown by the host, never stylable by the app. In order:

- Icon, name, **author** (verified handle or fingerprint), tier badge, anti-feature labels.
- "Offline" badge, or the full list of network origins.
- Capabilities grouped by risk, each with the manifest `reason`.
- Libraries (10.10): each missing dependency URL, grouped by host. They download only when the user confirms, before any grant is written.
- License (with a "Free software" label for OSI/FSF licenses), source repo link, "Reproduced from source" badge when `reproduced: true`.
- Lint warnings count and suppression count, linking to the report.
- Size, version, release date. Bundled components (name, version, author) are listed on the app's details page, not in the prompt.
- Warnings when relevant: first package from this author; name similar to an installed or curated app (edit distance ≤ 2 or a confusable-character match); not from a configured source.

### 11.6 Trust tiers and the default source policy

| Tier | How it gets there | Shown as |
| --- | --- | --- |
| Curated | Default-source PR reviewed and approved by 2 maintainers against `POLICY.md`; all automated checks pass | "Curated" badge |
| Community | Default-source PR passing all automated checks; no human review | "Community" label, "not reviewed" in consent |
| Third-party source | Listed in a source the user added | Source name shown on every screen |
| Direct | URL, QR or file | Warning in consent |
| Local | Created on this device | "Local" |

Automated checks for the default source (CI in the index repo, all public):

- Package signature valid and author key matches the PR's previous entries.
- **Reproduced from source:** CI fetches `git.commit`, builds the package tree (the repo tree minus `.git` and the paths in `.flignore`), and asserts that every file hash equals `deft-package.json`. Since there is no build step, this proves the installed code is exactly the public source at that commit.
- `deft-lint` in publish mode: zero errors.
- License is OSI- or FSF-approved; `LICENSE` file present; repo public.
- Network origins and capabilities match the PR metadata. New high-risk capabilities or new network origins in an update force human review even for community-tier apps.

Anti-feature labels (from F-Droid's model, applied by reviewers or self-declared): `Tracking`, `Ads`, `NonFreeNet` (depends on a non-free network service), `NonFreeAssets`, `NSFW`, `KnownVuln`, `Upstream-NonFree`. Apps with `Tracking` or `Ads` are never curated.

### 11.7 Updates

- A WorkManager job refreshes indexes at a randomized time each day, on unmetered networks by default.
- Auto-update happens only when all hold: same author key (or valid rotation), no new capabilities or network origins, lint passes, the user has auto-update on for that app. Otherwise the update waits under "Needs approval" with a permission diff.
- Overlays are merged per 9.2. One-tap rollback to the previous version; older downgrades only from a file, with a warning.

### 11.8 Revocation and incident response

- Sources publish revocations in the signed index. On refresh, a revoked package is disabled, not deleted: user data and overlays are kept, and the app screen explains why and links to the notice.
- For `malware` revocations, running the app is blocked; the user can still export their data. For other reasons (e.g. `abandoned`, `license`), the user may choose to keep running it.
- The default source keeps a public `INCIDENTS.md` log, and a 72-hour target from a confirmed report to a revocation entry.

### 11.9 Publishing from the device

- "Publish" in the launcher (agent can prepare, user confirms): create or select an author key, run lint in publish mode, write `LICENSE` if missing (user chooses; GPL-3.0-or-later, MIT and Apache-2.0 offered first), pack, sign, and produce `<id>-<version>.deftpkg` plus a metadata JSON for the default source.
- v1 exports the files and opens the default-source contribution guide. v1.1 MAY push to Forgejo/Codeberg, GitLab or GitHub with a user-supplied token and open the PR.
- The reference packer (`fl pack`, `fl sign`, `fl verify`) ships in `tools/deft-lint/` for Node and in the static lint page, so CI pipelines can pack and verify; nothing needs a build.

### 11.10 Decentralization guarantees

- Indexes and packages are static files; any HTTPS host or forge Pages works, and mirrors need no trust.
- The default source ships preconfigured but is removable; adding sources is a first-class settings screen, not a hidden developer option.
- Sources can be shared as QR codes containing URL + key fingerprint.
- The index repo, review policy, CI and all decisions are public; anyone can fork the default source.

## 12. Contributor workflow and tooling

A contributor needs a text editor and a phone, nothing more. Node is optional and only speeds up CI. Every tool below is a thin wrapper over the same `deft-lint.js` bundle and runtime files the device uses.

### 12.1 Three ways to build an app

| Path | Who | Loop |
| --- | --- | --- |
| On device, by prompt | Anyone | Describe the app to the agent; it writes files and fixes lint; preview; publish (11.9). |
| On device, by hand | Tinkerers | Built-in file editor on a local app or overlay; live preview; lint panel. |
| External editor + phone | Developers | Edit on another device over **Dev link**; the app always runs and previews in the launcher on the phone; push to a forge; tag to release. |

### 12.2 Dev link (another device ↔ phone)

- Off by default. Settings → Developer → Dev link starts a LAN HTTP endpoint bound to one chosen local app, shows a QR with URL and a 128-bit pairing token, and turns itself off after 60 minutes or when the screen locks.
- API: `GET/PUT/DELETE /files/<path>`, `GET /lint`, `GET /events` (SSE: lint results, console, CSP violations). Writes go through `MyAppsStore` with the same path rules and history recording.
- A tiny CLI `fl link <url> <dir>` (zero-dependency Node script, also usable via `curl`) syncs a folder and streams diagnostics. Live preview reloads on write.

### 12.3 On-device preview and debugging

The launcher's preview runs the app in its real origin with the real runtime, `theme.css` and CSP. A dev panel shows lint results, console output and CSP violations; switches themes and light/dark; resizes to common widths (360, 412, 600, 840 px); and simulates bridge capabilities (granted, denied, erroring) and events without granting real access. The same panel is available during Dev link sessions.

### 12.4 App repository template

`deft-app-template` (published by the project on Codeberg and mirrored to GitHub):

```
manifest.json  index.html  main.js  app.css  icon.svg
LICENSE        README.md   .flignore
.forgejo/workflows/fl.yml  and  .github/workflows/fl.yml   # run fl lint --publish, fl pack, attach .deftpkg on tag
```

The release workflow only lints, packs and attaches the package; signing happens on the author's machine or phone, so CI never holds author keys. (An author MAY opt into CI signing with a dedicated key, shown as a separate key in the index.)

### 12.5 Documentation set (part of the deliverable)

- Runtime reference and cookbook (one page per module, plus recipes: list + detail, form + storage, settings screen, canvas widget, bridge permission flow).
- Theme token reference with a live gallery of every primitive in every bundled theme.
- One page per lint rule, generated from fixtures.
- Capability catalog with risk levels and consent wording. Component catalog with recipes and example pages, plus an deft-component-template repo.
- "Publishing your first app" guide and the default-source `POLICY.md`.
- `samples/`: 12 reference apps matching the eval prompts (14.1), all lint-clean and curated-quality, all GPL-3.0-or-later or MIT.

### 12.6 Contributing to FL itself

Public repo, DCO sign-off (no CLA), `CONTRIBUTING.md`, code of conduct, labelled good-first issues for lint rules and runtime docs, and an RFC process for anything that changes the spec, the runtime API, the catalog or the source policy (13.3).

## 13. Governance, licensing, transparency, reputation

The free-software community will judge FL first on whether it can turn into a walled garden, a surveillance channel or a slop store, and only then on features. This section makes the answers to those questions structural and public.

### 13.1 Licensing

| Component | License | Why |
| --- | --- | --- |
| Launcher (host) | GPL-3.0-or-later | Copyleft for the platform; forks stay free. |
| Runtime `/_deft/v1/`, `base.css`, deft-lint, packer, templates, samples | MIT (or Apache-2.0) | No obligations on apps; forks of the launcher can embed them. |
| This spec, docs | CC BY 4.0 | Anyone can implement a compatible host. |
| Bundled fonts / default themes | OFL-1.1 / CC0 | Standard for fonts; themes free to remix. |

Apps choose their own license. The default source accepts only OSI- or FSF-approved licenses; other sources set their own policy.

### 13.2 Shipping the launcher itself

- Primary channels: F-Droid main repository (reproducible build) and signed APKs on the project's forge releases. Launcher builds MUST be reproducible; CI publishes the expected APK hash.
- Google Play is optional and secondary. Play's policies on SMS/call-log permissions may require a reduced-capability Play flavor; that flavor MUST be clearly labelled and never be the only one.
- **Android developer verification:** Google plans to require apps on certified devices to come from verified developers, starting September 30, 2026 in Brazil, Indonesia, Singapore and Thailand and expanding globally in 2027 ([Android Authority](https://www.androidauthority.com/android-sideloading-changes-timeline-3679204/)). This applies to the launcher APK, so the project (or its fiscal host) SHOULD register as a verified developer before public launch. FL apps are not APKs and are unaffected. Publish a stance document saying so plainly: FL apps are sandboxed web content behind a capability-gated bridge, comparable to a browser, and FL is not a tool to bypass platform security. Review this before each milestone, since the policy is still changing.

### 13.3 Decision-making

- `MAINTAINERS.md` lists maintainers and their areas (host, runtime, lint, default source, security).
- An RFC repository (`deft-rfcs`) is required for changes to the spec, runtime API, capability catalog, CSP, or default-source policy. Each RFC gets at least 14 days of public comment, then lazy consensus among maintainers, with recorded objections.
- Two-person rule: curated approvals, revocations, key events and releases need two maintainers. Exception: one security maintainer may revoke for confirmed malware immediately; a second reviews within 72 hours, and the action is logged in `INCIDENTS.md`.
- Maintainers disclose conflicts of interest and do not review their own apps.
- Move to a fiscal host or foundation before 1.0. Funding sources are listed publicly; no funding may come with telemetry, ranking or listing conditions.
- Trademark policy: forks may use any name that is not confusingly similar; "compatible with Deft apps" is explicitly allowed.

### 13.4 Transparency

- The default-source index repo is the transparency log: every listing, review, revocation and key event is a public commit.
- A quarterly transparency report covers revocations, takedown and legal requests (published where legally possible), security incidents and audit results.
- Takedowns affect only the default source; the device keeps the installed app and its data, and other sources are unaffected.

### 13.5 Security process

- `SECURITY.md` with a private reporting address (and an age/PGP key), a 90-day coordinated disclosure policy, and advisories with CVEs.
- Continuous fuzzing of the zip extractor, manifest/index/palette parsers and signature verification (Jazzer for Kotlin, a JS fuzzer for deft-lint).
- A red-team test suite of hostile apps (`samples/hostile/`): bridge escalation, cross-origin access, CSP bypass attempts, Trusted Types bypass, zip-slip, oversized files, index rollback, key confusion. Every one MUST fail safely in CI on an emulator.
- An independent security audit of host, bridge and installer before 1.0, with the report published.

### 13.6 Agent and model transparency

- The agent provider is pluggable and shown in settings. A self-hosted or local model (OpenAI-compatible endpoint on the LAN or on device) MUST be supported, so the agent works without a cloud provider.
- Before a session sends app source or user data to a remote model, the user sees which provider and what scope ("this app's source" vs. "this app's data").
- Agent use is never required: every app can be installed, edited and published without the agent.

### 13.7 Reputational risks

| Risk | Mitigation |
| --- | --- |
| "AI slop floods the store" | Curated tier is human-reviewed; community submissions from keys younger than 30 days are limited to 3 per week; near-duplicate apps are rejected; curated requires zero lint warnings, a README and screenshots. Apps are judged on quality, not on whether an agent helped. |
| "Another walled garden" | Removable default source, any number of sources, static mirrorable files, open spec, MIT runtime, forks of the launcher welcome (11.10). |
| "Web apps are insecure" | Published threat model, strict CSP + Trusted Types, origin isolation, hostile-app CI suite, external audit. |
| "Hidden telemetry" | Normative no-telemetry rule (8.7), verifiable via reproducible builds. |
| "Circumventing Google's verification" | Stance document; verified launcher; sandbox comparable to a browser (13.2). |
| "Web apps feel bad" | `base.css` native feel, reference apps at curated quality, performance budgets, safe-area and accessibility rules in lint. |
| "Locked to one launcher" | Spec under CC BY and runtime under MIT, so anyone can fork the launcher or build a compatible Android host; apps are standard HTML/CSS/JS. |
| Name collision | Do a trademark and app-store name search before public launch. Check the existing Android app called "Freedom" that is widely associated with circumventing in-app purchases. |
| Agent sends private data to a cloud | Local-model support, per-session disclosure, data access only with consent (13.6, 8.6). |
| "npm-style supply chain" | Components are copied source with no dependency tree, signed inside each app, scoped by lint, and never fetched at runtime (section 10). |

## 14. Evaluation and metrics

The platform is judged on whether agents produce correct, themed, responsive, safe apps in fewer rounds, and whether the security suite stays green. Each milestone runs the eval before and after its changes, and the results go into the PR.

### 14.1 Agent eval

- **12 fixed prompts:** notes, todo, timer, contacts viewer, SMS inbox, calendar agenda, canvas clock, small game, BLE scanner, share target, form + storage, multi-screen app.
- **Models:** at least two, one of them cheap or small. If supported, also one local model.
- **Per run, from `AgentLog`:** tool rounds, rounds to first green `apps.verify`, total tokens, lint errors per round, suppressions added, runtime console errors and CSP violations at first preview.
- **Automated rendering checks in `AppVerifier`:** render at 360×780 and 800×1280, in light and dark, with two different themes.
  - No horizontal overflow (`scrollWidth > clientWidth` on `html` and the main containers).
  - No text outside the viewport.
  - Contrast of computed text colors ≥ 4.5:1 on sampled elements.
  - Count of raw color literals in app CSS.
  - Theme switch changes the computed background of `body`. This proves the app follows the theme.
- **Targets for v1 release:**
  - Median rounds to green ≤ 3 on the strong model and ≤ 5 on the cheap one.
  - At least 11 of 12 prompts green.
  - Zero CSP violations at first preview.
  - Zero overflow failures.
  - At least 90% of apps pass the theme-follow check.

### 14.2 Platform health

- Hostile-app suite (13.5): 100% fail-safe, which blocks merges.
- Lint false positives: zero new findings on `samples/`, which blocks merges.
- Performance budgets (7.1, 7.7): incremental lint ≤ 150 ms and cold app start ≤ 500 ms to first paint on the reference mid-range device; regressions over 20% block merges.
- Runtime size ≤ 8 KB JS, `base.css` ≤ 16 KB.

### 14.3 Ecosystem health (public, no telemetry)

Computed only from the public index repo: number of packages per tier, the share of packages that are offline, the share reproduced from source, median review time, revocations per quarter, and the number of third-party sources known to the project. Published in the transparency report.

## 15. Implementation phases

Build security foundations first, then the authoring loop, then distribution. Each milestone is one or more PRs. It is done only when its gate passes in CI on an emulator running the minimum supported WebView, and when the eval (14.1) has been re-run if the milestone touches the agent loop. Do not start distribution (M4+) before M0's hostile-app gate is green.

### M0 — Security foundations

- [ ] `bridge-catalog.json` with `risk` per capability; JVM drift test against `Capability.kt` and `js-bridge.md`.
- [ ] Per-app origins `https://<origin-id>.app.deft.internal` in `LocalOriginPathHandler`; update callers (`WebViewHostActivity`, `AgentMyAppPreview`, `HomeCanvas`, `AppVerifier`).
- [ ] CSP header on every response (8.2), connect-src from the manifest; WebView hardening settings (8.3); navigation lock and external-link sheet.
- [ ] Bridge moved to `addWebMessageListener` with origin rules; host-side grant checks; per-method argument schemas; audit log.
- [ ] `_deft/` reserved in `MyAppsStore` and `HomeCanvasStore`.
- [ ] `samples/hostile/` v0: inline script, eval, cross-origin storage read, bridge call from another origin, undeclared fetch, undeclared capability call.

* **Gate:** every hostile sample fails safely; existing apps still run (any break gets a migration note).

### M1 — Runtime and theme

- [ ] `/_deft/v1/` modules (5.1) with header comments; Trusted Types handshake via `HOOK_JS`; CSSOM style bindings.
- [ ] `base.css` layers and primitives (6.4); `palette.json` schema; `theme.css` generator; bundled themes (at least 6, including `system` dynamic color); live theme switch.
- [ ] Updated scaffolds (`MyAppsStore.create`, `HomeCanvasDefaults.kt`); 3 reference apps in `samples/`.

* **Gate:** reference apps pass the rendering checks of 14.1 in all bundled themes; runtime and CSS size budgets met; focus and caret survive re-render (automated test).

### M2 — deft-lint and harness loop

- [ ] `tools/deft-lint/` with all rule groups FL0xx–FL8xx, fixtures, docs generation, committed bundle and a reproducibility check.
- [ ] `DeftLintEngine.kt`, `DeftLintFormat.kt`, harness wiring (7.8), `AgentLog` counters.
- [ ] Skill rewrites: `SKILL.md`, `runtime.md`, `theme.md`, `js-bridge.md`.
- [ ] Eval baseline recorded **before** wiring, then after.

* **Gate:** eval targets in 14.1; zero false positives on `samples/`; lint performance budgets met on the reference device.

### M3 — Personalization

- [ ] Overlay storage and resolution, `overlay.json`, "Modified by you" and diff UI, reset per file and per app.
- [ ] Content-addressed history, "Undo agent session", retention.
- [ ] Fork flow with new origin and optional data copy.

* **Gate:** tests for update with no conflict, update with conflict (all three choices), deleted-path conflict, manifest capability change via overlay triggering consent, undo session restoring exact bytes.

### M4 — Packages, keys, local install

- [ ] `deft-package.json` + JCS + ed25519 signing; key creation, Keystore-wrapped storage, export, rotation and revocation statements.
- [ ] Packer `fl pack/sign/verify` in Node and in the browser page; on-device publish export (11.9).
- [ ] Install pipeline (11.4) from file, URL and deep link; consent screen (11.5); rollback.
- [ ] Hostile packaging samples: zip-slip, symlink, extra file, hash mismatch, bad signature, wrong key on update, permission mismatch versus metadata, oversized download.

* **Gate:** all hostile packages are rejected with the device state unchanged; an install → update → rollback round trip preserves data and overlay.

### M4b — Components and recipes

- [ ] Component and recipe formats (10.2, 10.3); `components.search/read/add/update` tools and launcher UI; provenance headers.
- [ ] Lint group FL9xx; component-scoped capability checks; three-way update merge; acceptance-check runner in `AppVerifier`.
- [ ] First blessed components extracted from the reference apps, each with recipe, example page, checks and accessibility review.
- [ ] Hostile samples: component calling an undeclared capability, component update adding a capability silently, recipe containing injected instructions, tampered provenance hash.

* **Gate:** all hostile samples are caught; the eval (14.1) re-run with the component tools shows equal or fewer rounds to green.

### M5 — Sources and the default index

- [ ] Index format, source management UI, QR sharing, rollback and freeze protection, revocation handling (11.3, 11.8).
- [ ] WorkManager update job; auto-update rules; "Needs approval" permission diffs.
- [ ] Default-source repo: metadata format, CI (signature, reproduction from git commit, lint publish mode, license check, permission diff forcing review), static index generation and signing, `POLICY.md`, `INCIDENTS.md`.
- [ ] Similar-name and confusable warnings; verified-handle proofs.

* **Gate:** end to end on a clean device: author publishes from the phone → PR → CI reproduces → index updated → install. Index rollback and expired-index tests pass. Revocation disables an installed app within one refresh.

### M6 — Contributor experience

- [ ] On-device preview and dev panel (12.3); Dev link + `fl link`; static lint page.
- [ ] `deft-app-template` with Forgejo and GitHub workflows; docs site (12.5); 12 reference apps.

* **Gate:** two people outside the project each publish an app to the community tier using only the docs.

### M7 — Hardening and public launch

- [ ] Fuzzing in CI; full hostile suite; external audit, with high and critical findings fixed and the report published.
- [ ] Reproducible launcher build; F-Droid submission; developer verification decision executed (13.2); name and trademark check.
- [ ] Governance files (`MAINTAINERS.md`, `SECURITY.md`, RFC repo, trademark policy, stance document); first transparency report.

* **Gate:** audit sign-off, F-Droid build reproduced, and every MUST in this spec traced to a test or a documented manual check (`docs/spec-trace.md`).

### M8 — Optional device setup (Appendix A)

- [ ] Setup wizard with levels 1–3; `RoleManager` flows.
- [ ] Host ADB client over wireless debugging with the command allow-list and package deny-list.
- [ ] `profile` package kind, profile review CI, first curated profiles for a few popular device families.
- [ ] Restore list, one-tap restore, post-OTA re-apply.
- [ ] UnifiedPush integration in the host (A.6).

* **Gate:** hostile profile samples rejected; apply → reboot → restore round trip leaves the device identical on every lab device; level 1 path works with no debugging access.

## 16. Open decisions (with defaults)

The implementer proceeds with each default unless the project owner overrides it. Each needs a short check before its milestone.

| # | Decision | Default | Check before |
| --- | --- | --- | --- |
| 1 | `MIN_WEBVIEW_MAJOR` | 124 | M1: confirm against the WebView versions on target devices |
| 2 | App origin domain | `<id>.app.deft.internal` (never resolved, intercepted) | M0: confirm secure-context behavior and that interception covers all request types |
| 3 | Network default | No network unless declared in the manifest. CDN code only as hash-pinned `dependencies`, fetched once and served locally (10.10); never fetched at runtime | Decided |
| 4 | Remote images | Blocked (`img-src 'self' data: blob:`); fetch from declared origins and use blob URLs | M1: revisit if reference apps suffer |
| 5 | WebAssembly | Allowed behind the `wasm` capability (PARITY G2): `'wasm-unsafe-eval'` only for apps with the grant; `.wasm` only in the package or as a hash-pinned dependency | Decided (2026-11-19) |
| 6 | Runtime/lint license | MIT | M1 |
| 7 | Default-source hosting | Codeberg primary, GitHub mirror, static Pages | M5 |
| 8 | Key and signature format | Raw ed25519 + JCS; evaluate minisign compatibility for CI tooling | M4 |
| 9 | Play Store flavor | Not at launch; F-Droid and forge APKs first | M7 |
| 10 | Project name | "Deft" pending a trademark and name-collision check | Before any public announcement |
| 11 | Per-app WebView profiles | Use where androidx.webkit supports it, origin isolation otherwise | M0 |
| 12 | Component format | Template functions + CSS; light-DOM custom elements allowed; no shadow DOM | M4b |
| 13 | Component dependencies | None; flat | Decided |
| 14 | Core components inside the runtime | Only the CSS classes in base.css; all JS components live in the catalog | M4b |

### 16.1 Questions for the implementing agent

These questions come from the parity goal. With full repo context (`Capability.kt`, the bridge, `js-bridge.md`, existing stores), decide for each one whether it is relevant now, later or never. Record the answer in the table above or in an RFC. Anything adopted must follow the rule in section 1: declared in the manifest, granted by the user, enforced by the host.

1. Should apps be able to use **user-provided network origins** (the user enters a server URL in a host screen) for self-hosted and federated services? Is a high-risk **open network** capability needed for feed readers and similar clients?
2. Should `img-src` and `media-src` allow the app's declared and user-provided origins, so remote images and streams work?
3. Should the host run **background tasks** (a hidden WebView runs an app module on schedule or event, within a budget)? Is a **foreground service** capability needed for navigation, recording or workouts?
4. Push: UnifiedPush only, or also an optional FCM relay in a separate build?
5. Should the host route **intents** (declared link patterns and file types, "open with", Android intents into FL apps) between apps, without direct app-to-app calls?
6. Is a host **auth session** (OAuth via Custom Tab with redirect back) needed? Should apps be able to open an in-app browser view they cannot script?
7. Are **asset packs** (hash-verified downloads after install, covered by the package signature) needed beyond the package size cap?
8. Which **file and storage** capabilities are needed: system file picker, an app folder, persistent storage that the OS will not evict?
9. Is a **media session** capability (background audio, lock-screen controls, picture-in-picture) needed?
10. Which native APIs that WebView lacks (BLE, NFC, USB, biometrics, Keystore, CameraX, quick settings tiles, notification actions) should the bridge catalog cover, and in what order?
11. Should apps support **per-app data export/import** and an encrypted full backup for moving to a new phone?
12. Should manifests carry **translations** of name and description, and should the runtime include a small translation helper with right-to-left checks?
13. Should listings allow an optional **donation link**?

## Appendix A. Optional device setup

FL is an ordinary APK that sits next to the user's existing Android apps; debloating and removing Google services are optional extras, done entirely on the phone, without root and without a computer on Android 11+. Nothing in this appendix is required to use FL apps.

### A.1 Setup levels

The first-run wizard offers three levels. The user can move between them later; every change is reversible.

| Level | What happens | Android apps | Needs |
| --- | --- | --- | --- |
| 1. Launcher only | Install, set FL as home | Unaffected | Nothing extra |
| 2. Privacy-hardened | Remove OEM/carrier bloat from a signed profile, delete the advertising ID, set Private DNS, revoke permissions from leftover system apps, deep-links to Google account controls (Web & App Activity, Location History) | Keep working; Play Services stays | Wireless debugging (A.3) |
| 3. No Google | Level 2 plus disabling Google Play Services and Google apps | Many break: push in many apps, banking/Play Integrity, Google sign-in, maps | Wireless debugging; explicit opt-in after a list of what breaks; a recommendation to use a free ROM for the full version |

FL apps work identically at every level; they do not depend on Google Play Services (push via A.6).

### A.2 Steps that never need debugging access

- Install the APK (F-Droid or forge release; the user allows "install unknown apps").
- Set FL as home via `RoleManager` (`ROLE_HOME`) with the system prompt.
- Optionally offer free replacements for SMS, dialer, browser (`RoleManager` prompts, one per role) and the keyboard (deep-link to input settings).

### A.3 On-device ADB via wireless debugging

- The launcher pairs with the phone's own ADB over wireless debugging (Android 11+), as Shizuku and LADB do. The ADB client lives in the host (Kotlin), never in a WebView or FL app.
- Manual steps the wizard guides with screenshots: enable Developer options (tap Build number 7 times), enable Wireless debugging, enter the pairing code (split-screen recommended). Needs a Wi-Fi network (no internet required).
- Session is used only during setup and re-apply; the wizard offers to turn wireless debugging and Developer options off afterwards.
- Allowed commands are a fixed allow-list in the host: `pm uninstall -k --user 0`, `pm disable-user --user 0`, `cmd package install-existing`, `pm enable`, `pm revoke`, `settings put` for listed keys, `cmd appops set` for listed ops. No shell passthrough, no command strings from profiles or apps.
- Android 10 and older: level 2/3 require a computer once (documented desktop path) or are skipped.

### A.4 Debloat profiles

- New data-only package kind `profile`, distributed through the same sources, signing and tiers as themes (section 11). Profiles contain no scripts.
- Content: device match (manufacturer, model family, Android/OEM version range) and a package list. Each entry has: package name, plain-language description, risk (`safe`, `recommended`, `advanced`, `breaks`), what stops working, source/evidence link (e.g. the UAD-ng community list), and action (`uninstall-user` or `disable`).
- Curated tier requires two-maintainer review and at least two community device test reports for the matching device family.
- Matching happens on the device; profile fetches send no device identifiers (8.7).

### A.5 Apply, restore, re-apply

1. Detect device, suggest a profile, show every package with risk; `recommended` preselected, `advanced` opt-in, `breaks` hidden unless level 3.
2. Dry run: list exactly what will change.
3. Apply with progress; record a restore list (package, previous state) in local storage and offer to export it.
4. **Restore everything** is one tap (`install-existing` / `pm enable` for every recorded package); per-package restore too.
5. After an OTA update, the launcher detects reinstalled packages and offers to re-apply (requires wireless debugging again).
6. Hard limits shown in the wizard: removed packages stay on the read-only system partition and return after factory reset; firmware, baseband and carrier network are untouched; the mobile network still sees approximate location. Wording is "reduces data collection", never "observation-free".

### A.6 Push without Google

- FL apps receive push through the host using **UnifiedPush**: the host registers with the user's chosen distributor (e.g. Sunup, ntfy, NextPush, Conversations) and gets an endpoint URL per app; app servers send Web Push-encrypted messages to it; the host wakes the app (background task, see 16.1) or posts a notification.
- The host MAY include a built-in distributor so users at level 3 get push without installing another app; external distributors remain selectable.
- With Play Services present (levels 1–2), an FCM-backed distributor MAY be used in a separate build; the default stays UnifiedPush.

### A.7 Security and reputation

- Hostile tests: malformed profiles, profiles targeting critical packages (system UI, telephony, the launcher itself) marked anything but `breaks`, attempts to inject commands through profile fields. A built-in deny-list of packages that are never removable regardless of profile (system UI, package installer, phone/telephony core, settings, the FL launcher).
- Device lab and community test reports gate curated profiles.
- A public per-device page lists what still reports home after each level.

## Sources

- [Omarchy](https://omarchy.org/)
- [How the Omarchy theming system works](https://instagit.com/omacom/omarchy/how-does-omarchy-theming-system-work/)
- [How to create a custom Omarchy theme](https://instagit.com/omacom/omarchy/how-to-create-a-custom-omarchy-theme/)
- [Baseline (web.dev)](https://web.dev/baseline) and [Baseline in the wild](https://web-platform-dx.github.io/baseline-in-the-wild/)
- [Design Tokens specification, first stable version (W3C CG)](https://www.w3.org/community/design-tokens/2025/10/28/design-tokens-specification-reaches-first-stable-version/)
- [Android developer verification timeline (Android Authority)](https://www.androidauthority.com/android-sideloading-changes-timeline-3679204/)
