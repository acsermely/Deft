# Lint rules

Run it, fix errors first, then warnings.

- Device: `files.write` / `files.edit` print lint lines for that file. `apps.verify` (`canvas.verify` on Home) lints everything and fails on errors.
- External: `bun tools/deft-lint/cli.js <appDir> [--profile app|canvas] [--json]`. Output is `path:line:col severity ID message`. Exit 1 on any error.
- Suppress one finding only with a reason (a bare suppression is FL009).

Severity: E error, W warning, I info.

## Design system (FL6xx)

| ID | Sev | Meaning and fix |
|---|---|---|
| FL600 | E | Viewport meta needs `viewport-fit=cover`. |
| FL601 | E | Viewport must not disable zoom. |
| FL602 | E | Page needs `<script type="module" src="./main.js">`. |
| FL603 | E | Link `base.css`, `tokens.css`, `components.css`, `theme.css`, then app CSS. |
| FL604 | E | No `@import`, no remote `url()`. |
| FL605 | W | `fixed`/`sticky` needs a safe-area inset (`env(safe-area-inset-*)`). |
| FL606 | W | No `!important`. |
| FL607 | W | No `100vh`; use `100dvh` or the layout classes. |
| FL608 | W | No `100vw`. |
| FL609 | W | No fixed width over 480px. |
| FL610 | E | No hex, `rgb()` or `oklch()` literal; use a `--deft-*` token. |
| FL611 | W | No `font-family` literal; use a type class. |
| FL612 | I | px font size; use a type class (`.deft-body`, `.deft-footnote`...) or `rem`. |
| FL613 | W | `border-radius` literal; use a radius token (`var(--deft-radius-xs` to `-xl`, `-full` for circles and pills). `0` is fine, alone or in a shorthand. |
| FL614 | W | App CSS redefines a `--deft-*` token. Component hook properties (`--deft-min`, `--deft-gap`, `--deft-density`, `--deft-side`, `--deft-measure`, `--deft-cols`, `--deft-pad`, `--deft-radius-outer`, `--deft-value`, `--deft-segs`, `--deft-level`) may be set; they are listed in `references/components.md`. |
| FL615 | W | `outline: none` needs a `:focus-visible` style. |
| FL616 | W | Accent token only in `[data-primary]`, `.deft-btn-primary`, `.deft-tabbar`, `.deft-badge`, `[data-live]`. In JS, reading `--deft-accent` (for SVG or canvas) needs `data-live` in the same file. |
| FL617 | W | Error colour needs an icon and text on an `.deft-alert` or `.deft-toast` (a destructive button uses `data-tone=destructive` instead). An alert whose text JS fills later passes with `data-deft-text` or `aria-live` on it (or on its text element), or with default text in the markup. |
| FL618 | W | Glyph (`‹ ✕ ☰ + ×`) used as a button icon; use `<deft-icon>`. |
| FL619 | W | Deprecated token (`--deft-color-*`, `--deft-fg`, `--deft-surface`, `--deft-muted`, `--deft-red`); the message names the replacement. |
| FL620 | E | `<img>` without `alt`. |
| FL621 | E | Button without text or `aria-label`. |
| FL622 | E | Input, select or textarea without a label. |
| FL623 | W | Click handler on a non-interactive element; use a button or link. |
| FL624 | W | `<html>` needs `lang`. |
| FL625 | W | Heading level skipped. |
| FL626 | W | Positive `tabindex`. |
| FL627 | E | `@keyframes`. |
| FL628 | E | `cubic-bezier()` or `steps()`. |
| FL629 | W | CSS outside `@layer deft.components` or `@layer deft.utilities`. |
| FL630 | W | A second `data-primary` on one screen. Each `<dialog>`/sheet and each container with the `hidden` attribute (an inactive tab or view) is its own screen and may hold one. Lint cannot see routes: views swapped by `routes()` in one file still count together, so keep one primary per route or split the file. |
| FL631 | W | Raw duration (`200ms`); use `--deft-duration-*` or a spring duration token. |
| FL632 | W | `<deft-icon name>` (in HTML or in markup inside a JS string) or an `icons.svg#id` href names an icon that is not in the sprite; it draws nothing. The message lists the nearest names; the full list is `references/icons.md`. A name built with `${}` is skipped. |
| FL633 | W | A `<button>` or `[data-action]` element whose id, own class or `data-action` value never appears in the app's JS: nothing is wired to it. Heuristic; skipped for submit buttons in a form, popover/command buttons, buttons with no id/class/action, and apps that delegate with `closest('button')`. |

## Canvas and widget (FL63x)

Checked for a canvas package and for a package with a manifest `widget`. The home canvas and its widgets share the launcher's renderer. Heuristics on the source; suppress with a reason, as with any rule.

| ID | Sev | Meaning and fix |
|---|---|---|
| FL634 | E | A `requestAnimationFrame` callback schedules itself and the package never calls `cancelAnimationFrame`: an unbounded loop. Stop it when `document.hidden`, after a bound, or redraw on data. One finding per file. |
| FL635 | W | `setInterval` or `setTimeout` with a literal delay under 5000 ms. Refresh on view (`visibilitychange`) or use a longer interval. Non-literal delays are not checked. |
| FL636 | W | `blur()` over 8px in CSS, or `backdrop-filter` (other than `none`). Costs frames on low-end phones; use a smaller blur or a flat `--deft-*` surface. |
| FL637 | W | A canvas page uses `<deft-widget` but does not load `/_deft/v1/elements.js`, so the element stays blank and dead. Add `<script type="module" src="/_deft/v1/elements.js"></script>` in `<head>`. |
| FL638 | E | `var(--deft-x)` names a property the runtime does not define (CSS, `<style>`, style attributes and JS strings). It silently falls back to the initial value. The message suggests the nearest real token. A `--deft-x` declared in the same file passes (but FL614 still warns). |

## Code and structure

| ID | Sev | Meaning and fix |
|---|---|---|
| FL001 | E | Bad module import (message says why). |
| FL002 | E | Relative import needs the `.js` extension. |
| FL005 | E | Unknown `/_deft/v1/` module. |
| FL007 | E | Dynamic `import()` needs a string literal. |
| FL100 | E | `innerHTML`, `outerHTML`, `document.write`, `insertAdjacentHTML`, `DOMParser`: use `html` and `render`. |
| FL101 | E | `eval`, `new Function`, string timers. |
| FL102 | E | `on*` attribute; use a listener or `@click`. |
| FL103 | E | Inline `style=""`, `<style>`, inline `<script>`. |
| FL104 | E | Script must be `<script type="module" src="...">`. |
| FL105 | E | The blocking `alert()`, `confirm()`, `prompt()`. Use `confirm`, `prompt` or `dialog` imported from `/_deft/v1/app.js` (or `deft.confirm`, `deft.prompt`); imported names are not flagged. |
| FL106 | E | `style` attribute. |
| FL107 | E | `javascript:` URL. |
| FL108 | E | Unsupported element or `meta http-equiv`. |
| FL109 | E | Unsupported API (service worker and similar). |
| FL110 | E | `window.open`. |
| FL112 | E | Line over 400 characters (publish mode). |
| FL113 | E | Access to host internals (`__deft*`). |
| FL114 | E | SVG contains script, foreignObject, an `on*` attribute or an external href. |
| FL200 | W | Direct `deftBridge`; use `call()` from `/_deft/v1/bridge.js`. Mapping and when to migrate: `references/recipes/bridge.md`. |
| FL201 | E | Unknown bridge method or event. |
| FL202 | E | Capability used but not declared in the manifest. |
| FL203 | W | Capability declared but unused. |
| FL204 | E | Canvas cannot use the bridge or network. |
| FL205 | E | Network origin missing from `manifest.network`. |
| FL206 | I | Network URL not statically known. |
| FL207 | E | `call()` method name must be a literal. |
| FL300 | W | JS refers to an id that is not in the HTML. |
| FL301 | E | Duplicate `id`. |
| FL303 | W | File not reachable from `index.html` (a manifest `wallpaper.page`, `keyboard.page` or `background.module` counts as an entry point). |
| FL302 | I | Unknown `deft-btn-*` modifier; check `references/components.md`. |
| FL400 | W | `async` callback in `forEach`; use `for...of`. |
| FL402 | W | Unkeyed `.map()` of a template; use `repeat(items, key, tpl)`. |
| FL403 | W | Raw `localStorage` write, or `JSON.parse` outside `try`; use `store()` (see SKILL.md section 3). A `try`-guarded `localStorage.getItem` read of a legacy key is accepted. |
| FL500 | W | `var`; use `const`/`let`. |
| FL501 | W | `==` or `!=`; use `===`/`!==`. |
| FL502 | W | `main.js` over 80 lines, or any file over 200. Split into modules. |
| FL503 | W | Missing header comment: first line of each JS/CSS file starts `//` or `/*`; HTML has `<!-- -->` near the top. |
| FL504 | I | Leftover `console.log`. |

## Manifest and package (FL7xx)

| ID | Sev | Meaning and fix |
|---|---|---|
| FL003 | E | Path contains `..` or `_deft/`. |
| FL009 | W | Suppression without a reason. |
| FL700 | E | Too many files, empty or too deep path, bad segment or file type. |
| FL701 | E | `manifest.json` invalid or missing `deft`, `id`, `name`, `start_url`. |
| FL702 | E | Unknown capability id. |
| FL703 | E | `version` missing or not semver. |
| FL704 | E | `network` must be an array of https origins. |
| FL705 | W | Unknown manifest field. |
| FL706 | E | Canvas profile cannot declare capabilities, network, embeds, background, handles, wallpaper, keyboard, widget or tools. |
| FL707 | E | Publish needs LICENSE, `license` and `source`. |
| FL708 | E | Capability `reason` must be 10 to 140 characters; shortcuts: at most 8 `{id, name, url}`. |
| FL709 | E | File or package over the size limit. |
| FL710 | E | Bad `dependencies` entry (https file URL, hash). |
| FL711 | W | Dependency URL has no version. |
| FL712 | E | Bad `embeds`. |
| FL713 | E | Bad `background`. |
| FL714 | E/W | Bad `handles`. |
| FL715 | E | Bad `wallpaper`. |
| FL716 | E | Bad `keyboard`. |
| FL717 | E | Bad `widget` (page or sizes; canvas forbids it). |
| FL718 | E/W | Bad `tools` (name, description, params schema subset, handler ref; canvas forbids them), or a handler file that is missing, does not export its function (W), or, for an `isolate` handler, is not a plain synchronous script. |
| FL719 | E | Bad `passkeys` (rpIds: 1–4 lowercase domains, no IP, localhost, .internal, .local, .localhost, .test; canvas forbids it), `passkeys` without `webauthn`, or `webauthn` without `passkeys`. |
| FL720 | E | Theme `palette.json` invalid. |
| FL721 | E | Theme `palette.json` has a bad or missing value. |
| FL722 | E | Theme package contains code or SVG. |
