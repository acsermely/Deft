# CDN libraries (my-apps only)

No build step and no package manager. Import a library straight from its CDN URL, as in any browser. The launcher downloads each declared file once, checks its hash and serves it from the phone, so the app works offline and the code cannot change under it.

## Declare every file you import
`manifest.json`:
```json
"dependencies": {
  "https://cdn.jsdelivr.net/npm/three@0.160.0/build/three.module.min.js": null
}
```
- Key: the exact `https:` file URL with a version in it (`name@1.2.3`). No `?query`, no `#fragment`.
- Value: an SRI hash (`sha256-…`, `sha384-…`, `sha512-…`) or `null`. With `null`, the launcher computes the hash on the first confirmed download and writes it into `manifest.json`. Do not guess hashes; a wrong hash fails the download.
- Long form `{ "integrity": null, "as": "three" }` also lets code import the bare name `three`.
- Do not add the CDN host to `network`. Libraries are not network access.

## Import it
```js
// main.js — works unchanged in a desktop browser and in the launcher
import * as THREE from 'https://cdn.jsdelivr.net/npm/three@0.160.0/build/three.module.min.js';
```
With `"as": "three"`: `import * as THREE from 'three';` (launcher only).

## What happens
The first time the user opens or previews the app, the launcher's consent screen lists the URLs in a Libraries section, next to any capabilities. Nothing downloads until the user confirms. If you add a library mid-session, the same screen opens for just that library. You cannot download or approve it; tell the user to open Preview and confirm. Until then the import is blocked (a `[csp]` error in `apps.debug`).

## Quirks (read before picking a URL)
- Only `import` and `import()` are mapped. `<script src="https://…">`, `<link href="https://…">`, CSS `@import url(https://…)` and `fetch()` to a CDN stay blocked. Load the library from a local module; copy CSS into the app.
- Use single-file ES module builds: `*.module.min.js`, `dist/*.esm.js`, `*.mjs`. UMD/CommonJS builds (`require`, `module.exports`, globals) do not work as modules.
- Relative imports inside a library (`./chunk.js`) resolve to the same CDN folder. Declare each of those files too, or they fail with 404.
- Root-relative imports inside CDN files fail (`/npm/x@1/+esm`, `/v135/...` from esm.sh, jsDelivr `/+esm`, unpkg `?module`). Avoid those URLs.
- Bare imports inside a library (three's addons do `import ... from 'three'`) need an `as` alias with that name.
- Only `.js`, `.mjs`, extensionless JS, and `.json` files. Max 16 MB per file.
- Redirects are refused: `https://unpkg.com/three` redirects to a versioned URL. Declare the final URL (the error message shows it).
- Canvas (home) cannot use dependencies.
- Changing the version means a new URL: declare the new one, remove the old one. The old copy is deleted once no app uses it.

## Lint
- FL001: remote import not in `dependencies`. Add the exact URL.
- FL710: bad entry (not https, query string, wrong file type, bad hash or alias).
- FL711: URL has no version. Pin one.
