# Structure, type, icons, motion

Detail moved out of SKILL.md. Read the part you need.

## 2. Files and link order

Every app page links the runtime in this order. Lint (FL603) rejects any other order.

```html
<link rel="stylesheet" href="/_deft/v1/base.css" />
<link rel="stylesheet" href="/_deft/v1/tokens.css" />
<link rel="stylesheet" href="/_deft/v1/components.css" />
<link rel="stylesheet" href="/_deft/v1/theme.css" />
<link rel="stylesheet" href="./app.css" />  <!-- optional, last -->
```

- The page wraps everything in `<div class="deft-app">`. Scrolling content goes in `<main class="deft-scroll">`.
- `main.js` is a `//` header comment, then `import '/_deft/v1/motion.js';` (FL503 wants the comment first). The import enables enter stagger, haptics, sheets, `<deft-number>`, `<deft-icon>` and `deft.toast`. Keep your own code below it under 80 lines (FL502); split into modules beyond that (example in `references/recipes/persistence.md`).
- `manifest.json`: example in `references/recipes/bridge.md`. Templates are single pages: copy them to `index.html`.
- `<html lang>` and `<meta viewport ... viewport-fit=cover>` are required.

## 3. Screen structure

- **Tab bar** (`.deft-tabbar`) only for 2 to 5 peer sections. One section or a drill-down flow: no tab bar. Use a `.deft-page` with a large title and, if it needs actions, a `.deft-toolbar`.
- **Single screen: no top bar.** `.deft-page[data-large-title]` with an `<h1 class="deft-largeTitle">` is the title. A toolbar only for actions that must stay visible while scrolling.
- **Lists: `.deft-group` holding `.deft-cell` rows.** Tappable rows are `a.deft-cell[href]` or `button.deft-cell`. `.deft-list` is deprecated. Detail in `references/recipes/layout.md`.
- **Several equal actions:** one `data-primary` on the likely next step, or none. Never to fill a slot. Rule in `references/recipes/layout.md`.
- **IME keyboard pages** are not app screens: no `.deft-app`, full-width flex rows, keys may be under 48px, `<html data-deft-haptic="off">`. See `references/recipes/bridge.md`.
- **Primary action placement.** Creating an item in a list or canvas (add note, new event): `.deft-fab[data-primary]`, bottom trailing. Acting on the content on screen (send, save, done): `data-primary` button in the top bar (compose form) or the toolbar. Never both a FAB and a toolbar primary on one screen.
- **Sheets and dialogs are their own screen.** Each `<dialog>` may hold its own single `data-primary`, separate from the page behind it (FL630 counts per dialog).
- **Persistence.** Use `store()`, never raw `localStorage` (FL403): `import { store } from '/_deft/v1/store.js'; const notes = store('notes', [], { version: 1 });`. It returns a signal: read and assign `notes.value` (assign a new array or object, do not mutate). It saves 250 ms after a change and loads on start. Bump `version` and pass `migrate(old, v)` when the shape changes. Existing data under an old raw key: read it as the initial value, see `references/recipes/persistence.md`. Store and bridge APIs: `skill.read name=runtime` and `name=js-bridge` (they are part of your harness context). Modules share state through one `state.js` that exports the store. Data that only grows (tracks, logs, long histories) does not suit one store value: use chunked keys, or keep IndexedDB or a capped raw `localStorage` as a documented exception (see `references/recipes/persistence.md`). A guarded `try { localStorage.getItem(...) }` read of a legacy key is not flagged.

## 5. Type

Use the type classes. Each one sets family, size, leading, weight and tracking together.

- `.deft-largeTitle` (page title), `.deft-title1`, `.deft-title2`, `.deft-title3`
- `.deft-headline`, `.deft-body`, `.deft-callout`, `.deft-subhead`
- `.deft-footnote`, `.deft-caption` (smallest reading size)
- `.deft-labelMono`: uppercase micro-labels only (section labels, tile labels). Never for sentences.
- `.deft-mono`: ids, paths and logs in Geist Mono (`data-wrap` for multi-line).

Numerals use Doto: `<deft-number value="128">` (flips digits when `value` changes; any text works, `-` for empty; details in `references/recipes/layout.md`) or `.deft-tile-value` for a static big number. Never set a font-family literal (FL611).

## 6. Icons

Use `<deft-icon name="chevron-right" aria-hidden="true"></deft-icon>`. Names come from the Lucide sprite (`chevron-right`, `arrow-left`, `plus`, `minus`, `x`, `check`, `search`, `settings`, `trash-2`, `delete` for backspace, `arrow-big-up` for shift, ...). The full list is `references/icons.md`; a name outside it draws nothing. Give a button with only an icon an `aria-label`.

Never use `‹ › ✕ ☰ + × ← →` as icon content (FL618). Use an icon.

## 7. Motion (behaviours, not keyframes)

- `data-deft-enter="rise|scale|fade"` on a group: children rise in once, 45 ms apart, when scrolled into view. Children added later to a group that already entered appear without animation: swap in a new container, or call `deft.enter(box)` (see `references/motion.md`).
- `data-deft-morph="id"` on two elements: they morph across a navigation (View Transition). Wrap the change in `morph(fn)` from `/_deft/v1/route.js`.
- `dialog.deft-sheet[data-detents="medium large"]`: a bottom sheet with drag detents. Open it with `showModal()`.
- `<deft-number value="...">`: changing `value` flips the digits.
- `deft.toast(text, { tone, ms, action })`: one toast at a time. `tone` is `success` or `error`.
- `deft.haptic(kind)`: `light`, `medium`, `selection`, `success`, `warning`. Silent outside the launcher. Presses on `.deft-btn`, `.deft-cell[href]`, `.deft-row[href]`, `.deft-fab` and `[data-deft-press]` already fire `light`.
- Springs and durations come from tokens only: `--deft-spring-snappy|smooth|bouncy` and their `-duration` pairs, `--deft-duration-xs|s|m|l`, `--deft-ease`, `--deft-ease-enter`, `--deft-ease-exit`.

Reduced motion is handled by the runtime. Do not add `prefers-reduced-motion` rules of your own.
