# Design system proposal: from "terminal themes" to a premium look

Status: implemented on design/system (uncommitted); phases 0-5 done. Decisions settled 2026-11-20 (section 8).

Known leftovers:
- Drawer: the "Web apps" heading and the "Bundled samples..." note sit side by side and cramped on a 1080-wide screen (AllAppsPage).
- Home canvas: `--chrome-clearance` is a fixed 240px; not verified against gesture-nav insets or very short screens.
- Dock and harness bar are translucent fills only; no backdrop blur (the canvas is an unblurred WebView by the A16 audit).
- Apps without a monochrome layer keep their full-colour icon in mono mode (by design; a silhouette would be a solid blob).
- sms Back at the inbox: the code matches HEAD (handler returns false at the list); whether the host then exits the app was not re-verified.
- Only a Galaxy A16 measurement pass on the final home canvas is still open; emulator only here.

> **Decisions in force (override anything below that says otherwise):**
> - One hybrid style. The theme is **Accent × Appearance** (auto/light/dark/black). There is no style switch.
> - Fonts: Geist (text and display), Geist Mono (micro-labels), Doto (big numerals). No Inter.
> - The terminal themes are dropped, with a migration to the nearest accent.
> - The accent marks **the primary action and a single active indicator** (selected-tab dot, live signal, unread dot). Links, toggles, secondary buttons and focus rings use `--deft-label`. The default accent is a signal red, and the error colour is shifted orange and always comes with an icon and text.
> - Glass blur is on for mid and high tier and opaque on low tier. A "Reduce transparency" setting forces opaque.
>
> Section 8 spells out what each one changes. Section 2 is background research and describes both references on purpose.

## TL;DR

- **Keep the plumbing, replace the language.** The delivery pipeline is good and stays: palette file → `/_deft/v1/theme.css` → layered `base.css` → live switch through `deft:theme`. The *token model* is what makes the result look like a Linux rice. It has 20 daisyUI-style hues (primary, secondary, accent, neutral…) filled with terminal schemes (Dracula, Nord, Synthwave, Tokyo Night). Swap it for an iOS-style **semantic, monochrome-first** model with **one accent**.
- **One hybrid style instead of a list of themes.** iOS layout, glass and springs combined with Nothing's monochrome palette and dot-matrix numerals. Users choose only an **accent** (one colour) and an **appearance** (light/dark/auto/black). See section 8.
- **Yes, pre-bake motion.** Agents are reliably bad at motion and good at naming things. Ship spring presets as tokens plus a small set of declarative behaviours (`data-deft-enter`, sheets with detents, view-transition morphs, number roll, press, haptics). Raw `@keyframes` and `cubic-bezier` stay banned.
- **Yes, colour has to change.** Move from multi-hue palettes to neutrals plus one accent, derived in OKLCH so any accent (or the wallpaper's) produces a contrast-safe palette. The accent is a rare signal, not the colour of everything you can tap.
- **The biggest quality lever is not tokens but patterns.** Ship screen templates (large-title list, grouped settings, detail, player, dashboard tile) and a component gallery app. Agents compose patterns well. They do not invent taste from tokens.
- **One source of truth, many harnesses.** A token JSON generates `theme.css`, the Compose theme and the skill docs. The same skill folder ships on-device (`skill.read`) and as a Claude Code skill. `deft-lint` enforces it.
- **Fix the current bugs first** (below). Some of today's "ugly" is simply tokens not arriving.

---

## 1. What we have today (survey summary)

Delivery:
- **Source:** `assets/config/themes/<id>/palette.json`, which a copy in filesDir can override (`config/ConfigStore.kt:204-215`).
- **Conversion:** `Palette.kt` turns it into CSS (`DeftRuntimeTheme.sheet`).
- **Serving and live switch:** `LocalOriginPathHandler` serves it as `/_deft/v1/theme.css`. `ThemeChrome.applyDeftTheme` reloads the link and fires `deft:theme`.
- **Compose:** gets six colours only. Radius, density, border and font never reach the native UI (`ui/theme/Theme.kt:33-97`).

Tokens:
- 20 colour keys. Radius steps none/s/m/l/full. Border width, density, and 8 fluid space steps.
- 6 text sizes. 4 durations plus 3 easings.
- The `font` key is parsed nowhere (`Palette.kt:69-70` hardcodes `system-ui`). No fonts ship. No icon set exists: apps use unicode glyphs.

`base.css` (16,183 B against a 16 KB test cap) has:
- A decent reset and layout primitives.
- Components: btn, card, badge, input, tabs, menu, list, appbar, tabbar, dialog/sheet, toast.
- Some `@starting-style` motion and one `.deft-morph` view transition.

Problems found:

| # | Problem | Effect |
|---|---|---|
| 1 | `themeCssJs` overwrites `DeftRuntimeTheme.sheet` with the legacy chrome CSS (`ThemeChrome.kt:477`), racing `Palette.publish` | theme.css may lack every `--deft-color-*`, radius and font token. **Needs a device check, likely the #1 cause of "looks off".** |
| 2 | 7 byte-identical 354-line `chrome.css` copies, **unlayered**, with hardcoded 10/12/14/16/24 px radii, `13px` text, `0 8px 24px` shadow | They beat every `@layer` rule in base.css, so the design system cannot win |
| 3 | sms, calendar, mesh and phone hardcode Tokyo Night hex and don't link theme.css | They never follow a theme switch |
| 4 | Canonical components (`deft-btn`, `deft-card`, `deft-input`) are essentially unused. `.deft-sheet`, `.deft-list`, `.deft-badge` and `.deft-empty` collide with legacy meanings | Two competing systems |
| 5 | Contrast failures: cupcake success/warning 3.3:1, nord several at 3.5–4.1:1. `muted = neutral` makes "muted" text full-strength in cupcake and dracula | Muddy hierarchy |
| 6 | No elevation, material, weight, leading or tracking tokens. No switch, chip, progress, skeleton, avatar or segmented control | Agents improvise, and improvisation looks generic |
| 7 | `SKILL.md` describes `colors.toml` and 2 themes. Lint tests reference nonexistent tokens | Agents get stale instructions |

Why it reads as "Linux-y":
- The palettes are editor colour schemes: saturated multi-hue, every role a different colour.
- There is no type hierarchy beyond size, and mono is used decoratively.
- Unicode glyphs stand in for icons.
- Everything is a bordered box, there are no materials or depth, and motion is minimal.

## 2. What iOS and Nothing actually do

Distilled to things CSS can encode. Full research notes cover iOS 26/27 Liquid Glass and Nothing OS 4.

**Shared by both:**
1. **One accent, neutral everything else.** iOS tint marks interactive elements. Nothing red is one rare signal per screen.
2. **Semantic colour.** iOS uses label / secondaryLabel / tertiary / quaternary as *alpha* steps, plus background / grouped / elevated surfaces, fills and separators. Alpha-based text works on any surface.
3. **True-black OLED dark mode.** Elevated surfaces are slightly lighter, not bordered.
4. **A tight, named type scale with few weights.** Tracking tightens as size grows. Large numerals and titles carry the layout.
5. **Generous, concentric radii** (inner = outer − padding) and **capsule controls**.
6. **Grouped inset cards with hairline separators.** Almost no heavy shadows.
7. **Monochrome line icons** on one grid, tinted with `currentColor`.
8. **Physical feedback:** press scales to about 0.96, paired with a haptic.
9. **Accessibility fallbacks are designed, not bolted on:** reduce transparency, increase contrast, reduce motion.

**Where they differ** (the hybrid in section 8.1 picks a side for each row):

| | iOS-like ("Glass") | Nothing-like ("Dot") |
|---|---|---|
| Material | Translucent glass for *controls and navigation only*, content stays opaque. iOS 27 adds stronger diffusion, a darker rim and a scroll-edge scrim | Flat, opaque, grey fills, hairline outlines, dot-grid textures |
| Display type | Humanist sans, optical sizes, bold large titles | Dot-matrix numerals/titles + uppercase mono micro-labels; plain grotesque body |
| Accent role | Tint used widely: buttons, toggles, links | Red used once: a dot, active state, a record light |
| Shape | Squircles (continuous corners), capsules | Perfect circles, pills, dot grids |
| Motion | Fluid, interruptible springs with slight overshoot, morphing | Mechanical, snappy, stepped: dot/pixel reveals, segmented progress |

Licensing guardrails:
- SF Pro, Ndot 57, NType 82 and Lettera are **not redistributable**. We take the *ideas*, not their assets, names or fonts.
- Open stand-ins:
  - **Inter** or **Geist** (sans, OFL)
  - **Geist Mono** or **JetBrains Mono** (OFL)
  - **Doto** (dot-matrix, OFL, variable, with a dot-roundness axis)

## 3. Proposed model

### 3.1 Two axes

```
theme = Accent     ×  Appearance            (one hybrid style, section 8.1)
        signal red    auto | light | dark | black
        wallpaper
        any OKLCH
```

- **The style** is one token file with the structure: material, type families and scale, radii, separators, motion character, icon stroke, and density. It is not user-selectable; do not build a style switch.
- **Accent** is one colour: a preset, user-picked, or extracted from the wallpaper (Material You extraction already exists in `Palette.kt:100-151`; the hidden "system" theme becomes accent = wallpaper and is shown in the picker, section 8.3).
- **Appearance** is light/dark/auto, plus "black" (pure OLED, like Nothing's Extra Dark).

The old themes (dracula, nord…) are dropped; section 8.3 describes the migration.

### 3.2 Colour: semantic tokens derived from one accent

Use CSS relative colour syntax with OKLCH. It is supported in the WebView's Chromium (119+), so the launcher only publishes **accent + appearance**, and contrast-safe steps are computed:

```css
@layer deft.theme {
  :root {
    --deft-accent: oklch(0.58 0.21 27);          /* published by launcher; default signal red; user- or wallpaper-chosen */
    /* surfaces (dark shown) */
    --deft-bg:          oklch(0 0 0);            /* black appearance; 0.16 for dark */
    --deft-bg-grouped:  oklch(0.13 0 0);
    --deft-bg-elevated: oklch(0.21 0 0);
    /* labels: alpha steps, work on any surface */
    --deft-label:   oklch(1 0 0);
    --deft-label-2: oklch(1 0 0 / .62);
    --deft-label-3: oklch(1 0 0 / .32);
    --deft-label-4: oklch(1 0 0 / .18);
    --deft-fill:    oklch(1 0 0 / .12);  /* + fill-2..4 */
    --deft-separator: oklch(1 0 0 / .14);
    /* accent family, derived */
    --deft-on-accent:   oklch(from var(--deft-accent) clamp(0, (0.62 - l) * 999, 1) 0 0);
    /* status: fixed set, tuned per appearance; always shown with icon + text */
    --deft-error:   oklch(0.70 0.19 50);   /* orange-shifted so it never reads as the red accent */
    --deft-warning: oklch(0.82 0.16 85);
    --deft-success: oklch(0.75 0.17 150);
    --deft-info:    var(--deft-label-2);
  }
}
```

Rules encoded in the skill and lint (the component colour map in 8.1 is the precise version):
- The accent marks **the primary action and a single active indicator** (selected-tab dot, live signal, unread dot), about one signal per screen. Selected chips and segments stay neutral. Links, toggles, secondary buttons and focus rings use `--deft-label`.
- Large areas are neutral. The accent never tints surfaces, including when it comes from the wallpaper.
- Status colours mean status and always come with an icon and text.

`Palette.kt` validates every published combination against 4.5:1 (3:1 for large text) and nudges accent lightness if needed. If the accent hue lands within 30° of `--deft-error`, it shifts the error hue further away. That fixes problem #5 permanently.

### 3.3 Materials (and the low-end reality)

```css
--deft-material-bar:   color-mix(in oklch, var(--deft-bg-elevated) 62%, transparent); /* low tier / reduce transparency: 92% */
--deft-material-blur:  blur(24px) saturate(1.7);                                       /* low tier / reduce transparency: none */
--deft-material-rim:   inset 0 0 0 .5px oklch(1 0 0 / .22), inset 0 1px 0 oklch(1 0 0 / .18);
```

Apply them only through components (`.deft-bar`, `.deft-tabbar`, `.deft-sheet`, `.deft-toolbar`), never on content.

The Galaxy A16 audit already removed blur from the home canvas for performance. The launcher therefore publishes a **device tier**. Blur is on for mid and high tier (8.4); on low tier `--deft-material-blur` becomes `none` with the more opaque fill. Apps don't need to know.

The opaque material is also forced by the "Reduce transparency" setting, by `prefers-reduced-transparency`, and by `prefers-contrast: more` (which also makes the separator solid).

In the **black** appearance the glass base is `--deft-bg-elevated`, not `--deft-bg`, and the rim is mandatory, so bars read against true black instead of blurring black over black.

### 3.4 Type

- **Bundle the fonts** in `/_deft/v1/fonts/` as woff2, offline-safe, with a 400 KB build cap (expect 250–400 KB; measure after subsetting):
  - Geist Variable (text and display), Latin + Latin-Ext
  - Geist Mono, weights 400–500 only
  - Doto, limited to digits, `: . - % °` and A–Z, with the roundness axis pinned
  - `font-display: swap`; only Geist is preloaded
- The style file sets the roles: `--deft-font-text`, `--deft-font-display`, `--deft-font-mono`, `--deft-font-numeric`.
- Text and display are Geist (tight tracking at display sizes). Big numerals use Doto. Micro-labels are uppercase Geist Mono.
- A named scale replaces xs..2xl. Each step is a single class that sets size, weight, leading and tracking together, which is what makes type look designed:
  - `largeTitle 34/41 700 -0.4`
  - `title1 28`
  - `title2 22`
  - `title3 20`
  - `headline 17 600`
  - `body 17`
  - `callout 16`
  - `subhead 15`
  - `footnote 13`
  - `caption 12/11`
  - `labelMono 11 500`, Geist Mono, uppercase, tracking +0.08em, `--deft-label-2`. Micro-labels only.
- `font-variant-numeric: tabular-nums` is the default for `--deft-font-numeric`.

### 3.5 Shape

- `--deft-radius-xs/sm/md/lg/xl/screen` plus a **concentric helper**: `--deft-radius-inner: calc(var(--deft-radius-outer) - var(--deft-pad))`. Components set `--deft-radius-outer` and `--deft-pad` (default 12px) and children inherit the correct inner radius. Both are listed in `tokens.md`; lint rejects raw radius literals.
- `corner-shape: squircle` where the WebView supports it, falling back to plain `border-radius`. Containers use squircle; avatars, dots and icon buttons are perfect circles.
- Controls are capsules by default.

### 3.6 Motion: yes, pre-baked

Three spring presets, generated as `linear()` easings by a script, not handwritten. These are physically computed examples:

```css
--deft-spring-snappy: linear(0, .054, .174, .316, .456, .582, .687, .772, .838,
                           .888, .925, .951, .969, .982, .99, .995, 1);   /* 280ms, taps, toggles */
--deft-spring-smooth: linear(0, .1, .288, .474, .628, .744, .828, .886, .926,
                           .952, .969, .98, .988, .992, .995, .997, 1);   /* ~450-600ms, navigation, sheets */
--deft-spring-bouncy: linear(0, .126, .375, .625, .82, .947, 1.015, 1.042, 1.045,
                           1.036, 1.024, 1.014, 1.006, 1.001, .999, .998, 1); /* 660ms, playful confirms */
--deft-step-dot: steps(8, jump-end);  /* numbers, segmented progress, dot reveals only */
```

Springs are the default everywhere. The stepped curve is reserved for numbers, segmented progress and dot reveals.

Pre-baked behaviours (declarative, so agents can't get them wrong):

| Behaviour | API | Notes |
|---|---|---|
| Enter / stagger | `data-deft-enter="rise\|fade\|scale"` on a list; children stagger automatically | IntersectionObserver in runtime, ~1 KB |
| Press | automatic on `.deft-btn`, `.deft-cell[href]`, `[data-deft-press]` | scale .96 + `deft.haptic('light')` |
| Sheet with detents | `<dialog class="deft-sheet" data-detents="medium large">` | drag handle, velocity-aware snap, glass→opaque at large |
| Navigation | `fl.routes()` uses View Transitions; `data-deft-morph="id"` pairs elements | list-cell → detail hero morph |
| Large title collapse | `.deft-page[data-large-title]` | scroll-driven animation (`animation-timeline: scroll()`), zero JS |
| Number change | `<deft-number value>` | Doto numerals with a stepped dot-matrix flip |
| Progress | `.deft-progress` / `.deft-progress[data-segments]` | segmented variant for counts and steps |
| Skeleton / shimmer | `.deft-skeleton` | |
| Toast queue | `deft.toast(msg, {tone})` | |
| Haptics | `deft.haptic('light\|medium\|success\|warning\|selection')` | bridge to `HapticFeedbackConstants` |

All of them collapse to opacity cross-fades under `prefers-reduced-motion`.

### 3.7 Icons

- Ship one open line-icon set as an SVG sprite at `/_deft/v1/icons.svg`, with a tiny `<deft-icon name="…">` element. Candidates: **Lucide** (ISC) or **Phosphor** (MIT, has weights).
- Stroke width is a token (`--deft-icon-stroke`, 1.5).
- On the launcher side, support **Android themed (monochrome) icons** as a home-screen option. This is the single biggest Nothing-like move for the home screen.
- Lint flags unicode-glyph-as-icon (`‹ › + ✕ ☰`).

### 3.8 Patterns over primitives

Add screen-level building blocks. They are what make output look expensive:

- `.deft-page` with large title, search field and floating toolbar
- `.deft-group` grouped inset list: `.deft-cell` with leading icon tile, title, subtitle, trailing value, chevron or switch, and inset separators
- `.deft-tabbar` floating capsule that minimises on scroll
- `.deft-segmented`, `.deft-switch` (real styled switch, not `accent-color`; colours per 8.1), `.deft-chip`, `.deft-stepper`, `.deft-slider`
- `.deft-tile` glanceable widget: one big number, a label and an optional sparkline. The number renders in Doto.
- `.deft-hero` media header with scrim
- `.deft-empty` with icon, title, body and action

Plus **6 reference templates** in the skill: list→detail, settings, dashboard of tiles, media player, form/compose, onboarding. Each comes as complete HTML an agent can copy and adapt.

Ship a **Gallery** deft-app that renders every component in every accent and appearance, with and without blur. It is a visual test target for screenshots and the thing agents look at.

### 3.9 Size budget

`base.css` is at its 16 KB cap. Split it:
- `base.css`: reset, tokens, type and layout, kept under 16 KB.
- `components.css`
- `motion.js`, about 3 KB

Load the last two by default; the cap's purpose (low-end parse cost) is better served by measuring parse time on the A16 than by a byte count.

## 4. One source of truth for every harness

```
design/tokens/*.json   (W3C design-tokens format: color, type, shape, motion, material)
        │  tools/deft-design build
        ├─► assets/deft-runtime/v1/theme.css             (WebView)
        ├─► android/.../ui/theme/Tokens.kt             (Compose: ColorScheme, Shapes, Typography, motion specs)
        ├─► skill/references/tokens.md                 (generated table, never stale)
        └─► tools/deft-lint rules data                   (allowed token names)
```

**The skill** is one folder `skill/deft-design/` with:
- `SKILL.md`: rules and decision tree. Pick a template, then compose components, then use tokens only for gaps.
- `templates/*.html`
- `references/{tokens,components,motion}.md`

It is consumed by:
- The **on-device harness**: copied into assets, read through `skill.read`. This replaces today's `assets/agent/skills/theme.md`.
- **Claude Code and other external harnesses**: symlinked or exported to `.claude/skills/deft-design/`. The same markdown works because it is plain Agent Skills format.
- **Lint as the enforcer**:
  - no hex/rgb/oklch literals outside `color-mix()` of tokens
  - no raw durations or easings
  - no unlayered CSS
  - no glyph-icons
  - must link theme.css
  - accent placement: accent tokens only in `.deft-btn[data-primary]`, the tab-bar indicator, `.deft-badge` dots and `[data-live]` signals, at most one primary button per screen
  - error colour only on an element that also has an icon and text

  Agents read lint errors more reliably than prose.

**Self-check loop for harnesses:** after building, render the app in each appearance, with and without blur, and look at the screenshot. The on-device harness can capture its own WebView, and Claude Code can use the emulator. Taste needs eyes.

## 5. Native launcher

Compose has one theme, `DeftTheme(accent, appearance)`, from the generated `Tokens.kt`. There are no style variants.
- Shapes and Typography with the bundled Geist, Geist Mono and Doto via `res/font`
- The Material `ColorScheme` maps `primary` to `--deft-label` and everything else to the neutrals, so stock Material switches, sliders, text buttons and focus states come out neutral as 8.1 requires. The accent is a separate `DeftTheme.colors.accent`, used only by the primary button, the tab-bar indicator, badges and live signals
- Spring specs mapped to `spring(dampingRatio, stiffness)`
- Glass falls back to the opaque material on low tier and with Reduce transparency

Home-screen features that sell the style:
- monochrome themed icons
- dot-matrix clock and widgets in Doto
- glass dock on capable devices
- accent from wallpaper

## 6. Extend or change? Recommendation

**Change the token model, extend the pipeline.** Concretely:

- Keep `palette.json` loading, `theme.css` serving, `deft:theme` live switching, `@layer` order, the layout primitives and the motion-token ban.
- Change the colour schema (20 hues → accent + semantic neutrals), add the style token file, and wire `font` and `shape` through to both WebView and Compose.
- Add fonts, icons, motion runtime, patterns, templates, the Gallery app and the generated skill.
- Migrate with a compat shim for one release. Lint warns, and the shim is removed the release after:
  - `--deft-color-primary` → `--deft-accent`, `--deft-color-primary-content` → `--deft-on-accent`
  - `--deft-color-base-100 / -200 / -300` → `--deft-bg` / `--deft-bg-grouped` / `--deft-bg-elevated`
  - `--deft-color-base-content` → `--deft-label`
  - `--deft-color-secondary / accent / neutral` → `--deft-label-2`; `--deft-color-error / warning / success / info` → the status tokens
  - legacy `--deft-fg` → `--deft-label`, `--deft-surface` → `--deft-bg-grouped`, `--deft-muted` → `--deft-label-2`, `--deft-red` → `--deft-error`. Legacy `--deft-bg`, `--deft-accent` and `--deft-on-accent` keep their names with the new meaning.

## 7. Phased plan

| Phase | Scope | Done when |
|---|---|---|
| 0. Fix | theme.css ordering race; replace the 7 `chrome.css` copies with existing base.css components; make all apps link theme.css; trim SKILL.md of stale colors.toml and two-theme text (full rewrite is phase 3) | switching theme visibly restyles every bundled app |
| 1. Tokens v2 | token JSON + generator; accent/appearance model with OKLCH and contrast validation; the hybrid style; old themes removed with migration; Geist, Geist Mono and Doto bundled; device tier published; Compose wired | Gallery renders correctly in 4 appearances × 4 accents (incl. wallpaper), with and without blur |
| 2. Components + motion | split CSS, new components, `motion.js` behaviours, haptics bridge, icon sprite | Gallery interactions feel right on the A16 and the emulator |
| 3. Skill + lint | `skill/deft-design` with templates; Claude Code export; lint rules | a fresh agent builds a "notes app" that passes lint and looks on-style without manual fixes |
| 4. Migrate apps | port the bundled apps (clock, sms, calendar, phone, contacts, files…) to templates | zero legacy tokens, zero hardcoded colours |
| 5. Launcher | home screen in new style, themed icons, wallpaper accent | |

## 8. Decisions (settled 2026-11-20)

### 8.1 One hybrid style

There is no Style axis. The theme is **Accent × Appearance** only, and there is one structural style that combines the two references:

| Taken from iOS | Taken from Nothing |
|---|---|
| Layout patterns: large-title pages, grouped inset lists, floating capsule tab bar, sheets with detents | Monochrome palette: true-black / off-white neutrals, greys, no gradients |
| Glass material on bars, tab bar, sheets and toolbars (content stays opaque) | Accent restraint: one signal per screen |
| Spring motion: snappy, smooth, bouncy | Dot-matrix display numerals (Doto) for clocks, big values, tiles |
| Squircle corners, capsule controls, concentric radii | Uppercase mono micro-labels with wide tracking; segmented progress; stepped reveals for numbers |

Consequences:
- The token JSON has one style file. There is no style switch, no per-style lint data and no per-style Compose variant.
- **Accent rule (hybrid):** the accent marks the primary action and a single active indicator. Links, toggles, secondary buttons and focus rings use `--deft-label`. That keeps the Nothing restraint while staying usable.
- **Component colour map.** This is the binding version of the accent rule for web and Compose:

  | Component | Colour |
  |---|---|
  | `.deft-btn[data-primary]` (one per screen) | `--deft-accent` fill, `--deft-on-accent` text |
  | `.deft-btn` secondary | `--deft-fill` fill, `--deft-label` text |
  | `.deft-switch` | on: `--deft-label` track, `--deft-bg` knob; off: `--deft-fill` track |
  | `.deft-tabbar` selected tab | `--deft-label` icon and text, plus a 4px `--deft-accent` dot under it (the only accent in the bar) |
  | `.deft-segmented`, `.deft-chip` selected | `--deft-label` text on a `--deft-fill` pill; no accent |
  | `.deft-slider`, `.deft-progress` fill | `--deft-label`; switches to `--deft-accent` only when marked `[data-live]` (for example recording) |
  | Links | `--deft-label`, underlined |
  | Focus ring | 2px `--deft-label` |
  | Badges / unread dots | `--deft-accent` |
  | Errors | `--deft-error` with an icon and text |
- **Default accent:** a signal red, about `oklch(0.58 0.21 27)`. Because the default accent is red, the status "error" colour shifts slightly orange and is always paired with an icon and text, never colour alone. Users can pick any accent or take it from the wallpaper.
- Motion: springs everywhere. `--deft-step-dot` is used only by `<deft-number>`, `.deft-progress[data-segments]` and dot-matrix reveals.
- Do not build a style switch now. The style tokens live in one file only, which keeps the door open for a second style later without designing for it today.

### 8.2 Geist for text

- Bundle **Geist Variable** (text and display), **Geist Mono** (labels, code) and **Doto** (dot numerals), all OFL, subset to Latin + Latin-Ext.
- `--deft-font-text` and `--deft-font-display` are Geist. Display sizes use tighter tracking.
- Re-check legibility on the Galaxy A16 at `footnote` and `caption` sizes. If it is blurry there, raise `caption` weight to 500 rather than switching fonts.

### 8.3 Terminal themes are dropped

- Delete `config/themes/{cupcake,dracula,nord,synthwave,tokyo-night,light}` and the legacy `colors.toml` files.
- **Migration:** on first start after the update, a user whose saved theme is one of these gets the hybrid style with the **nearest accent** (Dracula → violet, Nord → frost blue, Synthwave → pink, Tokyo Night → blue, Cupcake → teal, Light → default red) and appearance from the old theme's mode.
- The `system` (Material You) theme becomes **accent = wallpaper**, and the picker shows it.
- User-authored `palette.json` overrides in filesDir: map `primary` to the accent and ignore the other hues, logging one warning.

### 8.4 Blur on for mid and high tier

- The launcher classifies the device tier once (RAM, GPU class, and a short measured frame-time probe at first start) and publishes it.
- `--deft-material-blur` is `blur(24px) saturate(1.7)` on mid and high tier, and `none` with a more opaque fill on low tier.
- Settings has a "Reduce transparency" switch that forces the opaque material, alongside the OS `prefers-reduced-transparency`.
- **Guardrail:** phase 2 must measure a scrolling list under a glass tab bar on a mid-tier device. If frames drop, cap blur radius on mid tier (for example 12px) rather than turning it off.
