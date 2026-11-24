# Components: lists, cards, bars, sheets, inputs, hooks

## Lists, cells and groups

- `.deft-group`: grouped inset card that holds `.deft-cell` rows. `<div class="deft-group" data-deft-enter="rise">...</div>`
- `.deft-cell`: a row with leading tile, title and subtitle, trailing value, chevron or switch. `<div class="deft-cell"><span class="deft-cell-body"><span class="deft-body deft-cell-title">Storage</span></span><span class="deft-subhead deft-cell-value">12 GB</span></div>`
- `a.deft-cell[href]`: tappable row, press scale and haptic. `<a class="deft-cell" href="#wifi">...</a>`
- `button.deft-cell`: tappable row that runs an action (reset: full width, no border, transparent, start-aligned). `<button class="deft-cell" type="button" data-deft-press>...</button>`
- `.deft-cell-body`: title and subtitle column. `<span class="deft-cell-body"><span class="deft-body deft-cell-title">Wi-Fi</span><span class="deft-footnote deft-cell-sub">Home</span></span>`
- `.deft-cell-title`, `.deft-cell-sub`, `.deft-cell-value`: row text parts. `<span class="deft-subhead deft-cell-value">On</span>`
- `.deft-cell` with a trailing control (`.deft-switch`, `.deft-slider`, `.deft-stepper`, a button): use `<div class="deft-cell">` or `<label class="deft-cell">`, not `a` or `button`. `<div class="deft-cell"><span class="deft-cell-body"><span class="deft-body deft-cell-title">Wi-Fi only</span></span><input class="deft-switch" type="checkbox" role="switch" aria-label="Wi-Fi only" /></div>`
- `.deft-row`, `.deft-row[href]`: the same row outside a group (no inset card). `<a class="deft-row" href="#item">...</a>`
- `.deft-list` (deprecated; use `.deft-group` and `.deft-cell`): plain list with hairline separators. `<ul class="deft-list"><li class="deft-cluster">...</li></ul>`
- `.deft-menu`: popover menu of tappable rows. `<div class="deft-menu" popover><button>Rename</button></div>`

## Cards, hero, empty and skeleton

- `.deft-card`: grouped surface with padding and an inner radius. `<article class="deft-card"><h2 class="deft-title2">Title</h2><p class="deft-body">Body</p></article>`
- `.deft-card-body`: padded body inside a card. `<div class="deft-card-body">...</div>`
- `.deft-card-actions`: wrapping button row at the bottom of a card. `<div class="deft-card-actions"><button class="deft-btn" data-primary type="button">Save</button></div>`
- `.deft-hero`: media header with a scrim; text sits on the scrim. Put an `<img alt="...">` as the first child for art. `<div class="deft-hero"><div class="deft-hero-body"><span class="deft-labelMono">Now playing</span><h1 class="deft-title1">Night Drive</h1></div></div>`
- `.deft-media-grid`: square tiles, `object-fit: cover`, radius concentric with the grid, 3 columns (`--deft-cols` in app.css). `<div class="deft-media-grid"><figure><img alt="" src="a.jpg"><figcaption class="deft-caption">a.jpg</figcaption></figure></div>`
- `.deft-hero-body`: the text block inside a hero. `<div class="deft-hero-body">...</div>`
- `.deft-empty`: icon, title, body and an action when there is nothing to show. `<div class="deft-empty"><deft-icon name="calendar" aria-hidden="true"></deft-icon><h3 class="deft-title3">Nothing here yet</h3><p class="deft-subhead">Add an event to see it here.</p><button class="deft-btn" data-primary type="button">Add event</button></div>`
- `.deft-skeleton`: loading placeholder with a shimmer. `<div class="deft-skeleton"></div>`
- `.deft-badge`: small pill, or a dot when empty. Accent. `<span class="deft-badge">New</span>` or `<span class="deft-badge" aria-label="unread"></span>`

## Bars, pages and the tab bar

- `.deft-page`: a screen with its own padding and background. `<section class="deft-page" data-large-title><h1 class="deft-largeTitle">Notes</h1>...</section>`
- `.deft-page[data-large-title]`: the title collapses on scroll where the browser supports scroll timelines. `<section class="deft-page" data-large-title>...</section>`
- `.deft-topbar`: sticky top bar with glass material and safe-area padding. `<header class="deft-topbar"><button class="deft-btn deft-btn-ghost" aria-label="Back">...</button><h1 class="deft-headline">Title</h1></header>`
- `.deft-appbar`: same material as `.deft-topbar` for an app bar. `<header class="deft-appbar">...</header>`
- `.deft-bar`: sticky glass bar inside a page. `<header class="deft-bar"><span class="deft-headline">Inbox</span></header>`
- `.deft-toolbar`: sticky glass toolbar, usually at the bottom of a screen. `<div class="deft-toolbar">...</div>`
- `.deft-tabbar`: floating capsule tab bar, sticky at the bottom. Children may be links or buttons: put `aria-current="page"` (or `aria-selected="true"`) on the current one; a `<button>` tab switching in-page sections needs no extra CSS. With `.deft-app > .deft-scroll` as the sibling above it, the bar sits at the bottom of a short screen. The dot under it is the accent. `<nav class="deft-tabbar" aria-label="Sections"><a href="#home" aria-current="page"><deft-icon name="house" aria-hidden="true"></deft-icon><span>Home</span></a></nav>`
- `.deft-tabs`, `.deft-tab`: underline tabs. Selected = `aria-selected="true"`. `<div class="deft-tabs" role="tablist"><button class="deft-tab" role="tab" aria-selected="true">Today</button></div>`

## Sheets, dialogs, toasts

- `dialog.deft-dialog`: centred elevated dialog. Open with `showModal()`. Close with a `data-close` button. `<dialog class="deft-dialog" aria-labelledby="t"><h2 class="deft-title3" id="t">Delete?</h2><div class="deft-card-actions"><button class="deft-btn" type="button" data-close>Cancel</button></div></dialog>`
- `dialog.deft-dialog.deft-fullscreen`: a full-screen modal such as a call screen or takeover. Edge to edge, safe-area padding, centred `.deft-stack` child; open with `showModal()` (this also gives focus containment and safe-area padding for a game lobby, pairing screen or takeover that sits over a canvas: a hand-made `position: fixed; inset: 0` overlay has neither); close it with `close()`, not `hidden`; prevent Esc with a `cancel` listener (`ev.preventDefault()`) when it must stay up. `<dialog class="deft-dialog deft-fullscreen" aria-labelledby="who"><div class="deft-stack"><h2 class="deft-title1" id="who">Ada</h2><div class="deft-cluster"><button class="deft-btn" data-primary type="button">Answer</button></div></div></dialog>`
- `.deft-keypad`: 3-column grid of round digit keys (Geist numerals, `--deft-radius-full`), buttons as direct children. Give each key text or an `aria-label`. `<div class="deft-keypad"><button type="button">1</button><button type="button">2</button><button type="button">3</button></div>`
- `deft.confirm(message, { destructive, ok })` and `deft.prompt(label, { value })`: promise helpers for a yes/no and a one-line text input; no markup needed. `await deft.confirm('Delete Ada?', { destructive: true, ok: 'Delete' })`. Ask before every destructive action. Signatures in `references/runtime-api.md`.
- `dialog.deft-sheet`: bottom sheet with a grabber added automatically. Add `data-detents="medium large"` for drag detents; one value (`data-detents="large"`) opens at that height without snapping (use it for forms, so the fields are not squeezed into half the screen). `<dialog class="deft-sheet" data-detents="medium large" aria-labelledby="s">...</dialog>`
- `dialog.deft-sheet[data-detent=large]`: set by the runtime; opaque at the large detent. Do not set it yourself.
- `[popover]` with `.deft-toast`: a toast. `deft.toast()` builds these for you. `<div class="deft-toast" popover>Saved</div>`
- `.deft-toast[data-tone="error"]`: error toast; the runtime adds a `!` mark. Use `deft.toast(text, { tone: "error" })`.
- `.deft-toast` with `data-tone`: `success`, `error` or none.

## Alerts and status

- `.deft-alert[data-tone="error"|"warning"|"success"|"info"]`: inline message with a tinted icon. Always include an icon and text. `<div class="deft-alert" data-tone="error" role="alert"><deft-icon name="circle-alert" aria-hidden="true"></deft-icon><span class="deft-body">Could not connect</span></div>`

## Inputs

- `.deft-input`: one-line field. Give it a `<label>` or `aria-label`. `<label class="deft-subhead" for="to">To</label><input id="to" class="deft-input" type="email" />`
- `.deft-select`: select menu. `<select class="deft-select" aria-label="Repeat"><option>Never</option></select>`
- `.deft-textarea`: multi-line field, resizes vertically. `<textarea class="deft-textarea" rows="4" aria-label="Notes"></textarea>`

## Component hook properties

Set these in your `app.css` layer on a component or its container; lint (FL614) accepts them. Any other `--deft-*` name is a token and must not be redefined.

- `--deft-min`: smallest column of `.deft-grid`, the width at which `.deft-switcher` stacks, the main-pane width of `.deft-sidebar`. `.stats { --deft-min: 8rem; }`
- `--deft-gap`, `--deft-density`: gap of `.deft-stack`, `.deft-cluster`, `.deft-grid`, `.deft-cover`; density scales type and gaps.
- `--deft-side`, `--deft-measure`: `.deft-sidebar` side width, `.deft-center` maximum width.
- `--deft-cols`: columns of `.deft-media-grid`.
- `--deft-pad`, `--deft-radius-outer`: padding and outer radius of `.deft-card`, `.deft-group`, `.deft-media-grid`; the inner radius follows (concentric).
- `--deft-value`: fill of `.deft-slider` and `.deft-progress` (a percentage, from JS). `--deft-segs`: segment count of `.deft-progress[data-segments]`.
- `--deft-level`: fill of `.deft-meter`, unitless 0 to 1 (from JS).

## Motion hooks (attributes, see motion.md)

- `data-deft-enter="rise|scale|fade"`: staged enter for the children of a group.
- `data-deft-press`: adds press scale and a light haptic to any element. `<button class="deft-btn" data-deft-press type="button">...</button>`
- `data-deft-morph="id"`: pairs two elements across a view transition.
- `data-live`: accent on slider, progress or tab bar signals (recording, live).
- `data-large-title`: on `.deft-page`, collapses the title on scroll.
- `data-primary`: marks the one accent button.
- `data-detents`: sheet detents, one or two of `medium large`. One value pins the sheet to that height.
- `data-segments`, `data-segs`: stepped progress.

## Focus, links and text

- Links (`a`) are label-coloured and underlined automatically. Do not restyle them.
- Focus ring: 2px `--deft-label`, automatic on `:focus-visible`. Do not remove it.
