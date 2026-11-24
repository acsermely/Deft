---
name: deft-design
description: Build Deft apps, canvas pages and any CSS with the launcher design system. Pick a template, compose runtime components (deft-btn, deft-group, deft-tabbar, deft-tile...), use tokens only for gaps. Use for every app, screen or stylesheet in the launcher.
---

# deft-design

Layout from iOS (large titles, grouped inset lists, floating tab bar, sheets), colour from Nothing (neutrals, one accent, dot numerals). The user picks only an accent and an appearance. You compose; you do not style.

## Minimal read set (stop here and start writing)

1. This file.
2. ONE template from `templates/` (copy it to `index.html`, adapt it).
3. `references/tokens.md` (only if you need a gap token).

Read other references only when the index below says you need them. Do not read all of them.

## Decision tree

1. A template fits? Copy and adapt it. All are lint clean.
   - `list-detail.html`: large-title list, detail card, search, tab bar.
   - `settings.html`: grouped rows, switches.
   - `dashboard-tiles.html`: tiles, Doto number, sparkline.
   - `player.html`: hero, scrubber, transport, volume.
   - `compose-form.html`: top bar with send, fields, segmented control.
   - `onboarding.html`: cover, one primary action.
2. None fits? Closest template plus components (`references/components.md`), not new CSS.
3. Still a gap? A semantic token in `@layer deft.components { }` in `app.css`. Layout glue only.
4. A home widget with live data? `references/recipes/widgets.md` with `templates/widget.html` and `templates/widget-main.js` (copy as `main.js`).
5. A tool the agent can call? `references/recipes/tools.md` with `templates/tool-app/`.

Page skeleton: link order `base.css`, `tokens.css`, `components.css`, `theme.css`, then `./app.css` (FL603). Wrap in `<div class="deft-app">`, scroll in `<main class="deft-scroll">`. `main.js` starts with a `//` comment, then `import '/_deft/v1/motion.js';`.

## Accent map

The accent marks the primary action and one active indicator per screen. Nothing else.

| Component | Colour |
|---|---|
| `.deft-btn[data-primary]` (one per screen) | `--deft-accent` fill, `--deft-on-accent` text |
| secondary `.deft-btn`, `.deft-btn-soft` | `--deft-fill` fill, `--deft-label` text |
| `.deft-switch`, `.deft-segmented`, `.deft-chip`, links, focus | `--deft-label` / `--deft-fill`, no accent |
| `.deft-tabbar` selected | `--deft-label` plus one 4px `--deft-accent` dot |
| `.deft-slider`, `.deft-progress` | `--deft-label`; accent only with `data-live` |
| badges, unread dots | `--deft-accent` |
| errors | `--deft-error` with icon and text (`.deft-alert`, `.deft-toast`) |

Text `--deft-label`, `-2`, `-3`, `-4`; surfaces `--deft-bg`, `--deft-bg-grouped`, `--deft-bg-elevated`; fills `--deft-fill`..`--deft-fill-4`; `--deft-separator`; status `--deft-success|warning|error`. No old shims (`--deft-fg`, `--deft-muted`: FL619).

## Hard rules (lint enforces most)

- No hex, `rgb()`, `oklch()` (FL610). `color-mix()` of tokens is fine.
- No `cubic-bezier()`, `steps()`, `@keyframes` (FL627/628); raw `200ms` warns (FL631). Use `--deft-duration-*`, `--deft-ease*`, `--deft-spring-*`.
- Every CSS rule inside `@layer deft.components { }` or `@layer deft.utilities { }` (FL629).
- No inline `style=""`, `<style>`, inline `<script>`, `on*` attributes (FL102/103/106).
- Accent token only in `.deft-btn[data-primary]`, `.deft-fab[data-primary]`, `.deft-tabbar`, `.deft-badge`, `[data-live]` (FL616).
- One `data-primary` per visible screen; each `<dialog>` and each `hidden` container counts separately (FL630).
- Persistence: `store()` from `/_deft/v1/store.js`, never raw `localStorage` (FL403). Phone features: `call()` from `/_deft/v1/bridge.js` (read `js-bridge` index).
- Use `el.hidden`, not your own display rule. Destructive actions: `await deft.confirm(...)`.
- Icons: `<deft-icon name="..." aria-hidden="true">`, names only from `references/icons.md`; never glyphs (FL618). Never set a font-family (FL611).
- Tap targets 48px. Every control has text or `aria-label`. Heading levels never skip (FL625).
- Wiring: every button and `data-action` needs a handler in JS (lint warns FL633).

## Self-check (before saying done)

1. `files.write`/`files.edit` print lint lines; `apps.verify` (`canvas.verify` on Home) lints the whole app and loads it. Zero errors. External: `bun tools/deft-lint/cli.js <appDir>`.
2. `apps.verify` / `canvas.verify` also clicks up to 12 visible controls (buttons, tabs, `data-action`, `#hash` links, checkboxes, ranges) in a hidden page with the bridge stubbed (bridge calls are recorded, not run; destructive ones are skipped). Read its lines:
   - `ERROR ... [uncaught]`: a handler or load code threw; fix first.
   - `WARN smoke <el> — click changed nothing`: no DOM change, hash change, dialog, focus move, checkbox toggle or bridge call within 150 ms; the handler is missing, bound to another selector/id, or waits on state. Fix it.
   - `INFO smoke <el> — disabled at load`: not clicked. Fine if it enables after input; a Start button that never enables is a bug.
   Still read the JS: each `id`, `data-action` and class must be bound. Green does not mean every flow works.
3. One primary, heading order, classes exist in `references/components.md`, no fixed widths that overflow.
4. Claude Code only: emulator screenshot in light and dark. Never `connectedAndroidTest`.

## Reference index (read only when the need matches)

- `references/structure.md`: page structure rules (tab bar vs none, FAB placement, sheets, persistence detail), type classes, icons, motion behaviours.
- `references/components.md`: layout, type, buttons, switches, sliders. `references/components/screens.md`: lists/cells, cards, bars, tab bar, sheets, alerts, inputs, hook properties.
- `references/recipes.md`: index of `recipes/bridge.md` (manifest, bridge, wallpaper, IME), `recipes/persistence.md` (store, state modules, FL502), `recipes/layout.md` (patterns), `recipes/widgets.md` (home widget with live data, `net.fetch`, sizes, states), `recipes/tools.md` (tool app: `tools` manifest, handlers, limits).
- `references/runtime-api.md`: `html`, `render`, `repeat`, `dialog`, `confirm`, `prompt`, `toast`, `routes`.
- `references/motion.md`: morphs, sheets, toasts. `references/lint.md`: every FL rule (FL634 to FL636 for canvas and widget packages).
- `references/tokens.md`, `references/icons.md`: generated; never edit.
- Device: `skill.read name=deft-design/references/<file>`. Claude Code: `.claude/skills/deft-design/references/<file>`.
