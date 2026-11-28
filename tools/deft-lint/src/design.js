/**
 * Design-system rules : accent placement, legacy tokens,
 * raw motion, unlayered CSS, glyph icons, error colour without icon and text, one primary per screen, dialog or [hidden] view.
 * Hand-written scanners, same style as index.js and extra.js. Called from afterCss and afterHtml.
 */

// Accent tokens may only colour these (8.1 component colour map): the primary button, the tab bar's
// selected dot, badges and live signals. A primary FAB is a primary action too.
const ACCENT_SELECTORS = /\[data-primary\]|\.deft-btn-primary|\.deft-tabbar|\.deft-badge|\[data-live\]/;

// Shim tokens emitted by theme.css for older apps. New code uses the semantic name.
const LEGACY = {
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
  red: "--deft-error",
};

import { ICONS } from "../../deft-design/icon-list.js";
// The runtime CSS is the source of truth for token names: every `--deft-x:` it declares is a real token. The bundler inlines the text.
import baseCss from "../../../android/app/src/main/assets/deft-runtime/v1/base.css" with { type: "text" };
import tokensCss from "../../../android/app/src/main/assets/deft-runtime/v1/tokens.css" with { type: "text" };
import componentsCss from "../../../android/app/src/main/assets/deft-runtime/v1/components.css" with { type: "text" };

// theme.css is built on the device (Palette.kt), so its names are listed here; a test compares this list with Palette.kt.
export const THEME_TOKENS = [
  "appearance", "accent", "on-accent", "bg", "bg-grouped", "bg-elevated", "label", "label-2", "label-3", "label-4",
  "fill", "fill-2", "fill-3", "fill-4", "separator", "error", "warning", "success", "info", "tier",
  "material-bar", "material-bar-reduced", "material-blur", "border", "density", "radius-box", "radius-field", "radius-selector",
].map((n) => "--deft-" + n);
// Component hook properties an app may set (references/components.md), kept in step with HOOK_PROPS in index.js.
const HOOKS = ["--deft-min", "--deft-gap", "--deft-density", "--deft-side", "--deft-measure", "--deft-cols", "--deft-pad", "--deft-radius-outer", "--deft-value", "--deft-segs", "--deft-level",
  "--deft-sheet-h", "--deft-snap", "--deft-snap-align"];
const DECLARED = /(--deft-[a-z0-9-]+)\s*:/g;
const KNOWN_TOKENS = new Set([...THEME_TOKENS, ...HOOKS]);
for (const css of [baseCss, tokensCss, componentsCss]) for (const m of css.matchAll(DECLARED)) KNOWN_TOKENS.add(m[1]);
const LEGACY_NAME = /^--deft-(color-[a-z0-9-]+|fg|surface|muted|red)$/;

function editDistance(a, b) {
  let prev = Array.from({ length: b.length + 1 }, (_, j) => j);
  for (let i = 1; i <= a.length; i++) {
    const row = [i];
    for (let j = 1; j <= b.length; j++) row[j] = Math.min(prev[j] + 1, row[j - 1] + 1, prev[j - 1] + (a[i - 1] === b[j - 1] ? 0 : 1));
    prev = row;
  }
  return prev[b.length];
}

// Names models reach for from other design systems (Material, Tailwind, Open Props), and what they mean here.
const GUESSES = [
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
  [/^--deft-space-([0-9]+)$/, (m) => ["--deft-space-3xs", "--deft-space-2xs", "--deft-space-xs", "--deft-space-s", "--deft-space-m", "--deft-space-l", "--deft-space-xl", "--deft-space-2xl"][Math.min(Number(m[1]), 8) - 1] || "--deft-space-s"],
];

function nearestToken(name) {
  for (const [re, to] of GUESSES) {
    const m = re.exec(name);
    if (m) return typeof to === "function" ? to(m) : to;
  }
  let best = "";
  let bestScore = Infinity;
  for (const known of KNOWN_TOKENS) {
    if (LEGACY_NAME.test(known)) continue;
    // A shared prefix (--deft-space-2 vs --deft-space-s) beats raw distance.
    const score = editDistance(name, known) - (known.startsWith(name.slice(0, name.length - 1)) ? 2 : 0);
    if (score < bestScore || (score === bestScore && known < best)) { best = known; bestScore = score; }
  }
  return best;
}

/** FL638: var(--deft-x) for a property neither the runtime nor this file defines (it silently falls back). */
export function designTokens(path, source, out) {
  const text = source.replace(/\/\*[\s\S]*?\*\//g, (m) => m.replace(/[^\n]/g, " "));
  const local = new Set();
  for (const m of text.matchAll(DECLARED)) local.add(m[1]);
  const at = locate(source);
  for (const m of text.matchAll(/var\(\s*(--deft-[a-z0-9-]*)(?![a-z0-9-]|\$\{|["'`])/g)) {
    const name = m[1];
    if (KNOWN_TOKENS.has(name) || local.has(name) || LEGACY_NAME.test(name)) continue;
    const pos = at(m.index);
    out.push(issue("FL638", "error", path, pos.line, pos.col,
      `${name} is not a design token, so the value silently falls back; did you mean var(${nearestToken(name)})? (tokens: references/tokens.md)`));
  }
}

// Glyphs that stand in for icons. Lucide icons come from <deft-icon name="...">.
const GLYPH = /^(?:[‹›✕✖☰+×←→↑↓⋮⋯✓✔✗]|&(?:lsaquo|rsaquo|times|larr|rarr|uarr|darr|hellip|check|#x?[0-9a-f]+);)$/i;

function issue(id, severity, path, line, col, message) {
  return { id, severity, path, line, col, message };
}

/** Same-length copy of the CSS with comments blanked, so offsets still map to the source. */
function blankComments(source) {
  return source.replace(/\/\*[\s\S]*?\*\//g, (m) => m.replace(/[^\n]/g, " "));
}

function locate(source) {
  const starts = [0];
  for (let i = 0; i < source.length; i++) if (source[i] === "\n") starts.push(i + 1);
  return (index) => {
    let lo = 0;
    let hi = starts.length - 1;
    while (lo < hi) {
      const mid = (lo + hi + 1) >> 1;
      if (starts[mid] <= index) lo = mid;
      else hi = mid - 1;
    }
    return { line: lo + 1, col: index - starts[lo] + 1 };
  };
}

/** Innermost rule blocks: { selector, body, bodyStart } over comment-free CSS. */
function innermostBlocks(css) {
  const blocks = [];
  const re = /([^{};]*)\{([^{}]*)\}/g;
  let m;
  while ((m = re.exec(css))) {
    blocks.push({ selector: m[1].trim(), body: m[2], bodyStart: m.index + m[1].length + 1 });
  }
  return blocks;
}

/** Rules outside any @layer block (FL629). Walks depth, so nested blocks are not reported twice. */
function unlayeredPreludes(css) {
  const found = [];
  let depth = 0;
  let prelude = "";
  let preludeStart = 0;
  for (let i = 0; i < css.length; i++) {
    const c = css[i];
    if (c === "{") {
      if (depth === 0) {
        const text = prelude.trim();
        const ok = /^@layer\b/i.test(text) || /^@font-face\b/i.test(text) || text === "";
        if (!ok) found.push({ text, index: preludeStart + prelude.search(/\S/) });
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

/** CSS rules: accent placement, legacy tokens, raw motion, oklch literals, unlayered rules. */
export function designCss(path, source, out) {
  designTokens(path, source, out);
  const at = locate(source);
  const css = blankComments(source);

  for (const b of innermostBlocks(css)) {
    if (/--deft-(on-)?accent\b/.test(b.body) && !ACCENT_SELECTORS.test(b.selector)) {
      const hit = /--deft-(on-)?accent\b/.exec(b.body);
      const pos = at(b.bodyStart + hit.index);
      out.push(issue("FL616", "warn", path, pos.line, pos.col,
        `accent token outside the primary button, tab-bar dot, badge or [data-live]; use --deft-label (${b.selector || "rule"})`));
    }
  }

  const legacy = /var\(\s*--deft-(color-[a-z0-9-]+|fg|surface|muted|red)\b/g;
  let m;
  while ((m = legacy.exec(css))) {
    const name = m[1];
    const pos = at(m.index);
    out.push(issue("FL619", "warn", path, pos.line, pos.col,
      `deprecated token --deft-${name}; use ${LEGACY[name] || "a semantic token"}`));
  }

  const keyframes = /@keyframes\b/gi;
  while ((m = keyframes.exec(css))) {
    const pos = at(m.index);
    out.push(issue("FL627", "error", path, pos.line, pos.col, "@keyframes is not allowed; use data-deft-enter, a spring token or a view transition"));
  }

  const easing = /(?<![\w-])(cubic-bezier|steps)\(/gi;
  while ((m = easing.exec(css))) {
    const pos = at(m.index);
    out.push(issue("FL628", "error", path, pos.line, pos.col, `raw ${m[1]}() easing; use var(--deft-ease*) or a spring token`));
  }

  const decl = /(?:^|[{;\s])((?:transition|animation)(?:-duration)?)\s*:\s*([^;{}]*)/gi;
  while ((m = decl.exec(css))) {
    const value = m[2];
    if (/var\(/.test(value)) continue;
    const raw = /(?<![\w.-])(?!0+(?:\.0+)?m?s\b)\d*\.?\d+m?s\b/.exec(value);
    if (!raw) continue;
    const pos = at(m.index + m[0].length - value.length + raw.index);
    out.push(issue("FL631", "warn", path, pos.line, pos.col, `raw duration ${raw[0]}; use a --deft-duration-* or --deft-spring-*-duration token`));
  }

  const oklch = /(?<![\w-])oklch\(\s*[\d.]/gi;
  while ((m = oklch.exec(css))) {
    const pos = at(m.index);
    out.push(issue("FL610", "error", path, pos.line, pos.col, "oklch literal; use a var(--deft-*) token"));
  }

  for (const u of unlayeredPreludes(css)) {
    if (/^@(import|charset|namespace)\b/i.test(u.text)) continue;
    const pos = at(u.index);
    out.push(issue("FL629", "warn", path, pos.line, pos.col, "unlayered CSS; put rules in @layer deft.components or @layer deft.utilities"));
  }
}

/** Names closest to [name]: a shared prefix or substring first, then small edit distance. Up to three. */
function nearestIcons(name) {
  const dist = (a, b) => {
    let prev = Array.from({ length: b.length + 1 }, (_, j) => j);
    for (let i = 1; i <= a.length; i++) {
      const row = [i];
      for (let j = 1; j <= b.length; j++) row[j] = Math.min(prev[j] + 1, row[j - 1] + 1, prev[j - 1] + (a[i - 1] === b[j - 1] ? 0 : 1));
      prev = row;
    }
    return prev[b.length];
  };
  return ICONS.map((n) => ({ n, d: n.includes(name) || name.includes(n) ? 0 : dist(name, n) }))
    .filter((x) => x.d <= Math.max(2, name.length / 3))
    .sort((a, b) => a.d - b.d || a.n.localeCompare(b.n))
    .slice(0, 3)
    .map((x) => x.n);
}

/** FL632: <deft-icon name="x"> or a sprite href (icons.svg#x) naming an icon that is not in the sprite. Used for HTML and for markup inside JS strings. */
export function designIcons(path, source, out) {
  const at = locate(source);
  const known = new Set(ICONS);
  const report = (name, index) => {
    if (!name || known.has(name) || /[${}]/.test(name)) return;
    const near = nearestIcons(name);
    const pos = at(index);
    out.push(issue("FL632", "warn", path, pos.line, pos.col, `unknown icon "${name}"; it draws nothing${near.length ? `. Nearest: ${near.join(", ")}` : ""}. See references/icons.md`));
  };
  let m;
  const tag = /<deft-icon\b[^>]*?\bname\s*=\s*(["'])([^"']*)\1/gi;
  while ((m = tag.exec(source))) report(m[2], m.index);
  const use = /icons\.svg#([A-Za-z0-9_-]+)/g;
  while ((m = use.exec(source))) report(m[1], m.index);
}

/** HTML: glyph icons, error colour without icon and text, more than one primary per screen, dialog or [hidden] view. */
export function designHtml(path, source, out) {
  designTokens(path, source, out);
  designIcons(path, source, out);
  const at = locate(source);
  const html = source.replace(/<!--[\s\S]*?-->/g, (m) => m.replace(/[^\n]/g, " "));

  const button = /<button\b[^>]*>([^<]*)<\/button\s*>/gi;
  let m;
  while ((m = button.exec(html))) {
    if (!GLYPH.test(m[1].trim())) continue;
    const pos = at(m.index);
    out.push(issue("FL618", "warn", path, pos.line, pos.col, "glyph used as an icon; use <deft-icon name=\"...\"> (Lucide)"));
  }

  // Only elements that show an error (alerts and toasts), not buttons that trigger one.
  const tone = /<([a-z][a-z0-9-]*)\b([^>]*\bdata-tone\s*=\s*["']error["'][^>]*)>([\s\S]*?)<\/\1\s*>/gi;
  while ((m = tone.exec(html))) {
    if (!/\bclass\s*=\s*["'][^"']*\bdeft-(alert|toast)\b/i.test(m[2])) continue;
    const inner = m[3];
    const hasIcon = /<(svg|deft-icon)\b/i.test(inner);
    // Text may be filled by JS later: data-deft-text or aria-live on the alert (or its text node) marks that.
    const hasText = inner.replace(/<[^>]*>/g, "").trim().length > 0 || /\b(data-deft-text|aria-live)\b/i.test(m[2] + inner);
    if (hasIcon && hasText) continue;
    const pos = at(m.index);
    out.push(issue("FL617", "warn", path, pos.line, pos.col, "error colour needs an icon and text, not colour alone"));
  }

  // A dialog or sheet is its own screen, and so is a container with the hidden attribute (a tab or view shown
  // later): each holds one primary, and the page behind them one. Lint cannot see routes, so two primaries in
  // different routes of one file still warn; mark the second view's container hidden or split the file.
  const scopes = [];
  let rest = html;
  const carve = (re, endOf) => {
    let mm;
    while ((mm = re.exec(rest))) {
      const end = endOf(rest, mm);
      if (end < 0) continue;
      scopes.push({ text: rest.slice(mm.index, end), offset: mm.index });
      rest = rest.slice(0, mm.index) + rest.slice(mm.index, end).replace(/[^\n]/g, " ") + rest.slice(end);
      re.lastIndex = end;
    }
  };
  carve(/<dialog\b/gi, (t, mm) => { const e = t.slice(mm.index).search(/<\/dialog\s*>/i); return e < 0 ? -1 : mm.index + e + 9; });
  const attr = String.raw`(?:\s+[^\s=>\/]+(?:\s*=\s*(?:"[^"]*"|'[^']*'|[^\s>"']+))?)`;
  carve(new RegExp(String.raw`<([a-z][a-z0-9-]*)${attr}*?\s+hidden(?=[\s>\/=])${attr}*\s*\/?>`, "gi"), (t, mm) => {
    const tag = mm[1].toLowerCase();
    const tags = new RegExp(`<(/?)${tag}\\b[^>]*>`, "gi");
    tags.lastIndex = mm.index + mm[0].length;
    let depth = 1, x;
    while ((x = tags.exec(t))) {
      if (x[0].endsWith("/>")) continue;
      depth += x[1] ? -1 : 1;
      if (!depth) return x.index + x[0].length;
    }
    return -1;
  });
  scopes.push({ text: rest, offset: 0 });
  for (const scope of scopes) {
    const primary = /<(button|a|input)\b[^>]*\sdata-primary\b[^>]*>/gi;
    let count = 0;
    while ((m = primary.exec(scope.text))) {
      count++;
      if (count === 2) {
        const pos = at(scope.offset + m.index);
        out.push(issue("FL630", "warn", path, pos.line, pos.col, "more than one primary button on this screen (a dialog, sheet or [hidden] view counts as its own screen; lint cannot see routes); keep one accent action"));
      }
    }
  }
}

/**
 * FL633 (warn): a button or [data-action] element in the HTML whose id, own class or data-action value never
 * appears in any of the app's JS: nothing is wired to it. Cheap heuristic. Skipped when no JS is in the input
 * (single-file lint), when the button has no id/class/data-action, for submit buttons in a form, for
 * popover/command buttons, and when the JS delegates on a bare button selector.
 */
export function designHandlers(files, out) {
  const names = Object.keys(files).filter((n) => typeof files[n] === "string");
  const js = names.filter((n) => /\.m?js$/i.test(n)).map((n) => files[n]).join("\n");
  if (!js.trim()) return;
  const delegated = /closest\(\s*["'`](?:button|\[data-action\]|\.deft-btn)/.test(js);
  const referenced = (token) => new RegExp(`(?<![\\w-])${token.replace(/[.*+?^${}()|[\]\\]/g, "\\$&")}(?![\\w-])`).test(js);
  for (const name of names.filter((n) => /\.html?$/i.test(n))) {
    const source = files[name].replace(/<!--[\s\S]*?-->/g, (m) => m.replace(/[^\n]/g, " "));
    const at = locate(source);
    const forms = [];
    const formRe = /<form\b[\s\S]*?<\/form\s*>/gi;
    let f;
    while ((f = formRe.exec(source))) forms.push([f.index, f.index + f[0].length]);
    const tag = /<(button|[a-z][a-z0-9-]*)\b([^>]*)>/gi;
    let m;
    while ((m = tag.exec(source))) {
      const attrs = m[2];
      const isButton = m[1].toLowerCase() === "button";
      const action = /\bdata-action\s*=\s*["']([^"']+)["']/i.exec(attrs);
      if (!isButton && !action) continue;
      if (/\b(popovertarget|command|commandfor|formmethod)\b/i.test(attrs)) continue;
      const type = /\btype\s*=\s*["']?(\w+)/i.exec(attrs);
      const inForm = forms.some(([s, e]) => m.index > s && m.index < e);
      if (isButton && inForm && (!type || type[1].toLowerCase() === "submit")) continue;
      if (isButton && type && type[1].toLowerCase() === "submit") continue;
      const id = /\bid\s*=\s*["']([^"']+)["']/i.exec(attrs);
      const cls = /\bclass\s*=\s*["']([^"']+)["']/i.exec(attrs);
      const tokens = [];
      if (action) tokens.push(action[1]);
      if (id) tokens.push(id[1]);
      if (cls) tokens.push(...cls[1].split(/\s+/).filter((c) => c && !c.startsWith("deft-") && !/^(is-|has-)/.test(c)));
      if (!tokens.length || delegated) continue;
      if (tokens.some(referenced)) continue;
      const pos = at(m.index);
      out.push(issue("FL633", "warn", name, pos.line, pos.col, `interactive element (${tokens.join(", ")}) is never referenced in the app's JS; it does nothing. Wire a handler and test the action`));
    }
  }
}
