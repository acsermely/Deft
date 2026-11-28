# Motion

Motion is declared, not hand-written. Apps get behaviours from `motion.js` (imported by one line in `main.js`) and springs from tokens. Raw `@keyframes`, `cubic-bezier()`, `steps()` and millisecond literals are lint errors (FL627, FL628, FL631).

Setup, once per page:

```js
// main.js
import '/_deft/v1/motion.js';
```

`motion.js` defines `deft.haptic` and `deft.toast`, starts the sheets and registers `<deft-number>` and `<deft-icon>`. It is idempotent, so importing it twice is safe.

## Tokens

| Token | Use |
|---|---|
| `--deft-spring-snappy`, `--deft-spring-snappy-duration` | Press, switch, tap feedback (280 ms) |
| `--deft-spring-smooth`, `--deft-spring-smooth-duration` | Progress, sheet snap, default enter (560 ms) |
| `--deft-spring-bouncy`, `--deft-spring-bouncy-duration` | Playful reveals, sparingly (660 ms) |
| `--deft-duration-xs` (100 ms), `-s` (120 ms), `-m` (220 ms), `-l` (320 ms) | Colour and opacity fades |
| `--deft-ease`, `--deft-ease-enter`, `--deft-ease-exit` | Easing for those fades |
| `--deft-step-dot` | Stepped reveals only: numbers and segmented progress |

Use them in app CSS like this, inside a layer:

```css
@layer deft.components {
  .note-row { transition: background-color var(--deft-duration-s) var(--deft-ease); }
  .note-row:active { transform: scale(.98); transition: transform var(--deft-spring-snappy-duration) var(--deft-spring-snappy); }
}
```

## Enter stagger: `data-deft-enter`

Put the attribute on a container. Its direct children rise in once, the first time the container scrolls into view (IntersectionObserver, 10% visible). Each child starts 45 ms after the previous one; the stagger caps at 10 children.

| Value | Motion |
|---|---|
| `rise` | Fades in and moves up 12 px |
| `scale` | Fades in and grows from 94% |
| `fade` | Opacity only |

```html
<div class="deft-group" data-deft-enter="rise">
  <a class="deft-cell" href="#a">...</a>
  <a class="deft-cell" href="#b">...</a>
</div>
```

Containers inserted into the page later (a re-render, a route change) are picked up by a MutationObserver and enter when they scroll into view. A container enters once: children appended to it afterwards appear at once with no animation, which is right for a long list that grows. To animate a rebuilt list, replace the whole container element instead of its children. For a list filled after an `await`, fill the container and call `deft.enter(box)` (or `deft.enter(box, 'scale')`): it plays the stagger now, even if the container already entered.

Before the enter runs the children are hidden (`.deft-motion [data-deft-enter]:not(.deft-in)`). Under reduced motion the enter becomes a 200 ms opacity cross-fade with no delay. Do not add your own enter animation.

## Press feedback and haptics

- The WebView's square blue tap highlight is off for the whole page (base.css). Do not turn it back on.
- Every runtime control (`.deft-btn`, `.deft-fab`, `.deft-chip`, `.deft-segmented`, `.deft-tabbar` and `.deft-menu` items, `.deft-tab`, `.deft-stepper` and `.deft-keypad` buttons, `.deft-cell[href]`, `.deft-row[href]`, `button.deft-cell`) and `[data-deft-press]` show a press layer: the control's own text colour at 12% (`--deft-press`) over its background, clipped to its radius. It shows at once on press and fades out on release.
- `.deft-btn`, `.deft-fab`, `.deft-cell[href]`, `.deft-row[href]`, `.deft-stepper > button` and `[data-deft-press]` also scale to 96% while pressed.
- For a custom tappable element, add `data-deft-press` and give it a `border-radius`. Set its colour with `background-color`, not the `background` shorthand: the shorthand removes the press layer.
- A `<button>` or `<a>` with no class dims to 60% while pressed.
- A press on any of those fires `deft.haptic('light')` through the native bridge. No permission is needed.
- `deft.haptic(kind)` is rate-limited to one call per 40 ms. The kinds are `light`, `medium`, `selection`, `success` and `warning`. Use `selection` for a choice changing, `success` and `warning` for outcomes.
- Outside the launcher `deft.haptic` is silent, so it is always safe to call.
- `<html data-deft-haptic="off">` silences every haptic on the page (press and `deft.haptic`). For IME key pages, where Android handles keypress feedback.

```js
deft.haptic('success');
```

## Sheets with detents: `dialog.deft-sheet`

A sheet is a `<dialog>`. It opens with `showModal()` and closes with `close()` or a `data-close` button. Add `data-detents` to get drag detents. `motion.js` adds a grabber (`.deft-grab`) automatically.

```html
<dialog class="deft-sheet" data-detents="medium large" aria-labelledby="share-title">
  <h2 class="deft-title3" id="share-title">Share</h2>
  <p class="deft-body">Choose where to send the note.</p>
  <div class="deft-card-actions"><button class="deft-btn" data-primary type="button" data-close>Done</button></div>
</dialog>
```

```js
document.getElementById('share').addEventListener('click', () => document.querySelector('dialog.deft-sheet').showModal());
```

- Detents: `medium` is 50% of the viewport height and uses glass. `large` is 92% and opaque. `data-detents` takes one or two names. A single value (`data-detents="large"`) opens at that height and is not draggable between detents (the sheet can still be flung down to dismiss). Use `large` alone for forms: at `medium` with the keyboard open, the fields and Save are hidden.
- Drag from the grabber area (the top 40 px). Snap goes to the nearest detent, or dismisses on a fast downward fling.
- The runtime sets `data-detent` (the current detent) and `--deft-sheet-h`. Do not set them yourself.
- A detent change fires `haptic('selection')`.
- A plain `dialog.deft-dialog` (no detents) is a centred modal. It fades and scales in. Use it for confirmations.

## Numbers: `<deft-number>`

`<deft-number value="...">` renders each character as a Doto digit. Changing `value` flips only the digits that changed, stepped (`--deft-step-dot`). Set the attribute from JS:

```html
<deft-number id="steps" value="8412"></deft-number>
```

```js
document.getElementById('steps').setAttribute('value', String(next));
```

It keeps the whole string readable through `aria-label`. Use it for a value that changes while the user watches (a counter, a timer, a battery level). For a static big number use `.deft-tile-value` instead.

## Icons: `<deft-icon>`

`<deft-icon name="share-2" aria-hidden="true"></deft-icon>` draws one Lucide icon from `/_deft/v1/icons.svg` in `currentColor`. Size it with a class on the element (1.25rem by default, tinted with `color`). Give an icon-only button an `aria-label`; the icon itself stays `aria-hidden`.

## Morphs across a navigation: `data-deft-morph`

Two elements with the same `data-deft-morph="id"` are paired across a View Transition, so the compact card grows into the expanded one. The swap goes through `morph()` from `/_deft/v1/route.js`:

```js
import { morph } from '/_deft/v1/route.js';

document.getElementById('expand').addEventListener('click', () => morph(() => {
  compact.hidden = true;
  expanded.hidden = false;
}));
```

```html
<div class="deft-card" id="compact" data-deft-morph="hero">...</div>
<div class="deft-card" id="expanded" data-deft-morph="hero" hidden>...</div>
```

Only elements with `data-deft-morph` move; everything else cross-fades, except `.deft-tabbar`, which stays still (one tab bar per page). Under reduced motion only the root cross-fade remains. Browsers without View Transitions just run the change.

## Toasts: `deft.toast`

```js
deft.toast('Saved', { tone: 'success' });
deft.toast('Could not connect', { tone: 'error', ms: 5000 });
deft.toast('Note deleted', { action: { label: 'Undo', run: () => restore() } });
```

- One toast shows at a time; later ones queue behind it.
- `tone` is `success` or `error`. An error toast gets a `!` mark from the runtime, so it is never colour alone.
- `ms` is how long it stays (default 3200). `action` adds one button.
- The toast is a `popover`. It does not block input.

## Large titles

`.deft-page[data-large-title]` collapses its first child (the title) as the page scrolls, when the browser supports scroll-driven animations. Where it does not, the title just stays put. Nothing to wire up.

## Reduced motion and transparency

- `prefers-reduced-motion: reduce`: enters become a short cross-fade, morphs keep only the root fade, transitions are switched off by the reset.
- The "Reduce transparency" setting, or `prefers-reduced-transparency`, swaps glass for the opaque material. Do not check it in your code; the tokens already switch.

## Things to avoid

- `@keyframes` in app CSS. Use `data-deft-enter`, a spring token, or a View Transition.
- `transition: opacity 200ms ease`. Use `var(--deft-duration-s) var(--deft-ease)`.
- Animating layout properties (width, height, top) in app code. Animate `transform` and `opacity`.
- Long or looping motion on content. Motion marks a change; it does not decorate.
- An `onclick` attribute to start an animation. Use `addEventListener` in `main.js`.
