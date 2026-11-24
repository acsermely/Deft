# Components

Every class the runtime ships in `components.css`, `base.css` and the type scale. Each line is one example. Compose these; do not restyle them. Modifiers are attributes or extra classes.

More classes in `references/components/screens.md`: read it for lists and cells (`.deft-group`, `.deft-cell`), cards, hero, empty and skeleton states, bars, pages and the tab bar, sheets, dialogs, toasts, alerts, inputs, component hook properties (`--deft-min`, `--deft-cols`...) and motion attributes.

## Layout (base.css)

- `.deft-app`: the page root. `<div class="deft-app">...</div>`
- `.deft-scroll`: the scrolling region. `<main class="deft-scroll">...</main>`
- `.deft-stack`: vertical stack with the standard gap. `<div class="deft-stack"><p class="deft-body">A</p><p class="deft-body">B</p></div>`
- `.deft-cluster`: wrapping row with gap. `<div class="deft-cluster"><button class="deft-btn">Save</button><button class="deft-btn deft-btn-ghost">Cancel</button></div>`
- `.deft-grid`: auto-fit grid of tiles; columns are at least `--deft-min` wide (default 12rem: one column on a phone, set `--deft-min: 8rem` in app.css for two). `<div class="deft-grid"><div class="deft-tile">...</div></div>`
- `.deft-sidebar`: side panel plus main, wraps on narrow screens. `<div class="deft-sidebar"><nav>...</nav><article>...</article></div>`
- `.deft-switcher`: row that becomes a column below `--deft-min`. `<div class="deft-switcher">...</div>`
- `.deft-center`: centred column with a measure. `<main class="deft-scroll deft-center">...</main>`
- `.deft-cover`: full-height column with the middle child centred (the non-scrolling instrument screen, see recipes). `<div class="deft-cover"><p class="deft-labelMono">Step 1</p><section>...</section><div>...</div></div>`
- `.deft-safe-top`, `.deft-safe-bottom`, `.deft-safe-inline`: safe-area padding. `<header class="deft-topbar deft-safe-top">`
- `.deft-truncate`: one line, ellipsis. `<span class="deft-truncate">Very long title</span>`
- `.deft-visually-hidden`: screen-reader text. `<span class="deft-visually-hidden">Opens in a sheet</span>`
- `[hidden]`: always display none, whatever class the element has (reset layer). `<div class="deft-stack" hidden>...</div>`
- `.hidden`: display none. `<div class="hidden">...</div>`

## Type (type classes, one per scale step)

- `.deft-largeTitle`: page title in a large-title page. `<h1 class="deft-largeTitle">Settings</h1>`
- `.deft-title1`: section hero or big heading. `<h2 class="deft-title1">Today</h2>`
- `.deft-title2`: card or hero title. `<h2 class="deft-title2">Trip plan</h2>`
- `.deft-title3`: dialog or sheet title. `<h2 class="deft-title3">Share</h2>`
- `.deft-headline`: row title, strong label. `<p class="deft-headline">Notifications</p>`
- `.deft-body`: default reading text. `<p class="deft-body">Edited today.</p>`
- `.deft-callout`: emphasised body. `<p class="deft-callout">Your storage is full.</p>`
- `.deft-subhead`: secondary line, row values. `<span class="deft-subhead">On</span>`
- `.deft-footnote`: small helper text. `<p class="deft-footnote">Changes apply at once.</p>`
- `.deft-caption`: smallest reading size. `<span class="deft-caption">3 of 4</span>`
- `.deft-mono`: Geist Mono at footnote size for ids, paths and logs; `data-wrap` adds pre-wrap. `<pre class="deft-mono" data-wrap>2026-11-20 ok</pre>`
- `.deft-labelMono`: uppercase micro-label, tracked. `<h2 class="deft-labelMono">Connections</h2>`

## Numerals and tiles

- `<deft-number value="128">`: Doto digits that flip when `value` changes. `<deft-number value="98"></deft-number>`
- `.deft-tile-value`: static Doto number, for tiles and steppers. `<span class="deft-tile-value">8412</span>`
- `.deft-tile`: glanceable card with a label, a number and an optional sparkline. `<div class="deft-tile"><span class="deft-labelMono">Steps</span><span class="deft-tile-value">8412</span></div>`
- `.deft-tile > svg`: sparkline inside a tile (stroke `label-2`). `<svg viewBox="0 0 100 32" preserveAspectRatio="none"><path d="M0 26 L50 12 L100 4" /></svg>`

## Icons

- `<deft-icon name="...">`: Lucide icon from the sprite, `currentColor`, `aria-hidden` by default. `<deft-icon name="share-2" aria-hidden="true"></deft-icon>`
- `.deft-icon`: an inline SVG icon with the same stroke rules. `<svg class="deft-icon" viewBox="0 0 24 24"><path d="M5 12h14" /></svg>` (prefer `<deft-icon>`)
- `.deft-tile-icon`: 2rem tinted square behind a row icon. `<span class="deft-tile-icon"><deft-icon name="wifi" aria-hidden="true"></deft-icon></span>`
- `.deft-chevron`: disclosure chevron, tertiary label. `<deft-icon class="deft-chevron" name="chevron-right" aria-hidden="true"></deft-icon>`

## Buttons

- `.deft-btn`: default secondary button (fill, label text). `<button class="deft-btn" type="button">Edit</button>`
- `.deft-btn[data-primary]` or `.deft-btn-primary`: the one accent action per screen. `<button class="deft-btn" data-primary type="button">Save</button>`
- `.deft-btn[data-tone="destructive"]`: secondary button with `--deft-error` label and icon. Never primary; keep text beside the icon. `<button class="deft-btn" data-tone="destructive" type="button"><deft-icon name="trash-2" aria-hidden="true"></deft-icon>Delete</button>`
- `.deft-btn-secondary`: explicit alias for the default. `<button class="deft-btn deft-btn-secondary" type="button">Edit</button>`
- `.deft-btn-ghost` (or `.deft-ghost`): no fill, label text. `<button class="deft-btn deft-btn-ghost" type="button">Cancel</button>`
- `.deft-btn-outline`: hairline ring, no fill. `<button class="deft-btn deft-btn-outline" type="button">Details</button>`
- `.deft-btn-soft`: quiet filled, for toolbars. `<button class="deft-btn deft-btn-soft" type="button">Copy</button>`
- `.deft-btn-link`: looks like a link, label colour, underlined. `<button class="deft-btn deft-btn-link" type="button">I have an account</button>`
- `.deft-btn-lg`: taller, for onboarding and sheets. `<button class="deft-btn deft-btn-lg" data-primary type="button">Get started</button>`
- `.deft-btn-sm`, `.deft-btn-xs`: dense sizes. Avoid on touch screens. `<button class="deft-btn deft-btn-sm" type="button">Undo</button>`
- `:disabled` or `aria-disabled="true"`: dimmed, no press. `<button class="deft-btn" type="button" disabled>Send</button>`
- `.deft-fab[data-float]`: pins the FAB bottom-trailing over the content (fixed, safe-area aware). Give the page bottom padding of `calc(var(--deft-tap) + 3 * var(--deft-space-m))` so the last row clears it. Without `data-float` the FAB is an inline button.
- `.deft-fab`: round icon button. Add `data-primary` for the accent. `<button class="deft-fab" data-primary type="button" aria-label="Add"><deft-icon name="plus" aria-hidden="true"></deft-icon></button>`

## Switch, checkbox, segmented, chip, stepper

- `.deft-switch` (or `.deft-toggle`): real styled switch, a checkbox with `role="switch"`. `<input class="deft-switch" type="checkbox" role="switch" checked aria-label="Wi-Fi" />`
- `.deft-checkbox`: checkbox tinted with the label colour. `<input class="deft-checkbox" type="checkbox" aria-label="Agree" />`
- `.deft-segmented`: capsule of mutually exclusive buttons. Selected = `aria-selected="true"` (or `aria-pressed`). `<div class="deft-segmented" role="tablist"><button role="tab" aria-selected="true">Day</button><button role="tab" aria-selected="false">Week</button></div>`
- `.deft-chip`: selectable pill. Selected = `aria-pressed="true"`. `<button class="deft-chip" type="button" aria-pressed="true">Shuffle</button>`
- `.deft-stepper`: fill capsule with − and + buttons around a value. `<div class="deft-stepper"><button type="button" aria-label="Decrease"><deft-icon name="minus" aria-hidden="true"></deft-icon></button><output class="deft-tile-value">3</output><button type="button" aria-label="Increase"><deft-icon name="plus" aria-hidden="true"></deft-icon></button></div>`

## Sliders and progress

- `.deft-slider`: range input, label-coloured fill. Set its position with `--deft-value` from JS if you need the fill to follow. `<input class="deft-slider" type="range" min="0" max="100" value="40" aria-label="Position" />`
- `.deft-slider[data-live]`: fill in the accent (recording, live signal). `<input class="deft-slider" type="range" data-live aria-label="Level" />`
- `.deft-progress`: thin bar. Fill width comes from `--deft-value` (set from JS). `<div class="deft-progress" role="progressbar" aria-label="Storage"></div>`
- `.deft-progress[data-live]`: accent fill. `<div class="deft-progress" data-live role="progressbar" aria-label="Recording"></div>`
- `.deft-meter`: gauge or HUD bar (health, level, signal). The child fills by `transform: scaleX()` from the start edge, so it is cheap to update every frame and eases with the snappy spring. Set `--deft-level` (0 to 1) on the meter from JS: `meter.style.setProperty('--deft-level', hp / 100)`. Tone: `data-tone="success|warning|error"` (default label colour; use the tone to say what the bar means, such as `success` for your health and `error` for the enemy's), `data-live` for accent. Put `role="meter"` with `aria-valuenow` and a label on it. `<div class="deft-meter" data-tone="success" role="meter" aria-label="Health" aria-valuemin="0" aria-valuemax="100" aria-valuenow="80"><div></div></div>`. Do not animate `width` or `inline-size` for a bar.
- `.deft-progress[data-segments]`: stepped bar. The count comes from `data-segs`, which JS copies to `--deft-segs` (as the gallery does). `<div class="deft-progress" data-segments role="progressbar" aria-label="Setup"></div>`
