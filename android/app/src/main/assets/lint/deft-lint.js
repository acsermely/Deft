// ../deft-design/icon-list.js
var ICONS = [
  "chevron-left",
  "chevron-right",
  "chevron-up",
  "chevron-down",
  "chevrons-up-down",
  "arrow-left",
  "arrow-right",
  "arrow-up",
  "arrow-down",
  "arrow-up-down",
  "plus",
  "minus",
  "x",
  "check",
  "check-check",
  "search",
  "settings",
  "sliders-horizontal",
  "filter",
  "menu",
  "list",
  "layout-grid",
  "ellipsis",
  "ellipsis-vertical",
  "phone",
  "phone-call",
  "phone-missed",
  "phone-incoming",
  "phone-outgoing",
  "message-circle",
  "message-square",
  "mail",
  "calendar",
  "calendar-days",
  "clock",
  "timer",
  "alarm-clock",
  "history",
  "camera",
  "image",
  "images",
  "video",
  "play",
  "pause",
  "square",
  "skip-back",
  "skip-forward",
  "rewind",
  "fast-forward",
  "volume-1",
  "volume-2",
  "volume-x",
  "mic",
  "mic-off",
  "music",
  "headphones",
  "heart",
  "star",
  "bookmark",
  "tag",
  "gift",
  "trash-2",
  "pencil",
  "copy",
  "clipboard",
  "scissors",
  "share-2",
  "download",
  "upload",
  "external-link",
  "link",
  "undo",
  "redo",
  "rotate-cw",
  "refresh-cw",
  "wifi",
  "wifi-off",
  "bluetooth",
  "battery",
  "battery-charging",
  "battery-low",
  "battery-full",
  "bell",
  "bell-off",
  "user",
  "user-round",
  "users",
  "user-plus",
  "house",
  "map-pin",
  "map",
  "navigation",
  "compass",
  "globe",
  "lock",
  "lock-open",
  "unlock",
  "eye",
  "eye-off",
  "alert-triangle",
  "circle-alert",
  "circle-check",
  "circle-x",
  "info",
  "sun",
  "moon",
  "flashlight",
  "cloud",
  "cloud-off",
  "folder",
  "file",
  "file-text",
  "log-out",
  "smartphone",
  "cpu",
  "activity",
  "gauge",
  "route",
  "wallet",
  "credit-card",
  "shopping-bag",
  "zoom-in",
  "zoom-out",
  "maximize",
  "thermometer",
  "droplet",
  "umbrella",
  "wind",
  "bolt",
  "sparkles",
  "delete",
  "arrow-big-up",
  "shield",
  "flame",
  "radio"
];

// ../../android/app/src/main/assets/deft-runtime/v1/base.css
var base_default = `/* Load order: base.css, tokens.css, components.css, theme.css (see docs/design/DESIGN-SYSTEM-PROPOSAL.md 3.9). */
@layer deft.reset, deft.theme, deft.base, deft.layout, deft.components, deft.utilities;
/* Press state layer (components.css). Registered so it can fade out on release. */
@property --deft-press-layer { syntax: "<color>"; inherits: false; initial-value: transparent; }

@layer deft.reset {
  *, *::before, *::after { box-sizing: border-box; }
  /* No square blue WebView tap flash; controls draw their own rounded press state. */
  html { text-size-adjust: 100%; -webkit-tap-highlight-color: transparent; }
  body { margin: 0; }
  [hidden] { display: none !important; }
  img, svg, video { display: block; max-width: 100%; }
  @media (prefers-reduced-motion: reduce) {
    *, *::before, *::after { animation: none !important; transition: none !important; }
    ::view-transition-group(*),
    ::view-transition-old(*),
    ::view-transition-new(*) { animation-duration: 0s !important; animation-delay: 0s !important; }
  }
}

@layer deft.base {
  :root {
    --deft-space-3xs: clamp(.25rem, .22rem + .15vw, .35rem);
    --deft-space-2xs: clamp(.35rem, .3rem + .2vw, .5rem);
    --deft-space-xs: clamp(.5rem, .45rem + .25vw, .7rem);
    --deft-space-s: clamp(.75rem, .65rem + .4vw, 1rem);
    --deft-space-m: clamp(1rem, .85rem + .6vw, 1.35rem);
    --deft-space-l: clamp(1.5rem, 1.2rem + 1vw, 2.1rem);
    --deft-space-xl: clamp(2.1rem, 1.6rem + 1.6vw, 3rem);
    --deft-space-2xl: clamp(3rem, 2.2rem + 2.4vw, 4.5rem);
    --deft-text-xs: clamp(.75rem, .72rem + .12vw, .82rem);
    --deft-text-s: clamp(.875rem, .84rem + .16vw, .95rem);
    --deft-text-m: clamp(1rem, .96rem + .2vw, 1.125rem);
    --deft-text-l: clamp(1.25rem, 1.1rem + .7vw, 1.6rem);
    --deft-text-xl: clamp(1.6rem, 1.25rem + 1.2vw, 2.25rem);
    --deft-text-2xl: clamp(2rem, 1.4rem + 2vw, 3.25rem);
    --deft-tap: 48px;
    --deft-duration-xs: 100ms;
    --deft-duration-s: 120ms;
    --deft-duration-m: 220ms;
    --deft-duration-l: 320ms;
    --deft-ease: cubic-bezier(.2, 0, 0, 1);
    --deft-ease-enter: cubic-bezier(.05, .7, .1, 1);
    --deft-ease-exit: cubic-bezier(.3, 0, .8, .15);
    --deft-line: var(--deft-separator);
    --deft-focus: var(--deft-label);
    --deft-press: color-mix(in srgb, currentColor 12%, transparent);
  }
  body {
    background: var(--deft-color-base-100);
    color: var(--deft-color-base-content);
    font-family: var(--deft-font-ui, system-ui, sans-serif);
    font-size: calc(var(--deft-text-m) * var(--deft-density, 1));
    line-height: 1.45;
  }
  h1 { font-size: calc(var(--deft-text-2xl) * var(--deft-density, 1)); }
  h2 { font-size: calc(var(--deft-text-xl) * var(--deft-density, 1)); }
  h3 { font-size: calc(var(--deft-text-l) * var(--deft-density, 1)); }
  h4, h5, h6 { font-size: calc(var(--deft-text-m) * var(--deft-density, 1)); }
  h1, h2, h3, h4, h5, h6, p { margin: 0 0 var(--deft-space-s); }
  a { color: var(--deft-label); text-decoration: underline; }
  ::selection { background: var(--deft-color-primary); color: var(--deft-color-primary-content); }
  :focus-visible { outline: 2px solid var(--deft-focus); outline-offset: 2px; }
  /* Unstyled buttons and links still answer a press: they dim, like iOS text buttons. Styled ones use data-deft-press. */
  :where(a, button):not([class]) { transition: opacity var(--deft-duration-m) var(--deft-ease); }
  :where(a, button):not([class], :disabled):active { opacity: .6; transition-duration: 0s; }
  button, input, select, textarea { font: inherit; color: inherit; background: var(--deft-color-base-100); }
  button, input, select, textarea, nav a { min-height: var(--deft-tap); }
  table { width: 100%; border-collapse: collapse; }
  th, td { padding: var(--deft-space-xs); text-align: start; border-bottom: var(--deft-border, 1px) solid var(--deft-line); }
}

@layer deft.layout {
  .deft-stack { display: flex; flex-direction: column; gap: calc(var(--deft-gap, var(--deft-space-m)) * var(--deft-density, 1)); }
  .deft-cluster { display: flex; flex-wrap: wrap; align-items: center; gap: calc(var(--deft-gap, var(--deft-space-s)) * var(--deft-density, 1)); }
  .deft-grid { display: grid; grid-template-columns: repeat(auto-fit, minmax(min(100%, var(--deft-min, 12rem)), 1fr)); gap: calc(var(--deft-gap, var(--deft-space-m)) * var(--deft-density, 1)); }
  .deft-sidebar { display: flex; flex-wrap: wrap; gap: calc(var(--deft-gap, var(--deft-space-m)) * var(--deft-density, 1)); }
  .deft-sidebar > :first-child { flex-basis: var(--deft-side, 14rem); flex-grow: 1; }
  .deft-sidebar > :last-child { flex-basis: 0; flex-grow: 999; min-inline-size: var(--deft-min, 50%); }
  .deft-switcher { display: flex; flex-wrap: wrap; gap: calc(var(--deft-gap, var(--deft-space-m)) * var(--deft-density, 1)); }
  .deft-switcher > * { flex-grow: 1; flex-basis: calc((var(--deft-min, 24rem) - 100%) * 999); }
  .deft-center { box-sizing: content-box; margin-inline: auto; max-inline-size: var(--deft-measure, 40rem); padding-inline: var(--deft-space-m); }
  .deft-cover { display: flex; flex-direction: column; min-block-size: 100dvh; }
  .deft-cover > * { margin-block: calc(var(--deft-gap, var(--deft-space-s)) * var(--deft-density, 1)); }
  .deft-cover > :nth-child(2) { margin-block: auto; }
  .deft-scroll { overflow: auto; min-block-size: 0; scroll-snap-type: var(--deft-snap, none); }
  .deft-scroll > * { scroll-snap-align: var(--deft-snap-align, start); }
  .deft-app > .deft-scroll { flex: 1 1 auto; }
}

@layer deft.utilities {
  .deft-safe-top { padding-top: env(safe-area-inset-top); }
  .deft-safe-bottom { padding-bottom: env(safe-area-inset-bottom); }
  .deft-safe-inline { padding-left: env(safe-area-inset-left); padding-right: env(safe-area-inset-right); }
  .deft-visually-hidden { position: absolute; width: 1px; height: 1px; padding: 0; margin: -1px; overflow: hidden; clip: rect(0 0 0 0); white-space: nowrap; border: 0; }
  .deft-truncate { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
  .deft-app { display: flex; flex-direction: column; min-height: 100dvh; padding: env(safe-area-inset-top) 0 env(safe-area-inset-bottom); }
  .hidden { display: none !important; }
}
`;

// ../../android/app/src/main/assets/deft-runtime/v1/tokens.css
var tokens_default = `/* GENERATED by tools/deft-design from design/tokens/*.json. Do not edit. Run: bun tools/deft-design/build.js */
@font-face {
  font-family: "Geist";
  src: url("/_deft/v1/fonts/geist.woff2") format("woff2");
  font-weight: 100 900;
  font-style: normal;
  font-display: swap;
}
@font-face {
  font-family: "Geist Mono";
  src: url("/_deft/v1/fonts/geist-mono.woff2") format("woff2");
  font-weight: 400 500;
  font-style: normal;
  font-display: swap;
}
@font-face {
  font-family: "Doto";
  src: url("/_deft/v1/fonts/doto.woff2") format("woff2");
  font-weight: 100 900;
  font-style: normal;
  font-display: swap;
  unicode-range: U+0020,U+0025,U+002D-003A,U+0041-005A,U+00B0;
}
@layer deft.theme {
  :root {
    --deft-font-text: "Geist", system-ui, sans-serif;
    --deft-font-display: "Geist", system-ui, sans-serif;
    --deft-font-mono: "Geist Mono", ui-monospace, monospace;
    --deft-font-numeric: "Doto", "Geist Mono", ui-monospace, monospace;
    --deft-font-ui: var(--deft-font-text);
    --deft-font-numeric-weight: 650;
    --deft-radius-xs: 6px;
    --deft-radius-sm: 10px;
    --deft-radius-md: 14px;
    --deft-radius-lg: 20px;
    --deft-radius-xl: 28px;
    --deft-radius-screen: 44px;
    --deft-radius-full: 9999px;
    --deft-radius-outer: var(--deft-radius-lg);
    --deft-pad: 12px;
    --deft-type-large-title-family: var(--deft-font-text);
    --deft-type-large-title-size: 34px;
    --deft-type-large-title-leading: 41px;
    --deft-type-large-title-weight: 700;
    --deft-type-large-title-tracking: -0.4px;
    --deft-type-title1-family: var(--deft-font-display);
    --deft-type-title1-size: 28px;
    --deft-type-title1-leading: 34px;
    --deft-type-title1-weight: 700;
    --deft-type-title1-tracking: -0.3px;
    --deft-type-title2-family: var(--deft-font-display);
    --deft-type-title2-size: 22px;
    --deft-type-title2-leading: 28px;
    --deft-type-title2-weight: 700;
    --deft-type-title2-tracking: -0.2px;
    --deft-type-title3-family: var(--deft-font-display);
    --deft-type-title3-size: 20px;
    --deft-type-title3-leading: 25px;
    --deft-type-title3-weight: 600;
    --deft-type-title3-tracking: -0.1px;
    --deft-type-headline-family: var(--deft-font-text);
    --deft-type-headline-size: 17px;
    --deft-type-headline-leading: 22px;
    --deft-type-headline-weight: 600;
    --deft-type-headline-tracking: -0.2px;
    --deft-type-body-family: var(--deft-font-text);
    --deft-type-body-size: 17px;
    --deft-type-body-leading: 22px;
    --deft-type-body-weight: 400;
    --deft-type-body-tracking: -0.2px;
    --deft-type-callout-family: var(--deft-font-text);
    --deft-type-callout-size: 16px;
    --deft-type-callout-leading: 21px;
    --deft-type-callout-weight: 400;
    --deft-type-callout-tracking: -0.1px;
    --deft-type-subhead-family: var(--deft-font-text);
    --deft-type-subhead-size: 15px;
    --deft-type-subhead-leading: 20px;
    --deft-type-subhead-weight: 400;
    --deft-type-subhead-tracking: -0.1px;
    --deft-type-footnote-family: var(--deft-font-text);
    --deft-type-footnote-size: 13px;
    --deft-type-footnote-leading: 18px;
    --deft-type-footnote-weight: 400;
    --deft-type-footnote-tracking: 0px;
    --deft-type-caption-family: var(--deft-font-text);
    --deft-type-caption-size: 12px;
    --deft-type-caption-leading: 16px;
    --deft-type-caption-weight: 400;
    --deft-type-caption-tracking: 0px;
    --deft-type-label-mono-family: var(--deft-font-mono);
    --deft-type-label-mono-size: 11px;
    --deft-type-label-mono-leading: 14px;
    --deft-type-label-mono-weight: 500;
    --deft-type-label-mono-tracking: 0.88px;
    --deft-type-label-mono-transform: uppercase;
    --deft-spring-snappy: linear(0, 0.067, 0.206, 0.359, 0.501, 0.62, 0.717, 0.792, 0.849, 0.891, 0.922, 0.945, 0.961, 0.973, 0.981, 0.987, 1);
    --deft-spring-snappy-duration: 280ms;
    --deft-spring-smooth: linear(0, 0.089, 0.261, 0.437, 0.589, 0.708, 0.796, 0.86, 0.905, 0.937, 0.958, 0.972, 0.982, 0.988, 0.992, 0.995, 1);
    --deft-spring-smooth-duration: 560ms;
    --deft-spring-bouncy: linear(0, 0.097, 0.301, 0.525, 0.719, 0.865, 0.961, 1.016, 1.041, 1.046, 1.041, 1.031, 1.021, 1.012, 1.006, 1.001, 1);
    --deft-spring-bouncy-duration: 660ms;
    --deft-step-dot: steps(8, jump-end);
    --deft-material-bar: color-mix(in oklch, var(--deft-bg-elevated) 62%, transparent);
    --deft-material-bar-reduced: color-mix(in oklch, var(--deft-bg-elevated) 92%, transparent);
    --deft-material-blur: blur(24px) saturate(1.7);
    --deft-material-rim: inset 0 0 0 .5px oklch(1 0 0 / 0.22), inset 0 1px 0 oklch(1 0 0 / 0.18);
    --deft-icon-stroke: 1.5;
    --deft-hairline: 0.5px;
  }
  /* Concentric helper: each element computes its own inner radius from its own outer radius and pad. */
  * { --deft-radius-inner: calc(var(--deft-radius-outer) - var(--deft-pad)); }
}
@layer deft.components {
  .deft-largeTitle { font-family: var(--deft-type-large-title-family); font-size: var(--deft-type-large-title-size); line-height: var(--deft-type-large-title-leading); font-weight: var(--deft-type-large-title-weight); letter-spacing: var(--deft-type-large-title-tracking); }
  .deft-title1 { font-family: var(--deft-type-title1-family); font-size: var(--deft-type-title1-size); line-height: var(--deft-type-title1-leading); font-weight: var(--deft-type-title1-weight); letter-spacing: var(--deft-type-title1-tracking); }
  .deft-title2 { font-family: var(--deft-type-title2-family); font-size: var(--deft-type-title2-size); line-height: var(--deft-type-title2-leading); font-weight: var(--deft-type-title2-weight); letter-spacing: var(--deft-type-title2-tracking); }
  .deft-title3 { font-family: var(--deft-type-title3-family); font-size: var(--deft-type-title3-size); line-height: var(--deft-type-title3-leading); font-weight: var(--deft-type-title3-weight); letter-spacing: var(--deft-type-title3-tracking); }
  .deft-headline { font-family: var(--deft-type-headline-family); font-size: var(--deft-type-headline-size); line-height: var(--deft-type-headline-leading); font-weight: var(--deft-type-headline-weight); letter-spacing: var(--deft-type-headline-tracking); }
  .deft-body { font-family: var(--deft-type-body-family); font-size: var(--deft-type-body-size); line-height: var(--deft-type-body-leading); font-weight: var(--deft-type-body-weight); letter-spacing: var(--deft-type-body-tracking); }
  .deft-callout { font-family: var(--deft-type-callout-family); font-size: var(--deft-type-callout-size); line-height: var(--deft-type-callout-leading); font-weight: var(--deft-type-callout-weight); letter-spacing: var(--deft-type-callout-tracking); }
  .deft-subhead { font-family: var(--deft-type-subhead-family); font-size: var(--deft-type-subhead-size); line-height: var(--deft-type-subhead-leading); font-weight: var(--deft-type-subhead-weight); letter-spacing: var(--deft-type-subhead-tracking); }
  .deft-footnote { font-family: var(--deft-type-footnote-family); font-size: var(--deft-type-footnote-size); line-height: var(--deft-type-footnote-leading); font-weight: var(--deft-type-footnote-weight); letter-spacing: var(--deft-type-footnote-tracking); }
  .deft-caption { font-family: var(--deft-type-caption-family); font-size: var(--deft-type-caption-size); line-height: var(--deft-type-caption-leading); font-weight: var(--deft-type-caption-weight); letter-spacing: var(--deft-type-caption-tracking); }
  .deft-labelMono { font-family: var(--deft-type-label-mono-family); font-size: var(--deft-type-label-mono-size); line-height: var(--deft-type-label-mono-leading); font-weight: var(--deft-type-label-mono-weight); letter-spacing: var(--deft-type-label-mono-tracking); text-transform: uppercase; }
}
`;

// ../../android/app/src/main/assets/deft-runtime/v1/components.css
var components_default = `/* Components layer (deft.components). Tokens only (--deft-bg*, --deft-label*, --deft-fill*, --deft-separator, --deft-accent, --deft-error). Colour map: DESIGN-SYSTEM-PROPOSAL 8.1. Requires base.css and tokens.css. */
@layer deft.components {
  /* Buttons. Secondary (fill + label) is the default; the accent marks one primary per screen. */
  .deft-btn, .deft-btn-secondary {
    display: inline-flex; align-items: center; justify-content: center; gap: var(--deft-space-2xs);
    min-block-size: var(--deft-tap); padding-inline: var(--deft-space-m);
    border: 0; border-radius: var(--deft-radius-screen);
    background: var(--deft-fill); color: var(--deft-label);
    font: var(--deft-type-headline-weight) var(--deft-type-headline-size) / var(--deft-type-headline-leading) var(--deft-type-headline-family);
    letter-spacing: var(--deft-type-headline-tracking); text-decoration: none; cursor: pointer;
  }
  .deft-btn[data-primary], .deft-btn-primary { background: var(--deft-accent); color: var(--deft-on-accent); }
  .deft-btn-ghost, .deft-ghost { background: transparent; color: var(--deft-label); }
  .deft-btn-outline { background: transparent; box-shadow: inset 0 0 0 var(--deft-hairline) var(--deft-label-3); }
  .deft-btn-soft { background: var(--deft-fill-2); }
  .deft-btn-link { background: transparent; color: var(--deft-label); padding-inline: 0; text-decoration: underline; text-underline-offset: 3px; }
  .deft-btn-sm { min-block-size: 2.25rem; padding-inline: var(--deft-space-s); font-size: var(--deft-type-subhead-size); }
  .deft-btn-lg { min-block-size: 3.5rem; padding-inline: var(--deft-space-l); font-size: var(--deft-type-body-size); }
  .deft-btn:disabled, .deft-btn[aria-disabled=true] { opacity: .38; }
  .deft-fab {
    display: inline-grid; place-items: center; flex: none; inline-size: var(--deft-tap); block-size: var(--deft-tap);
    border: 0; border-radius: var(--deft-radius-screen); background: var(--deft-fill-2); color: var(--deft-label); cursor: pointer;
  }
  .deft-fab[data-primary] { background: var(--deft-accent); color: var(--deft-on-accent); }
  /* Create action floating bottom-trailing over the scrolling content. Give the scroll page bottom padding of at least calc(var(--deft-tap) + 2 * var(--deft-space-m)). */
  .deft-fab[data-float] { position: fixed; z-index: 2; inset-block-end: calc(var(--deft-space-m) + env(safe-area-inset-bottom)); inset-inline-end: var(--deft-space-m); }
  .deft-btn:not(:disabled):active, .deft-fab:active, .deft-cell[href]:active, .deft-row[href]:active,
  [data-deft-press]:not(:disabled):active, .deft-stepper > button:not(:disabled):active { transform: scale(.96); }
  .deft-btn-xs { font-size: var(--deft-type-caption-size); }
  /* Destructive: still the secondary style; only the label and icon turn --deft-error (always with text, 8.1). */
  .deft-btn[data-tone=destructive] { color: var(--deft-error); }

  /* Switch: a real styled checkbox with role=switch. On = label track + bg knob; off = fill track. */
  .deft-switch, .deft-toggle {
    appearance: none; position: relative; flex: none; margin: 0;
    inline-size: 51px; block-size: 31px; border-radius: var(--deft-radius-screen);
    background: var(--deft-fill); cursor: pointer;
    transition: background-color var(--deft-duration-m) var(--deft-ease);
  }
  .deft-switch::before, .deft-toggle::before {
    content: ""; position: absolute; inset-block-start: 2px; inset-inline-start: 2px;
    inline-size: 27px; block-size: 27px; border-radius: var(--deft-radius-screen); background: var(--deft-label);
    transition: transform var(--deft-spring-snappy-duration) var(--deft-spring-snappy), background-color var(--deft-duration-m) var(--deft-ease);
  }
  .deft-switch:checked, .deft-toggle:checked { background: var(--deft-label); }
  .deft-switch:checked::before, .deft-toggle:checked::before { background: var(--deft-bg); transform: translateX(20px); }
  .deft-switch:disabled, .deft-toggle:disabled { opacity: .38; }
  .deft-checkbox { accent-color: var(--deft-label); }

  /* Segmented control and chips: selected is label text on a fill pill, never accent. */
  .deft-segmented {
    display: inline-flex; gap: 2px; padding: 2px; border-radius: var(--deft-radius-screen);
    box-shadow: inset 0 0 0 var(--deft-hairline) var(--deft-separator);
  }
  .deft-segmented > * {
    appearance: none; border: 0; min-block-size: 2.25rem; padding-inline: var(--deft-space-s); border-radius: var(--deft-radius-screen);
    background: transparent; color: var(--deft-label-2); cursor: pointer;
    font: var(--deft-type-subhead-weight) var(--deft-type-subhead-size) / var(--deft-type-subhead-leading) var(--deft-type-subhead-family);
  }
  .deft-segmented > [aria-selected=true], .deft-segmented > [aria-pressed=true], .deft-segmented > [data-selected] { background: var(--deft-fill); color: var(--deft-label); }
  .deft-chip {
    display: inline-flex; align-items: center; gap: var(--deft-space-3xs); min-block-size: 2rem; padding-inline: var(--deft-space-s);
    border: 0; border-radius: var(--deft-radius-screen); background: transparent; color: var(--deft-label-2); cursor: pointer;
    box-shadow: inset 0 0 0 var(--deft-hairline) var(--deft-separator);
    font: var(--deft-type-subhead-weight) var(--deft-type-subhead-size) / var(--deft-type-subhead-leading) var(--deft-type-subhead-family);
  }
  .deft-chip[aria-pressed=true], .deft-chip[aria-selected=true], .deft-chip[data-selected] { background: var(--deft-fill); color: var(--deft-label); box-shadow: none; }

  /* Stepper: fill capsule with two round buttons around a Doto value. */
  .deft-stepper { display: inline-flex; align-items: center; border-radius: var(--deft-radius-screen); background: var(--deft-fill); }
  .deft-stepper > button {
    appearance: none; display: grid; place-items: center; flex: none; padding: 0; border: 0; border-radius: var(--deft-radius-screen);
    inline-size: var(--deft-tap); block-size: var(--deft-tap); background: transparent; color: var(--deft-label); cursor: pointer;
  }
  .deft-stepper > output {
    min-inline-size: 3ch; text-align: center; color: var(--deft-label);
    font-family: var(--deft-font-numeric); font-size: var(--deft-type-title3-size); font-variant-numeric: tabular-nums;
  }

  /* Slider and progress: fill is label; [data-live] switches the fill to accent (recording, live signal). */
  .deft-slider {
    appearance: none; inline-size: 100%; min-block-size: var(--deft-tap); margin: 0; background: transparent; cursor: pointer;
    --_fill: var(--deft-label); --deft-value: 50%;
  }
  .deft-slider[data-live] { --_fill: var(--deft-accent); }
  .deft-slider::-webkit-slider-runnable-track {
    block-size: 4px; border-radius: var(--deft-radius-screen);
    background: linear-gradient(to right, var(--_fill) var(--deft-value), var(--deft-fill-3) var(--deft-value));
  }
  .deft-slider::-webkit-slider-thumb {
    appearance: none; inline-size: 28px; block-size: 28px; margin-top: -12px; border: 0; border-radius: var(--deft-radius-screen); background: var(--deft-label);
  }
  .deft-slider::-moz-range-track { block-size: 4px; border-radius: var(--deft-radius-screen); background: var(--deft-fill-3); }
  .deft-slider::-moz-range-progress { block-size: 4px; border-radius: var(--deft-radius-screen); background: var(--_fill); }
  .deft-slider::-moz-range-thumb { inline-size: 28px; block-size: 28px; border: 0; border-radius: var(--deft-radius-screen); background: var(--deft-label); }
  .deft-progress {
    position: relative; overflow: hidden; block-size: 6px; border-radius: var(--deft-radius-screen);
    background: var(--deft-fill); --_bar: var(--deft-label);
  }
  .deft-progress[data-live] { --_bar: var(--deft-accent); }
  .deft-progress::before {
    content: ""; position: absolute; inset-block: 0; inset-inline-start: 0; inline-size: var(--deft-value, 0%);
    border-radius: inherit; background: var(--_bar);
    transition: inline-size var(--deft-spring-smooth-duration) var(--deft-spring-smooth);
  }
  .deft-progress[data-segments]::before { transition-timing-function: var(--deft-step-dot); }
  .deft-progress[data-segments]::after {
    content: ""; position: absolute; inset: 0; pointer-events: none;
    background: repeating-linear-gradient(to right, transparent 0 calc(100% / var(--deft-segs, 8) - 3px), var(--deft-bg) 0 calc(100% / var(--deft-segs, 8)));
  }

  /* Meter: a gauge or HUD bar. Set --deft-level (0 to 1) from JS; the child scales on the compositor, so it is cheap to update every frame. */
  .deft-meter {
    position: relative; overflow: hidden; block-size: 0.75rem; border-radius: var(--deft-radius-screen);
    background: var(--deft-fill); --_bar: var(--deft-label);
  }
  .deft-meter[data-tone="success"] { --_bar: var(--deft-success); }
  .deft-meter[data-tone="warning"] { --_bar: var(--deft-warning); }
  .deft-meter[data-tone="error"] { --_bar: var(--deft-error); }
  .deft-meter[data-live] { --_bar: var(--deft-accent); }
  .deft-meter > * {
    display: block; block-size: 100%; inline-size: 100%; border-radius: inherit; background: var(--_bar);
    transform: scaleX(var(--deft-level, 0)); transform-origin: left center;
    transition: transform var(--deft-spring-snappy-duration) var(--deft-spring-snappy);
  }
  .deft-meter:dir(rtl) > * { transform-origin: right center; }

  /* Skeleton, badge, empty state. */
  .deft-skeleton { position: relative; overflow: hidden; min-block-size: 1em; border-radius: var(--deft-radius-sm); background: var(--deft-fill); }
  .deft-skeleton::after {
    content: ""; position: absolute; inset: 0; transform: translateX(-100%);
    background: linear-gradient(90deg, transparent, var(--deft-fill-2), transparent);
    animation: deft-shimmer 1.6s var(--deft-ease) infinite;
  }
  .deft-badge {
    display: inline-flex; align-items: center; gap: var(--deft-space-3xs); min-block-size: 1.25rem; padding-inline: var(--deft-space-2xs);
    border-radius: var(--deft-radius-screen); background: var(--deft-fill); color: var(--deft-label);
    font: 600 var(--deft-type-caption-size) / var(--deft-type-caption-leading) var(--deft-type-caption-family);
  }
  .deft-badge::before { content: ""; inline-size: .4rem; block-size: .4rem; border-radius: var(--deft-radius-screen); background: var(--deft-accent); }
  .deft-badge:empty { min-block-size: 0; inline-size: .5rem; block-size: .5rem; padding: 0; background: var(--deft-accent); }
  .deft-badge:empty::before { display: none; }
  .deft-empty {
    display: flex; flex-direction: column; align-items: center; gap: var(--deft-space-xs);
    padding: var(--deft-space-l) var(--deft-space-m); text-align: center; color: var(--deft-label-2);
  }
  .deft-empty > :is(h2, h3) { margin: 0; color: var(--deft-label); }
  .deft-empty > p { margin: 0; }
  .deft-empty > .deft-icon { inline-size: 2.5rem; block-size: 2.5rem; color: var(--deft-label-2); }

  /* Tile (glanceable number) and hero (media header with scrim). */
  .deft-tile {
    --deft-radius-outer: var(--deft-radius-lg); --deft-pad: var(--deft-space-s);
    display: flex; flex-direction: column; gap: var(--deft-space-3xs); min-inline-size: 0; overflow: hidden;
    padding: var(--deft-pad); border-radius: var(--deft-radius-outer); background: var(--deft-bg-grouped); color: var(--deft-label);
  }
  .deft-tile-value {
    margin: 0; color: var(--deft-label); font-family: var(--deft-font-numeric); font-weight: var(--deft-font-numeric-weight); font-variant-numeric: tabular-nums;
    font-size: clamp(2.25rem, 1.9rem + 1.6vw, 3.25rem); line-height: 1;
  }
  .deft-tile > svg { inline-size: 100%; block-size: 2rem; fill: none; stroke: var(--deft-label-2); stroke-width: var(--deft-icon-stroke); }
  .deft-labelMono { color: var(--deft-label-2); }
  /* Mono text (ids, paths, logs): footnote size, Geist Mono. Add data-wrap for pre-wrap. */
  .deft-mono { font-family: var(--deft-font-mono); font-size: var(--deft-type-footnote-size); line-height: var(--deft-type-footnote-leading); color: var(--deft-label); }
  .deft-mono[data-wrap] { white-space: pre-wrap; overflow-wrap: anywhere; }
  /* Media grid: square tiles of photos or videos. Tile radius is concentric with the grid's outer radius and gap. */
  .deft-media-grid {
    --deft-radius-outer: var(--deft-radius-sm); --deft-pad: var(--deft-space-3xs);
    display: grid; grid-template-columns: repeat(var(--deft-cols, 3), minmax(0, 1fr)); gap: var(--deft-pad);
  }
  .deft-media-grid > * { position: relative; overflow: hidden; margin: 0; min-inline-size: 0; aspect-ratio: 1; border-radius: var(--deft-radius-inner); background: var(--deft-fill); }
  .deft-media-grid img, .deft-media-grid video { inline-size: 100%; block-size: 100%; object-fit: cover; }
  .deft-media-grid figcaption { position: absolute; inset: auto 0 0; padding: var(--deft-space-3xs); background: linear-gradient(to top, var(--deft-bg), transparent); }
  .deft-hero {
    position: relative; isolation: isolate; overflow: hidden; display: flex; flex-direction: column; justify-content: flex-end;
    min-block-size: 12rem; border-radius: var(--deft-radius-xl); background: var(--deft-bg-grouped); color: var(--deft-label);
  }
  .deft-hero > img, .deft-hero > video { position: absolute; inset: 0; z-index: -2; inline-size: 100%; block-size: 100%; object-fit: cover; }
  .deft-hero::after {
    content: ""; position: absolute; inset: 0; z-index: -1;
    background: linear-gradient(to top, var(--deft-bg) 8%, transparent 70%);
  }
  .deft-hero > .deft-hero-body { display: flex; flex-direction: column; gap: var(--deft-space-3xs); padding: var(--deft-space-m); }

  /* Page, bars and tab bar. Glass only on bars; content stays opaque. */
  .deft-page {
    display: flex; flex-direction: column; gap: var(--deft-space-s); min-block-size: 100%;
    padding: var(--deft-space-s) var(--deft-space-m) var(--deft-space-l); background: var(--deft-bg); color: var(--deft-label);
  }
  .deft-page > :first-child { margin: 0; }
  @supports (animation-timeline: scroll()) {
    .deft-page[data-large-title] > :first-child { animation: deft-title-collapse linear both; animation-timeline: scroll(nearest); animation-range: 0 3rem; }
  }
  .deft-bar, .deft-toolbar, .deft-appbar, .deft-topbar {
    position: sticky; inset-block-start: 0; z-index: 2; display: flex; align-items: center; gap: var(--deft-space-s);
    padding: var(--deft-space-s) var(--deft-space-m); color: var(--deft-label);
    background: var(--deft-material-bar); -webkit-backdrop-filter: var(--deft-material-blur); backdrop-filter: var(--deft-material-blur);
    box-shadow: var(--deft-material-rim);
  }
  .deft-appbar, .deft-topbar { padding-block-start: calc(var(--deft-space-s) + env(safe-area-inset-top)); }
  .deft-tabbar {
    position: sticky; inset-block-end: calc(var(--deft-space-s) + env(safe-area-inset-bottom)); z-index: 2;
    display: flex; align-items: stretch; gap: 2px; width: max-content; max-inline-size: calc(100% - 2 * var(--deft-space-s));
    margin-inline: auto; padding: 4px; border-radius: var(--deft-radius-screen);
    background: var(--deft-material-bar); -webkit-backdrop-filter: var(--deft-material-blur); backdrop-filter: var(--deft-material-blur);
    box-shadow: var(--deft-material-rim);
  }
  .deft-tabbar > * {
    appearance: none; display: flex; flex-direction: column; align-items: center; justify-content: center; gap: 2px;
    min-inline-size: 3.5rem; padding: var(--deft-space-3xs) var(--deft-space-s); border: 0; border-radius: var(--deft-radius-screen);
    background: transparent; color: var(--deft-label-3); text-decoration: none;
    font: var(--deft-type-caption-weight) var(--deft-type-caption-size) / var(--deft-type-caption-leading) var(--deft-type-caption-family);
  }
  .deft-tabbar > [aria-current], .deft-tabbar > [aria-selected=true] { color: var(--deft-label); }
  .deft-tabbar > [aria-current]::after, .deft-tabbar > [aria-selected=true]::after {
    content: ""; inline-size: 4px; block-size: 4px; border-radius: var(--deft-radius-screen); background: var(--deft-accent);
  }

  /* Grouped inset list. 4px group padding gives cells the concentric inner radius. */
  .deft-group {
    --deft-radius-outer: var(--deft-radius-lg); --deft-pad: 4px;
    display: flex; flex-direction: column; overflow: hidden; padding: var(--deft-pad);
    border-radius: var(--deft-radius-outer); background: var(--deft-bg-grouped);
  }
  .deft-cell, .deft-row {
    position: relative; display: flex; align-items: center; gap: var(--deft-space-s);
    min-block-size: var(--deft-tap); padding: var(--deft-space-xs) var(--deft-space-s);
    border-radius: var(--deft-radius-inner); color: var(--deft-label); text-decoration: none;
  }
  button.deft-cell { inline-size: 100%; border: 0; background: none; font: inherit; text-align: start; }
  .deft-cell:has(> .deft-tile-icon) { --_cell-lead: calc(2rem + var(--deft-space-s)); }
  .deft-cell + .deft-cell::before, .deft-row + .deft-row::before {
    content: ""; position: absolute; inset-block-start: 0; inset-inline: calc(var(--deft-space-s) + var(--_cell-lead, 0px)) var(--deft-space-s);
    block-size: var(--deft-hairline); background: var(--deft-separator);
  }
  .deft-cell-body { display: flex; flex: 1; flex-direction: column; gap: 1px; min-inline-size: 0; }
  .deft-cell-title { overflow: hidden; color: var(--deft-label); text-overflow: ellipsis; white-space: nowrap; }
  .deft-cell-sub { color: var(--deft-label-2); }
  .deft-cell-value { color: var(--deft-label-2); white-space: nowrap; font-variant-numeric: tabular-nums; }
  .deft-tile-icon {
    display: grid; place-items: center; flex: none; inline-size: 2rem; block-size: 2rem;
    border-radius: var(--deft-radius-sm); background: var(--deft-fill); color: var(--deft-label);
  }
  .deft-icon { flex: none; inline-size: 1.25rem; block-size: 1.25rem; fill: none; stroke: currentColor; stroke-width: var(--deft-icon-stroke); stroke-linecap: round; stroke-linejoin: round; }
  .deft-tile-icon > .deft-icon { inline-size: 1.125rem; block-size: 1.125rem; }
  .deft-chevron { color: var(--deft-label-3); }

  /* Sheets, dialogs, popovers, toasts. */
  dialog, .deft-dialog {
    border: 0; padding: var(--deft-space-m); border-radius: var(--deft-radius-xl);
    background: var(--deft-bg-elevated); color: var(--deft-label); max-inline-size: min(32rem, 100% - 2rem);
  }
  dialog::backdrop { background: color-mix(in oklch, var(--deft-bg) 60%, transparent); }
  dialog.deft-sheet {
    inline-size: 100%; max-inline-size: 100%; margin: auto 0 0;
    padding-block-start: var(--deft-space-xs); border-radius: var(--deft-radius-xl) var(--deft-radius-xl) var(--deft-radius-xs) var(--deft-radius-xs);
    background: var(--deft-material-bar); -webkit-backdrop-filter: var(--deft-material-blur); backdrop-filter: var(--deft-material-blur);
    box-shadow: var(--deft-material-rim);
  }
  dialog.deft-sheet::before {
    content: ""; display: block; inline-size: 2.25rem; block-size: 5px; margin: 0 auto var(--deft-space-s);
    border-radius: var(--deft-radius-screen); background: var(--deft-label-4);
  }
  /* Full-screen modal (call screen, camera, onboarding takeover): dialog.deft-dialog.deft-fullscreen, open with showModal(). Centred stack, safe-area padding. */
  dialog.deft-fullscreen {
    inset: 0; inline-size: 100%; block-size: 100%; max-inline-size: none; max-block-size: none; margin: 0; box-sizing: border-box;
    padding: calc(var(--deft-space-l) + env(safe-area-inset-top)) var(--deft-space-l) calc(var(--deft-space-l) + env(safe-area-inset-bottom));
    border-radius: 0; background: var(--deft-bg);
  }
  dialog.deft-fullscreen > .deft-stack { min-block-size: 100%; justify-content: center; text-align: center; }
  dialog.deft-fullscreen .deft-cluster { justify-content: center; }
  /* Keypad: 3 columns of round digit keys (Geist, not Doto). Buttons are direct children; add a data-key label if you want. */
  .deft-keypad { display: grid; grid-template-columns: repeat(3, minmax(0, 1fr)); justify-items: center; gap: var(--deft-space-xs); padding-block: var(--deft-space-s); }
  .deft-keypad > button {
    appearance: none; display: grid; place-items: center; inline-size: min(100%, calc(var(--deft-tap) * 1.5)); aspect-ratio: 1; padding: 0;
    border: 0; border-radius: var(--deft-radius-full); background: var(--deft-fill); color: var(--deft-label); cursor: pointer;
    font: var(--deft-type-title2-weight) var(--deft-type-title2-size) / 1 var(--deft-type-title2-family); font-variant-numeric: tabular-nums;
  }
  .deft-keypad > button:active { transform: scale(.94); }
  [popover], .deft-menu { border: 0; border-radius: var(--deft-radius-lg); background: var(--deft-bg-elevated); color: var(--deft-label); padding: var(--deft-space-2xs); box-shadow: var(--deft-material-rim); }
  .deft-menu { display: flex; flex-direction: column; gap: 2px; }
  .deft-menu > * { display: flex; align-items: center; justify-content: flex-start; gap: var(--deft-space-xs); min-block-size: var(--deft-tap); padding-inline: var(--deft-space-s); border: 0; border-radius: var(--deft-radius-inner); background: transparent; color: var(--deft-label); text-align: start; }
  .deft-toast { margin: auto; padding: var(--deft-space-s) var(--deft-space-m); border-radius: var(--deft-radius-lg); background: var(--deft-bg-elevated); color: var(--deft-label); box-shadow: var(--deft-material-rim); }

  /* Press feedback: a currentColor state layer clipped to the control's own radius (base.css drops the square WebView highlight). In at once, fades on release. */
  :is(.deft-btn, .deft-fab, .deft-chip, .deft-segmented > *, .deft-tabbar > *, .deft-tab, .deft-menu > *, .deft-stepper > button, .deft-keypad > button, .deft-cell[href], .deft-row[href], button.deft-cell, [data-deft-press]) {
    background-image: linear-gradient(var(--deft-press-layer), var(--deft-press-layer));
    transition: transform var(--deft-spring-snappy-duration) var(--deft-spring-snappy), background-color var(--deft-duration-s) var(--deft-ease), --deft-press-layer var(--deft-duration-l) var(--deft-ease);
  }
  :is(.deft-btn, .deft-fab, .deft-chip, .deft-segmented > *, .deft-tabbar > *, .deft-tab, .deft-menu > *, .deft-stepper > button, .deft-keypad > button, .deft-cell[href], .deft-row[href], button.deft-cell, [data-deft-press]):not(:disabled, [aria-disabled=true]):active { --deft-press-layer: var(--deft-press); transition-duration: var(--deft-spring-snappy-duration), 0s, 0s; }

  /* Cards, alerts and status. Status colour is only ever an icon tint next to text (8.1). */
  .deft-card { --deft-radius-outer: var(--deft-radius-lg); --deft-pad: var(--deft-space-s); display: flex; flex-direction: column; gap: var(--deft-space-xs); padding: var(--deft-pad); border-radius: var(--deft-radius-outer); background: var(--deft-bg-grouped); color: var(--deft-label); }
  .deft-card-body { display: flex; flex-direction: column; gap: var(--deft-space-xs); padding: var(--deft-space-m); }
  .deft-card-actions { display: flex; flex-wrap: wrap; gap: var(--deft-space-xs); }
  .deft-alert {
    display: flex; align-items: flex-start; gap: var(--deft-space-xs); padding: var(--deft-space-s) var(--deft-space-m);
    border-radius: var(--deft-radius-md); background: var(--deft-fill); color: var(--deft-label); --_tone: var(--deft-label);
  }
  .deft-alert > .deft-icon { color: var(--_tone); }
  .deft-alert[data-tone=error] { --_tone: var(--deft-error); }
  .deft-alert[data-tone=warning] { --_tone: var(--deft-warning); }
  .deft-alert[data-tone=success] { --_tone: var(--deft-success); }
  .deft-alert[data-tone=info] { --_tone: var(--deft-label-2); }

  /* Inputs, tabs, lists. */
  .deft-input, .deft-select, .deft-textarea {
    inline-size: 100%; min-block-size: var(--deft-tap); padding: var(--deft-space-xs) var(--deft-space-s);
    border: 0; border-radius: var(--deft-radius-md); background: var(--deft-fill); color: var(--deft-label);
    font: var(--deft-type-body-weight) var(--deft-type-body-size) / var(--deft-type-body-leading) var(--deft-type-body-family);
  }
  .deft-input::placeholder, .deft-textarea::placeholder { color: var(--deft-label-3); }
  .deft-textarea { min-block-size: calc(var(--deft-tap) * 2); resize: vertical; }
  .deft-tabs { display: flex; gap: var(--deft-space-xs); overflow-x: auto; }
  .deft-tab {
    appearance: none; min-block-size: var(--deft-tap); padding-inline: var(--deft-space-2xs); border: 0; border-radius: var(--deft-radius-sm); background: transparent;
    color: var(--deft-label-3); font: var(--deft-type-subhead-weight) var(--deft-type-subhead-size) / var(--deft-type-subhead-leading) var(--deft-type-subhead-family);
  }
  .deft-tab[aria-selected=true], .deft-tab[aria-current] { color: var(--deft-label); box-shadow: inset 0 -2px 0 var(--deft-label); }
  .deft-list { display: flex; flex-direction: column; margin: 0; padding: 0; list-style: none; }
  .deft-list > * { padding-block: var(--deft-space-s); }
  .deft-list > * + * { box-shadow: inset 0 var(--deft-hairline) 0 var(--deft-separator); }
  details { padding: var(--deft-space-s); border-radius: var(--deft-radius-lg); background: var(--deft-bg-grouped); }
  summary { display: flex; align-items: center; min-block-size: var(--deft-tap); cursor: pointer; }

  /* Links and focus. */
  a { color: var(--deft-label); text-decoration: underline; text-underline-offset: 3px; }
  :focus-visible { outline: 2px solid var(--deft-label); outline-offset: 2px; }
  @supports (corner-shape: squircle) {
    .deft-group, .deft-card, .deft-tile, .deft-hero, .deft-alert, .deft-toast, .deft-menu, dialog, [popover] { corner-shape: squircle; }
  }

  /* Motion: springs, view transitions, sheet and dialog enter/exit. Reduced motion: base.css. */
  .deft-sheet:not(dialog):not(.hidden) > form,
  .deft-sheet:not(dialog):not(.hidden) > div { animation: deft-sheet-in var(--deft-duration-l) var(--deft-ease-enter); }
  .deft-morph { view-transition-name: deft-morph; }
  ::view-transition-old(root) { animation: deft-fade-out var(--deft-duration-s) var(--deft-ease-exit) both; }
  ::view-transition-new(root) { animation: deft-rise-in var(--deft-duration-l) var(--deft-ease-enter) both; }
  ::view-transition-group(deft-morph) { animation-duration: var(--deft-duration-l); animation-timing-function: var(--deft-ease-enter); }
  dialog.deft-dialog, dialog.deft-sheet {
    transition: opacity var(--deft-duration-s) var(--deft-ease-exit), transform var(--deft-duration-s) var(--deft-ease-exit), overlay var(--deft-duration-s) allow-discrete, display var(--deft-duration-s) allow-discrete;
  }
  dialog.deft-dialog { transform: translateY(12px) scale(.96); opacity: 0; }
  dialog.deft-sheet { transform: translateY(100%); }
  dialog.deft-dialog[open], dialog.deft-sheet[open] {
    opacity: 1; transform: none;
    transition: opacity var(--deft-duration-l) var(--deft-ease-enter), transform var(--deft-duration-l) var(--deft-ease-enter), overlay var(--deft-duration-l) allow-discrete, display var(--deft-duration-l) allow-discrete;
  }
  dialog.deft-dialog::backdrop, dialog.deft-sheet::backdrop { opacity: 0; transition: opacity var(--deft-duration-s) var(--deft-ease-exit), overlay var(--deft-duration-s) allow-discrete, display var(--deft-duration-s) allow-discrete; }
  dialog.deft-dialog[open]::backdrop, dialog.deft-sheet[open]::backdrop { opacity: 1; transition: opacity var(--deft-duration-l) var(--deft-ease-enter), overlay var(--deft-duration-l) allow-discrete, display var(--deft-duration-l) allow-discrete; }
  @starting-style {
    dialog.deft-dialog[open] { opacity: 0; transform: translateY(12px) scale(.96); }
    dialog.deft-sheet[open] { transform: translateY(100%); }
    dialog.deft-dialog[open]::backdrop, dialog.deft-sheet[open]::backdrop { opacity: 0; }
    .deft-toast:popover-open, [popover]:popover-open { opacity: 0; transform: translateY(8px); }
  }
  [popover], .deft-toast { opacity: 0; transform: translateY(8px); transition: opacity var(--deft-duration-s) var(--deft-ease-exit), transform var(--deft-duration-s) var(--deft-ease-exit), overlay var(--deft-duration-s) allow-discrete, display var(--deft-duration-s) allow-discrete; }
  .deft-toast:popover-open, [popover]:popover-open { opacity: 1; transform: none; transition: opacity var(--deft-duration-m) var(--deft-ease-enter), transform var(--deft-duration-m) var(--deft-ease-enter), overlay var(--deft-duration-m) allow-discrete, display var(--deft-duration-m) allow-discrete; }
  @keyframes deft-sheet-in { from { transform: translateY(100%); } }
  @keyframes deft-fade-out { to { opacity: 0; } }
  @keyframes deft-rise-in { from { opacity: 0; transform: translateY(8px); } }
  @keyframes deft-shimmer { to { transform: translateX(100%); } }
  @keyframes deft-title-collapse { to { opacity: 0; transform: translateY(-.5rem); } }

  /* motion.js: enter gating, sheet detents (glass at medium, opaque at large), grab zone, <deft-icon>, <deft-number>. */
  .deft-motion [data-deft-enter]:not(.deft-in)>* { opacity: 0; }
  dialog.deft-sheet[data-detents] { block-size: var(--deft-sheet-h, 60dvh); max-block-size: 100dvh; }
  dialog.deft-sheet[data-detent=large] { background: var(--deft-bg-elevated); -webkit-backdrop-filter: none; backdrop-filter: none; }
  .deft-grab { position: absolute; inset: 0 0 auto; block-size: 2.5rem; touch-action: none; }
  deft-icon { display: inline-flex; inline-size: 1.25rem; block-size: 1.25rem; }
  deft-icon > svg { inline-size: 100%; block-size: 100%; fill: none; stroke: currentColor; stroke-linecap: round; stroke-linejoin: round; }
  deft-number, deft-number[value] { display: inline-flex; font-family: var(--deft-font-numeric); font-weight: var(--deft-font-numeric-weight); font-variant-numeric: tabular-nums; }
  .deft-toast[data-tone=error]::before { content: "!"; color: var(--deft-error); font-weight: 700; margin-inline-end: .5em; }

  /* Widgets: <deft-widget app size> spans its size in .deft-widgets (4 columns, rows of 7 pads). Frames fill it; the placeholder is quiet. */
  .deft-widgets { display: grid; grid-template-columns: repeat(4, minmax(0, 1fr)); grid-auto-rows: calc(var(--deft-pad) * 7); gap: var(--deft-pad); }
  deft-widget { --deft-w: 1; --deft-h: 1; position: relative; display: block; min-inline-size: 0; min-block-size: calc(var(--deft-h) * var(--deft-pad) * 7); overflow: hidden; border-radius: var(--deft-radius-lg); background: var(--deft-bg-grouped); grid-column: span var(--deft-w); grid-row: span var(--deft-h); }
  /* Outside .deft-widgets (a canvas that forgot the wrapper) it still takes its cells: w of the parent's 4 columns, h rows of 7 pads. */
  deft-widget:not(.deft-widgets > *) { inline-size: calc((100% - 3 * var(--deft-pad)) / 4 * var(--deft-w) + (var(--deft-w) - 1) * var(--deft-pad)); block-size: calc(var(--deft-h) * var(--deft-pad) * 7 + (var(--deft-h) - 1) * var(--deft-pad)); }
  deft-widget[size="2x1"] { --deft-w: 2; } deft-widget[size="2x2"] { --deft-w: 2; --deft-h: 2; }
  deft-widget[size="4x1"] { --deft-w: 4; } deft-widget[size="4x2"] { --deft-w: 4; --deft-h: 2; }
  /* A widget page's body: transparent, so the host's deft-widget card (--deft-bg-grouped) shows through the frame. */
  .deft-widget-body, .deft-in-widget, .deft-in-widget body { background: transparent; }
  deft-widget > iframe { position: absolute; inset: 0; inline-size: 100%; block-size: 100%; border: 0; }
  .deft-widget-quiet { display: grid; place-items: center; block-size: 100%; margin: 0; padding: var(--deft-space-xs); text-align: center; font-size: var(--deft-type-footnote-size); color: var(--deft-label-2); }
  .deft-widget-setup { align-content: center; gap: var(--deft-space-xs); text-decoration: none; cursor: pointer; background: var(--deft-fill); color: var(--deft-label); }
  .deft-widget-setup > b { font-weight: 600; color: var(--deft-accent); }

  /* Accessibility fallbacks (3.3). deft.components wins over deft.theme. */
  @media (prefers-reduced-transparency: reduce) {
    :root { --deft-material-bar: var(--deft-material-bar-reduced); --deft-material-blur: none; }
  }
  @media (prefers-contrast: more) {
    :root { --deft-material-bar: var(--deft-material-bar-reduced); --deft-material-blur: none; --deft-separator: var(--deft-label-3); }
  }
}
`;

// src/design.js
var ACCENT_SELECTORS = /\[data-primary\]|\.deft-btn-primary|\.deft-tabbar|\.deft-badge|\[data-live\]/;
var LEGACY = {
  "color-base-100": "--deft-bg",
  "color-base-200": "--deft-bg-grouped",
  "color-base-300": "--deft-bg-elevated",
  "color-base-content": "--deft-label",
  "color-primary": "--deft-accent",
  "color-primary-content": "--deft-on-accent",
  "color-secondary": "--deft-fill-2",
  "color-secondary-content": "--deft-label",
  "color-accent": "--deft-accent",
  "color-accent-content": "--deft-on-accent",
  "color-neutral": "--deft-fill",
  "color-neutral-content": "--deft-label",
  "color-info": "--deft-label-2",
  "color-info-content": "--deft-label",
  "color-success": "--deft-success",
  "color-success-content": "--deft-label",
  "color-warning": "--deft-warning",
  "color-warning-content": "--deft-label",
  "color-error": "--deft-error",
  "color-error-content": "--deft-label",
  fg: "--deft-label",
  surface: "--deft-bg-grouped",
  muted: "--deft-label-2",
  red: "--deft-error"
};
var THEME_TOKENS = [
  "appearance",
  "accent",
  "on-accent",
  "bg",
  "bg-grouped",
  "bg-elevated",
  "label",
  "label-2",
  "label-3",
  "label-4",
  "fill",
  "fill-2",
  "fill-3",
  "fill-4",
  "separator",
  "error",
  "warning",
  "success",
  "info",
  "tier",
  "material-bar",
  "material-bar-reduced",
  "material-blur",
  "border",
  "density",
  "radius-box",
  "radius-field",
  "radius-selector"
].map((n) => "--deft-" + n);
var HOOKS = [
  "--deft-min",
  "--deft-gap",
  "--deft-density",
  "--deft-side",
  "--deft-measure",
  "--deft-cols",
  "--deft-pad",
  "--deft-radius-outer",
  "--deft-value",
  "--deft-segs",
  "--deft-level",
  "--deft-sheet-h",
  "--deft-snap",
  "--deft-snap-align"
];
var DECLARED = /(--deft-[a-z0-9-]+)\s*:/g;
var KNOWN_TOKENS = new Set([...THEME_TOKENS, ...HOOKS]);
for (const css of [base_default, tokens_default, components_default])
  for (const m of css.matchAll(DECLARED))
    KNOWN_TOKENS.add(m[1]);
var LEGACY_NAME = /^--deft-(color-[a-z0-9-]+|fg|surface|muted|red)$/;
function editDistance(a, b) {
  let prev = Array.from({ length: b.length + 1 }, (_, j) => j);
  for (let i = 1;i <= a.length; i++) {
    const row = [i];
    for (let j = 1;j <= b.length; j++)
      row[j] = Math.min(prev[j] + 1, row[j - 1] + 1, prev[j - 1] + (a[i - 1] === b[j - 1] ? 0 : 1));
    prev = row;
  }
  return prev[b.length];
}
var GUESSES = [
  [/^--deft-(on-surface-variant|on-background-variant|text-(?:muted|secondary)|muted-foreground)$/, "--deft-label-2"],
  [/^--deft-(on-surface|on-background|text|foreground|text-primary)$/, "--deft-label"],
  [/^--deft-(surface|surface-container|card|card-bg|background-grouped)$/, "--deft-bg-grouped"],
  [/^--deft-(background|bg-primary|page-bg)$/, "--deft-bg"],
  [/^--deft-(primary|brand)$/, "--deft-accent"],
  [/^--deft-(outline|border-color|divider)$/, "--deft-separator"],
  [/^--deft-font-size-(xs|sm|s)$/, "--deft-text-s"],
  [/^--deft-font-size-(md|m|base)$/, "--deft-text-m"],
  [/^--deft-font-size-(lg|l)$/, "--deft-text-l"],
  [/^--deft-font-size-(xl|2xl)$/, "--deft-text-xl"],
  [/^--deft-space-([0-9]+)$/, (m) => ["--deft-space-3xs", "--deft-space-2xs", "--deft-space-xs", "--deft-space-s", "--deft-space-m", "--deft-space-l", "--deft-space-xl", "--deft-space-2xl"][Math.min(Number(m[1]), 8) - 1] || "--deft-space-s"]
];
function nearestToken(name) {
  for (const [re, to] of GUESSES) {
    const m = re.exec(name);
    if (m)
      return typeof to === "function" ? to(m) : to;
  }
  let best = "";
  let bestScore = Infinity;
  for (const known of KNOWN_TOKENS) {
    if (LEGACY_NAME.test(known))
      continue;
    const score = editDistance(name, known) - (known.startsWith(name.slice(0, name.length - 1)) ? 2 : 0);
    if (score < bestScore || score === bestScore && known < best) {
      best = known;
      bestScore = score;
    }
  }
  return best;
}
function designTokens(path, source, out) {
  const text = source.replace(/\/\*[\s\S]*?\*\//g, (m) => m.replace(/[^\n]/g, " "));
  const local = new Set;
  for (const m of text.matchAll(DECLARED))
    local.add(m[1]);
  const at = locate(source);
  for (const m of text.matchAll(/var\(\s*(--deft-[a-z0-9-]*)(?![a-z0-9-]|\$\{|["'`])/g)) {
    const name = m[1];
    if (KNOWN_TOKENS.has(name) || local.has(name) || LEGACY_NAME.test(name))
      continue;
    const pos = at(m.index);
    out.push(issue("FL638", "error", path, pos.line, pos.col, `${name} is not a design token, so the value silently falls back; did you mean var(${nearestToken(name)})? (tokens: references/tokens.md)`));
  }
}
var GLYPH = /^(?:[‹›✕✖☰+×←→↑↓⋮⋯✓✔✗]|&(?:lsaquo|rsaquo|times|larr|rarr|uarr|darr|hellip|check|#x?[0-9a-f]+);)$/i;
function issue(id, severity, path, line, col, message) {
  return { id, severity, path, line, col, message };
}
function blankComments(source) {
  return source.replace(/\/\*[\s\S]*?\*\//g, (m) => m.replace(/[^\n]/g, " "));
}
function locate(source) {
  const starts = [0];
  for (let i = 0;i < source.length; i++)
    if (source[i] === `
`)
      starts.push(i + 1);
  return (index) => {
    let lo = 0;
    let hi = starts.length - 1;
    while (lo < hi) {
      const mid = lo + hi + 1 >> 1;
      if (starts[mid] <= index)
        lo = mid;
      else
        hi = mid - 1;
    }
    return { line: lo + 1, col: index - starts[lo] + 1 };
  };
}
function innermostBlocks(css) {
  const blocks = [];
  const re = /([^{};]*)\{([^{}]*)\}/g;
  let m;
  while (m = re.exec(css)) {
    blocks.push({ selector: m[1].trim(), body: m[2], bodyStart: m.index + m[1].length + 1 });
  }
  return blocks;
}
function unlayeredPreludes(css) {
  const found = [];
  let depth = 0;
  let prelude = "";
  let preludeStart = 0;
  for (let i = 0;i < css.length; i++) {
    const c = css[i];
    if (c === "{") {
      if (depth === 0) {
        const text = prelude.trim();
        const ok = /^@layer\b/i.test(text) || /^@font-face\b/i.test(text) || text === "";
        if (!ok)
          found.push({ text, index: preludeStart + prelude.search(/\S/) });
      }
      depth++;
      prelude = "";
      preludeStart = i + 1;
    } else if (c === "}") {
      depth = Math.max(0, depth - 1);
      prelude = "";
      preludeStart = i + 1;
    } else if (c === ";") {
      if (depth === 0) {
        prelude = "";
        preludeStart = i + 1;
      }
    } else if (depth === 0) {
      prelude += c;
    }
  }
  return found;
}
function designCss(path, source, out) {
  designTokens(path, source, out);
  const at = locate(source);
  const css = blankComments(source);
  for (const b of innermostBlocks(css)) {
    if (/--deft-(on-)?accent\b/.test(b.body) && !ACCENT_SELECTORS.test(b.selector)) {
      const hit = /--deft-(on-)?accent\b/.exec(b.body);
      const pos = at(b.bodyStart + hit.index);
      out.push(issue("FL616", "warn", path, pos.line, pos.col, `accent token outside the primary button, tab-bar dot, badge or [data-live]; use --deft-label (${b.selector || "rule"})`));
    }
  }
  const legacy = /var\(\s*--deft-(color-[a-z0-9-]+|fg|surface|muted|red)\b/g;
  let m;
  while (m = legacy.exec(css)) {
    const name = m[1];
    const pos = at(m.index);
    out.push(issue("FL619", "warn", path, pos.line, pos.col, `deprecated token --deft-${name}; use ${LEGACY[name] || "a semantic token"}`));
  }
  const keyframes = /@keyframes\b/gi;
  while (m = keyframes.exec(css)) {
    const pos = at(m.index);
    out.push(issue("FL627", "error", path, pos.line, pos.col, "@keyframes is not allowed; use data-deft-enter, a spring token or a view transition"));
  }
  const easing = /(?<![\w-])(cubic-bezier|steps)\(/gi;
  while (m = easing.exec(css)) {
    const pos = at(m.index);
    out.push(issue("FL628", "error", path, pos.line, pos.col, `raw ${m[1]}() easing; use var(--deft-ease*) or a spring token`));
  }
  const decl = /(?:^|[{;\s])((?:transition|animation)(?:-duration)?)\s*:\s*([^;{}]*)/gi;
  while (m = decl.exec(css)) {
    const value = m[2];
    if (/var\(/.test(value))
      continue;
    const raw = /(?<![\w.-])(?!0+(?:\.0+)?m?s\b)\d*\.?\d+m?s\b/.exec(value);
    if (!raw)
      continue;
    const pos = at(m.index + m[0].length - value.length + raw.index);
    out.push(issue("FL631", "warn", path, pos.line, pos.col, `raw duration ${raw[0]}; use a --deft-duration-* or --deft-spring-*-duration token`));
  }
  const oklch = /(?<![\w-])oklch\(\s*[\d.]/gi;
  while (m = oklch.exec(css)) {
    const pos = at(m.index);
    out.push(issue("FL610", "error", path, pos.line, pos.col, "oklch literal; use a var(--deft-*) token"));
  }
  for (const u of unlayeredPreludes(css)) {
    if (/^@(import|charset|namespace)\b/i.test(u.text))
      continue;
    const pos = at(u.index);
    out.push(issue("FL629", "warn", path, pos.line, pos.col, "unlayered CSS; put rules in @layer deft.components or @layer deft.utilities"));
  }
}
function nearestIcons(name) {
  const dist = (a, b) => {
    let prev = Array.from({ length: b.length + 1 }, (_, j) => j);
    for (let i = 1;i <= a.length; i++) {
      const row = [i];
      for (let j = 1;j <= b.length; j++)
        row[j] = Math.min(prev[j] + 1, row[j - 1] + 1, prev[j - 1] + (a[i - 1] === b[j - 1] ? 0 : 1));
      prev = row;
    }
    return prev[b.length];
  };
  return ICONS.map((n) => ({ n, d: n.includes(name) || name.includes(n) ? 0 : dist(name, n) })).filter((x) => x.d <= Math.max(2, name.length / 3)).sort((a, b) => a.d - b.d || a.n.localeCompare(b.n)).slice(0, 3).map((x) => x.n);
}
function designIcons(path, source, out) {
  const at = locate(source);
  const known = new Set(ICONS);
  const report = (name, index) => {
    if (!name || known.has(name) || /[${}]/.test(name))
      return;
    const near = nearestIcons(name);
    const pos = at(index);
    out.push(issue("FL632", "warn", path, pos.line, pos.col, `unknown icon "${name}"; it draws nothing${near.length ? `. Nearest: ${near.join(", ")}` : ""}. See references/icons.md`));
  };
  let m;
  const tag = /<deft-icon\b[^>]*?\bname\s*=\s*(["'])([^"']*)\1/gi;
  while (m = tag.exec(source))
    report(m[2], m.index);
  const use = /icons\.svg#([A-Za-z0-9_-]+)/g;
  while (m = use.exec(source))
    report(m[1], m.index);
}
function designHtml(path, source, out) {
  designTokens(path, source, out);
  designIcons(path, source, out);
  const at = locate(source);
  const html = source.replace(/<!--[\s\S]*?-->/g, (m) => m.replace(/[^\n]/g, " "));
  const button = /<button\b[^>]*>([^<]*)<\/button\s*>/gi;
  let m;
  while (m = button.exec(html)) {
    if (!GLYPH.test(m[1].trim()))
      continue;
    const pos = at(m.index);
    out.push(issue("FL618", "warn", path, pos.line, pos.col, 'glyph used as an icon; use <deft-icon name="..."> (Lucide)'));
  }
  const tone = /<([a-z][a-z0-9-]*)\b([^>]*\bdata-tone\s*=\s*["']error["'][^>]*)>([\s\S]*?)<\/\1\s*>/gi;
  while (m = tone.exec(html)) {
    if (!/\bclass\s*=\s*["'][^"']*\bdeft-(alert|toast)\b/i.test(m[2]))
      continue;
    const inner = m[3];
    const hasIcon = /<(svg|deft-icon)\b/i.test(inner);
    const hasText = inner.replace(/<[^>]*>/g, "").trim().length > 0 || /\b(data-deft-text|aria-live)\b/i.test(m[2] + inner);
    if (hasIcon && hasText)
      continue;
    const pos = at(m.index);
    out.push(issue("FL617", "warn", path, pos.line, pos.col, "error colour needs an icon and text, not colour alone"));
  }
  const scopes = [];
  let rest = html;
  const carve = (re, endOf) => {
    let mm;
    while (mm = re.exec(rest)) {
      const end = endOf(rest, mm);
      if (end < 0)
        continue;
      scopes.push({ text: rest.slice(mm.index, end), offset: mm.index });
      rest = rest.slice(0, mm.index) + rest.slice(mm.index, end).replace(/[^\n]/g, " ") + rest.slice(end);
      re.lastIndex = end;
    }
  };
  carve(/<dialog\b/gi, (t, mm) => {
    const e = t.slice(mm.index).search(/<\/dialog\s*>/i);
    return e < 0 ? -1 : mm.index + e + 9;
  });
  const attr = String.raw`(?:\s+[^\s=>\/]+(?:\s*=\s*(?:"[^"]*"|'[^']*'|[^\s>"']+))?)`;
  carve(new RegExp(String.raw`<([a-z][a-z0-9-]*)${attr}*?\s+hidden(?=[\s>\/=])${attr}*\s*\/?>`, "gi"), (t, mm) => {
    const tag = mm[1].toLowerCase();
    const tags = new RegExp(`<(/?)${tag}\\b[^>]*>`, "gi");
    tags.lastIndex = mm.index + mm[0].length;
    let depth = 1, x;
    while (x = tags.exec(t)) {
      if (x[0].endsWith("/>"))
        continue;
      depth += x[1] ? -1 : 1;
      if (!depth)
        return x.index + x[0].length;
    }
    return -1;
  });
  scopes.push({ text: rest, offset: 0 });
  for (const scope of scopes) {
    const primary = /<(button|a|input)\b[^>]*\sdata-primary\b[^>]*>/gi;
    let count = 0;
    while (m = primary.exec(scope.text)) {
      count++;
      if (count === 2) {
        const pos = at(scope.offset + m.index);
        out.push(issue("FL630", "warn", path, pos.line, pos.col, "more than one primary button on this screen (a dialog, sheet or [hidden] view counts as its own screen; lint cannot see routes); keep one accent action"));
      }
    }
  }
}
function designHandlers(files, out) {
  const names = Object.keys(files).filter((n) => typeof files[n] === "string");
  const js = names.filter((n) => /\.m?js$/i.test(n)).map((n) => files[n]).join(`
`);
  if (!js.trim())
    return;
  const delegated = /closest\(\s*["'`](?:button|\[data-action\]|\.deft-btn)/.test(js);
  const referenced = (token) => new RegExp(`(?<![\\w-])${token.replace(/[.*+?^${}()|[\]\\]/g, "\\$&")}(?![\\w-])`).test(js);
  for (const name of names.filter((n) => /\.html?$/i.test(n))) {
    const source = files[name].replace(/<!--[\s\S]*?-->/g, (m) => m.replace(/[^\n]/g, " "));
    const at = locate(source);
    const forms = [];
    const formRe = /<form\b[\s\S]*?<\/form\s*>/gi;
    let f;
    while (f = formRe.exec(source))
      forms.push([f.index, f.index + f[0].length]);
    const tag = /<(button|[a-z][a-z0-9-]*)\b([^>]*)>/gi;
    let m;
    while (m = tag.exec(source)) {
      const attrs = m[2];
      const isButton = m[1].toLowerCase() === "button";
      const action = /\bdata-action\s*=\s*["']([^"']+)["']/i.exec(attrs);
      if (!isButton && !action)
        continue;
      if (/\b(popovertarget|command|commandfor|formmethod)\b/i.test(attrs))
        continue;
      const type = /\btype\s*=\s*["']?(\w+)/i.exec(attrs);
      const inForm = forms.some(([s, e]) => m.index > s && m.index < e);
      if (isButton && inForm && (!type || type[1].toLowerCase() === "submit"))
        continue;
      if (isButton && type && type[1].toLowerCase() === "submit")
        continue;
      const id = /\bid\s*=\s*["']([^"']+)["']/i.exec(attrs);
      const cls = /\bclass\s*=\s*["']([^"']+)["']/i.exec(attrs);
      const tokens = [];
      if (action)
        tokens.push(action[1]);
      if (id)
        tokens.push(id[1]);
      if (cls)
        tokens.push(...cls[1].split(/\s+/).filter((c) => c && !c.startsWith("deft-") && !/^(is-|has-)/.test(c)));
      if (!tokens.length || delegated)
        continue;
      if (tokens.some(referenced))
        continue;
      const pos = at(m.index);
      out.push(issue("FL633", "warn", name, pos.line, pos.col, `interactive element (${tokens.join(", ")}) is never referenced in the app's JS; it does nothing. Wire a handler and test the action`));
    }
  }
}

// src/extra.js
var FL_MODULES = new Set([
  "core.js",
  "dom.js",
  "events.js",
  "store.js",
  "app.js",
  "route.js",
  "bridge.js",
  "index.js",
  "motion.js",
  "sheet.js",
  "elements.js"
]);
var ALLOWED_EXT = new Set([
  "html",
  "htm",
  "js",
  "css",
  "json",
  "svg",
  "png",
  "webp",
  "jpg",
  "jpeg",
  "gif",
  "avif",
  "woff2",
  "wasm",
  "txt",
  "md"
]);
var TEXT_LIMIT = 512 * 1024;
var PACKAGE_LIMIT = 10 * 1024 * 1024;
function issue2(id, severity, path, line, col, message) {
  return { id, severity, path, line, col, message };
}
function lineCount(source) {
  if (!source)
    return 0;
  const n = source.split(`
`).length;
  return source.endsWith(`
`) ? n - 1 : n;
}
function firstLine(source) {
  const lines = source.split(`
`);
  for (let i = 0;i < lines.length; i++) {
    if (lines[i].trim())
      return { line: i + 1, text: lines[i].trim() };
  }
  return null;
}
function stripComments(source) {
  let out = "";
  let i = 0;
  let quote = "";
  while (i < source.length) {
    const c = source[i];
    if (quote) {
      out += c;
      if (c === "\\") {
        out += source[i + 1] ?? "";
        i += 2;
        continue;
      }
      if (c === quote)
        quote = "";
      i++;
      continue;
    }
    if (c === "'" || c === '"' || c === "`") {
      quote = c;
      out += c;
      i++;
      continue;
    }
    if (c === "/" && source[i + 1] === "/") {
      while (i < source.length && source[i] !== `
`) {
        out += " ";
        i++;
      }
      continue;
    }
    if (c === "/" && source[i + 1] === "*") {
      out += "  ";
      i += 2;
      while (i < source.length && !(source[i] === "*" && source[i + 1] === "/")) {
        out += source[i] === `
` ? `
` : " ";
        i++;
      }
      if (source[i] === "*") {
        out += "  ";
        i += 2;
      }
      continue;
    }
    out += c;
    i++;
  }
  return out;
}
function isDocument(source) {
  return /<!doctype\s+html/i.test(source) || /<html[\s>]/i.test(source) || /<head[\s>]/i.test(source);
}
var declaredUrls = new Set;
var declaredAliases = new Set;
function setDependencies(deps) {
  declaredUrls = new Set(deps.urls);
  declaredAliases = new Set(deps.aliases);
}
function afterJs(path, source, tokens, at, out, mode) {
  designIcons(path, source, out);
  designTokens(path, source, out);
  const liveMarked = /data-live|dataset\.live/.test(source);
  const dialogs = new Set;
  for (const m of source.matchAll(/import\s*\{([^}]*)\}\s*from\s*['"]\/_deft\/v1\/(?:app|index)\.js['"]/g)) {
    for (const part of m[1].split(",")) {
      const [orig, local] = part.trim().split(/\s+as\s+/);
      if (orig === "confirm" || orig === "prompt")
        dialogs.add(local || orig);
    }
  }
  for (let i = 0;i < tokens.length; i++) {
    const t = tokens[i];
    if (t.type === "string" && /--deft-(on-)?accent\b/.test(t.value) && !liveMarked) {
      const pos = at(t.index);
      out.push(issue2("FL616", "warn", path, pos.line, pos.col, "accent read in JS without data-live; draw in --deft-label unless this is the screen's one [data-live] signal"));
    }
    const prev = tokens[i - 1];
    const dotted = prev && prev.type === "punct" && prev.value === ".";
    if (t.type === "ident") {
      const next = tokens[i + 1];
      const call = next && next.type === "punct" && next.value === "(";
      if (t.value === "import" || t.value === "export")
        classifyModule(path, tokens, i, at, out);
      if (!dotted && (t.value === "alert" || t.value === "confirm" || t.value === "prompt") && call && !dialogs.has(t.value)) {
        const pos = at(t.index);
        out.push(issue2("FL105", "error", path, pos.line, pos.col, `${t.value}() is not allowed; use deft.confirm, deft.prompt or dialog() from /_deft/v1/app.js`));
      }
      if ((t.value === "alert" || t.value === "confirm" || t.value === "prompt") && call && dotted) {
        const obj = tokens[i - 2];
        if (obj && obj.type === "ident" && obj.value === "window") {
          const pos = at(t.index);
          out.push(issue2("FL105", "error", path, pos.line, pos.col, `${t.value}() is not allowed; use dialog()`));
        }
      }
      if (t.value === "outerHTML" || t.value === "createContextualFragment") {
        const pos = at(t.index);
        out.push(issue2("FL100", "error", path, pos.line, pos.col, `${t.value} is not allowed`));
      }
      if (t.value === "DOMParser") {
        const dot = tokens[i + 1];
        const method = tokens[i + 2];
        if (dot && dot.value === "." && method && method.value === "parseFromString") {
          const pos = at(t.index);
          out.push(issue2("FL100", "error", path, pos.line, pos.col, "DOMParser.parseFromString is not allowed"));
        }
      }
      if ((t.value === "setTimeout" || t.value === "setInterval") && call) {
        const arg = tokens[i + 2];
        if (arg && arg.type === "string") {
          const pos = at(t.index);
          out.push(issue2("FL101", "error", path, pos.line, pos.col, `string ${t.value}() is not allowed`));
        }
      }
      if (t.value === "Function" && call && !dotted) {
        const before = tokens[i - 1];
        const keyword = before && before.type === "ident" && (before.value === "function" || before.value === "new");
        if (!keyword) {
          const pos = at(t.index);
          out.push(issue2("FL101", "error", path, pos.line, pos.col, "Function() is not allowed"));
        }
      }
      if (t.value === "writeln" && dotted) {
        const obj = tokens[i - 2];
        if (obj && obj.value === "document") {
          const pos = at(obj.index);
          out.push(issue2("FL100", "error", path, pos.line, pos.col, "document.writeln is not allowed"));
        }
      }
      if ((t.value === "serviceWorker" || t.value === "SharedWorker") && !dotted) {
        const pos = at(t.index);
        out.push(issue2("FL109", "error", path, pos.line, pos.col, `${t.value} is not supported`));
      }
      if (t.value === "serviceWorker" && dotted) {
        const pos = at(t.index);
        out.push(issue2("FL109", "error", path, pos.line, pos.col, "service worker registration is not supported"));
      }
      if (t.value === "open" && dotted && call) {
        const obj = tokens[i - 2];
        if (obj && obj.value === "window") {
          const pos = at(t.index);
          out.push(issue2("FL110", "error", path, pos.line, pos.col, "window.open is not allowed"));
        }
      }
      if (t.value.startsWith("__deft") || t.value.startsWith("__fl")) {
        const pos = at(t.index);
        out.push(issue2("FL113", "error", path, pos.line, pos.col, "access to host internal __deft*"));
      }
      if (t.value === "deftBridge" || t.value === "freedomBridge") {
        const pos = at(t.index);
        out.push(issue2("FL200", "warn", path, pos.line, pos.col, "direct deftBridge; use call() from bridge.js"));
      }
      if (t.value === "forEach" && call) {
        const arg = tokens[i + 2];
        if (arg && arg.type === "ident" && arg.value === "async") {
          const pos = at(t.index);
          out.push(issue2("FL400", "warn", path, pos.line, pos.col, "async callback in forEach; use for...of + await"));
        }
      }
      const legacyRead = t.value === "localStorage" && tokens[i + 1] && tokens[i + 1].value === "." && tokens[i + 2] && tokens[i + 2].value === "getItem" && nearTry(source, t.index);
      if (t.value === "localStorage" && !legacyRead) {
        const pos = at(t.index);
        out.push(issue2("FL403", "warn", path, pos.line, pos.col, "raw localStorage; use store()"));
      }
      if (t.value === "parse" && dotted) {
        const obj = tokens[i - 2];
        if (obj && obj.value === "JSON" && !nearTry(source, t.index)) {
          const pos = at(t.index);
          out.push(issue2("FL403", "warn", path, pos.line, pos.col, "JSON.parse outside try; use store()"));
        }
      }
      if (t.value === "var" && !dotted) {
        const pos = at(t.index);
        out.push(issue2("FL500", "warn", path, pos.line, pos.col, "var; use let or const"));
      }
      if (t.value === "log" && dotted) {
        const obj = tokens[i - 2];
        if (obj && obj.value === "console" && call) {
          const pos = at(t.index);
          out.push(issue2("FL504", "info", path, pos.line, pos.col, "leftover console.log"));
        }
      }
      if ((t.value === "fetch" || t.value === "EventSource" || t.value === "WebSocket") && call) {
        const arg = tokens[i + 2];
        const literal = arg && arg.type === "string" && !arg.tmpl;
        if (!literal) {
          const pos = at(t.index);
          out.push(issue2("FL206", "info", path, pos.line, pos.col, "network URL is not statically known"));
        }
      }
      noteBridgeCall(path, tokens, i, at, out);
    }
    if (t.type === "punct" && t.value === "=") {
      const prevP = tokens[i - 1];
      const continued = prevP && prevP.type === "punct" && (prevP.value === "=" || prevP.value === "!" || prevP.value === "<" || prevP.value === ">");
      const n1 = tokens[i + 1];
      const n2 = tokens[i + 2];
      if (!continued && n1 && n1.type === "punct" && n1.value === "=" && !(n2 && n2.type === "punct" && n2.value === "=")) {
        const pos = at(t.index);
        out.push(issue2("FL501", "warn", path, pos.line, pos.col, "== ; use ==="));
      }
    }
    if (t.type === "punct" && t.value === "!") {
      const n1 = tokens[i + 1];
      const n2 = tokens[i + 2];
      if (n1 && n1.type === "punct" && n1.value === "=" && !(n2 && n2.type === "punct" && n2.value === "=")) {
        const pos = at(t.index);
        out.push(issue2("FL501", "warn", path, pos.line, pos.col, "!= ; use !=="));
      }
    }
    if (t.type === "string" && /javascript\s*:/i.test(t.value)) {
      const pos = at(t.index);
      out.push(issue2("FL107", "error", path, pos.line, pos.col, "javascript: URL"));
    }
  }
  const lines = lineCount(source);
  const base = path.split("/").pop();
  if (base === "main.js" && lines > 80 || lines > 200) {
    out.push(issue2("FL502", "warn", path, 1, 1, base === "main.js" && lines > 80 ? "main.js is longer than 80 lines" : "file is longer than 200 lines"));
  }
  const header = firstLine(source);
  if (header && !header.text.startsWith("//") && !header.text.startsWith("/*")) {
    out.push(issue2("FL503", "warn", path, header.line, 1, "missing header comment"));
  }
  if (mode === "publish")
    flagLongLines(path, source, out);
}
function classifyModule(path, tokens, i, at, out) {
  const dynamic = tokens[i + 1] && tokens[i + 1].type === "punct" && tokens[i + 1].value === "(";
  let spec = null;
  if (dynamic) {
    const arg = tokens[i + 2];
    if (!arg || arg.type !== "string" || arg.tmpl) {
      const pos = at(tokens[i].index);
      out.push(issue2("FL007", "error", path, pos.line, pos.col, "import() specifier must be a string literal"));
      return;
    }
    spec = arg;
  } else {
    spec = fromString(tokens, i);
  }
  if (!spec)
    return;
  const value = spec.value;
  const pos = at(spec.index);
  if (value.startsWith("./") || value.startsWith("../")) {
    const file = value.split("?")[0].split("#")[0];
    if (!file.endsWith(".js")) {
      out.push(issue2("FL002", "error", path, pos.line, pos.col, "relative import without .js"));
    }
    return;
  }
  if (value.startsWith("/_deft/v1/")) {
    const mod = value.slice("/_deft/v1/".length).split("?")[0].split("#")[0];
    if (!FL_MODULES.has(mod)) {
      out.push(issue2("FL005", "error", path, pos.line, pos.col, `unknown /_deft/v1/ module "${mod}"`));
    }
    return;
  }
  if (declaredUrls.has(value) || declaredAliases.has(value))
    return;
  if (tokens[i].value === "import" || value) {
    const remote = /^https?:\/\//i.test(value);
    const message = remote ? "remote import not in manifest dependencies; add the exact URL with its hash (or null to pin on first download)" : "bare or remote import specifier";
    out.push(issue2("FL001", "error", path, pos.line, pos.col, message));
  }
}
function fromString(tokens, i) {
  const n = tokens[i + 1];
  if (n && n.type === "string" && !n.tmpl)
    return n;
  const stop = Math.min(tokens.length, i + 40);
  for (let j = i + 1;j < stop; j++) {
    const t = tokens[j];
    if (t.type === "punct" && t.value === ";")
      return null;
    if (t.type === "ident" && t.value === "from") {
      const s = tokens[j + 1];
      return s && s.type === "string" && !s.tmpl ? s : null;
    }
  }
  return null;
}
function nearTry(source, index) {
  const start = Math.max(0, index - 180);
  return /\btry\b/.test(source.slice(start, index));
}
function noteBridgeCall(path, tokens, i, at, out) {
  const t = tokens[i];
  if (t.value !== "invoke" && t.value !== "call" && t.value !== "subscribe")
    return;
  const next = tokens[i + 1];
  if (!next || next.type !== "punct" || next.value !== "(")
    return;
  const prev = tokens[i - 1];
  if (prev && prev.type === "ident" && prev.value === "function")
    return;
  if (t.value === "call" && prev && prev.type === "punct" && prev.value === ".") {
    const obj = tokens[i - 2];
    if (!obj || obj.value !== "deftBridge" && obj.value !== "freedomBridge")
      return;
  }
  const arg = tokens[i + 2];
  if (!arg || arg.type !== "string" || arg.tmpl) {
    const pos = at(t.index);
    out.push(issue2("FL207", "error", path, pos.line, pos.col, "call() method name must be a literal"));
  }
}
function flagLongLines(path, source, out) {
  const lines = source.split(`
`);
  for (let i = 0;i < lines.length; i++) {
    if (lines[i].length > 400) {
      out.push(issue2("FL112", "error", path, i + 1, 1, "line is longer than 400 characters"));
      return;
    }
  }
}
function afterHtml(path, source, out, mode) {
  const at = indexAt(source);
  const comments = source.replace(/<!--[\s\S]*?-->/g, " ");
  if (/<style\b/i.test(comments)) {
    const pos = at(comments.search(/<style\b/i));
    out.push(issue2("FL103", "error", path, pos.line, pos.col, "inline style; use ./app.css"));
  }
  const scriptRe = /<script\b[^>]*>/gi;
  let script;
  while (script = scriptRe.exec(comments)) {
    if (!/\bsrc\s*=/i.test(script[0])) {
      const pos = at(script.index);
      out.push(issue2("FL103", "error", path, pos.line, pos.col, "inline script; use ./main.js"));
    }
  }
  const onRe = /\s(on[a-z]+)\s*=/gi;
  let onAttr;
  const tagRe = /<[^>]+>/g;
  let tag;
  while (tag = tagRe.exec(comments)) {
    if (tag[0].startsWith("</") || tag[0].startsWith("<!"))
      continue;
    onRe.lastIndex = 0;
    let hit;
    while (hit = onRe.exec(tag[0])) {
      const pos = at(tag.index + hit.index + 1);
      out.push(issue2("FL102", "error", path, pos.line, pos.col, `${hit[1].toLowerCase()} attribute is not allowed`));
      const name = tagName(tag[0]);
      if (name === "div" || name === "span") {
        out.push(issue2("FL623", "warn", path, pos.line, pos.col, `click handler on ${name}; use button`));
      }
    }
  }
  for (const name of ["iframe", "object", "embed", "frame", "base"]) {
    const re = new RegExp(`<${name}\\b`, "i");
    if (re.test(comments)) {
      const pos = at(comments.search(re));
      out.push(issue2("FL108", "error", path, pos.line, pos.col, `<${name}> is not supported`));
    }
  }
  if (/<meta\b[^>]*http-equiv\s*=/i.test(comments)) {
    const pos = at(comments.search(/<meta\b[^>]*http-equiv\s*=/i));
    out.push(issue2("FL108", "error", path, pos.line, pos.col, "meta http-equiv is not supported"));
  }
  if (/javascript\s*:/i.test(comments)) {
    const pos = at(comments.search(/javascript\s*:/i));
    out.push(issue2("FL107", "error", path, pos.line, pos.col, "javascript: URL"));
  }
  const ids = new Map;
  const idRe = /\sid\s*=\s*["']([^"']+)["']/gi;
  let idMatch;
  while (idMatch = idRe.exec(comments)) {
    const id = idMatch[1];
    if (ids.has(id)) {
      const pos = at(idMatch.index);
      out.push(issue2("FL301", "error", path, pos.line, pos.col, `duplicate id "${id}"`));
    } else
      ids.set(id, idMatch.index);
  }
  const imgRe = /<img\b[^>]*>/gi;
  let img;
  while (img = imgRe.exec(comments)) {
    if (!/\balt\s*=/i.test(img[0])) {
      const pos = at(img.index);
      out.push(issue2("FL620", "error", path, pos.line, pos.col, "img without alt"));
    }
  }
  const buttonRe = /<button\b([^>]*)>([\s\S]*?)<\/button>/gi;
  let button;
  while (button = buttonRe.exec(comments)) {
    const attrs = button[1];
    const text = button[2].replace(/<[^>]*>/g, "").trim();
    if (/\baria-label\s*=/i.test(attrs) || /\baria-labelledby\s*=/i.test(attrs) || /\btitle\s*=/i.test(attrs) || text)
      continue;
    const pos = at(button.index);
    out.push(issue2("FL621", "error", path, pos.line, pos.col, "button without an accessible name"));
  }
  flagUnlabeledControls(path, comments, at, out);
  const tabRe = /\stabindex\s*=\s*["']?(\d+)/gi;
  let tab;
  while (tab = tabRe.exec(comments)) {
    if (Number(tab[1]) > 0) {
      const pos = at(tab.index);
      out.push(issue2("FL626", "warn", path, pos.line, pos.col, "positive tabindex"));
    }
  }
  const svgRe = /<svg\b[\s\S]*?<\/svg>/gi;
  let svg;
  while (svg = svgRe.exec(comments))
    scanSvg(path, svg[0], out);
  if (isDocument(source))
    scanDocument(path, source, out);
  if (mode === "publish")
    flagLongLines(path, source, out);
  const header = firstLine(source);
  if (isDocument(source) && header && !source.slice(0, 400).includes("<!--")) {
    out.push(issue2("FL503", "warn", path, 1, 1, "missing header comment"));
  }
}
function tagName(tag) {
  const m = tag.match(/^<\/?\s*([a-zA-Z0-9]+)/);
  return m ? m[1].toLowerCase() : "";
}
function flagUnlabeledControls(path, source, at, out) {
  const fors = new Set;
  for (const m of source.matchAll(/<label\b[^>]*\bfor\s*=\s*["']([^"']+)["']/gi))
    fors.add(m[1]);
  const bare = source.replace(/<label\b[\s\S]*?<\/label>/gi, "");
  const re = /<(input|select|textarea)\b[^>]*>/gi;
  let m;
  while (m = re.exec(bare)) {
    const tag = m[0];
    const type = (/ \btype\s*=\s*["']([^"']+)["']/i.exec(` ${tag}`) || [])[1] || "";
    if (/^(hidden|submit|button|reset)$/i.test(type))
      continue;
    if (/\baria-label\s*=/i.test(tag) || /\baria-labelledby\s*=/i.test(tag))
      continue;
    const id = (/\bid\s*=\s*["']([^"']+)["']/i.exec(tag) || [])[1];
    if (id && fors.has(id))
      continue;
    const pos = at(m.index);
    out.push(issue2("FL622", "error", path, pos.line, pos.col, `${m[1].toLowerCase()} without a label`));
  }
}
function scanDocument(path, source, out) {
  const at = indexAt(source);
  let viewport = null;
  const metaRe = /<meta\b[^>]*>/gi;
  let meta;
  while (meta = metaRe.exec(source)) {
    if (/name\s*=\s*["']viewport["']/i.test(meta[0]) || /["']viewport["']/.test(meta[0]) && /name\s*=/i.test(meta[0])) {
      viewport = meta;
    }
  }
  if (!viewport || !/viewport-fit\s*=\s*cover/i.test(viewport[0])) {
    const pos = at(viewport ? viewport.index : 0);
    out.push(issue2("FL600", "error", path, pos.line, pos.col, "viewport meta missing viewport-fit=cover"));
  } else if (/user-scalable\s*=\s*["']?no/i.test(viewport[0]) || /maximum-scale\s*=/i.test(viewport[0])) {
    const pos = at(viewport.index);
    out.push(issue2("FL601", "error", path, pos.line, pos.col, "viewport disables zoom"));
  }
  const moduleScript = /<script\b[^>]*type\s*=\s*["']module["'][^>]*src\s*=\s*["'](?:\.\/)?main\.js["'][^>]*>/i.test(source) || /<script\b[^>]*src\s*=\s*["'](?:\.\/)?main\.js["'][^>]*type\s*=\s*["']module["'][^>]*>/i.test(source);
  if (!moduleScript) {
    out.push(issue2("FL602", "error", path, 1, 1, 'missing <script type="module" src="./main.js">'));
  }
  const sheets = [];
  const linkRe = /<link\b[^>]*>/gi;
  let link;
  while (link = linkRe.exec(source)) {
    if (!/rel\s*=\s*["']stylesheet["']/i.test(link[0]))
      continue;
    const href = (/\bhref\s*=\s*["']([^"']+)["']/i.exec(link[0]) || [])[1] || "";
    sheets.push({ href, index: link.index });
  }
  const RUNTIME_ORDER = ["base", "tokens", "components", "theme"];
  const runtimeSheet = /(?:base|tokens|components|theme)\.css(?:$|\?)/;
  const runtimeAt = RUNTIME_ORDER.map((name) => sheets.findIndex((s) => new RegExp(`${name}\\.css(?:$|\\?)`).test(s.href)));
  const otherAt = sheets.findIndex((s) => !runtimeSheet.test(s.href));
  const lastRuntime = Math.max(...sheets.map((s, i) => runtimeSheet.test(s.href) ? i : -1));
  const missing = runtimeAt.some((i) => i < 0);
  if (missing || otherAt >= 0 && otherAt < lastRuntime) {
    const pos = at(sheets[0] ? sheets[0].index : 0);
    out.push(issue2("FL603", "error", path, pos.line, pos.col, "base.css, tokens.css, components.css and theme.css must come before app CSS"));
  } else if (runtimeAt.some((i, k) => k > 0 && i <= runtimeAt[k - 1])) {
    const pos = at(sheets[runtimeAt[0]].index);
    out.push(issue2("FL603", "error", path, pos.line, pos.col, "runtime CSS must link base.css, tokens.css, components.css, theme.css in that order"));
  }
  designHtml(path, source, out);
  if (!/<html\b[^>]*\blang\s*=/i.test(source)) {
    out.push(issue2("FL624", "warn", path, 1, 1, "html element missing lang"));
  }
  let lastHeading = 0;
  const headingRe = /<h([1-6])\b/gi;
  let heading;
  while (heading = headingRe.exec(source)) {
    const level = Number(heading[1]);
    if (lastHeading && level > lastHeading + 1) {
      const pos = at(heading.index);
      out.push(issue2("FL625", "warn", path, pos.line, pos.col, `skipped heading level h${lastHeading} to h${level}`));
    }
    lastHeading = level;
  }
}
function afterCss(path, source, out, mode) {
  const at = indexAt(source);
  designCss(path, source, out);
  const stripped = source.replace(/\/\*[\s\S]*?\*\//g, " ");
  const importAt = stripped.search(/(^|[^\w-])@import\b/i);
  if (importAt >= 0) {
    const pos = at(importAt);
    out.push(issue2("FL604", "error", path, pos.line, pos.col, "@import is not allowed"));
  }
  const remote = /url\(\s*["']?(https?:|\/\/)/i.exec(stripped);
  if (remote) {
    const pos = at(remote.index);
    out.push(issue2("FL604", "error", path, pos.line, pos.col, "remote url() is not allowed"));
  }
  const important = /!important/i.exec(stripped);
  if (important) {
    const pos = at(important.index);
    out.push(issue2("FL606", "warn", path, pos.line, pos.col, "!important"));
  }
  const vh = /(^|[^d])100vh\b/.exec(stripped);
  if (vh) {
    const pos = at(vh.index + vh[1].length);
    out.push(issue2("FL607", "warn", path, pos.line, pos.col, "100vh; use 100dvh"));
  }
  const vw = /(^|[^d])100vw\b/.exec(stripped);
  if (vw) {
    const pos = at(vw.index + vw[1].length);
    out.push(issue2("FL608", "warn", path, pos.line, pos.col, "100vw; use 100%"));
  }
  flagWide(path, stripped, at, out, /(?<![\w-])width\s*:\s*[^;{}]*?(\d+(?:\.\d+)?)px/gi);
  flagWide(path, stripped, at, out, /(?<![\w-])min-width\s*:\s*[^;{}]*?(\d+(?:\.\d+)?)px/gi);
  flagFixed(path, stripped, at, out);
  if (mode === "publish")
    flagLongLines(path, source, out);
  const header = firstLine(source);
  if (header && !header.text.startsWith("/*") && !header.text.startsWith("//")) {
    out.push(issue2("FL503", "warn", path, header.line, 1, "missing header comment"));
  }
}
function flagWide(path, source, at, out, re) {
  let m;
  while (m = re.exec(source)) {
    if (Number(m[1]) > 480) {
      const pos = at(m.index);
      out.push(issue2("FL609", "warn", path, pos.line, pos.col, "fixed width over 480px"));
    }
  }
}
function flagFixed(path, source, at, out) {
  let depth = 0;
  let start = -1;
  for (let i = 0;i < source.length; i++) {
    const c = source[i];
    if (c === "{") {
      if (depth === 0)
        start = i;
      depth++;
    } else if (c === "}" && depth) {
      depth--;
      if (depth === 0 && start >= 0) {
        const block = source.slice(start, i);
        if (/position\s*:\s*(fixed|sticky)\b/i.test(block) && !/safe-area-inset/.test(block)) {
          const pos = at(start + block.search(/position\s*:/i));
          out.push(issue2("FL605", "warn", path, pos.line, pos.col, "fixed or sticky position without a safe-area inset"));
        }
        start = -1;
      }
    }
  }
}
function scanSvg(path, source, out) {
  const at = indexAt(source);
  const checks = [
    [/<script\b/i, "SVG contains script"],
    [/<foreignObject\b/i, "SVG contains foreignObject"],
    [/\son[a-z]+\s*=/i, "SVG contains an on* attribute"],
    [/\b(?:href|xlink:href)\s*=\s*["']https?:/i, "SVG contains an external href"]
  ];
  for (const [re, message] of checks) {
    const m = re.exec(source);
    if (!m)
      continue;
    const pos = at(m.index);
    out.push(issue2("FL114", "error", path, pos.line, pos.col, message));
  }
}
function indexAt(source) {
  const starts = [0];
  for (let i = 0;i < source.length; i++)
    if (source[i] === `
`)
      starts.push(i + 1);
  return function at(index) {
    const idx = index < 0 ? 0 : index;
    let lo = 0;
    let hi = starts.length - 1;
    while (lo < hi) {
      const mid = lo + hi + 1 >> 1;
      if (starts[mid] <= idx)
        lo = mid;
      else
        hi = mid - 1;
    }
    return { line: lo + 1, col: idx - starts[lo] + 1 };
  };
}
function quota(limits, key, fallback) {
  const n = limits && Number(limits[key]);
  return Number.isFinite(n) && n > 0 ? n : fallback;
}
function scanProject(files, profile, mode, catalog, out, limits) {
  const textLimit = quota(limits, "textBytes", TEXT_LIMIT);
  const fileLimit = quota(limits, "maxFiles", 256);
  const packageLimit = quota(limits, "packageBytes", PACKAGE_LIMIT);
  let bytes = 0;
  for (const path of Object.keys(files)) {
    const source = files[path];
    if (typeof source !== "string")
      continue;
    bytes += source.length;
    flagPath(path, out);
    if (source.length > textLimit) {
      out.push(issue2("FL709", "error", path, 1, 1, "file exceeds the text size limit"));
    }
    if (path.toLowerCase().endsWith(".svg"))
      scanSvg(path, source, out);
  }
  if (Object.keys(files).length > fileLimit) {
    out.push(issue2("FL700", "error", "manifest.json", 1, 1, "package has more than " + fileLimit + " files"));
  }
  if (bytes > packageLimit) {
    out.push(issue2("FL709", "error", "manifest.json", 1, 1, "package exceeds " + packageLimit + " bytes"));
  }
  const manifest = readManifest(files);
  const canvas = profile === "canvas" || manifest && manifest.profile === "canvas";
  if (canvas)
    flagCanvas(files, out);
  if (canvas || manifest && manifest.widget)
    flagCanvasWidget(files, out);
  if (mode === "publish")
    flagPublish(files, manifest, out);
  if (profile === "theme" || manifest && manifest.kind === "theme")
    flagThemeFiles(files, out);
  flagNetwork(files, manifest, out);
  flagBridge(files, manifest, catalog, out);
  flagWasm(files, manifest, out);
  flagToolFiles(files, manifest, out);
  flagIds(files, out);
  flagReachable(files, out);
}
function flagPath(path, out) {
  const parts = path.split("/").filter((part) => part !== "");
  if (path.split("/").some((part) => part === "")) {
    out.push(issue2("FL700", "error", path, 1, 1, "path has an empty segment"));
  }
  if (parts.length > 6) {
    out.push(issue2("FL700", "error", path, 1, 1, "path is deeper than 6"));
  }
  for (const part of parts) {
    if (part.startsWith(".") || !/^[a-z0-9._-]+$/.test(part)) {
      out.push(issue2("FL700", "error", path, 1, 1, `path segment "${part}" is not allowed`));
      break;
    }
  }
  const base = parts[parts.length - 1] || path;
  if (base.toLowerCase() === "license" || base.toLowerCase() === "copying")
    return;
  const dot = base.lastIndexOf(".");
  const ext = dot < 0 ? "" : base.slice(dot + 1).toLowerCase();
  if (!ALLOWED_EXT.has(ext)) {
    out.push(issue2("FL700", "error", path, 1, 1, `disallowed file type ".${ext}"`));
  }
}
function readManifest(files) {
  const raw = files["manifest.json"];
  if (typeof raw !== "string")
    return null;
  try {
    const data = JSON.parse(raw);
    if (!data || typeof data !== "object" || Array.isArray(data))
      return null;
    const caps = new Set;
    if (Array.isArray(data.capabilities)) {
      for (const entry of data.capabilities) {
        if (typeof entry === "string")
          caps.add(entry);
        else if (entry && typeof entry.id === "string")
          caps.add(entry.id);
      }
    }
    return {
      dependencies: data.dependencies && typeof data.dependencies === "object" && !Array.isArray(data.dependencies) ? Object.keys(data.dependencies) : [],
      profile: data.profile,
      kind: data.kind,
      license: data.license,
      source: data.source,
      network: Array.isArray(data.network) ? data.network.filter((item) => typeof item === "string") : [],
      embeds: Array.isArray(data.embeds) ? data.embeds.filter((item) => typeof item === "string") : [],
      widget: data.widget != null,
      caps
    };
  } catch {
    return null;
  }
}
function flagPublish(files, manifest, out) {
  const names = Object.keys(files).map((path) => path.split("/").pop().toLowerCase());
  if (!names.includes("license") && !names.includes("copying")) {
    out.push(issue2("FL707", "error", "manifest.json", 1, 1, "publish requires a LICENSE file"));
  }
  if (!manifest || typeof manifest.license !== "string" || !manifest.license.trim()) {
    out.push(issue2("FL707", "error", "manifest.json", 1, 1, "publish requires manifest license"));
  }
  if (!manifest || typeof manifest.source !== "string" || !/^https:\/\//i.test(manifest.source)) {
    out.push(issue2("FL707", "error", "manifest.json", 1, 1, "publish requires manifest source"));
  }
}
function flagThemeFiles(files, out) {
  for (const path of Object.keys(files)) {
    if (/\.(css|js|html|htm|svg)$/i.test(path)) {
      out.push(issue2("FL722", "error", path, 1, 1, "theme packages cannot contain code or SVG"));
    }
  }
}
function flagCanvas(files, out) {
  for (const path of Object.keys(files)) {
    if (!path.endsWith(".js") && !path.endsWith(".html"))
      continue;
    const text = stripComments(files[path]);
    if (/(?:deft|freedom)Bridge\b|\/_deft\/v1\/bridge\.js\b|\bfetch\s*\(|\bWebSocket\b|\bEventSource\b/.test(text)) {
      const at = indexAt(files[path]);
      const pos = at(Math.max(0, text.search(/(?:deft|freedom)Bridge|bridge\.js|\bfetch\s*\(|WebSocket|EventSource/)));
      out.push(issue2("FL204", "error", path, pos.line, pos.col, "canvas cannot use the bridge or network"));
    }
    if (path.endsWith(".html") && /<deft-widget\b/i.test(text) && !/\/_deft\/v1\/(elements|motion)\.js/.test(text)) {
      const pos = indexAt(files[path])(Math.max(0, text.search(/<deft-widget\b/i)));
      out.push(issue2("FL637", "warn", path, pos.line, pos.col, 'deft-widget never starts unless the page loads its element: add <script type="module" src="/_deft/v1/elements.js"></script>'));
    }
  }
}
var MIN_TIMER_MS = 5000;
var MAX_BLUR_PX = 8;
var RAF_CALL = /\brequestAnimationFrame\s*\(/;
function flagCanvasWidget(files, out) {
  const jsPaths = Object.keys(files).filter((p) => /\.m?js$/i.test(p) && typeof files[p] === "string");
  const code = Object.fromEntries(jsPaths.map((p) => [p, stripComments(files[p])]));
  const anyCancel = jsPaths.some((p) => /\bcancelAnimationFrame\s*\(/.test(code[p]));
  for (const path of jsPaths) {
    const text = code[path];
    const at = indexAt(files[path]);
    let reported = false;
    for (const m of text.matchAll(new RegExp(RAF_CALL.source, "g"))) {
      const open = m.index + m[0].length - 1;
      const args = callArgs(text, open);
      const cb = args && args[0];
      if (!cb || anyCancel)
        continue;
      const name = /^[A-Za-z_$][\w$]*$/.test(cb) ? cb : null;
      const body = name ? definitionBody(text, name) : cb;
      if (!body || !RAF_CALL.test(body))
        continue;
      if (reported)
        break;
      reported = true;
      const pos = at(m.index);
      out.push(issue2("FL634", "error", path, pos.line, pos.col, `unbounded requestAnimationFrame loop: ${name ? `"${name}"` : "the callback"} schedules itself and nothing calls cancelAnimationFrame. Stop when document.hidden or after a bound, or redraw on data`));
    }
    for (const m of text.matchAll(/\b(setInterval|setTimeout)\s*\(/g)) {
      const args = callArgs(text, m.index + m[0].length - 1);
      if (!args || args.length < 2 || !/^\d+(\.\d+)?$/.test(args[1]))
        continue;
      const ms = Number(args[1]);
      if (ms >= MIN_TIMER_MS)
        continue;
      const pos = at(m.index);
      out.push(issue2("FL635", "warn", path, pos.line, pos.col, `${m[1]} with ${ms} ms wakes the canvas or widget often; use ${MIN_TIMER_MS} ms or more, or refresh on view (visibilitychange)`));
    }
  }
  for (const path of Object.keys(files)) {
    if (!/\.css$/i.test(path) || typeof files[path] !== "string")
      continue;
    const css = stripComments(files[path]);
    const at = indexAt(files[path]);
    for (const m of css.matchAll(/\bblur\(\s*(\d+(?:\.\d+)?)px\s*\)/g)) {
      if (Number(m[1]) <= MAX_BLUR_PX)
        continue;
      const pos = at(m.index);
      out.push(issue2("FL636", "warn", path, pos.line, pos.col, `blur(${m[1]}px) over ${MAX_BLUR_PX}px costs frames on low-end phones; use a smaller blur or a flat tint`));
    }
    for (const m of css.matchAll(/\bbackdrop-filter\s*:(?!\s*none\b)/gi)) {
      const pos = at(m.index);
      out.push(issue2("FL636", "warn", path, pos.line, pos.col, "backdrop-filter costs frames on low-end phones; use a flat --deft-* surface"));
    }
  }
}
function callArgs(text, open) {
  const args = [];
  let depth = 0;
  let start = open + 1;
  let quote = "";
  for (let i = open;i < text.length; i++) {
    const c = text[i];
    if (quote) {
      if (c === "\\")
        i++;
      else if (c === quote)
        quote = "";
      continue;
    }
    if (c === "'" || c === '"' || c === "`")
      quote = c;
    else if (c === "(" || c === "[" || c === "{")
      depth++;
    else if (c === ")" || c === "]" || c === "}") {
      depth--;
      if (depth === 0) {
        args.push(text.slice(start, i).trim());
        return args;
      }
    } else if (c === "," && depth === 1) {
      args.push(text.slice(start, i).trim());
      start = i + 1;
    }
  }
  return null;
}
function definitionBody(text, name) {
  const def = new RegExp(`(?:\\bfunction\\s+${name}\\s*\\(|\\b(?:const|let|var)\\s+${name}\\s*=)`).exec(text);
  if (!def)
    return null;
  const brace = text.indexOf("{", def.index);
  const semi = text.indexOf(";", def.index);
  if (brace < 0 || semi >= 0 && semi < brace)
    return text.slice(def.index, semi < 0 ? undefined : semi);
  let depth = 0;
  for (let i = brace;i < text.length; i++) {
    if (text[i] === "{")
      depth++;
    else if (text[i] === "}" && --depth === 0)
      return text.slice(def.index, i + 1);
  }
  return text.slice(def.index);
}
function embedMatches(entry, origin) {
  const o = origin.toLowerCase();
  if (!/^https:\/\/[a-z0-9.-]+(?::\d{1,5})?$/.test(o))
    return false;
  if (!entry.startsWith("https://*."))
    return entry === o;
  const tail = entry.slice("https://*".length);
  return o.startsWith("https://") && o.length > "https://".length + tail.length && o.endsWith(tail);
}
function flagNetwork(files, manifest, out) {
  if (manifest && manifest.caps.has("network.open"))
    return;
  const allowed = new Set((manifest ? manifest.network : []).map((item) => item.toLowerCase().replace(/\/$/, "")));
  const embeds = manifest && manifest.caps.has("web.embed") ? manifest.embeds : [];
  for (const path of Object.keys(files)) {
    const source = files[path];
    if (typeof source !== "string")
      continue;
    const text = path.endsWith(".js") || path.endsWith(".css") ? stripComments(source) : source;
    const re = path.endsWith(".css") ? /url\(\s*["']?(https?:\/\/[^"')\s]+)/gi : path.endsWith(".html") || path.endsWith(".htm") ? /\b(?:src|href)\s*=\s*["'](https?:\/\/[^"']+)/gi : /["'`](https?:\/\/[^"'`\s]+)/g;
    let m;
    while (m = re.exec(text)) {
      const url = m[1];
      if (/^https?:\/\/(?:www\.)?w3\.org\b/i.test(url))
        continue;
      const origin = (url.match(/^https?:\/\/[^/]+/i) || [])[0];
      if (!origin)
        continue;
      if (allowed.has(origin.toLowerCase()))
        continue;
      if (embeds.some((entry) => embedMatches(entry, origin)))
        continue;
      if (path === "manifest.json" && manifest && manifest.embeds.includes(url))
        continue;
      if (declaredUrls.has(url) && (path.endsWith(".js") || path === "manifest.json"))
        continue;
      const at = indexAt(source);
      const pos = at(m.index);
      out.push(issue2("FL205", "error", path, pos.line, pos.col, `network origin ${origin} is not in manifest.network`));
    }
  }
}
function flagBridge(files, manifest, catalog, out) {
  if (!catalog)
    return;
  const maps = catalogMaps(catalog);
  const declared = manifest ? manifest.caps : new Set;
  const used = new Set;
  for (const path of Object.keys(files)) {
    if (!path.endsWith(".js"))
      continue;
    const source = files[path];
    const text = stripComments(source);
    const re = /(^|[^\w])(invoke|subscribe|call)\s*\(\s*(['"`])([^'"`]*)\3/g;
    let m;
    while (m = re.exec(text)) {
      const kind = m[2];
      const literal = m[4];
      const prefix = text.slice(Math.max(0, m.index - 24), m.index + (m[1] ? m[1].length : 0));
      if (/function\s*$/.test(prefix))
        continue;
      if (kind === "call" && /\.\s*$/.test(prefix) && !/(?:deft|freedom)Bridge\s*\.\s*$/.test(prefix))
        continue;
      if (kind === "call" && !literal.includes("."))
        continue;
      const table = kind === "subscribe" ? maps.event : maps.method;
      const cap = table.get(literal);
      if (!cap) {
        if (literal.includes(".")) {
          const at = indexAt(source);
          const pos = at(m.index);
          out.push(issue2("FL201", "error", path, pos.line, pos.col, `unknown bridge ${kind === "subscribe" ? "event" : "method"} "${literal}"`));
        }
        continue;
      }
      used.add(cap);
      const netFetch = literal === "net.fetch" && (declared.has("network.user") || declared.has("network.open") || manifest && manifest.network.length > 0);
      if (!declared.has(cap) && !netFetch) {
        const at = indexAt(source);
        const pos = at(m.index);
        out.push(issue2("FL202", "error", path, pos.line, pos.col, `capability ${cap} is used but not declared`));
      }
    }
  }
  if (!manifest)
    return;
  const caps = catalog.capabilities || {};
  for (const id of declared) {
    const entry = caps[id];
    if (!entry)
      continue;
    const names = [...entry.methods || [], ...entry.events || []];
    if (!names.length)
      continue;
    const blob = Object.values(files).join(`
`);
    if (!names.some((name) => blob.includes(name))) {
      out.push(issue2("FL203", "warn", "manifest.json", 1, 1, `capability ${id} is declared but unused`));
    }
  }
}
function flagWasm(files, manifest, out) {
  const declared = !!manifest && manifest.caps.has("wasm");
  if (declared)
    return;
  const uses = [];
  for (const path of Object.keys(files)) {
    if (/\.wasm$/i.test(path)) {
      uses.push({ path, line: 1, col: 1 });
      continue;
    }
    if (!path.endsWith(".js") || typeof files[path] !== "string")
      continue;
    const text = stripComments(files[path]);
    const m = /\bWebAssembly\b/.exec(text);
    if (m) {
      const pos = indexAt(files[path])(m.index);
      uses.push({ path, line: pos.line, col: pos.col });
    }
  }
  if (manifest) {
    for (const url of manifest.dependencies) {
      if (/\.wasm$/i.test(url))
        uses.push({ path: "manifest.json", line: 1, col: 1 });
    }
  }
  for (const use of uses) {
    out.push(issue2("FL202", "error", use.path, use.line, use.col, "capability wasm is used but not declared"));
  }
}
function catalogMaps(catalog) {
  const method = new Map;
  const event = new Map;
  const caps = catalog && catalog.capabilities;
  if (!caps || typeof caps !== "object" || Array.isArray(caps))
    return { method, event };
  for (const id of Object.keys(caps)) {
    const entry = caps[id] || {};
    for (const name of entry.methods || [])
      method.set(name, id);
    for (const name of entry.events || [])
      event.set(name, id);
  }
  return { method, event };
}
function flagIds(files, out) {
  const ids = new Set;
  for (const path of Object.keys(files)) {
    const source = files[path];
    if (typeof source !== "string")
      continue;
    for (const m of source.matchAll(/\sid\s*=\s*["']([^"']+)["']/g))
      ids.add(m[1]);
    for (const m of source.matchAll(/\bid\s*=\s*["']([^"'${}]+)["']/g))
      ids.add(m[1]);
  }
  for (const path of Object.keys(files)) {
    if (!path.endsWith(".js"))
      continue;
    const source = stripComments(files[path]);
    const re = /getElementById\(\s*["']([^"']+)["']\s*\)|querySelector\(\s*["']#([A-Za-z][\w-]*)["']\s*\)/g;
    let m;
    while (m = re.exec(source)) {
      const id = m[1] || m[2];
      if (ids.has(id))
        continue;
      const at = indexAt(files[path]);
      const pos = at(m.index);
      out.push(issue2("FL300", "warn", path, pos.line, pos.col, `id "${id}" is not in the HTML`));
    }
  }
}
function flagReachable(files, out) {
  if (typeof files["index.html"] !== "string" || !isDocument(files["index.html"]))
    return;
  const seen = new Set;
  const queue = ["index.html"];
  const wallpaperPage = manifestWallpaperPage(files["manifest.json"]);
  if (wallpaperPage)
    queue.push(wallpaperPage);
  const keyboardPage = manifestWallpaperPage(files["manifest.json"], "keyboard");
  if (keyboardPage)
    queue.push(keyboardPage);
  const widgetPage = manifestWallpaperPage(files["manifest.json"], "widget");
  if (widgetPage)
    queue.push(widgetPage);
  const backgroundModule = manifestWallpaperPage(files["manifest.json"], "background", "module");
  if (backgroundModule)
    queue.push(backgroundModule);
  for (const tool of manifestTools(files["manifest.json"]))
    queue.push(tool.path);
  while (queue.length) {
    const path = queue.pop();
    if (!path || seen.has(path) || typeof files[path] !== "string")
      continue;
    seen.add(path);
    for (const ref of refsIn(path, files[path])) {
      const next = resolveRel(path, ref);
      if (next)
        queue.push(next);
    }
  }
  for (const path of Object.keys(files)) {
    if (!/\.(js|css|html|htm)$/i.test(path))
      continue;
    if (seen.has(path))
      continue;
    out.push(issue2("FL303", "warn", path, 1, 1, "file is not reachable from index.html"));
  }
}
function manifestTools(source) {
  if (typeof source !== "string")
    return [];
  let data;
  try {
    data = JSON.parse(source);
  } catch {
    return [];
  }
  if (!data || !Array.isArray(data.tools))
    return [];
  const out = [];
  for (const tool of data.tools) {
    if (!tool || typeof tool !== "object")
      continue;
    const h = tool.handler;
    const isolate = !!h && typeof h === "object" && !Array.isArray(h);
    const ref = isolate ? h.isolate : h;
    if (typeof ref !== "string" || ref.indexOf("#") <= 0)
      continue;
    const rawPath = ref.slice(0, ref.indexOf("#"));
    const normalized = normalizeModulePath(rawPath);
    const path = (normalized ?? rawPath).replace(/^\.?\//, "");
    out.push({ name: String(tool.name), path, fn: ref.slice(ref.indexOf("#") + 1), isolate });
  }
  return out;
}
function flagToolFiles(files, manifest, out) {
  if (typeof files["index.html"] !== "string")
    return;
  for (const tool of manifestTools(files["manifest.json"])) {
    const src = files[tool.path];
    if (typeof src !== "string") {
      out.push(issue2("FL718", "error", "manifest.json", 1, 1, `tool "${tool.name}" handler file ${tool.path} is not in the package`));
      continue;
    }
    const code = stripComments(src);
    const fn = tool.fn.replace(/\$/g, "\\$");
    if (tool.isolate) {
      const mod = /^\s*(?:import\s*[\w{*"']|export\b)/m.exec(code);
      if (mod) {
        const line = code.slice(0, mod.index + mod[0].search(/\S/)).split(`
`).length;
        out.push(issue2("FL718", "error", tool.path, line, 1, `${tool.path} is an isolate handler: it must be a plain script (no import/export; the isolate has no modules, no bridge, no network)`));
      }
      if (!new RegExp("(?:^|[^\\w$.])(?:async\\s+)?function\\s*\\*?\\s*" + fn + "\\s*\\(|(?:^|[^\\w$.])(?:var|let|const)\\s+" + fn + "\\s*=", "m").test(code)) {
        out.push(issue2("FL718", "error", tool.path, 1, 1, `${tool.path} must define the global function ${tool.fn} for the isolate handler`));
      }
      if (/\basync\s+function\s+/.test(code) && new RegExp("async\\s+function\\s+" + fn + "\\b").test(code)) {
        out.push(issue2("FL718", "error", tool.path, 1, 1, `isolate handler ${tool.fn} must be synchronous (no async function, no promises)`));
      }
    } else if (!new RegExp("export\\s+(?:async\\s+)?(?:function\\s*\\*?|const|let|var|class)\\s*" + fn + "\\b|export\\s*\\{[^}]*\\b" + fn + "\\b").test(code)) {
      out.push(issue2("FL718", "warn", tool.path, 1, 1, `${tool.path} does not export ${tool.fn}`));
    }
  }
}
function manifestWallpaperPage(source, field = "wallpaper", key = "page") {
  if (typeof source !== "string")
    return null;
  let data;
  try {
    data = JSON.parse(source);
  } catch {
    return null;
  }
  const page = data && data[field] && data[field][key];
  if (typeof page !== "string" || !/^\.?\//.test(page))
    return null;
  return page.replace(/^\.?\//, "");
}
function refsIn(path, source) {
  const refs = [];
  if (/\.(html|htm)$/i.test(path)) {
    for (const m of source.matchAll(/\b(?:src|href)\s*=\s*["']([^"']+)["']/gi))
      refs.push(m[1]);
  } else if (path.endsWith(".js")) {
    for (const m of source.matchAll(/\bfrom\s+["']([^"']+)["']|\bimport\s*\(\s*["']([^"']+)["']|\bimport\s+["']([^"']+)["']/g)) {
      refs.push(m[1] || m[2] || m[3]);
    }
  } else if (path.endsWith(".css")) {
    for (const m of source.matchAll(/url\(\s*["']?([^"')]+)["']?\s*\)|@import\s+["']([^"']+)["']/gi))
      refs.push(m[1] || m[2]);
  }
  return refs;
}
function resolveRel(from, ref) {
  if (!ref || /^(https?:|data:|mailto:|tel:|sms:|#|\/)/i.test(ref))
    return null;
  const clean = ref.split("#")[0].split("?")[0];
  if (!clean)
    return null;
  const base = from.split("/").slice(0, -1);
  for (const part of clean.split("/")) {
    if (!part || part === ".")
      continue;
    if (part === "..")
      base.pop();
    else
      base.push(part);
  }
  return base.join("/");
}
function reservedPath(absolute) {
  const p = absolute.toLowerCase();
  return p.startsWith("/_deft/") || p.startsWith("/_fl/");
}
function normalizeModulePath(value) {
  if (typeof value !== "string" || value.length > 256)
    return null;
  if (!/^\.?\/[A-Za-z0-9._~\/-]+\.m?js$/.test(value))
    return null;
  if (value.includes("..") || value.includes("//"))
    return null;
  const absolute = value.startsWith(".") ? value.slice(1) : value;
  const normalized = "/" + absolute.split("/").filter((seg) => seg !== "" && seg !== ".").join("/");
  if (reservedPath(normalized) || !/^\/[A-Za-z0-9._~\/-]+\.m?js$/.test(normalized))
    return null;
  return normalized;
}

// src/index.js
var REQUIRED_MANIFEST = ["deft", "id", "name", "start_url"];
var REGEX_AFTER = new Set([
  "return",
  "throw",
  "case",
  "void",
  "typeof",
  "delete",
  "in",
  "of",
  "do",
  "else",
  "yield",
  "await",
  "instanceof",
  "new"
]);
function run(input) {
  const files = input && input.files && typeof input.files === "object" ? input.files : {};
  const profile = input ? input.profile : undefined;
  const catalog = input ? input.catalog : undefined;
  const mode = input && typeof input.mode === "string" ? input.mode : "";
  const out = [];
  const allows = [];
  setDependencies(dependencyNames(files["manifest.json"]));
  for (const path of Object.keys(files)) {
    if (pathHasDotDotOrFl(path)) {
      out.push(issue3("FL003", "error", path, 1, 1, 'path must not contain "..", "_deft/" or "_fl/"'));
    }
    const source = files[path];
    if (typeof source !== "string")
      continue;
    const base = basename(path).toLowerCase();
    if (base.endsWith(".js"))
      scanJs(path, source, out, allows, mode);
    else if (base.endsWith(".html") || base.endsWith(".htm"))
      scanHtml(path, source, out, mode);
    else if (base.endsWith(".css"))
      scanCss(path, source, out, allows, mode);
    else if (base === "manifest.json")
      scanManifest(path, source, profile, catalog, out, mode);
    if (base === "palette.json")
      scanPalette(path, source, mode, out);
  }
  designHandlers(files, out);
  scanProject(files, profile, mode, catalog, out, input && input.limits);
  applyAllows(out, allows);
  out.sort(compareDiag);
  return out;
}
function issue3(id, severity, path, line, col, message) {
  return { id, severity, path, line, col, message };
}
function compareDiag(a, b) {
  if (a.path !== b.path)
    return a.path < b.path ? -1 : 1;
  if (a.line !== b.line)
    return a.line - b.line;
  if (a.col !== b.col)
    return a.col - b.col;
  if (a.id !== b.id)
    return a.id < b.id ? -1 : 1;
  return 0;
}
function basename(path) {
  const slash = Math.max(path.lastIndexOf("/"), path.lastIndexOf("\\"));
  return slash < 0 ? path : path.slice(slash + 1);
}
var RUNTIME_REF = /^\/_(?:deft|fl)\/v1\/[a-z0-9._\/-]+\.(?:js|css|svg)(?:[?#].*)?$/;
function pathHasDotDotOrFl(value) {
  if (typeof value !== "string")
    return false;
  if (RUNTIME_REF.test(value))
    return false;
  return value.includes("..") || value.includes("_deft/") || value.includes("_fl/");
}
function locator(source) {
  const starts = [0];
  for (let i = 0;i < source.length; i++) {
    if (source[i] === `
`)
      starts.push(i + 1);
  }
  return function at(index) {
    const idx = index < 0 ? 0 : index;
    let lo = 0;
    let hi = starts.length - 1;
    while (lo < hi) {
      const mid = lo + hi + 1 >> 1;
      if (starts[mid] <= idx)
        lo = mid;
      else
        hi = mid - 1;
    }
    return { line: lo + 1, col: idx - starts[lo] + 1 };
  };
}
function lineText(source, index) {
  const start = source.lastIndexOf(`
`, Math.max(0, index - 1)) + 1;
  let end = source.indexOf(`
`, index);
  if (end < 0)
    end = source.length;
  return source.slice(start, end);
}
function findKey(source, key) {
  const needle = `"${key}"`;
  const at = source.indexOf(needle);
  return at < 0 ? 0 : at;
}
var BTN_MODIFIERS = new Set([
  "primary",
  "secondary",
  "accent",
  "neutral",
  "info",
  "success",
  "warning",
  "error",
  "xs",
  "sm",
  "md",
  "lg",
  "xl",
  "outline",
  "soft",
  "ghost",
  "dash",
  "link"
]);
var PALETTE_ROLES = ["primary", "secondary", "accent", "neutral", "info", "success", "warning", "error"];
var PALETTE_BASES = ["base-100", "base-200", "base-300"];
function noteAllow(allows, path, bodyStart, text, kind, at) {
  const rel = text.search(/deft-allow/i);
  if (rel < 0)
    return;
  const parsed = parseAllow(text.slice(rel));
  if (!parsed)
    return;
  const pos = at(bodyStart + rel);
  allows.push({
    path,
    line: pos.line,
    col: pos.col,
    id: parsed.id,
    reason: parsed.reason,
    kind
  });
}
function parseAllow(text) {
  const match = text.match(/deft-allow\b(?:\s+(FL\d(?:xx|\d{2})))?(?:\s*:\s*([^\n]*))?/i);
  if (!match)
    return null;
  let id = "";
  if (match[1]) {
    id = /xx$/i.test(match[1]) ? match[1].slice(0, -2).toUpperCase() + "xx" : match[1].toUpperCase();
  }
  return { id, reason: (match[2] ?? "").trim() };
}
function allowMatches(pattern, id) {
  if (!pattern || !id)
    return false;
  if (pattern.endsWith("xx")) {
    const prefix = pattern.slice(0, -2);
    return id.startsWith(prefix) && id.length === prefix.length + 2 && /^\d{2}$/.test(id.slice(prefix.length));
  }
  return pattern === id;
}
function allowFamily(kind, id) {
  if (kind === "js")
    return id.startsWith("FL4");
  if (kind === "css")
    return id.startsWith("FL6");
  return false;
}
function ruleLocked(id) {
  return id.startsWith("FL1") || id.startsWith("FL2") || id.startsWith("FL7");
}
function applyAllows(out, allows) {
  const drop = new Set;
  for (const allow of allows) {
    if (!allow.reason) {
      out.push(issue3("FL009", "warn", allow.path, allow.line, allow.col, "suppression without a reason"));
      continue;
    }
    let used = false;
    for (let i = 0;i < out.length; i++) {
      if (used || drop.has(i))
        continue;
      const diag = out[i];
      if (diag.path !== allow.path || diag.line !== allow.line + 1)
        continue;
      if (!allowMatches(allow.id, diag.id) || !allowFamily(allow.kind, diag.id))
        continue;
      if (diag.severity === "error" || ruleLocked(diag.id))
        continue;
      drop.add(i);
      used = true;
    }
  }
  for (let i = out.length - 1;i >= 0; i--) {
    if (drop.has(i))
      out.splice(i, 1);
  }
}
function scanBtnModifiers(path, text, valueStart, out, at) {
  const re = /deft-btn-([a-z0-9-]+)/gi;
  let match;
  while (match = re.exec(text)) {
    if (BTN_MODIFIERS.has(match[1].toLowerCase()))
      continue;
    const pos = at(valueStart + match.index);
    out.push(issue3("FL302", "info", path, pos.line, pos.col, `unknown deft-btn modifier "${match[0].toLowerCase()}"`));
  }
}
function scanJs(path, source, out, allows, mode) {
  const at = locator(source);
  const comments = [];
  const tokens = tokenizeJs(source, comments);
  if (allows) {
    for (const comment of comments)
      noteAllow(allows, path, comment.body, comment.text, "js", at);
  }
  for (let i = 0;i < tokens.length; i++) {
    const t = tokens[i];
    if (t.type === "string" && t.value) {
      const quoted = source[t.index] === '"' || source[t.index] === "'" || source[t.index] === "`";
      scanBtnModifiers(path, t.value, quoted ? t.index + 1 : t.index, out, at);
    }
    if (t.type !== "ident")
      continue;
    const prev = tokens[i - 1];
    const dotted = prev && prev.type === "punct" && prev.value === ".";
    if (t.value === "map" && dotted && t.expr) {
      const open = tokens[i + 1];
      if (open && open.type === "punct" && open.value === "(") {
        const close = matchingParen(tokens, i + 1);
        if (close > i && argsHaveHtmlTemplate(tokens, i + 2, close)) {
          const pos = at(t.index);
          out.push(issue3("FL402", "warn", path, pos.line, pos.col, "unkeyed .map() of a template; use repeat(items, key, tpl)"));
        }
      }
    }
    if (t.value === "innerHTML" || t.value === "insertAdjacentHTML") {
      const pos = at(t.index);
      out.push(issue3("FL100", "error", path, pos.line, pos.col, `${t.value} is not allowed`));
      continue;
    }
    if (t.value === "document") {
      const dot = tokens[i + 1];
      const write = tokens[i + 2];
      if (dot && dot.type === "punct" && dot.value === "." && write && write.type === "ident" && write.value === "write") {
        const pos = at(t.index);
        out.push(issue3("FL100", "error", path, pos.line, pos.col, "document.write is not allowed"));
      }
      continue;
    }
    if (t.value === "eval") {
      const next = tokens[i + 1];
      if (next && next.type === "punct" && next.value === "(") {
        const pos = at(t.index);
        out.push(issue3("FL101", "error", path, pos.line, pos.col, "eval() is not allowed"));
      }
      continue;
    }
    if (t.value === "new") {
      const fn = tokens[i + 1];
      const paren = tokens[i + 2];
      if (fn && fn.type === "ident" && fn.value === "Function" && paren && paren.type === "punct" && paren.value === "(") {
        const pos = at(t.index);
        out.push(issue3("FL101", "error", path, pos.line, pos.col, "new Function() is not allowed"));
      }
      continue;
    }
    if (dotted)
      continue;
    if (t.value === "import") {
      const spec = importSpecifier(tokens, i);
      if (spec && pathHasDotDotOrFl(spec.value)) {
        const pos = at(spec.index);
        out.push(issue3("FL003", "error", path, pos.line, pos.col, 'path must not contain "..", "_deft/" or "_fl/"'));
      }
    } else if (t.value === "export") {
      const spec = exportSpecifier(tokens, i);
      if (spec && pathHasDotDotOrFl(spec.value)) {
        const pos = at(spec.index);
        out.push(issue3("FL003", "error", path, pos.line, pos.col, 'path must not contain "..", "_deft/" or "_fl/"'));
      }
    }
  }
  afterJs(path, source, tokens, at, out, mode);
}
function importSpecifier(tokens, i) {
  const n = tokens[i + 1];
  if (!n)
    return null;
  if (n.type === "punct" && n.value === "(") {
    const s = tokens[i + 2];
    return s && s.type === "string" ? s : null;
  }
  if (n.type === "string")
    return n;
  return fromSpecifier(tokens, i);
}
function exportSpecifier(tokens, i) {
  return fromSpecifier(tokens, i);
}
function fromSpecifier(tokens, i) {
  const stop = Math.min(tokens.length, i + 60);
  for (let j = i + 1;j < stop; j++) {
    const t = tokens[j];
    if (t.type === "punct" && t.value === ";")
      return null;
    if (t.type === "ident" && t.value === "from") {
      const s = tokens[j + 1];
      return s && s.type === "string" ? s : null;
    }
  }
  return null;
}
function tokenizeJs(source, comments) {
  const tokens = [];
  const n = source.length;
  let i = 0;
  const mode = ["code"];
  const exprDepth = [];
  const push = (type, value, index, extra) => {
    const token = { type, value, index, expr: exprDepth.length > 0 };
    if (extra)
      Object.assign(token, extra);
    tokens.push(token);
  };
  while (i < n) {
    if (mode[mode.length - 1] === "tmpl") {
      if (source[i] === "\\") {
        i += 2;
        continue;
      }
      if (source[i] === "`") {
        mode.pop();
        i++;
        continue;
      }
      if (source[i] === "$" && source[i + 1] === "{") {
        mode.push("code");
        exprDepth.push(1);
        i += 2;
        continue;
      }
      const index = i;
      let value = "";
      while (i < n) {
        if (source[i] === "\\") {
          value += source[i + 1] ?? "";
          i += 2;
          continue;
        }
        if (source[i] === "`" || source[i] === "$" && source[i + 1] === "{")
          break;
        value += source[i++];
      }
      if (value)
        push("string", value, index, { tmpl: true });
      continue;
    }
    const c = source[i];
    if (c === " " || c === "\t" || c === `
` || c === "\r" || c === "\f") {
      i++;
      continue;
    }
    if (c === "/" && source[i + 1] === "/") {
      i += 2;
      const body = i;
      while (i < n && source[i] !== `
`)
        i++;
      if (comments)
        comments.push({ body, text: source.slice(body, i) });
      continue;
    }
    if (c === "/" && source[i + 1] === "*") {
      i += 2;
      while (i < n && !(source[i] === "*" && source[i + 1] === "/"))
        i++;
      i = Math.min(n, i + 2);
      continue;
    }
    if (c === "'" || c === '"') {
      const index = i;
      const q = c;
      i++;
      let value = "";
      while (i < n && source[i] !== q && source[i] !== `
`) {
        if (source[i] === "\\") {
          value += source[i + 1] ?? "";
          i += 2;
          continue;
        }
        value += source[i++];
      }
      if (source[i] === q)
        i++;
      push("string", value, index);
      continue;
    }
    if (c === "`") {
      mode.push("tmpl");
      i++;
      continue;
    }
    if (c === "/" && maybeRegex(tokens)) {
      const index = i;
      i++;
      while (i < n && source[i] !== "/" && source[i] !== `
`) {
        if (source[i] === "\\") {
          i += 2;
          continue;
        }
        if (source[i] === "[") {
          i++;
          while (i < n && source[i] !== "]" && source[i] !== `
`) {
            if (source[i] === "\\")
              i += 2;
            else
              i++;
          }
          if (source[i] === "]")
            i++;
          continue;
        }
        i++;
      }
      if (source[i] === "/")
        i++;
      while (i < n && /[a-z]/i.test(source[i]))
        i++;
      push("regex", source.slice(index, i), index);
      continue;
    }
    if (c === "{") {
      if (exprDepth.length)
        exprDepth[exprDepth.length - 1]++;
      push("punct", c, i);
      i++;
      continue;
    }
    if (c === "}") {
      if (exprDepth.length) {
        exprDepth[exprDepth.length - 1]--;
        if (exprDepth[exprDepth.length - 1] === 0) {
          exprDepth.pop();
          mode.pop();
          i++;
          continue;
        }
      }
      push("punct", c, i);
      i++;
      continue;
    }
    if (/[A-Za-z_$]/.test(c)) {
      const index = i;
      i++;
      while (i < n && /[A-Za-z0-9_$]/.test(source[i]))
        i++;
      push("ident", source.slice(index, i), index);
      continue;
    }
    if (c === "." && /[0-9]/.test(source[i + 1] || "")) {
      const index = i;
      i++;
      while (i < n && /[0-9_]/.test(source[i]))
        i++;
      push("number", source.slice(index, i), index);
      continue;
    }
    if (/[0-9]/.test(c)) {
      const index = i;
      i++;
      while (i < n && /[0-9_]/.test(source[i]))
        i++;
      if (source[i] === "." && /[0-9]/.test(source[i + 1] || "")) {
        i++;
        while (i < n && /[0-9_]/.test(source[i]))
          i++;
      }
      push("number", source.slice(index, i), index);
      continue;
    }
    push("punct", c, i);
    i++;
  }
  return tokens;
}
function matchingParen(tokens, openIdx) {
  let depth = 0;
  for (let j = openIdx;j < tokens.length; j++) {
    const t = tokens[j];
    if (t.type !== "punct")
      continue;
    if (t.value === "(")
      depth++;
    else if (t.value === ")") {
      depth--;
      if (depth === 0)
        return j;
    }
  }
  return -1;
}
function argsHaveHtmlTemplate(tokens, from, to) {
  for (let j = from;j < to; j++) {
    const t = tokens[j];
    if (t.type === "string" && t.tmpl && t.value.includes("<"))
      return true;
  }
  return false;
}
function maybeRegex(tokens) {
  const prev = tokens[tokens.length - 1];
  if (!prev)
    return true;
  if (prev.type === "ident")
    return REGEX_AFTER.has(prev.value);
  if (prev.type === "number" || prev.type === "string" || prev.type === "regex")
    return false;
  if (prev.type === "punct" && (prev.value === ")" || prev.value === "]" || prev.value === "}"))
    return false;
  return true;
}
function scanHtml(path, source, out, mode) {
  const at = locator(source);
  const n = source.length;
  let i = 0;
  while (i < n) {
    if (source.startsWith("<!--", i)) {
      const end = source.indexOf("-->", i + 4);
      i = end < 0 ? n : end + 3;
      continue;
    }
    if (source[i] !== "<") {
      i++;
      continue;
    }
    const next = source[i + 1];
    if (next === "/" || next === "!" || next === "?") {
      const end = source.indexOf(">", i + 2);
      i = end < 0 ? n : end + 1;
      continue;
    }
    const tagStart = i;
    i++;
    const nameStart = i;
    while (i < n && /[A-Za-z0-9]/.test(source[i]))
      i++;
    const name = source.slice(nameStart, i).toLowerCase();
    if (nameStart === i) {
      i++;
      continue;
    }
    const attrs = [];
    while (i < n && source[i] !== ">") {
      if (source[i] === "/" && source[i + 1] === ">")
        break;
      if (/\s/.test(source[i])) {
        i++;
        continue;
      }
      if (source[i] === "/") {
        i++;
        continue;
      }
      const aStart = i;
      while (i < n && /[^\s=/>]/.test(source[i]))
        i++;
      const aname = source.slice(aStart, i).toLowerCase();
      if (!aname) {
        i++;
        continue;
      }
      let value = "";
      let valueIndex = aStart;
      let valueStart = aStart;
      while (i < n && /\s/.test(source[i]))
        i++;
      if (source[i] === "=") {
        i++;
        while (i < n && /\s/.test(source[i]))
          i++;
        valueIndex = i;
        valueStart = i;
        const q = source[i];
        if (q === '"' || q === "'") {
          i++;
          valueStart = i;
          while (i < n && source[i] !== q)
            i++;
          value = source.slice(valueStart, i);
          if (source[i] === q)
            i++;
        } else {
          const vStart = i;
          valueStart = vStart;
          while (i < n && /[^\s>]/.test(source[i]))
            i++;
          value = source.slice(vStart, i);
        }
      }
      attrs.push({ name: aname, value, index: aStart, valueIndex, valueStart });
    }
    if (source[i] === ">")
      i++;
    else if (source[i] === "/" && source[i + 1] === ">")
      i += 2;
    if (name === "script") {
      const src = attrs.find((a) => a.name === "src");
      const type = attrs.find((a) => a.name === "type");
      const typeVal = type ? type.value.trim().toLowerCase() : "";
      const problems = [];
      if (!src)
        problems.push("inline script without src");
      if (typeVal !== "module")
        problems.push("script without type=module");
      if (problems.length) {
        const pos = at(tagStart);
        out.push(issue3("FL104", "error", path, pos.line, pos.col, problems.join("; ")));
      }
    }
    for (const attr of attrs) {
      if (attr.name === "style") {
        const pos = at(attr.index);
        out.push(issue3("FL106", "error", path, pos.line, pos.col, "style attribute is not allowed"));
      }
      if (attr.name === "class" || attr.name === "classname") {
        scanBtnModifiers(path, attr.value, attr.valueStart, out, at);
      }
      if ((attr.name === "src" || attr.name === "href") && pathHasDotDotOrFl(attr.value)) {
        const pos = at(attr.valueIndex);
        out.push(issue3("FL003", "error", path, pos.line, pos.col, 'path must not contain "..", "_deft/" or "_fl/"'));
      }
    }
  }
  afterHtml(path, source, out, mode);
}
function scanCss(path, source, out, allows, mode) {
  const at = locator(source);
  const n = source.length;
  let i = 0;
  let paren = 0;
  let valueDepth = -1;
  scanCssDecls(path, source, out, at);
  const reportColor = (index) => {
    const pos = at(index);
    const severity = lineText(source, index).includes("var(--deft-") ? "warn" : "error";
    out.push(issue3("FL610", severity, path, pos.line, pos.col, "color literal; use a var(--deft-*) token"));
  };
  while (i < n) {
    if (source.startsWith("/*", i)) {
      const end = source.indexOf("*/", i + 2);
      const commentEnd = end < 0 ? n : end;
      const text = source.slice(i + 2, commentEnd);
      if (allows)
        noteAllow(allows, path, i + 2, text, "css", at);
      if (!text.includes("var(--deft-"))
        scanColorSpan(source, i + 2, commentEnd, reportColor);
      i = end < 0 ? n : end + 2;
      continue;
    }
    if (isCssUrl(source, i)) {
      const url = readCssUrl(source, i);
      if (url && pathHasDotDotOrFl(url.value)) {
        const pos = at(url.valueIndex);
        out.push(issue3("FL003", "error", path, pos.line, pos.col, 'path must not contain "..", "_deft/" or "_fl/"'));
      }
      i = url ? url.end : i + 3;
      continue;
    }
    if (isCssImport(source, i)) {
      const imported = readImportPath(source, i);
      if (imported && pathHasDotDotOrFl(imported.value)) {
        const pos = at(imported.valueIndex);
        out.push(issue3("FL003", "error", path, pos.line, pos.col, 'path must not contain "..", "_deft/" or "_fl/"'));
      }
      i = imported ? imported.end : i + 7;
      continue;
    }
    const q = source[i];
    if (q === '"' || q === "'") {
      i++;
      while (i < n && source[i] !== q && source[i] !== `
`) {
        if (source[i] === "\\")
          i += 2;
        else
          i++;
      }
      if (source[i] === q)
        i++;
      continue;
    }
    if (q === "{") {
      valueDepth = -1;
      paren = 0;
      i++;
      continue;
    }
    if (q === "}") {
      valueDepth = -1;
      paren = 0;
      i++;
      continue;
    }
    if (q === "(") {
      paren++;
      i++;
      continue;
    }
    if (q === ")") {
      paren--;
      if (valueDepth >= 0 && paren < valueDepth)
        valueDepth = -1;
      i++;
      continue;
    }
    if (q === ";") {
      if (valueDepth >= 0 && paren <= valueDepth)
        valueDepth = -1;
      i++;
      continue;
    }
    if (q === ":") {
      if (valueDepth < 0)
        valueDepth = paren;
      i++;
      continue;
    }
    if (valueDepth >= 0 && q === "#") {
      const len = hexLen(source, i);
      if (len) {
        reportColor(i);
        i += len;
        continue;
      }
    }
    if (valueDepth >= 0) {
      const len = rgbLen(source, i);
      if (len) {
        reportColor(i);
        i += len;
        continue;
      }
    }
    i++;
  }
  afterCss(path, source, out, mode);
}
function scanColorSpan(source, from, to, reportColor) {
  for (let i = from;i < to; i++) {
    if (source[i] === "#") {
      const len = hexLen(source, i);
      if (len && i + len <= to) {
        reportColor(i);
        i += len - 1;
        continue;
      }
    }
    const len = rgbLen(source, i);
    if (len && i + len <= to) {
      reportColor(i);
      i += len - 1;
    }
  }
}
function hexLen(source, i) {
  if (source[i] !== "#")
    return 0;
  let j = i + 1;
  while (j < source.length && /[0-9a-fA-F]/.test(source[j]))
    j++;
  const digits = j - (i + 1);
  if (digits === 3 || digits === 4 || digits === 6 || digits === 8)
    return digits + 1;
  return 0;
}
function rgbLen(source, i) {
  if (i > 0 && /[A-Za-z0-9_-]/.test(source[i - 1]))
    return 0;
  if (source.slice(i, i + 3).toLowerCase() !== "rgb")
    return 0;
  let j = i + 3;
  if (source[j] === "a" || source[j] === "A")
    j++;
  let k = j;
  while (k < source.length && /\s/.test(source[k]))
    k++;
  if (source[k] !== "(")
    return 0;
  return k - i + 1;
}
function isIdentChar(c) {
  return !!c && /[A-Za-z0-9_-]/.test(c);
}
function isCssUrl(source, i) {
  if (isIdentChar(source[i - 1]))
    return false;
  return source.slice(i, i + 3).toLowerCase() === "url";
}
function isCssImport(source, i) {
  if (isIdentChar(source[i - 1]))
    return false;
  return source.slice(i, i + 7).toLowerCase() === "@import";
}
function readCssUrl(source, i) {
  let j = i + 3;
  while (j < source.length && /\s/.test(source[j]))
    j++;
  if (source[j] !== "(")
    return null;
  j++;
  while (j < source.length && /\s/.test(source[j]))
    j++;
  let quote = "";
  if (source[j] === '"' || source[j] === "'")
    quote = source[j++];
  const valueIndex = j;
  let value = "";
  while (j < source.length) {
    if (quote && source[j] === quote) {
      j++;
      break;
    }
    if (!quote && (source[j] === ")" || /\s/.test(source[j])))
      break;
    if (source[j] === "\\" && j + 1 < source.length) {
      value += source[j + 1];
      j += 2;
      continue;
    }
    if (source[j] === `
`)
      break;
    value += source[j++];
  }
  while (j < source.length && source[j] !== ")")
    j++;
  if (source[j] === ")")
    j++;
  return { value, valueIndex, end: j };
}
function readImportPath(source, i) {
  let j = i + 7;
  while (j < source.length && /\s/.test(source[j]))
    j++;
  if (source.slice(j, j + 3).toLowerCase() === "url")
    return readCssUrl(source, j);
  if (source[j] === '"' || source[j] === "'") {
    const quote = source[j++];
    const valueIndex = j;
    let value = "";
    while (j < source.length && source[j] !== quote && source[j] !== `
`) {
      if (source[j] === "\\") {
        value += source[j + 1] ?? "";
        j += 2;
        continue;
      }
      value += source[j++];
    }
    if (source[j] === quote)
      j++;
    while (j < source.length && /\s/.test(source[j]))
      j++;
    if (source[j] === ";")
      j++;
    return { value, valueIndex, end: j };
  }
  return { value: "", valueIndex: i, end: j };
}
function scanCssDecls(path, source, out, at) {
  const n = source.length;
  const focusVisible = source.replace(/\/\*[\s\S]*?\*\//g, "").includes(":focus-visible");
  let i = 0;
  let brace = 0;
  while (i < n) {
    if (source.startsWith("/*", i)) {
      const end = source.indexOf("*/", i + 2);
      i = end < 0 ? n : end + 2;
      continue;
    }
    const q = source[i];
    if (q === '"' || q === "'") {
      i++;
      while (i < n && source[i] !== q && source[i] !== `
`) {
        if (source[i] === "\\")
          i += 2;
        else
          i++;
      }
      if (source[i] === q)
        i++;
      continue;
    }
    if (q === "{") {
      brace++;
      i++;
      continue;
    }
    if (q === "}") {
      if (brace > 0)
        brace--;
      i++;
      continue;
    }
    if (brace > 0 && (q === "-" || /[A-Za-z_]/.test(q))) {
      const nameStart = i;
      i++;
      while (i < n && /[A-Za-z0-9_-]/.test(source[i]))
        i++;
      const name = source.slice(nameStart, i).toLowerCase();
      let j = i;
      while (j < n && /\s/.test(source[j]))
        j++;
      if (source[j] !== ":")
        continue;
      j++;
      while (j < n && /\s/.test(source[j]))
        j++;
      const valueStart = j;
      let paren = 0;
      while (j < n) {
        if (source.startsWith("/*", j)) {
          const end = source.indexOf("*/", j + 2);
          j = end < 0 ? n : end + 2;
          continue;
        }
        const c = source[j];
        if (c === '"' || c === "'") {
          j++;
          while (j < n && source[j] !== c && source[j] !== `
`) {
            if (source[j] === "\\")
              j += 2;
            else
              j++;
          }
          if (source[j] === c)
            j++;
          continue;
        }
        if (c === "(")
          paren++;
        else if (c === ")" && paren > 0)
          paren--;
        else if (paren === 0 && (c === ";" || c === "}"))
          break;
        j++;
      }
      reportCssDecl(path, name, source.slice(valueStart, j), nameStart, out, at, focusVisible);
      i = j;
      continue;
    }
    i++;
  }
}
var HOOK_PROPS = new Set([
  "--deft-min",
  "--deft-gap",
  "--deft-density",
  "--deft-side",
  "--deft-measure",
  "--deft-cols",
  "--deft-pad",
  "--deft-radius-outer",
  "--deft-value",
  "--deft-segs",
  "--deft-level"
]);
function reportCssDecl(path, name, rawValue, nameStart, out, at, focusVisible) {
  const pos = at(nameStart);
  if (name.startsWith("--deft-") && !HOOK_PROPS.has(name)) {
    out.push(issue3("FL614", "warn", path, pos.line, pos.col, "redefines a --deft-* token; define a private token"));
  }
  if (name === "font-family" && !fontFamilyOk(rawValue)) {
    out.push(issue3("FL611", "warn", path, pos.line, pos.col, "font-family literal; use var(--deft-font-ui)"));
  }
  if (name === "font-size" && /(?:^|[\s,(])\d*\.?\d+px\b/i.test(rawValue)) {
    out.push(issue3("FL612", "info", path, pos.line, pos.col, "px font size; use a type class (.deft-body, .deft-footnote...) or rem"));
  }
  if ((name === "border-radius" || name.startsWith("border-") && name.endsWith("-radius")) && !radiusOk(rawValue)) {
    out.push(issue3("FL613", "warn", path, pos.line, pos.col, "border-radius literal; use var(--deft-radius-*)"));
  }
  if (name === "outline" && outlineNone(rawValue) && !focusVisible) {
    out.push(issue3("FL615", "warn", path, pos.line, pos.col, "outline: none without a :focus-visible style"));
  }
}
function cssKeyword(value) {
  return /^(inherit|initial|unset|revert|revert-layer)$/i.test(value.trim());
}
function fontFamilyOk(raw) {
  const value = raw.replace(/!important/gi, "").trim();
  if (!value || cssKeyword(value))
    return true;
  return /^var\(\s*--deft-font-[a-z0-9-]+\s*\)$/i.test(value);
}
function radiusOk(raw) {
  const value = raw.replace(/!important/gi, "").trim();
  if (!value || cssKeyword(value))
    return true;
  const parts = value.split(/\s+/);
  return parts.every((part) => part === "/" || /^0(?:px|rem)?$/.test(part) || /^var\(\s*--deft-radius-[a-z0-9-]+\s*\)$/i.test(part));
}
function outlineNone(raw) {
  const value = raw.replace(/!important/gi, "").trim().toLowerCase();
  return value === "none" || value === "0" || value === "0px";
}
function scanPalette(path, source, mode, out) {
  let data;
  try {
    data = JSON.parse(source);
  } catch {
    out.push(issue3("FL720", "error", path, 1, 1, "palette.json is not valid JSON"));
    return;
  }
  if (!data || typeof data !== "object" || Array.isArray(data)) {
    out.push(issue3("FL720", "error", path, 1, 1, "palette.json must be an object"));
    return;
  }
  const severity = mode === "local" ? "warn" : "error";
  const at = locator(source);
  const modes = Array.isArray(data.modes) ? data.modes.filter((name) => typeof name === "string") : [];
  const names = modes.length ? modes : ["light", "dark"].filter((name) => data[name] && typeof data[name] === "object");
  for (const name of names) {
    const colors = data[name];
    if (!colors || typeof colors !== "object" || Array.isArray(colors))
      continue;
    const required = ["base-100", "base-200", "base-300", "base-content"];
    for (const role of PALETTE_ROLES)
      required.push(role, `${role}-content`);
    for (const key of required) {
      if (typeof colors[key] !== "string" || !colors[key].trim()) {
        const regionForKey = objectAfterKey(source, name);
        const keyAt = keyInRegion(source, regionForKey, key);
        const missingAt = at(keyAt);
        out.push(issue3("FL720", "error", path, missingAt.line, missingAt.col, `palette missing ${key}`));
      }
    }
    const region = objectAfterKey(source, name);
    const pairs = [];
    for (const role of PALETTE_ROLES)
      pairs.push([role, `${role}-content`]);
    for (const base of PALETTE_BASES)
      pairs.push(["base-content", base]);
    for (const [fg, bg] of pairs) {
      if (typeof colors[fg] !== "string" || typeof colors[bg] !== "string")
        continue;
      const left = colorLuminance(colors[fg]);
      const right = colorLuminance(colors[bg]);
      if (left == null || right == null)
        continue;
      const ratio = contrastRatio(left, right);
      if (ratio >= 4.5)
        continue;
      const keyAt = keyInRegion(source, region, bg);
      const pos = at(keyAt);
      out.push(issue3("FL721", severity, path, pos.line, pos.col, `${name} ${fg} / ${bg} contrast ${ratio.toFixed(2)} < 4.5`));
    }
  }
}
function objectAfterKey(source, key) {
  const needle = `"${key}"`;
  const at = source.indexOf(needle);
  if (at < 0)
    return null;
  const brace = source.indexOf("{", at + needle.length);
  if (brace < 0)
    return null;
  let depth = 0;
  for (let i = brace;i < source.length; i++) {
    if (source[i] === "{")
      depth++;
    else if (source[i] === "}") {
      depth--;
      if (depth === 0)
        return { start: brace, end: i };
    }
  }
  return null;
}
function keyInRegion(source, region, key) {
  const needle = `"${key}"`;
  if (!region) {
    const at = source.indexOf(needle);
    return at < 0 ? 0 : at;
  }
  const at = source.indexOf(needle, region.start);
  if (at < 0 || at > region.end)
    return region.start;
  return at;
}
function contrastRatio(l1, l2) {
  const lighter = Math.max(l1, l2);
  const darker = Math.min(l1, l2);
  return (lighter + 0.05) / (darker + 0.05);
}
function colorLuminance(raw) {
  const text = String(raw).trim();
  const hex = text.match(/^#([0-9a-fA-F]{6})$/);
  if (hex) {
    const n = hex[1];
    const rgb = [0, 2, 4].map((i) => srgbChannel(parseInt(n.slice(i, i + 2), 16) / 255));
    return luminanceLinear(rgb);
  }
  const oklch = text.match(/^oklch\(\s*([0-9.]+)(%)?\s+([0-9.]+)\s+([0-9.]+)(?:deg)?(?:\s*\/\s*[0-9.]+%?)?\s*\)$/i);
  if (!oklch)
    return null;
  let L = Number(oklch[1]);
  if (oklch[2] || L > 1)
    L /= 100;
  const C = Number(oklch[3]);
  const H = Number(oklch[4]);
  if (!Number.isFinite(L) || !Number.isFinite(C) || !Number.isFinite(H))
    return null;
  return luminanceLinear(oklchToLinear(L, C, H));
}
function srgbChannel(c) {
  return c <= 0.04045 ? c / 12.92 : ((c + 0.055) / 1.055) ** 2.4;
}
function luminanceLinear(rgb) {
  return 0.2126 * rgb[0] + 0.7152 * rgb[1] + 0.0722 * rgb[2];
}
function oklchToLinear(L, C, Hdeg) {
  const h = Hdeg * Math.PI / 180;
  const a = C * Math.cos(h);
  const b = C * Math.sin(h);
  const l_ = L + 0.3963377774 * a + 0.2158037573 * b;
  const m_ = L - 0.1055613458 * a - 0.0638541728 * b;
  const s_ = L - 0.0894841775 * a - 1.291485548 * b;
  const l = l_ ** 3;
  const m = m_ ** 3;
  const s = s_ ** 3;
  const r = 4.0767416621 * l - 3.3077115913 * m + 0.2309699292 * s;
  const g = -1.2684380046 * l + 2.6097574011 * m - 0.3413193965 * s;
  const bl = -0.0041960863 * l - 0.7034186147 * m + 1.707614701 * s;
  const clamp = (x) => x < 0 ? 0 : x > 1 ? 1 : x;
  return [clamp(r), clamp(g), clamp(bl)];
}
function dependencyNames(source) {
  const none = { urls: [], aliases: [] };
  if (typeof source !== "string")
    return none;
  try {
    const deps = JSON.parse(source).dependencies;
    if (!deps || typeof deps !== "object" || Array.isArray(deps))
      return none;
    const aliases = Object.values(deps).map((spec) => spec && typeof spec === "object" ? spec.as : undefined).filter((name) => typeof name === "string");
    return { urls: Object.keys(deps), aliases };
  } catch {
    return none;
  }
}
var DEP_URL = /^https:\/\/[a-z0-9.-]+\.[a-z]{2,}(?::[0-9]{1,5})?\/[A-Za-z0-9@._~+/-]+$/;
var DEP_ALIAS = /^(@[a-z0-9-~][a-z0-9-._~]*\/)?[a-z0-9-~][a-z0-9-._~]*$/;
var SRI = /^sha(256|384|512)-[A-Za-z0-9+/]+={0,2}$/;
var HAS_VERSION = /@v?\d+\.\d+|\/v?\d+\.\d+(\.\d+)?\//;
function scanDependencies(path, source, data, canvas, mode, at, out) {
  if (data.dependencies == null)
    return;
  const pos = at(findKey(source, "dependencies"));
  const deps = data.dependencies;
  if (typeof deps !== "object" || Array.isArray(deps)) {
    out.push(issue3("FL710", "error", path, pos.line, pos.col, "dependencies must be an object of https URL -> SRI hash"));
    return;
  }
  if (canvas && Object.keys(deps).length) {
    out.push(issue3("FL706", "error", path, pos.line, pos.col, "canvas profile cannot declare dependencies"));
  }
  for (const [url, spec] of Object.entries(deps)) {
    const where = at(findKey(source, url));
    if (url.length > 2048 || !DEP_URL.test(url) || url.includes("..") || url.indexOf("//", 8) >= 0) {
      out.push(issue3("FL710", "error", path, where.line, where.col, `dependency "${url}" must be an https file URL without query or fragment`));
      continue;
    }
    const last = url.slice(url.lastIndexOf("/") + 1);
    if (/\.[a-z]+$/i.test(last) && !/\.(m?js|json|wasm)$/i.test(last)) {
      out.push(issue3("FL710", "error", path, where.line, where.col, `dependency ${url} must be a JavaScript module (.js, .mjs), .json or .wasm`));
      continue;
    }
    const long = spec && typeof spec === "object";
    const integrity = long ? spec.integrity : spec;
    if (/\.wasm$/i.test(last) && integrity == null) {
      out.push(issue3("FL710", "error", path, where.line, where.col, `WebAssembly dependency ${url} must be hash-pinned`));
    }
    if (integrity != null && (typeof integrity !== "string" || !SRI.test(integrity))) {
      out.push(issue3("FL710", "error", path, where.line, where.col, `dependency ${url} hash must be sha256-, sha384- or sha512-<base64>`));
    } else if (integrity == null && mode === "publish") {
      out.push(issue3("FL710", "error", path, where.line, where.col, `dependency ${url} needs a hash to publish`));
    }
    if (long && spec.as != null && (typeof spec.as !== "string" || !DEP_ALIAS.test(spec.as))) {
      out.push(issue3("FL710", "error", path, where.line, where.col, `dependency alias "${spec.as}" must be a bare package name`));
    }
    if (!HAS_VERSION.test(url)) {
      out.push(issue3("FL711", "warn", path, where.line, where.col, `dependency ${url} has no version in its URL; pin one (name@1.2.3)`));
    }
  }
}
function scanManifest(path, source, profile, catalog, out, mode) {
  let data;
  try {
    data = JSON.parse(source);
  } catch {
    out.push(issue3("FL701", "error", path, 1, 1, "manifest.json is not valid JSON"));
    return;
  }
  const at = locator(source);
  if (!data || typeof data !== "object" || Array.isArray(data)) {
    for (const key of REQUIRED_MANIFEST) {
      out.push(issue3("FL701", "error", path, 1, 1, `manifest.json missing ${key}`));
    }
    return;
  }
  for (const key of REQUIRED_MANIFEST) {
    if (key === "deft" && !fieldMissing(data, "fl"))
      continue;
    if (fieldMissing(data, key)) {
      out.push(issue3("FL701", "error", path, 1, 1, `manifest.json missing ${key}`));
    }
  }
  if (typeof data.start_url === "string" && pathHasDotDotOrFl(data.start_url)) {
    const pos = at(source.indexOf(data.start_url) < 0 ? 0 : source.indexOf(data.start_url));
    out.push(issue3("FL003", "error", path, pos.line, pos.col, 'path must not contain "..", "_deft/" or "_fl/"'));
  }
  const canvas = profile === "canvas" || data.profile === "canvas";
  if (canvas && nonEmpty(data.capabilities)) {
    const pos = at(findKey(source, "capabilities"));
    out.push(issue3("FL706", "error", path, pos.line, pos.col, "canvas profile cannot declare capabilities"));
  }
  if (canvas && nonEmpty(data.network)) {
    const pos = at(findKey(source, "network"));
    out.push(issue3("FL706", "error", path, pos.line, pos.col, "canvas profile cannot declare network"));
  }
  if (canvas && nonEmpty(data.embeds)) {
    const pos = at(findKey(source, "embeds"));
    out.push(issue3("FL706", "error", path, pos.line, pos.col, "canvas profile cannot declare embeds"));
  }
  scanEmbeds(path, source, data, at, out);
  if (canvas && data.background != null) {
    const pos = at(findKey(source, "background"));
    out.push(issue3("FL706", "error", path, pos.line, pos.col, "canvas profile cannot declare background"));
  }
  scanBackground(path, source, data, at, out);
  if (canvas && data.handles != null) {
    const pos = at(findKey(source, "handles"));
    out.push(issue3("FL706", "error", path, pos.line, pos.col, "canvas profile cannot declare handles"));
  }
  scanHandles(path, source, data, at, out);
  if (canvas && data.wallpaper != null) {
    const pos = at(findKey(source, "wallpaper"));
    out.push(issue3("FL706", "error", path, pos.line, pos.col, "canvas profile cannot declare wallpaper"));
  }
  scanWallpaper(path, source, data, at, out);
  if (canvas && data.keyboard != null) {
    const pos = at(findKey(source, "keyboard"));
    out.push(issue3("FL706", "error", path, pos.line, pos.col, "canvas profile cannot declare keyboard"));
  }
  scanKeyboard(path, source, data, at, out);
  if (canvas && data.widget != null) {
    const pos = at(findKey(source, "widget"));
    out.push(issue3("FL706", "error", path, pos.line, pos.col, "canvas profile cannot declare widget"));
  }
  scanWidget(path, source, data, at, out);
  if (canvas && data.tools != null) {
    const pos = at(findKey(source, "tools"));
    out.push(issue3("FL706", "error", path, pos.line, pos.col, "canvas profile cannot declare tools"));
  }
  scanTools(path, source, data, at, out);
  scanDependencies(path, source, data, canvas, mode, at, out);
  if (fieldMissing(data, "version")) {
    out.push(issue3("FL703", "error", path, 1, 1, "manifest.json missing version"));
  } else if (typeof data.version !== "string" || !/^\d+\.\d+\.\d+(?:[-+][0-9A-Za-z.-]+)?$/.test(data.version)) {
    const pos = at(findKey(source, "version"));
    out.push(issue3("FL703", "error", path, pos.line, pos.col, "version must be semver"));
  }
  if (Array.isArray(data.network)) {
    for (const entry of data.network) {
      if (validNetworkOrigin(entry))
        continue;
      const shown = typeof entry === "string" ? entry : "";
      const index = shown ? source.indexOf(shown) : findKey(source, "network");
      const pos = at(index < 0 ? 0 : index);
      out.push(issue3("FL704", "error", path, pos.line, pos.col, `invalid network entry "${shown}"`));
    }
  } else if (data.network != null) {
    const pos = at(findKey(source, "network"));
    out.push(issue3("FL704", "error", path, pos.line, pos.col, "network must be an array of https origins"));
  }
  if (Array.isArray(data.capabilities)) {
    for (const entry of data.capabilities) {
      const reason = typeof entry === "string" ? "" : entry && typeof entry === "object" ? entry.reason : "";
      if (typeof reason === "string" && reason.length >= 10 && reason.length <= 140)
        continue;
      const pos = at(findKey(source, "capabilities"));
      out.push(issue3("FL708", "error", path, pos.line, pos.col, "capability reason must be 10–140 characters"));
    }
  }
  const knownFields = new Set([
    "deft",
    "fl",
    "id",
    "name",
    "version",
    "description",
    "profile",
    "start_url",
    "icon",
    "license",
    "source",
    "authors",
    "capabilities",
    "network",
    "categories",
    "forked_from",
    "min_host",
    "large",
    "kind",
    "dependencies",
    "shortcuts",
    "embeds",
    "background",
    "handles",
    "wallpaper",
    "keyboard",
    "widget",
    "tools"
  ]);
  if (data.shortcuts != null) {
    const list = Array.isArray(data.shortcuts) ? data.shortcuts : null;
    const pos = at(findKey(source, "shortcuts"));
    if (!list || list.length > 8) {
      out.push(issue3("FL708", "error", path, pos.line, pos.col, "shortcuts must be an array of at most 8 {id, name, url}"));
    } else {
      for (const sc of list) {
        if (!validShortcut(sc)) {
          out.push(issue3("FL708", "error", path, pos.line, pos.col, `invalid shortcut ${JSON.stringify(sc && sc.id)}: id [a-z0-9-], name 1-30 chars, url a same-origin path like "./#new"`));
        }
      }
    }
  }
  for (const key of Object.keys(data)) {
    if (knownFields.has(key))
      continue;
    const pos = at(findKey(source, key));
    out.push(issue3("FL705", "warn", path, pos.line, pos.col, `unknown manifest field "${key}"`));
  }
  if (catalog != null && Array.isArray(data.capabilities)) {
    const known = capabilitySet(catalog);
    for (const entry of data.capabilities) {
      const id = typeof entry === "string" ? entry : entry && typeof entry === "object" ? entry.id : undefined;
      if (typeof id === "string" && known.has(id))
        continue;
      const shown = typeof id === "string" ? id : "";
      const index = shown ? source.indexOf(shown) : findKey(source, "capabilities");
      const pos = at(index < 0 ? 0 : index);
      out.push(issue3("FL702", "error", path, pos.line, pos.col, `unknown capability "${shown}"`));
    }
  }
}
function validShortcut(sc) {
  if (!sc || typeof sc !== "object")
    return false;
  if (typeof sc.id !== "string" || !/^[a-z0-9-]{1,32}$/.test(sc.id))
    return false;
  if (typeof sc.name !== "string" || sc.name.trim().length < 1 || sc.name.length > 30)
    return false;
  return validShortcutUrl(sc.url);
}
function validShortcutUrl(url) {
  if (typeof url !== "string" || url.length > 512)
    return false;
  if (!(url.startsWith("./") || url.startsWith("/")))
    return false;
  if (url.startsWith("//") || url.includes("..") || url.includes("\\") || /[\s]/.test(url))
    return false;
  return !reservedPath(url);
}
var BACKGROUND_TRIGGERS = new Set(["alarms", "sms", "calls", "notify.action", "boot", "network", "charging", "push", "geofence", "sync", "notification", "screening"]);
function scanBackground(path, source, data, at, out) {
  if (data.background == null)
    return;
  const pos = at(findKey(source, "background"));
  const bg = data.background;
  if (typeof bg !== "object" || Array.isArray(bg)) {
    out.push(issue3("FL713", "error", path, pos.line, pos.col, "background must be {module, triggers}"));
    return;
  }
  if (!validBackgroundModule(bg.module)) {
    out.push(issue3("FL713", "error", path, pos.line, pos.col, 'background.module must be a same-origin .js or .mjs path like "./bg.js" (no "..", "//", "/_deft/" or "/_fl/")'));
  }
  if (!Array.isArray(bg.triggers)) {
    out.push(issue3("FL713", "error", path, pos.line, pos.col, "background.triggers must be an array"));
  } else {
    for (const entry of bg.triggers) {
      if (validBackgroundTrigger(entry))
        continue;
      out.push(issue3("FL713", "error", path, pos.line, pos.col, `unknown background trigger ${JSON.stringify(entry)}: one of ${[...BACKGROUND_TRIGGERS].join(", ")} or "periodic:N" with N from 15 to 10080 minutes`));
    }
    const periodic = new Set(bg.triggers.filter((t) => typeof t === "string" && t.startsWith("periodic:")));
    if (periodic.size > 1) {
      out.push(issue3("FL713", "error", path, pos.line, pos.col, "background.triggers may hold one periodic entry"));
    }
  }
  const declared = Array.isArray(data.capabilities) && data.capabilities.some((entry) => (typeof entry === "string" ? entry : entry && entry.id) === "background");
  if (!declared) {
    out.push(issue3("FL713", "error", path, pos.line, pos.col, "background needs the background capability"));
  }
}
function validBackgroundModule(value) {
  return normalizeModulePath(value) !== null;
}
function validBackgroundTrigger(value) {
  if (typeof value !== "string")
    return false;
  if (BACKGROUND_TRIGGERS.has(value))
    return true;
  const m = /^periodic:([0-9]{1,5})$/.exec(value);
  if (!m)
    return false;
  const minutes = Number(m[1]);
  return minutes >= 15 && minutes <= 10080;
}
function scanWallpaper(path, source, data, at, out) {
  if (data.wallpaper == null)
    return;
  const pos = at(findKey(source, "wallpaper"));
  const wp = data.wallpaper;
  if (typeof wp !== "object" || Array.isArray(wp)) {
    out.push(issue3("FL715", "error", path, pos.line, pos.col, "wallpaper must be {page}"));
    return;
  }
  if (!validWallpaperPage(wp.page)) {
    out.push(issue3("FL715", "error", path, pos.line, pos.col, 'wallpaper.page must be a same-origin .html path like "./wallpaper.html" (no "..", "//", "/_deft/" or "/_fl/")'));
  }
  const declared = Array.isArray(data.capabilities) && data.capabilities.some((entry) => (typeof entry === "string" ? entry : entry && entry.id) === "wallpaper");
  if (!declared) {
    out.push(issue3("FL715", "error", path, pos.line, pos.col, "wallpaper needs the wallpaper capability"));
  }
}
function scanKeyboard(path, source, data, at, out) {
  if (data.keyboard == null)
    return;
  const pos = at(findKey(source, "keyboard"));
  const kb = data.keyboard;
  if (typeof kb !== "object" || Array.isArray(kb)) {
    out.push(issue3("FL716", "error", path, pos.line, pos.col, "keyboard must be {page}"));
    return;
  }
  if (!validWallpaperPage(kb.page)) {
    out.push(issue3("FL716", "error", path, pos.line, pos.col, 'keyboard.page must be a same-origin .html path like "./keyboard/index.html" (no "..", "//", "/_deft/" or "/_fl/")'));
  }
  const declared = Array.isArray(data.capabilities) && data.capabilities.some((entry) => (typeof entry === "string" ? entry : entry && entry.id) === "ime");
  if (!declared) {
    out.push(issue3("FL716", "error", path, pos.line, pos.col, "keyboard needs the ime capability"));
  }
}
var WIDGET_SIZES = new Set(["1x1", "2x1", "2x2", "4x1", "4x2"]);
function scanWidget(path, source, data, at, out) {
  if (data.widget == null)
    return;
  const pos = at(findKey(source, "widget"));
  const wg = data.widget;
  if (typeof wg !== "object" || Array.isArray(wg)) {
    out.push(issue3("FL717", "error", path, pos.line, pos.col, "widget must be {page, sizes}"));
    return;
  }
  if (!validWallpaperPage(wg.page)) {
    out.push(issue3("FL717", "error", path, pos.line, pos.col, 'widget.page must be a same-origin .html path like "./widget.html" (no "..", "//", "/_deft/" or "/_fl/")'));
  }
  if (!Array.isArray(wg.sizes) || wg.sizes.length === 0) {
    out.push(issue3("FL717", "error", path, pos.line, pos.col, "widget.sizes must be a non-empty array"));
    return;
  }
  for (const size of wg.sizes) {
    if (WIDGET_SIZES.has(size))
      continue;
    out.push(issue3("FL717", "error", path, pos.line, pos.col, `unknown widget size ${JSON.stringify(size)}: one of ${[...WIDGET_SIZES].join(", ")}`));
  }
}
var MAX_TOOLS_PER_APP = 8;
var MAX_TOOL_DESCRIPTION = 300;
var MAX_TOOL_PROPERTIES = 12;
var MAX_TOOL_SCHEMA_BYTES = 2048;
var MAX_TOOL_ENUM = 24;
var TOOL_PRIMITIVES = new Set(["string", "number", "integer", "boolean"]);
var TOOL_PROP_KEYS = new Set(["type", "description", "enum", "items", "minimum", "maximum", "maxLength"]);
function parseToolHandler(value) {
  if (typeof value !== "string")
    return null;
  const at = value.indexOf("#");
  if (at <= 0)
    return null;
  const path = value.slice(0, at);
  const fn = value.slice(at + 1);
  const normalized = normalizeModulePath(path);
  if (normalized === null || !/^[A-Za-z_$][A-Za-z0-9_$]{0,63}$/.test(fn))
    return null;
  return { path: normalized, fn };
}
function toolMatchesType(type, v) {
  if (type === "string")
    return typeof v === "string";
  if (type === "boolean")
    return typeof v === "boolean";
  if (type === "number")
    return typeof v === "number" && Number.isFinite(v);
  if (type === "integer")
    return Number.isInteger(v);
  return false;
}
function toolSchemaError(schema) {
  if (JSON.stringify(schema).length > MAX_TOOL_SCHEMA_BYTES)
    return `schema is larger than ${MAX_TOOL_SCHEMA_BYTES} bytes`;
  if (schema === null || typeof schema !== "object" || Array.isArray(schema))
    return "params must be a JSON schema object";
  for (const key of Object.keys(schema)) {
    if (!["type", "properties", "required", "description"].includes(key))
      return `unsupported schema keyword "${key}"`;
  }
  if (schema.type !== "object")
    return 'type must be "object"';
  const props = schema.properties == null ? {} : schema.properties;
  if (typeof props !== "object" || Array.isArray(props))
    return "properties must be an object";
  if (Object.keys(props).length > MAX_TOOL_PROPERTIES)
    return `at most ${MAX_TOOL_PROPERTIES} properties`;
  for (const [name, prop] of Object.entries(props)) {
    if (!/^[A-Za-z_][A-Za-z0-9_]{0,31}$/.test(name))
      return `property name "${name}" must match [A-Za-z_][A-Za-z0-9_]{0,31}`;
    if (prop === null || typeof prop !== "object" || Array.isArray(prop))
      return `property "${name}" must be an object`;
    for (const key of Object.keys(prop))
      if (!TOOL_PROP_KEYS.has(key))
        return `property "${name}": unsupported keyword "${key}"`;
    const type = prop.type;
    if (typeof type !== "string")
      return `property "${name}" needs a type`;
    if (type === "array") {
      const items = prop.items;
      if (!items || typeof items !== "object" || Object.keys(items).join() !== "type" || !TOOL_PRIMITIVES.has(items.type)) {
        return `property "${name}": items must be {type: string|number|integer|boolean}`;
      }
    } else if (!TOOL_PRIMITIVES.has(type)) {
      return `property "${name}": type must be string, number, integer, boolean or array`;
    }
    if (prop.description !== undefined && (typeof prop.description !== "string" || prop.description.length > 200)) {
      return `property "${name}": description must be a string of at most 200 characters`;
    }
    if (prop.enum !== undefined) {
      if (!TOOL_PRIMITIVES.has(type))
        return `property "${name}": enum only on string, number, integer or boolean`;
      if (!Array.isArray(prop.enum) || prop.enum.length === 0 || prop.enum.length > MAX_TOOL_ENUM)
        return `property "${name}": enum needs 1-${MAX_TOOL_ENUM} values`;
      for (const v of prop.enum)
        if (!toolMatchesType(type, v))
          return `property "${name}": enum value ${JSON.stringify(v)} is not a ${type}`;
    }
    for (const bound of ["minimum", "maximum"]) {
      if (prop[bound] === undefined)
        continue;
      if (type !== "number" && type !== "integer")
        return `property "${name}": ${bound} only on number or integer`;
      if (typeof prop[bound] !== "number")
        return `property "${name}": ${bound} must be a number`;
    }
    if (prop.maxLength !== undefined) {
      if (type !== "string")
        return `property "${name}": maxLength only on string`;
      if (!Number.isInteger(prop.maxLength) || prop.maxLength < 1)
        return `property "${name}": maxLength must be a positive integer`;
    }
  }
  if (schema.required !== undefined) {
    if (!Array.isArray(schema.required))
      return "required must be an array";
    for (const key of schema.required) {
      if (typeof key !== "string" || !Object.prototype.hasOwnProperty.call(props, key))
        return `required names an unknown property ${JSON.stringify(key)}`;
    }
  }
  return null;
}
function scanTools(path, source, data, at, out) {
  if (data.tools == null)
    return;
  const pos = at(findKey(source, "tools"));
  const bad = (message) => out.push(issue3("FL718", "error", path, pos.line, pos.col, message));
  if (!Array.isArray(data.tools))
    return bad("tools must be an array of {name, description, params, handler}");
  if (data.tools.length > MAX_TOOLS_PER_APP)
    bad(`at most ${MAX_TOOLS_PER_APP} tools per app`);
  const seen = new Set;
  for (const tool of data.tools) {
    if (tool === null || typeof tool !== "object" || Array.isArray(tool)) {
      bad("each tool must be {name, description, params, handler}");
      continue;
    }
    const name = tool.name;
    if (typeof name !== "string" || !/^[a-z][a-z0-9_]{0,31}$/.test(name))
      bad(`tool name ${JSON.stringify(name)} must match [a-z][a-z0-9_]{0,31}`);
    else if (seen.has(name))
      bad(`duplicate tool "${name}"`);
    else
      seen.add(name);
    const label = typeof name === "string" ? name : "?";
    const d = typeof tool.description === "string" ? tool.description.trim() : "";
    if (d.length === 0 || d.length > MAX_TOOL_DESCRIPTION)
      bad(`tool "${label}" description must be 1-${MAX_TOOL_DESCRIPTION} characters`);
    const err = tool.params === undefined ? null : toolSchemaError(tool.params);
    if (err)
      bad(`tool "${label}" params: ${err}`);
    const h = tool.handler;
    let ref = null;
    if (typeof h === "string")
      ref = h;
    else if (h && typeof h === "object" && !Array.isArray(h) && Object.keys(h).join() === "isolate")
      ref = h.isolate;
    else {
      bad(`tool "${label}" handler must be "./tools.js#fn" or {"isolate": "./tools.js#fn"}`);
      continue;
    }
    if (!parseToolHandler(ref))
      bad(`tool "${label}" handler must be a same-origin .js path and a function, like ./tools.js#run`);
  }
}
function validWallpaperPage(value) {
  if (typeof value !== "string" || value.length > 256)
    return false;
  if (!/^\.?\/[A-Za-z0-9._~\/-]+\.html?$/.test(value))
    return false;
  if (value.includes("..") || value.includes("//"))
    return false;
  const absolute = value.startsWith(".") ? value.slice(1) : value;
  return !reservedPath(absolute);
}
var MAX_HANDLES = 16;
var LIVE_SCHEMES = new Set(["web+deft", "web+fl", "mailto"]);
function validHandleMime(value) {
  return typeof value === "string" && /^[a-z0-9][a-z0-9.+-]{0,62}\/(\*|[a-z0-9][a-z0-9.+-]{0,126})$/.test(value);
}
function validHandleScheme(value) {
  return typeof value === "string" && (value === "mailto" || /^web\+[a-z]{1,32}$/.test(value));
}
function scanHandles(path, source, data, at, out) {
  if (data.handles == null)
    return;
  const pos = at(findKey(source, "handles"));
  const h = data.handles;
  if (typeof h !== "object" || Array.isArray(h)) {
    out.push(issue3("FL714", "error", path, pos.line, pos.col, "handles must be {mimeTypes?, schemes?, links?}"));
    return;
  }
  const rules = [
    ["mimeTypes", validHandleMime, "type/subtype or type/*, lowercase, not */*"],
    ["schemes", validHandleScheme, "web+name (lowercase letters) or mailto"],
    ["links", (v) => validNetworkOrigin(v) && v === v.toLowerCase(), "an https origin like https://example.com"]
  ];
  for (const [key, valid, what] of rules) {
    const list = h[key];
    if (list == null)
      continue;
    if (!Array.isArray(list)) {
      out.push(issue3("FL714", "error", path, pos.line, pos.col, `handles.${key} must be an array`));
      continue;
    }
    if (list.length > MAX_HANDLES) {
      out.push(issue3("FL714", "error", path, pos.line, pos.col, `at most ${MAX_HANDLES} handles.${key}`));
    }
    for (const entry of list) {
      if (valid(entry))
        continue;
      out.push(issue3("FL714", "error", path, pos.line, pos.col, `invalid handles.${key} entry ${JSON.stringify(entry)}: ${what}`));
    }
  }
  const declared = Array.isArray(data.capabilities) && data.capabilities.some((entry) => (typeof entry === "string" ? entry : entry && entry.id) === "share.receive");
  if (Array.isArray(h.mimeTypes) && h.mimeTypes.length > 0 && !declared) {
    out.push(issue3("FL714", "error", path, pos.line, pos.col, "handles.mimeTypes need the share.receive capability (files are read with intents.readShare)"));
  }
  if (Array.isArray(h.schemes)) {
    for (const scheme of h.schemes) {
      if (!validHandleScheme(scheme) || LIVE_SCHEMES.has(scheme))
        continue;
      out.push(issue3("FL714", "warn", path, pos.line, pos.col, `scheme ${JSON.stringify(scheme)} never reaches the app: Android only sends web+deft, web+fl and mailto to Deft`));
    }
  }
  if (Array.isArray(h.links) && h.links.length > 0) {
    out.push(issue3("FL714", "warn", path, pos.line, pos.col, "handles.links do nothing yet: Android cannot verify app links for an app origin, so https links open in the browser"));
  }
}
var MAX_EMBEDS = 16;
function scanEmbeds(path, source, data, at, out) {
  if (data.embeds == null)
    return;
  const pos = at(findKey(source, "embeds"));
  if (!Array.isArray(data.embeds)) {
    out.push(issue3("FL712", "error", path, pos.line, pos.col, "embeds must be an array of https origins"));
    return;
  }
  if (data.embeds.length > MAX_EMBEDS) {
    out.push(issue3("FL712", "error", path, pos.line, pos.col, `at most ${MAX_EMBEDS} embeds`));
  }
  for (const entry of data.embeds) {
    if (validEmbedOrigin(entry))
      continue;
    const shown = typeof entry === "string" ? entry : "";
    const index = shown ? source.indexOf(JSON.stringify(shown)) : -1;
    const where = index < 0 ? pos : at(index);
    out.push(issue3("FL712", "error", path, where.line, where.col, `invalid embed "${shown}": https://host[:port], "*." only as the leftmost label and not over a public suffix (*.github.io, *.co.uk), no IPs or localhost`));
  }
  const declared = Array.isArray(data.capabilities) && data.capabilities.some((entry) => (typeof entry === "string" ? entry : entry && entry.id) === "web.embed");
  if (data.embeds.length > 0 && !declared) {
    out.push(issue3("FL712", "error", path, pos.line, pos.col, "embeds need the web.embed capability"));
  }
}
function validEmbedOrigin(value) {
  if (typeof value !== "string" || !value.startsWith("https://"))
    return false;
  const rest = value.slice("https://".length);
  const wildcard = rest.startsWith("*.");
  const plain = wildcard ? "https://" + rest.slice(2) : value;
  if (plain.includes("*"))
    return false;
  if (plain !== plain.toLowerCase())
    return false;
  if (!validNetworkOrigin(plain))
    return false;
  if (wildcard && publicSuffix(plain.slice("https://".length).replace(/:\d+$/, "")))
    return false;
  return true;
}
var CC_SECOND_LEVELS = new Set([
  "ac",
  "co",
  "com",
  "edu",
  "go",
  "gob",
  "gouv",
  "gov",
  "lg",
  "ltd",
  "mil",
  "ne",
  "net",
  "nhs",
  "nic",
  "nom",
  "or",
  "org",
  "plc",
  "sch"
]);
var SHARED_HOSTING = new Set([
  "amazonaws.com",
  "appspot.com",
  "azurestaticapps.net",
  "azurewebsites.net",
  "blogspot.com",
  "cloudfront.net",
  "deno.dev",
  "firebaseapp.com",
  "fly.dev",
  "github.io",
  "gitlab.io",
  "glitch.me",
  "herokuapp.com",
  "myshopify.com",
  "netlify.app",
  "ngrok-free.app",
  "ngrok.io",
  "onrender.com",
  "pages.dev",
  "r2.dev",
  "repl.co",
  "replit.app",
  "s3.amazonaws.com",
  "surge.sh",
  "vercel.app",
  "web.app",
  "wixsite.com",
  "wordpress.com",
  "workers.dev"
]);
function publicSuffix(host) {
  if (SHARED_HOSTING.has(host))
    return true;
  const labels = host.split(".");
  return labels.length === 2 && labels[1].length === 2 && CC_SECOND_LEVELS.has(labels[0]);
}
function validNetworkOrigin(value) {
  if (typeof value !== "string")
    return false;
  if (!/^https:\/\/[a-z0-9.-]+(?::[0-9]{1,5})?$/i.test(value))
    return false;
  const host = value.slice("https://".length).replace(/:\d+$/, "").toLowerCase();
  if (!host.includes(".") || host.split(".").some((label) => label === ""))
    return false;
  if (host === "localhost" || host.endsWith(".localhost"))
    return false;
  if (host === "deft.internal" || host.endsWith(".deft.internal"))
    return false;
  if (/^\d{1,3}(?:\.\d{1,3}){3}$/.test(host))
    return false;
  if (host.includes("*"))
    return false;
  return true;
}
function fieldMissing(obj, key) {
  if (!Object.prototype.hasOwnProperty.call(obj, key))
    return true;
  const value = obj[key];
  if (value === null || value === undefined)
    return true;
  if (typeof value === "string" && value.trim() === "")
    return true;
  return false;
}
function nonEmpty(value) {
  if (value == null)
    return false;
  if (Array.isArray(value) || typeof value === "string")
    return value.length > 0;
  if (typeof value === "object")
    return Object.keys(value).length > 0;
  return true;
}
function capabilitySet(catalog) {
  const caps = catalog && catalog.capabilities;
  const set = new Set;
  if (!caps || typeof caps !== "object")
    return set;
  if (Array.isArray(caps)) {
    for (const entry of caps) {
      if (typeof entry === "string")
        set.add(entry);
      else if (entry && typeof entry === "object" && typeof entry.id === "string")
        set.add(entry.id);
    }
  } else {
    for (const key of Object.keys(caps))
      set.add(key);
  }
  return set;
}

// src/browser.js
globalThis.deftLint = { run };
