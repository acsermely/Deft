# Recipes: layout and component patterns

## Lists filled after load

`data-deft-enter` plays once per container, the first time it is visible. A list that is empty at load and filled after an `await` has nothing to animate. Two options:

- Build the list into a new container that has `data-deft-enter="rise"` and swap it in (`old.replaceWith(next)`). The runtime sees the new container and plays the enter.
- Or fill the existing container, then call `deft.enter(box)` (from `motion.js`; `deft.enter(box, 'scale')` also sets the kind). It plays the stagger now, even if it already ran. Use it for the first fill, not for every row of a growing feed.

Search and filter re-render the same list on every keystroke: do not call `deft.enter` there, and do not rebuild the container (a new container replays the enter). Fill it with `box.replaceChildren(...)` (or `render`) and call `deft.enter(box)` once, after the first population. A later reload from the data source (a new message arrived) may stay silent too.

## Media grid

`<div class="deft-media-grid">` holds square tiles (`img`, `video`, `figure`, `button`). Tiles crop with `object-fit: cover` and their radius is concentric with the grid. Three columns by default; `--deft-cols` is not an inline style, so set it in your `app.css` layer: `.photos { --deft-cols: 4; }`. A `figcaption` inside a tile sits on a bottom scrim. Use `.deft-grid` for tiles of different content (cards, stats): its columns are at least `--deft-min` wide (default 12rem, so one column on a phone); lower it in `app.css` with `.stats { --deft-min: 8rem; }`.

## Hero with a small square cover

`.deft-hero` stretches its first `<img>` over the whole card (`object-fit: cover`) and lays a scrim over it. A 128 px square cover therefore upscales about 2.5 times on a phone and looks soft (fine for a flat graphic, poor for a photo). Choices: give the hero art at least the screen width (about 800 px), or show the small cover as itself: a `.deft-cluster` holding the `<img width="128" height="128">` (round it with `border-radius: var(--deft-radius-lg)` in `app.css`) beside a `.deft-stack` of title and status text, with no hero.

## Small recipes

- `[hidden]` always wins: the reset layer sets `[hidden] { display: none !important }`, so `el.hidden = true` works on any component class (`.deft-stack`, `.deft-cell`...). Use it; do not toggle `.hidden` or write your own display rule.
- Mono text: `.deft-mono` is Geist Mono at footnote size for ids, paths and logs. Add `data-wrap` for `pre-wrap` (multi-line logs). `.deft-labelMono` stays for uppercase micro-labels.
- Alert tone and icon from JS: `box.dataset.tone = 'error'; box.querySelector('deft-icon').setAttribute('name', 'circle-alert'); box.querySelector('span').textContent = msg;`. Tones: `error`, `warning`, `success`, `info`. Pair them with `circle-alert`, `alert-triangle`, `circle-check`, `info`.
- Destructive secondary action: `<button class="deft-btn" data-tone="destructive" type="button"><deft-icon name="trash-2" aria-hidden="true"></deft-icon>Delete</button>`. Secondary fill, label and icon in `--deft-error`; never `data-primary`. Always text next to the icon.
- Accent in SVG and canvas drawings, one rule: use the accent only for the drawing that is the screen's one live signal, and mark that drawing or its container `data-live` (set it while the signal is live, remove it when not). In CSS the accent goes in a `[data-live]` rule: `.map[data-live] .here { fill: var(--deft-accent); }`; everything else uses `--deft-label`. In canvas JS read `getComputedStyle(el).getPropertyValue('--deft-accent')` only when `el.closest('[data-live]')` is set, otherwise `--deft-label`. Never use the accent for a second element or beside a primary button. FL616 checks both: CSS outside the allowed selectors, and JS that reads `--deft-accent` in a file with no `data-live`.

## Instrument screen that does not scroll

A compass, level, timer or meter shows one live thing in the middle. Use `.deft-cover` (full-height column; its second child is centred in the free space) inside `.deft-scroll`, with the large title as the first child, the instrument second and the readout or controls last. It still scrolls if a large font needs more room. `.deft-cover` is `100dvh` tall and `.deft-app` already pads the safe areas, so trim it in `app.css`: `.deft-cover { min-block-size: calc(100dvh - env(safe-area-inset-top) - env(safe-area-inset-bottom)); }`. Size the instrument with `width: min(86vw, 22.5rem); aspect-ratio: 1`, centre it with `margin-inline: auto`, and put `data-live` on it while its signal is live (see the accent rule below).

```html
<main class="deft-scroll">
  <div class="deft-cover">
    <h1 class="deft-largeTitle">Compass</h1>
    <div class="dial" id="dial" data-live>...</div>
    <div class="deft-group">...readout cells...</div>
  </div>
</main>
```

## Rows that hold controls

`.deft-cell` takes a trailing control. Make the row a `<div class="deft-cell">` (or a `<label class="deft-cell">` when the whole row toggles), never `a.deft-cell` or `button.deft-cell`, which are the tappable-row forms. Order: leading `.deft-tile-icon`, `.deft-cell-body` (title and sub), then the control.

```html
<div class="deft-group">
  <label class="deft-cell">
    <span class="deft-cell-body"><span class="deft-body deft-cell-title">Wi-Fi only</span></span>
    <input class="deft-switch" type="checkbox" role="switch" aria-label="Wi-Fi only" />
  </label>
  <div class="deft-cell">
    <span class="deft-cell-body"><span class="deft-body deft-cell-title">Volume</span></span>
    <input class="deft-slider" type="range" aria-label="Volume" />
  </div>
</div>
```

A row with several actions (Open, Clear, a reply field) puts them under the text inside `.deft-cell-body`, in a `.deft-cluster`; the row itself is not tappable.

## Lists: one rule

- Rows the user reads or taps, or settings: `.deft-group` holding `.deft-cell`. This is the default for any list of items.
- A tappable row: `<a class="deft-cell" href>` for navigation, `<button class="deft-cell" type="button">` for an action. `button.deft-cell` is reset by the runtime (full width, no border, transparent, start-aligned): add `data-deft-press` for the press scale and haptic, and a trailing `<deft-icon name="chevron-right">` if it navigates.
- `.deft-row`: the same row without the inset card, only when the list sits directly on the page background (a long feed, a chat).
- `.deft-list` is deprecated. It is plain text with hairlines and is only for non-interactive content such as a bullet list inside a card or a cover. For anything the user selects, use `.deft-group`.
- An empty group renders as a thin bar. Hide it until filled: `#rows:empty { display: none; }` in `@layer deft.components`.

## `<deft-number>` with non-digit text

`<deft-number value="...">` renders every character of the value as its own Doto cell, so `-`, `.`, `:` and `%` work: `value="-"` shows a dash for an empty state. Letters render too but Doto is a numeral face: use `.deft-body` for words. Changing `value` to a string of the same length flips only the changed cells; a different length redraws the whole number. Update it with `el.setAttribute('value', String(n))`, not `textContent`. The element sets its own `aria-label` to the value.

## Spacing

`--deft-space-3xs` to `--deft-space-2xl` (fluid steps) and `--deft-tap` (48px) are in `references/tokens.md`. Prefer layout classes (`.deft-stack`, `.deft-cluster`, `.deft-grid`) over spacing tokens; use a token only for a gap those classes do not cover.

## Single-screen apps: topbar or not

One rule: no top bar. A single screen uses `.deft-page[data-large-title]` with an `<h1 class="deft-largeTitle">`, inside `<main class="deft-scroll">`. The large title collapses on scroll by itself. Add a `.deft-toolbar` only when the screen has actions that must stay visible while scrolling (see compose-form for an acting-on-content primary). Use a top bar for a drill-down that needs a back button.

## Several equal actions: which one is primary

`data-primary` goes on the action the user most likely wants next on this screen, and it must be one only (FL630). Decide in order:

1. A flow with a natural next step (Scan, then results): the step that starts it.
2. A set of peer tools where no one is the likely next move (Apps / Used today / Device id): pick the one that is safest and most informative to run first, or put none. All secondary `.deft-btn` is a valid screen. Do not use the accent just to fill a slot.
3. Destructive actions are never primary. Cancel is `.deft-btn-ghost`.

## A sheet that Back closes (compose, pick, edit)

In the launcher, Back goes through `back()` handlers, not browser history, so a sheet needs no `pushState`. Register one handler that closes the sheet and consumes Back; then Back leaves the app only when nothing is open:

```js
import { back } from '/_deft/v1/app.js';
const sheet = document.getElementById('compose');   // <dialog class="deft-sheet" data-detents="large">
const open = () => sheet.showModal();
back(() => (sheet.open ? (sheet.close(), true) : false));
```

A two-level flow (list, thread, compose over the thread) stacks handlers: each screen registers its own `back()` when it appears and calls `off()` when it goes. For state that must survive reload or be linkable, make the sheet a route instead: `routes({ '/': list, '/compose': composeView }, root)` and `go('/compose')` to open it; `routes()` already sends Back from any other route to `/`. Do not mix hand-written `history.pushState` with `routes()`, which owns the hash. `Esc` or a backdrop tap closes a `<dialog>` without your handler, so keep derived state (an open flag) in the dialog's `open` property.

## Full-screen overlay or lobby over a canvas

A game lobby, pairing screen or calibration step sits over the scene. Make it `dialog.deft-dialog.deft-fullscreen` with a `.deft-stack` child, open it with `showModal()` and close it with `close()`; it already pads the safe areas, traps focus and needs no `z-index`. HUD and controls that stay on screen during play are fixed siblings of the canvas: pad them with `env(safe-area-inset-*)` (FL605) and keep the canvas under them. For a non-modal full-height page (an instrument, a stepper) use `.deft-cover`. A hand-built `position: fixed; inset: 0` overlay works if you pad it with the insets and toggle it with the `hidden` attribute, not `style.display`.

## Gauges and HUD bars

Use `.deft-meter`, not a `div` whose `width` you animate. JS sets one number, `meter.style.setProperty('--deft-level', value / max)`, and the child scales from the start edge on the compositor. Give the bar its meaning with `data-tone` (`success` for your health, `error` for the opponent's or for danger, `warning` for a limited resource) and keep the text value beside it, since colour alone is not enough. Over a canvas, place meters in a fixed `#hud` padded by the safe-area insets.
