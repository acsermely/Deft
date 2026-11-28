import { designCss, designHtml, designIcons, designTokens } from "./design.js";
/**
 * Line and token checks that do not need a parser, scope, or dataflow.
 */

const FL_MODULES = new Set([
  "core.js", "dom.js", "events.js", "store.js", "app.js", "route.js", "bridge.js", "index.js",
  "motion.js", "sheet.js", "elements.js",
]);

const ALLOWED_EXT = new Set([
  "html", "htm", "js", "css", "json", "svg", "png", "webp", "jpg", "jpeg", "gif", "avif", "woff2", "wasm", "txt", "md",
]);

const TEXT_LIMIT = 512 * 1024;
const PACKAGE_LIMIT = 10 * 1024 * 1024;

function issue(id, severity, path, line, col, message) {
  return { id, severity, path, line, col, message };
}

function lineCount(source) {
  if (!source) return 0;
  const n = source.split("\n").length;
  return source.endsWith("\n") ? n - 1 : n;
}

function firstLine(source) {
  const lines = source.split("\n");
  for (let i = 0; i < lines.length; i++) {
    if (lines[i].trim()) return { line: i + 1, text: lines[i].trim() };
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
      if (c === quote) quote = "";
      i++;
      continue;
    }
    if (c === "'" || c === "\"" || c === "`") {
      quote = c;
      out += c;
      i++;
      continue;
    }
    if (c === "/" && source[i + 1] === "/") {
      while (i < source.length && source[i] !== "\n") {
        out += " ";
        i++;
      }
      continue;
    }
    if (c === "/" && source[i + 1] === "*") {
      out += "  ";
      i += 2;
      while (i < source.length && !(source[i] === "*" && source[i + 1] === "/")) {
        out += source[i] === "\n" ? "\n" : " ";
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

/* CDN URLs and `as` aliases from manifest.dependencies; imports of these are allowed. */
let declaredUrls = new Set();
let declaredAliases = new Set();

export function setDependencies(deps) {
  declaredUrls = new Set(deps.urls);
  declaredAliases = new Set(deps.aliases);
}

export function afterJs(path, source, tokens, at, out, mode) {
  designIcons(path, source, out);
  designTokens(path, source, out);
  const liveMarked = /data-live|dataset\.live/.test(source);
  // confirm and prompt imported from the runtime are the in-page dialogs, not the blocking window functions.
  const dialogs = new Set();
  for (const m of source.matchAll(/import\s*\{([^}]*)\}\s*from\s*['"]\/_deft\/v1\/(?:app|index)\.js['"]/g)) {
    for (const part of m[1].split(",")) {
      const [orig, local] = part.trim().split(/\s+as\s+/);
      if (orig === "confirm" || orig === "prompt") dialogs.add(local || orig);
    }
  }
  for (let i = 0; i < tokens.length; i++) {
    const t = tokens[i];
    // FL616 in JS: the accent is read for an SVG or canvas drawing only when it is the [data-live] signal.
    if (t.type === "string" && /--deft-(on-)?accent\b/.test(t.value) && !liveMarked) {
      const pos = at(t.index);
      out.push(issue("FL616", "warn", path, pos.line, pos.col,
        "accent read in JS without data-live; draw in --deft-label unless this is the screen's one [data-live] signal"));
    }
    const prev = tokens[i - 1];
    const dotted = prev && prev.type === "punct" && prev.value === ".";
    if (t.type === "ident") {
      const next = tokens[i + 1];
      const call = next && next.type === "punct" && next.value === "(";
      if (t.value === "import" || t.value === "export") classifyModule(path, tokens, i, at, out);
      if (!dotted && (t.value === "alert" || t.value === "confirm" || t.value === "prompt") && call && !dialogs.has(t.value)) {
        const pos = at(t.index);
        out.push(issue("FL105", "error", path, pos.line, pos.col, `${t.value}() is not allowed; use deft.confirm, deft.prompt or dialog() from /_deft/v1/app.js`));
      }
      if ((t.value === "alert" || t.value === "confirm" || t.value === "prompt") && call && dotted) {
        const obj = tokens[i - 2];
        if (obj && obj.type === "ident" && obj.value === "window") {
          const pos = at(t.index);
          out.push(issue("FL105", "error", path, pos.line, pos.col, `${t.value}() is not allowed; use dialog()`));
        }
      }
      if (t.value === "outerHTML" || t.value === "createContextualFragment") {
        const pos = at(t.index);
        out.push(issue("FL100", "error", path, pos.line, pos.col, `${t.value} is not allowed`));
      }
      if (t.value === "DOMParser") {
        const dot = tokens[i + 1];
        const method = tokens[i + 2];
        if (dot && dot.value === "." && method && method.value === "parseFromString") {
          const pos = at(t.index);
          out.push(issue("FL100", "error", path, pos.line, pos.col, "DOMParser.parseFromString is not allowed"));
        }
      }
      if ((t.value === "setTimeout" || t.value === "setInterval") && call) {
        const arg = tokens[i + 2];
        if (arg && arg.type === "string") {
          const pos = at(t.index);
          out.push(issue("FL101", "error", path, pos.line, pos.col, `string ${t.value}() is not allowed`));
        }
      }
      if (t.value === "Function" && call && !dotted) {
        const before = tokens[i - 1];
        const keyword = before && before.type === "ident" && (before.value === "function" || before.value === "new");
        if (!keyword) {
          const pos = at(t.index);
          out.push(issue("FL101", "error", path, pos.line, pos.col, "Function() is not allowed"));
        }
      }
      if (t.value === "writeln" && dotted) {
        const obj = tokens[i - 2];
        if (obj && obj.value === "document") {
          const pos = at(obj.index);
          out.push(issue("FL100", "error", path, pos.line, pos.col, "document.writeln is not allowed"));
        }
      }
      if ((t.value === "serviceWorker" || t.value === "SharedWorker") && !dotted) {
        const pos = at(t.index);
        out.push(issue("FL109", "error", path, pos.line, pos.col, `${t.value} is not supported`));
      }
      if (t.value === "serviceWorker" && dotted) {
        const pos = at(t.index);
        out.push(issue("FL109", "error", path, pos.line, pos.col, "service worker registration is not supported"));
      }
      if (t.value === "open" && dotted && call) {
        const obj = tokens[i - 2];
        if (obj && obj.value === "window") {
          const pos = at(t.index);
          out.push(issue("FL110", "error", path, pos.line, pos.col, "window.open is not allowed"));
        }
      }
      if (t.value.startsWith("__deft")) {
        const pos = at(t.index);
        out.push(issue("FL113", "error", path, pos.line, pos.col, "access to host internal __deft*"));
      }
      if (t.value === "deftBridge") {
        const pos = at(t.index);
        out.push(issue("FL200", "warn", path, pos.line, pos.col, "direct deftBridge; use call() from bridge.js"));
      }
      if (t.value === "forEach" && call) {
        const arg = tokens[i + 2];
        if (arg && arg.type === "ident" && arg.value === "async") {
          const pos = at(t.index);
          out.push(issue("FL400", "warn", path, pos.line, pos.col, "async callback in forEach; use for...of + await"));
        }
      }
      // A try-guarded getItem is the legacy-key read (recipes: localStorage to store()); writes still warn.
      const legacyRead = t.value === "localStorage" && tokens[i + 1] && tokens[i + 1].value === "." &&
        tokens[i + 2] && tokens[i + 2].value === "getItem" && nearTry(source, t.index);
      if (t.value === "localStorage" && !legacyRead) {
        const pos = at(t.index);
        out.push(issue("FL403", "warn", path, pos.line, pos.col, "raw localStorage; use store()"));
      }
      if (t.value === "parse" && dotted) {
        const obj = tokens[i - 2];
        if (obj && obj.value === "JSON" && !nearTry(source, t.index)) {
          const pos = at(t.index);
          out.push(issue("FL403", "warn", path, pos.line, pos.col, "JSON.parse outside try; use store()"));
        }
      }
      if (t.value === "var" && !dotted) {
        const pos = at(t.index);
        out.push(issue("FL500", "warn", path, pos.line, pos.col, "var; use let or const"));
      }
      if (t.value === "log" && dotted) {
        const obj = tokens[i - 2];
        if (obj && obj.value === "console" && call) {
          const pos = at(t.index);
          out.push(issue("FL504", "info", path, pos.line, pos.col, "leftover console.log"));
        }
      }
      if ((t.value === "fetch" || t.value === "EventSource" || t.value === "WebSocket") && call) {
        const arg = tokens[i + 2];
        const literal = arg && arg.type === "string" && !arg.tmpl;
        if (!literal) {
          const pos = at(t.index);
          out.push(issue("FL206", "info", path, pos.line, pos.col, "network URL is not statically known"));
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
        out.push(issue("FL501", "warn", path, pos.line, pos.col, "== ; use ==="));
      }
    }
    if (t.type === "punct" && t.value === "!") {
      const n1 = tokens[i + 1];
      const n2 = tokens[i + 2];
      if (n1 && n1.type === "punct" && n1.value === "=" && !(n2 && n2.type === "punct" && n2.value === "=")) {
        const pos = at(t.index);
        out.push(issue("FL501", "warn", path, pos.line, pos.col, "!= ; use !=="));
      }
    }
    if (t.type === "string" && /javascript\s*:/i.test(t.value)) {
      const pos = at(t.index);
      out.push(issue("FL107", "error", path, pos.line, pos.col, "javascript: URL"));
    }
  }
  const lines = lineCount(source);
  const base = path.split("/").pop();
  if ((base === "main.js" && lines > 80) || lines > 200) {
    out.push(issue("FL502", "warn", path, 1, 1, base === "main.js" && lines > 80 ? "main.js is longer than 80 lines" : "file is longer than 200 lines"));
  }
  const header = firstLine(source);
  if (header && !header.text.startsWith("//") && !header.text.startsWith("/*")) {
    out.push(issue("FL503", "warn", path, header.line, 1, "missing header comment"));
  }
  if (mode === "publish") flagLongLines(path, source, out);
}

function classifyModule(path, tokens, i, at, out) {
  const dynamic = tokens[i + 1] && tokens[i + 1].type === "punct" && tokens[i + 1].value === "(";
  let spec = null;
  if (dynamic) {
    const arg = tokens[i + 2];
    if (!arg || arg.type !== "string" || arg.tmpl) {
      const pos = at(tokens[i].index);
      out.push(issue("FL007", "error", path, pos.line, pos.col, "import() specifier must be a string literal"));
      return;
    }
    spec = arg;
  } else {
    spec = fromString(tokens, i);
  }
  if (!spec) return;
  const value = spec.value;
  const pos = at(spec.index);
  if (value.startsWith("./") || value.startsWith("../")) {
    const file = value.split("?")[0].split("#")[0];
    if (!file.endsWith(".js")) {
      out.push(issue("FL002", "error", path, pos.line, pos.col, "relative import without .js"));
    }
    return;
  }
  if (value.startsWith("/_deft/v1/")) {
    const mod = value.slice("/_deft/v1/".length).split("?")[0].split("#")[0];
    if (!FL_MODULES.has(mod)) {
      out.push(issue("FL005", "error", path, pos.line, pos.col, `unknown /_deft/v1/ module "${mod}"`));
    }
    return;
  }
  if (declaredUrls.has(value) || declaredAliases.has(value)) return;
  if (tokens[i].value === "import" || value) {
    const remote = /^https?:\/\//i.test(value);
    const message = remote
      ? "remote import not in manifest dependencies; add the exact URL with its hash (or null to pin on first download)"
      : "bare or remote import specifier";
    out.push(issue("FL001", "error", path, pos.line, pos.col, message));
  }
}

function fromString(tokens, i) {
  const n = tokens[i + 1];
  if (n && n.type === "string" && !n.tmpl) return n;
  const stop = Math.min(tokens.length, i + 40);
  for (let j = i + 1; j < stop; j++) {
    const t = tokens[j];
    if (t.type === "punct" && t.value === ";") return null;
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
  if (t.value !== "invoke" && t.value !== "call" && t.value !== "subscribe") return;
  const next = tokens[i + 1];
  if (!next || next.type !== "punct" || next.value !== "(") return;
  const prev = tokens[i - 1];
  if (prev && prev.type === "ident" && prev.value === "function") return;
  if (t.value === "call" && prev && prev.type === "punct" && prev.value === ".") {
    const obj = tokens[i - 2];
    if (!obj || obj.value !== "deftBridge") return;
  }
  const arg = tokens[i + 2];
  if (!arg || arg.type !== "string" || arg.tmpl) {
    const pos = at(t.index);
    out.push(issue("FL207", "error", path, pos.line, pos.col, "call() method name must be a literal"));
  }
}

function flagLongLines(path, source, out) {
  const lines = source.split("\n");
  for (let i = 0; i < lines.length; i++) {
    if (lines[i].length > 400) {
      out.push(issue("FL112", "error", path, i + 1, 1, "line is longer than 400 characters"));
      return;
    }
  }
}

export function afterHtml(path, source, out, mode) {
  const at = indexAt(source);
  const comments = source.replace(/<!--[\s\S]*?-->/g, " ");
  if (/<style\b/i.test(comments)) {
    const pos = at(comments.search(/<style\b/i));
    out.push(issue("FL103", "error", path, pos.line, pos.col, "inline style; use ./app.css"));
  }
  const scriptRe = /<script\b[^>]*>/gi;
  let script;
  while ((script = scriptRe.exec(comments))) {
    if (!/\bsrc\s*=/i.test(script[0])) {
      const pos = at(script.index);
      out.push(issue("FL103", "error", path, pos.line, pos.col, "inline script; use ./main.js"));
    }
  }
  const onRe = /\s(on[a-z]+)\s*=/gi;
  let onAttr;
  const tagRe = /<[^>]+>/g;
  let tag;
  while ((tag = tagRe.exec(comments))) {
    if (tag[0].startsWith("</") || tag[0].startsWith("<!")) continue;
    onRe.lastIndex = 0;
    let hit;
    while ((hit = onRe.exec(tag[0]))) {
      const pos = at(tag.index + hit.index + 1);
      out.push(issue("FL102", "error", path, pos.line, pos.col, `${hit[1].toLowerCase()} attribute is not allowed`));
      const name = tagName(tag[0]);
      if (name === "div" || name === "span") {
        out.push(issue("FL623", "warn", path, pos.line, pos.col, `click handler on ${name}; use button`));
      }
    }
  }
  for (const name of ["iframe", "object", "embed", "frame", "base"]) {
    const re = new RegExp(`<${name}\\b`, "i");
    if (re.test(comments)) {
      const pos = at(comments.search(re));
      out.push(issue("FL108", "error", path, pos.line, pos.col, `<${name}> is not supported`));
    }
  }
  if (/<meta\b[^>]*http-equiv\s*=/i.test(comments)) {
    const pos = at(comments.search(/<meta\b[^>]*http-equiv\s*=/i));
    out.push(issue("FL108", "error", path, pos.line, pos.col, "meta http-equiv is not supported"));
  }
  if (/javascript\s*:/i.test(comments)) {
    const pos = at(comments.search(/javascript\s*:/i));
    out.push(issue("FL107", "error", path, pos.line, pos.col, "javascript: URL"));
  }
  const ids = new Map();
  const idRe = /\sid\s*=\s*["']([^"']+)["']/gi;
  let idMatch;
  while ((idMatch = idRe.exec(comments))) {
    const id = idMatch[1];
    if (ids.has(id)) {
      const pos = at(idMatch.index);
      out.push(issue("FL301", "error", path, pos.line, pos.col, `duplicate id "${id}"`));
    } else ids.set(id, idMatch.index);
  }
  const imgRe = /<img\b[^>]*>/gi;
  let img;
  while ((img = imgRe.exec(comments))) {
    if (!/\balt\s*=/i.test(img[0])) {
      const pos = at(img.index);
      out.push(issue("FL620", "error", path, pos.line, pos.col, "img without alt"));
    }
  }
  const buttonRe = /<button\b([^>]*)>([\s\S]*?)<\/button>/gi;
  let button;
  while ((button = buttonRe.exec(comments))) {
    const attrs = button[1];
    const text = button[2].replace(/<[^>]*>/g, "").trim();
    if (/\baria-label\s*=/i.test(attrs) || /\baria-labelledby\s*=/i.test(attrs) || /\btitle\s*=/i.test(attrs) || text) continue;
    const pos = at(button.index);
    out.push(issue("FL621", "error", path, pos.line, pos.col, "button without an accessible name"));
  }
  flagUnlabeledControls(path, comments, at, out);
  const tabRe = /\stabindex\s*=\s*["']?(\d+)/gi;
  let tab;
  while ((tab = tabRe.exec(comments))) {
    if (Number(tab[1]) > 0) {
      const pos = at(tab.index);
      out.push(issue("FL626", "warn", path, pos.line, pos.col, "positive tabindex"));
    }
  }
  const svgRe = /<svg\b[\s\S]*?<\/svg>/gi;
  let svg;
  while ((svg = svgRe.exec(comments))) scanSvg(path, svg[0], out);
  if (isDocument(source)) scanDocument(path, source, out);
  if (mode === "publish") flagLongLines(path, source, out);
  const header = firstLine(source);
  if (isDocument(source) && header && !source.slice(0, 400).includes("<!--")) {
    out.push(issue("FL503", "warn", path, 1, 1, "missing header comment"));
  }
}

function tagName(tag) {
  const m = tag.match(/^<\/?\s*([a-zA-Z0-9]+)/);
  return m ? m[1].toLowerCase() : "";
}

function flagUnlabeledControls(path, source, at, out) {
  const fors = new Set();
  for (const m of source.matchAll(/<label\b[^>]*\bfor\s*=\s*["']([^"']+)["']/gi)) fors.add(m[1]);
  const bare = source.replace(/<label\b[\s\S]*?<\/label>/gi, "");
  const re = /<(input|select|textarea)\b[^>]*>/gi;
  let m;
  while ((m = re.exec(bare))) {
    const tag = m[0];
    const type = (/ \btype\s*=\s*["']([^"']+)["']/i.exec(` ${tag}`) || [])[1] || "";
    if (/^(hidden|submit|button|reset)$/i.test(type)) continue;
    if (/\baria-label\s*=/i.test(tag) || /\baria-labelledby\s*=/i.test(tag)) continue;
    const id = (/\bid\s*=\s*["']([^"']+)["']/i.exec(tag) || [])[1];
    if (id && fors.has(id)) continue;
    const pos = at(m.index);
    out.push(issue("FL622", "error", path, pos.line, pos.col, `${m[1].toLowerCase()} without a label`));
  }
}

function scanDocument(path, source, out) {
  const at = indexAt(source);
  let viewport = null;
  const metaRe = /<meta\b[^>]*>/gi;
  let meta;
  while ((meta = metaRe.exec(source))) {
    if (/name\s*=\s*["']viewport["']/i.test(meta[0]) || /["']viewport["']/.test(meta[0]) && /name\s*=/i.test(meta[0])) {
      viewport = meta;
    }
  }
  if (!viewport || !/viewport-fit\s*=\s*cover/i.test(viewport[0])) {
    const pos = at(viewport ? viewport.index : 0);
    out.push(issue("FL600", "error", path, pos.line, pos.col, "viewport meta missing viewport-fit=cover"));
  } else if (/user-scalable\s*=\s*["']?no/i.test(viewport[0]) || /maximum-scale\s*=/i.test(viewport[0])) {
    const pos = at(viewport.index);
    out.push(issue("FL601", "error", path, pos.line, pos.col, "viewport disables zoom"));
  }
  const moduleScript = /<script\b[^>]*type\s*=\s*["']module["'][^>]*src\s*=\s*["'](?:\.\/)?main\.js["'][^>]*>/i.test(source)
    || /<script\b[^>]*src\s*=\s*["'](?:\.\/)?main\.js["'][^>]*type\s*=\s*["']module["'][^>]*>/i.test(source);
  if (!moduleScript) {
    out.push(issue("FL602", "error", path, 1, 1, 'missing <script type="module" src="./main.js">'));
  }
  const sheets = [];
  const linkRe = /<link\b[^>]*>/gi;
  let link;
  while ((link = linkRe.exec(source))) {
    if (!/rel\s*=\s*["']stylesheet["']/i.test(link[0])) continue;
    const href = (/\bhref\s*=\s*["']([^"']+)["']/i.exec(link[0]) || [])[1] || "";
    sheets.push({ href, index: link.index });
  }
  // Runtime sheets load in this order (lint FL603): base, tokens, components, theme, then app CSS.
  const RUNTIME_ORDER = ["base", "tokens", "components", "theme"];
  const runtimeSheet = /(?:base|tokens|components|theme)\.css(?:$|\?)/;
  const runtimeAt = RUNTIME_ORDER.map((name) => sheets.findIndex((s) => new RegExp(`${name}\\.css(?:$|\\?)`).test(s.href)));
  const otherAt = sheets.findIndex((s) => !runtimeSheet.test(s.href));
  const lastRuntime = Math.max(...sheets.map((s, i) => (runtimeSheet.test(s.href) ? i : -1)));
  const missing = runtimeAt.some((i) => i < 0);
  if (missing || (otherAt >= 0 && otherAt < lastRuntime)) {
    const pos = at(sheets[0] ? sheets[0].index : 0);
    out.push(issue("FL603", "error", path, pos.line, pos.col, "base.css, tokens.css, components.css and theme.css must come before app CSS"));
  } else if (runtimeAt.some((i, k) => k > 0 && i <= runtimeAt[k - 1])) {
    const pos = at(sheets[runtimeAt[0]].index);
    out.push(issue("FL603", "error", path, pos.line, pos.col, "runtime CSS must link base.css, tokens.css, components.css, theme.css in that order"));
  }
  designHtml(path, source, out);
  if (!/<html\b[^>]*\blang\s*=/i.test(source)) {
    out.push(issue("FL624", "warn", path, 1, 1, "html element missing lang"));
  }
  let lastHeading = 0;
  const headingRe = /<h([1-6])\b/gi;
  let heading;
  while ((heading = headingRe.exec(source))) {
    const level = Number(heading[1]);
    if (lastHeading && level > lastHeading + 1) {
      const pos = at(heading.index);
      out.push(issue("FL625", "warn", path, pos.line, pos.col, `skipped heading level h${lastHeading} to h${level}`));
    }
    lastHeading = level;
  }
}

export function afterCss(path, source, out, mode) {
  const at = indexAt(source);
  designCss(path, source, out);
  const stripped = source.replace(/\/\*[\s\S]*?\*\//g, " ");
  const importAt = stripped.search(/(^|[^\w-])@import\b/i);
  if (importAt >= 0) {
    const pos = at(importAt);
    out.push(issue("FL604", "error", path, pos.line, pos.col, "@import is not allowed"));
  }
  const remote = /url\(\s*["']?(https?:|\/\/)/i.exec(stripped);
  if (remote) {
    const pos = at(remote.index);
    out.push(issue("FL604", "error", path, pos.line, pos.col, "remote url() is not allowed"));
  }
  const important = /!important/i.exec(stripped);
  if (important) {
    const pos = at(important.index);
    out.push(issue("FL606", "warn", path, pos.line, pos.col, "!important"));
  }
  const vh = /(^|[^d])100vh\b/.exec(stripped);
  if (vh) {
    const pos = at(vh.index + vh[1].length);
    out.push(issue("FL607", "warn", path, pos.line, pos.col, "100vh; use 100dvh"));
  }
  const vw = /(^|[^d])100vw\b/.exec(stripped);
  if (vw) {
    const pos = at(vw.index + vw[1].length);
    out.push(issue("FL608", "warn", path, pos.line, pos.col, "100vw; use 100%"));
  }
  flagWide(path, stripped, at, out, /(?<![\w-])width\s*:\s*[^;{}]*?(\d+(?:\.\d+)?)px/gi);
  flagWide(path, stripped, at, out, /(?<![\w-])min-width\s*:\s*[^;{}]*?(\d+(?:\.\d+)?)px/gi);
  flagFixed(path, stripped, at, out);
  if (mode === "publish") flagLongLines(path, source, out);
  const header = firstLine(source);
  if (header && !header.text.startsWith("/*") && !header.text.startsWith("//")) {
    out.push(issue("FL503", "warn", path, header.line, 1, "missing header comment"));
  }
}

function flagWide(path, source, at, out, re) {
  let m;
  while ((m = re.exec(source))) {
    if (Number(m[1]) > 480) {
      const pos = at(m.index);
      out.push(issue("FL609", "warn", path, pos.line, pos.col, "fixed width over 480px"));
    }
  }
}

function flagFixed(path, source, at, out) {
  let depth = 0;
  let start = -1;
  for (let i = 0; i < source.length; i++) {
    const c = source[i];
    if (c === "{") {
      if (depth === 0) start = i;
      depth++;
    } else if (c === "}" && depth) {
      depth--;
      if (depth === 0 && start >= 0) {
        const block = source.slice(start, i);
        if (/position\s*:\s*(fixed|sticky)\b/i.test(block) && !/safe-area-inset/.test(block)) {
          const pos = at(start + block.search(/position\s*:/i));
          out.push(issue("FL605", "warn", path, pos.line, pos.col, "fixed or sticky position without a safe-area inset"));
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
    [/\b(?:href|xlink:href)\s*=\s*["']https?:/i, "SVG contains an external href"],
  ];
  for (const [re, message] of checks) {
    const m = re.exec(source);
    if (!m) continue;
    const pos = at(m.index);
    out.push(issue("FL114", "error", path, pos.line, pos.col, message));
  }
}

function indexAt(source) {
  const starts = [0];
  for (let i = 0; i < source.length; i++) if (source[i] === "\n") starts.push(i + 1);
  return function at(index) {
    const idx = index < 0 ? 0 : index;
    let lo = 0;
    let hi = starts.length - 1;
    while (lo < hi) {
      const mid = (lo + hi + 1) >> 1;
      if (starts[mid] <= idx) lo = mid;
      else hi = mid - 1;
    }
    return { line: lo + 1, col: idx - starts[lo] + 1 };
  };
}

function quota(limits, key, fallback) {
  const n = limits && Number(limits[key]);
  return Number.isFinite(n) && n > 0 ? n : fallback;
}

export function scanProject(files, profile, mode, catalog, out, limits) {
  const textLimit = quota(limits, "textBytes", TEXT_LIMIT);
  const fileLimit = quota(limits, "maxFiles", 256);
  const packageLimit = quota(limits, "packageBytes", PACKAGE_LIMIT);
  let bytes = 0;
  for (const path of Object.keys(files)) {
    const source = files[path];
    if (typeof source !== "string") continue;
    bytes += source.length;
    flagPath(path, out);
    if (source.length > textLimit) {
      out.push(issue("FL709", "error", path, 1, 1, "file exceeds the text size limit"));
    }
    if (path.toLowerCase().endsWith(".svg")) scanSvg(path, source, out);
  }
  if (Object.keys(files).length > fileLimit) {
    out.push(issue("FL700", "error", "manifest.json", 1, 1, "package has more than " + fileLimit + " files"));
  }
  if (bytes > packageLimit) {
    out.push(issue("FL709", "error", "manifest.json", 1, 1, "package exceeds " + packageLimit + " bytes"));
  }
  const manifest = readManifest(files);
  const canvas = profile === "canvas" || (manifest && manifest.profile === "canvas");
  if (canvas) flagCanvas(files, out);
  if (canvas || (manifest && manifest.widget)) flagCanvasWidget(files, out);
  if (mode === "publish") flagPublish(files, manifest, out);
  if (profile === "theme" || (manifest && manifest.kind === "theme")) flagThemeFiles(files, out);
  flagNetwork(files, manifest, out);
  flagBridge(files, manifest, catalog, out);
  flagWasm(files, manifest, out);
  flagRemoteWasm(files, manifest, out);
  flagAssetPacks(manifest, canvas, bytes, packageLimit, out);
  flagToolFiles(files, manifest, out);
  flagIds(files, out);
  flagReachable(files, out);
}

function flagPath(path, out) {
  const parts = path.split("/").filter((part) => part !== "");
  if (path.split("/").some((part) => part === "")) {
    out.push(issue("FL700", "error", path, 1, 1, "path has an empty segment"));
  }
  if (parts.length > 6) {
    out.push(issue("FL700", "error", path, 1, 1, "path is deeper than 6"));
  }
  for (const part of parts) {
    if (part.startsWith(".") || !/^[a-z0-9._-]+$/.test(part)) {
      out.push(issue("FL700", "error", path, 1, 1, `path segment "${part}" is not allowed`));
      break;
    }
  }
  const base = parts[parts.length - 1] || path;
  if (base.toLowerCase() === "license" || base.toLowerCase() === "copying") return;
  const dot = base.lastIndexOf(".");
  const ext = dot < 0 ? "" : base.slice(dot + 1).toLowerCase();
  if (!ALLOWED_EXT.has(ext)) {
    out.push(issue("FL700", "error", path, 1, 1, `disallowed file type ".${ext}"`));
  }
}

function readManifest(files) {
  const raw = files["manifest.json"];
  if (typeof raw !== "string") return null;
  try {
    const data = JSON.parse(raw);
    if (!data || typeof data !== "object" || Array.isArray(data)) return null;
    const caps = new Set();
    if (Array.isArray(data.capabilities)) {
      for (const entry of data.capabilities) {
        if (typeof entry === "string") caps.add(entry);
        else if (entry && typeof entry.id === "string") caps.add(entry.id);
      }
    }
    return {
      dependencies: data.dependencies && typeof data.dependencies === "object" && !Array.isArray(data.dependencies)
        ? Object.keys(data.dependencies)
        : [],
      // Raw manifest assetPacks, checked by flagAssetPacks.
      assetPacks: data.assetPacks,
      // Dependency URLs whose bytes are pinned by an SRI hash.
      pinned: data.dependencies && typeof data.dependencies === "object" && !Array.isArray(data.dependencies)
        ? Object.entries(data.dependencies)
          .filter(([, spec]) => {
            const integrity = spec && typeof spec === "object" ? spec.integrity : spec;
            return typeof integrity === "string" && /^sha(256|384|512)-[A-Za-z0-9+/]+={0,2}$/.test(integrity);
          })
          .map(([url]) => url)
        : [],
      profile: data.profile,
      kind: data.kind,
      license: data.license,
      source: data.source,
      network: Array.isArray(data.network) ? data.network.filter((item) => typeof item === "string") : [],
      embeds: Array.isArray(data.embeds) ? data.embeds.filter((item) => typeof item === "string") : [],
      widget: data.widget != null,
      caps,
    };
  } catch {
    return null;
  }
}

function flagPublish(files, manifest, out) {
  const names = Object.keys(files).map((path) => path.split("/").pop().toLowerCase());
  if (!names.includes("license") && !names.includes("copying")) {
    out.push(issue("FL707", "error", "manifest.json", 1, 1, "publish requires a LICENSE file"));
  }
  if (!manifest || typeof manifest.license !== "string" || !manifest.license.trim()) {
    out.push(issue("FL707", "error", "manifest.json", 1, 1, "publish requires manifest license"));
  }
  if (!manifest || typeof manifest.source !== "string" || !/^https:\/\//i.test(manifest.source)) {
    out.push(issue("FL707", "error", "manifest.json", 1, 1, "publish requires manifest source"));
  }
}

function flagThemeFiles(files, out) {
  for (const path of Object.keys(files)) {
    if (/\.(css|js|html|htm|svg)$/i.test(path)) {
      out.push(issue("FL722", "error", path, 1, 1, "theme packages cannot contain code or SVG"));
    }
  }
}

function flagCanvas(files, out) {
  for (const path of Object.keys(files)) {
    if (!path.endsWith(".js") && !path.endsWith(".html")) continue;
    const text = stripComments(files[path]);
    if (/deftBridge\b|\/_deft\/v1\/bridge\.js\b|\bfetch\s*\(|\bWebSocket\b|\bEventSource\b/.test(text)) {
      const at = indexAt(files[path]);
      const pos = at(Math.max(0, text.search(/deftBridge|bridge\.js|\bfetch\s*\(|WebSocket|EventSource/)));
      out.push(issue("FL204", "error", path, pos.line, pos.col, "canvas cannot use the bridge or network"));
    }
    // FL637: <deft-widget> only works once /_deft/v1/elements.js defines it.
    if (path.endsWith(".html") && /<deft-widget\b/i.test(text) && !/\/_deft\/v1\/(elements|motion)\.js/.test(text)) {
      const pos = indexAt(files[path])(Math.max(0, text.search(/<deft-widget\b/i)));
      out.push(issue("FL637", "warn", path, pos.line, pos.col,
        'deft-widget never starts unless the page loads its element: add <script type="module" src="/_deft/v1/elements.js"></script>'));
    }
  }
}

// FL634-FL636 (canvas and widget packages): the home canvas and its widgets share the launcher's renderer, so a loop
// that never stops, a timer that wakes the phone often, or a blur that costs frames is an error or a warning.
// Heuristics over comment-free source; a finding can be suppressed like the others (with a reason).
const MIN_TIMER_MS = 5000;
const MAX_BLUR_PX = 8;
const RAF_CALL = /\brequestAnimationFrame\s*\(/;

function flagCanvasWidget(files, out) {
  const jsPaths = Object.keys(files).filter((p) => /\.m?js$/i.test(p) && typeof files[p] === "string");
  const code = Object.fromEntries(jsPaths.map((p) => [p, stripComments(files[p])]));
  const anyCancel = jsPaths.some((p) => /\bcancelAnimationFrame\s*\(/.test(code[p]));
  for (const path of jsPaths) {
    const text = code[path];
    const at = indexAt(files[path]);
    // One finding per file: the reschedule inside a loop is the same loop, so the first call names it.
    let reported = false;
    for (const m of text.matchAll(new RegExp(RAF_CALL.source, "g"))) {
      const open = m.index + m[0].length - 1;
      const args = callArgs(text, open);
      const cb = args && args[0];
      if (!cb || anyCancel) continue;
      const name = /^[A-Za-z_$][\w$]*$/.test(cb) ? cb : null;
      const body = name ? definitionBody(text, name) : cb;
      if (!body || !RAF_CALL.test(body)) continue;
      if (reported) break;
      reported = true;
      const pos = at(m.index);
      out.push(issue("FL634", "error", path, pos.line, pos.col,
        `unbounded requestAnimationFrame loop: ${name ? `"${name}"` : "the callback"} schedules itself and nothing calls cancelAnimationFrame. Stop when document.hidden or after a bound, or redraw on data`));
    }
    for (const m of text.matchAll(/\b(setInterval|setTimeout)\s*\(/g)) {
      const args = callArgs(text, m.index + m[0].length - 1);
      if (!args || args.length < 2 || !/^\d+(\.\d+)?$/.test(args[1])) continue;
      const ms = Number(args[1]);
      if (ms >= MIN_TIMER_MS) continue;
      const pos = at(m.index);
      out.push(issue("FL635", "warn", path, pos.line, pos.col,
        `${m[1]} with ${ms} ms wakes the canvas or widget often; use ${MIN_TIMER_MS} ms or more, or refresh on view (visibilitychange)`));
    }
  }
  for (const path of Object.keys(files)) {
    if (!/\.css$/i.test(path) || typeof files[path] !== "string") continue;
    const css = stripComments(files[path]);
    const at = indexAt(files[path]);
    for (const m of css.matchAll(/\bblur\(\s*(\d+(?:\.\d+)?)px\s*\)/g)) {
      if (Number(m[1]) <= MAX_BLUR_PX) continue;
      const pos = at(m.index);
      out.push(issue("FL636", "warn", path, pos.line, pos.col, `blur(${m[1]}px) over ${MAX_BLUR_PX}px costs frames on low-end phones; use a smaller blur or a flat tint`));
    }
    for (const m of css.matchAll(/\bbackdrop-filter\s*:(?!\s*none\b)/gi)) {
      const pos = at(m.index);
      out.push(issue("FL636", "warn", path, pos.line, pos.col, "backdrop-filter costs frames on low-end phones; use a flat --deft-* surface"));
    }
  }
}

/** Top-level argument texts of the call whose "(" is at [open]; null when it never closes. */
function callArgs(text, open) {
  const args = [];
  let depth = 0;
  let start = open + 1;
  let quote = "";
  for (let i = open; i < text.length; i++) {
    const c = text[i];
    if (quote) {
      if (c === "\\") i++;
      else if (c === quote) quote = "";
      continue;
    }
    if (c === "'" || c === "\"" || c === "`") quote = c;
    else if (c === "(" || c === "[" || c === "{") depth++;
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

/** Source of `function name(...) {...}` or `const name = ...` (a block, or an expression up to ';'). */
function definitionBody(text, name) {
  const def = new RegExp(`(?:\\bfunction\\s+${name}\\s*\\(|\\b(?:const|let|var)\\s+${name}\\s*=)`).exec(text);
  if (!def) return null;
  const brace = text.indexOf("{", def.index);
  const semi = text.indexOf(";", def.index);
  if (brace < 0 || (semi >= 0 && semi < brace)) return text.slice(def.index, semi < 0 ? undefined : semi);
  let depth = 0;
  for (let i = brace; i < text.length; i++) {
    if (text[i] === "{") depth++;
    else if (text[i] === "}" && --depth === 0) return text.slice(def.index, i + 1);
  }
  return text.slice(def.index);
}

/** An origin matches an `embeds` entry exactly, or `*.` matches any subdomain (as in CSP). */
function embedMatches(entry, origin) {
  const o = origin.toLowerCase();
  if (!/^https:\/\/[a-z0-9.-]+(?::\d{1,5})?$/.test(o)) return false;
  if (!entry.startsWith("https://*.")) return entry === o;
  const tail = entry.slice("https://*".length);
  return o.startsWith("https://") && o.length > "https://".length + tail.length && o.endsWith(tail);
}

function flagNetwork(files, manifest, out) {
  // network.open lets the app reach any host, so no literal URL needs a manifest.network entry.
  if (manifest && manifest.caps.has("network.open")) return;
  const allowed = new Set((manifest ? manifest.network : []).map((item) => item.toLowerCase().replace(/\/$/, "")));
  // Embeds count only with web.embed declared, as on the device (FL712 reports the missing capability).
  const embeds = manifest && manifest.caps.has("web.embed") ? manifest.embeds : [];
  for (const path of Object.keys(files)) {
    const source = files[path];
    if (typeof source !== "string") continue;
    const text = path.endsWith(".js") || path.endsWith(".css") ? stripComments(source) : source;
    const re = path.endsWith(".css")
      ? /url\(\s*["']?(https?:\/\/[^"')\s]+)/gi
      : path.endsWith(".html") || path.endsWith(".htm")
        ? /\b(?:src|href)\s*=\s*["'](https?:\/\/[^"']+)/gi
        : /["'`](https?:\/\/[^"'`\s]+)/g;
    let m;
    while ((m = re.exec(text))) {
      const url = m[1];
      if (/^https?:\/\/(?:www\.)?w3\.org\b/i.test(url)) continue;
      const origin = (url.match(/^https?:\/\/[^/]+/i) || [])[0];
      if (!origin) continue;
      if (allowed.has(origin.toLowerCase())) continue;
      if (embeds.some((entry) => embedMatches(entry, origin))) continue;
      // The embeds list itself is checked by FL712, not here.
      if (path === "manifest.json" && manifest && manifest.embeds.includes(url)) continue;
      // Declared CDN dependencies are served locally through the import map, not fetched.
      if (declaredUrls.has(url) && (path.endsWith(".js") || path === "manifest.json")) continue;
      const at = indexAt(source);
      const pos = at(m.index);
      out.push(issue("FL205", "error", path, pos.line, pos.col, `network origin ${origin} is not in manifest.network`));
    }
  }
}

function flagBridge(files, manifest, catalog, out) {
  if (!catalog) return;
  const maps = catalogMaps(catalog);
  const declared = manifest ? manifest.caps : new Set();
  const used = new Set();
  for (const path of Object.keys(files)) {
    if (!path.endsWith(".js")) continue;
    const source = files[path];
    const text = stripComments(source);
    const re = /(^|[^\w])(invoke|subscribe|call)\s*\(\s*(['"`])([^'"`]*)\3/g;
    let m;
    while ((m = re.exec(text))) {
      const kind = m[2];
      const literal = m[4];
      const prefix = text.slice(Math.max(0, m.index - 24), m.index + (m[1] ? m[1].length : 0));
      if (/function\s*$/.test(prefix)) continue;
      if (kind === "call" && /\.\s*$/.test(prefix) && !/deftBridge\s*\.\s*$/.test(prefix)) continue;
      if (kind === "call" && !literal.includes(".")) continue;
      const table = kind === "subscribe" ? maps.event : maps.method;
      const cap = table.get(literal);
      if (!cap) {
        if (literal.includes(".")) {
          const at = indexAt(source);
          const pos = at(m.index);
          out.push(issue("FL201", "error", path, pos.line, pos.col, `unknown bridge ${kind === "subscribe" ? "event" : "method"} "${literal}"`));
        }
        continue;
      }
      used.add(cap);
      // net.fetch has no capability of its own: manifest `network` origins, network.user or network.open allow it.
      const netFetch = literal === "net.fetch" &&
        (declared.has("network.user") || declared.has("network.open") || (manifest && manifest.network.length > 0));
      if (!declared.has(cap) && !netFetch) {
        const at = indexAt(source);
        const pos = at(m.index);
        out.push(issue("FL202", "error", path, pos.line, pos.col, `capability ${cap} is used but not declared`));
      }
    }
  }
  if (!manifest) return;
  const caps = catalog.capabilities || {};
  for (const id of declared) {
    const entry = caps[id];
    if (!entry) continue;
    const names = [...(entry.methods || []), ...(entry.events || [])];
    if (!names.length) continue;
    const blob = Object.values(files).join("\n");
    if (!names.some((name) => blob.includes(name))) {
      out.push(issue("FL203", "warn", "manifest.json", 1, 1, `capability ${id} is declared but unused`));
    }
  }
}

/**
 * WebAssembly runs only when the app declares `wasm` (CSP 'wasm-unsafe-eval'). A `.wasm`
 * file in the package, a `.wasm` dependency (hash rule in index.js FL710) or a `WebAssembly` use in
 * a script needs that declaration. Declared apps pass; nothing else is allowed through.
 */
function flagWasm(files, manifest, out) {
  const declared = !!manifest && manifest.caps.has("wasm");
  if (declared) return;
  const uses = [];
  for (const path of Object.keys(files)) {
    if (/\.wasm$/i.test(path)) {
      uses.push({ path, line: 1, col: 1 });
      continue;
    }
    if (!path.endsWith(".js") || typeof files[path] !== "string") continue;
    const text = stripComments(files[path]);
    const m = /\bWebAssembly\b/.exec(text);
    if (m) {
      const pos = indexAt(files[path])(m.index);
      uses.push({ path, line: pos.line, col: pos.col });
    }
  }
  if (manifest) {
    for (const url of manifest.dependencies) {
      if (/\.wasm$/i.test(url)) uses.push({ path: "manifest.json", line: 1, col: 1 });
    }
  }
  for (const use of uses) {
    out.push(issue("FL202", "error", use.path, use.line, use.col, "capability wasm is used but not declared"));
  }
}

const PACK_NAME = /^[a-z0-9][a-z0-9-]{0,31}$/;
const PACK_URL = /^https:\/\/[a-z0-9.-]+\.[a-z]{2,}(?::[0-9]{1,5})?\/[A-Za-z0-9@._~+/-]+\.zip$/;
const PACK_SHA = /^[0-9a-f]{64}$/;
const MAX_ASSET_PACKS = 8;
const LARGE_PACKAGE_MAX_BYTES = 2 * 1024 * 1024 * 1024;

/**
 * Manifest `assetPacks: [{name, url, sha256, size}]`, the same rules as the
 * launcher's parser (DeftManifestParser.parseAssetPacks). Packs count against the package cap:
 * the app's files plus every pack must fit, so a big pack needs `storage.large`.
 */
function flagAssetPacks(manifest, canvas, appBytes, packageLimit, out) {
  if (!manifest || manifest.assetPacks == null) return;
  const packs = manifest.assetPacks;
  const bad = (message) => out.push(issue("FL723", "error", "manifest.json", 1, 1, message));
  if (canvas) {
    bad("canvas profile cannot declare assetPacks");
    return;
  }
  if (!Array.isArray(packs)) {
    bad("assetPacks must be an array of {name, url, sha256, size}");
    return;
  }
  if (packs.length > MAX_ASSET_PACKS) bad(`at most ${MAX_ASSET_PACKS} asset packs`);
  const seen = new Set();
  let total = 0;
  for (const pack of packs) {
    if (!pack || typeof pack !== "object" || Array.isArray(pack)) {
      bad("each asset pack must be {name, url, sha256, size}");
      continue;
    }
    const name = typeof pack.name === "string" ? pack.name : "";
    if (!PACK_NAME.test(name)) bad("asset pack name must be 1-32 chars [a-z0-9-]");
    else if (seen.has(name)) bad(`asset pack ${name} is listed twice`);
    seen.add(name);
    const url = typeof pack.url === "string" ? pack.url : "";
    if (url.length > 2048 || !PACK_URL.test(url) || url.includes("..") || url.indexOf("//", 8) >= 0) {
      bad(`asset pack ${name} url must be an https .zip URL without query or fragment`);
    }
    if (typeof pack.sha256 !== "string" || !PACK_SHA.test(pack.sha256)) {
      bad(`asset pack ${name} sha256 must be 64 lowercase hex characters`);
    }
    if (!Number.isInteger(pack.size) || pack.size < 1 || pack.size > LARGE_PACKAGE_MAX_BYTES) {
      bad(`asset pack ${name} size must be 1 to ${LARGE_PACKAGE_MAX_BYTES} bytes`);
    } else {
      total += pack.size;
    }
  }
  if (total + appBytes > packageLimit) {
    const hint = manifest.caps.has("storage.large") ? "" : "; declare storage.large for more";
    bad(`asset packs and app files are ${total + appBytes} bytes, over the ${packageLimit} byte cap${hint}`);
  }
}

/**
 * A `.wasm` from another origin runs only as a hash-pinned dependency, fetched by the
 * launcher and served same-origin under /_deft/deps/<host>/<path>. A remote `.wasm` URL in a
 * script or page is an error, even when its origin is in `network`: those bytes are not pinned.
 * A pinned dependency's own URL gets a warning, because the page must load the served copy.
 */
function flagRemoteWasm(files, manifest, out) {
  const pinned = new Set(manifest ? manifest.pinned : []);
  for (const path of Object.keys(files)) {
    if (!/\.(m?js|html?)$/i.test(path) || typeof files[path] !== "string") continue;
    const source = files[path];
    const text = /\.m?js$/i.test(path) ? stripComments(source) : source;
    const re = /["'`](https?:\/\/[^"'`\s?#]+\.wasm)(?:[?#][^"'`\s]*)?["'`]/gi;
    let m;
    while ((m = re.exec(text))) {
      const url = m[1];
      const pos = indexAt(source)(m.index);
      if (pinned.has(url)) {
        const served = "/_deft/deps/" + url.replace(/^https:\/\//i, "");
        out.push(issue("FL710", "warn", path, pos.line, pos.col, `load the pinned WebAssembly dependency from ${served}, not from ${url}`));
      } else {
        out.push(issue("FL710", "error", path, pos.line, pos.col, `WebAssembly from ${url} must be a hash-pinned dependency in manifest.json`));
      }
    }
  }
}

function catalogMaps(catalog) {
  const method = new Map();
  const event = new Map();
  const caps = catalog && catalog.capabilities;
  if (!caps || typeof caps !== "object" || Array.isArray(caps)) return { method, event };
  for (const id of Object.keys(caps)) {
    const entry = caps[id] || {};
    for (const name of entry.methods || []) method.set(name, id);
    for (const name of entry.events || []) event.set(name, id);
  }
  return { method, event };
}

function flagIds(files, out) {
  const ids = new Set();
  for (const path of Object.keys(files)) {
    const source = files[path];
    if (typeof source !== "string") continue;
    for (const m of source.matchAll(/\sid\s*=\s*["']([^"']+)["']/g)) ids.add(m[1]);
    for (const m of source.matchAll(/\bid\s*=\s*["']([^"'${}]+)["']/g)) ids.add(m[1]);
  }
  for (const path of Object.keys(files)) {
    if (!path.endsWith(".js")) continue;
    const source = stripComments(files[path]);
    const re = /getElementById\(\s*["']([^"']+)["']\s*\)|querySelector\(\s*["']#([A-Za-z][\w-]*)["']\s*\)/g;
    let m;
    while ((m = re.exec(source))) {
      const id = m[1] || m[2];
      if (ids.has(id)) continue;
      const at = indexAt(files[path]);
      const pos = at(m.index);
      out.push(issue("FL300", "warn", path, pos.line, pos.col, `id "${id}" is not in the HTML`));
    }
  }
}

function flagReachable(files, out) {
  if (typeof files["index.html"] !== "string" || !isDocument(files["index.html"])) return;
  const seen = new Set();
  const queue = ["index.html"];
  // The manifest wallpaper and keyboard pages are entry points of their own.
  const wallpaperPage = manifestWallpaperPage(files["manifest.json"]);
  if (wallpaperPage) queue.push(wallpaperPage);
  const keyboardPage = manifestWallpaperPage(files["manifest.json"], "keyboard");
  if (keyboardPage) queue.push(keyboardPage);
  // W1: the manifest widget page is an entry point too; the canvas loads it in an iframe.
  const widgetPage = manifestWallpaperPage(files["manifest.json"], "widget");
  if (widgetPage) queue.push(widgetPage);
  // The manifest background.module is loaded by the launcher's background page, not by index.html.
  const backgroundModule = manifestWallpaperPage(files["manifest.json"], "background", "module");
  if (backgroundModule) queue.push(backgroundModule);
  // W5: agent tool handler scripts are loaded by the launcher's tool page (or read into the isolate), not by index.html.
  for (const tool of manifestTools(files["manifest.json"])) queue.push(tool.path);
  while (queue.length) {
    const path = queue.pop();
    if (!path || seen.has(path) || typeof files[path] !== "string") continue;
    seen.add(path);
    for (const ref of refsIn(path, files[path])) {
      const next = resolveRel(path, ref);
      if (next) queue.push(next);
    }
  }
  for (const path of Object.keys(files)) {
    if (!/\.(js|css|html|htm)$/i.test(path)) continue;
    if (seen.has(path)) continue;
    out.push(issue("FL303", "warn", path, 1, 1, "file is not reachable from index.html"));
  }
}

// W5: the manifest `tools` handler refs as {name, path (file key), fn, isolate}; shape errors are FL718's (index.js).
function manifestTools(source) {
  if (typeof source !== "string") return [];
  let data;
  try {
    data = JSON.parse(source);
  } catch {
    return [];
  }
  if (!data || !Array.isArray(data.tools)) return [];
  const out = [];
  for (const tool of data.tools) {
    if (!tool || typeof tool !== "object") continue;
    const h = tool.handler;
    const isolate = !!h && typeof h === "object" && !Array.isArray(h);
    const ref = isolate ? h.isolate : h;
    if (typeof ref !== "string" || ref.indexOf("#") <= 0) continue;
    const rawPath = ref.slice(0, ref.indexOf("#"));
    const normalized = normalizeModulePath(rawPath);
    const path = (normalized ?? rawPath).replace(/^\.?\//, "");
    out.push({ name: String(tool.name), path, fn: ref.slice(ref.indexOf("#") + 1), isolate });
  }
  return out;
}

// W5 (FL718): the handler files of manifest `tools`. Checked only for a whole project (an index.html is present), so
// writing manifest.json before tools.js does not complain. An isolate handler is a plain script (the isolate has no
// module support): no import/export, and the function must be defined at the top level.
function flagToolFiles(files, manifest, out) {
  if (typeof files["index.html"] !== "string") return;
  for (const tool of manifestTools(files["manifest.json"])) {
    const src = files[tool.path];
    if (typeof src !== "string") {
      out.push(issue("FL718", "error", "manifest.json", 1, 1, `tool "${tool.name}" handler file ${tool.path} is not in the package`));
      continue;
    }
    const code = stripComments(src);
    const fn = tool.fn.replace(/\$/g, "\\$");
    if (tool.isolate) {
      const mod = /^\s*(?:import\s*[\w{*"']|export\b)/m.exec(code);
      if (mod) {
        const line = code.slice(0, mod.index + mod[0].search(/\S/)).split("\n").length;
        out.push(issue("FL718", "error", tool.path, line, 1,
          `${tool.path} is an isolate handler: it must be a plain script (no import/export; the isolate has no modules, no bridge, no network)`));
      }
      if (!new RegExp("(?:^|[^\\w$.])(?:async\\s+)?function\\s*\\*?\\s*" + fn + "\\s*\\(|(?:^|[^\\w$.])(?:var|let|const)\\s+" + fn + "\\s*=", "m").test(code)) {
        out.push(issue("FL718", "error", tool.path, 1, 1, `${tool.path} must define the global function ${tool.fn} for the isolate handler`));
      }
      if (/\basync\s+function\s+/.test(code) && new RegExp("async\\s+function\\s+" + fn + "\\b").test(code)) {
        out.push(issue("FL718", "error", tool.path, 1, 1, `isolate handler ${tool.fn} must be synchronous (no async function, no promises)`));
      }
    } else if (!new RegExp("export\\s+(?:async\\s+)?(?:function\\s*\\*?|const|let|var|class)\\s*" + fn + "\\b|export\\s*\\{[^}]*\\b" + fn + "\\b").test(code)) {
      out.push(issue("FL718", "warn", tool.path, 1, 1, `${tool.path} does not export ${tool.fn}`));
    }
  }
}

function manifestWallpaperPage(source, field = "wallpaper", key = "page") {
  if (typeof source !== "string") return null;
  let data;
  try {
    data = JSON.parse(source);
  } catch {
    return null;
  }
  const page = data && data[field] && data[field][key];
  if (typeof page !== "string" || !/^\.?\//.test(page)) return null;
  return page.replace(/^\.?\//, "");
}

function refsIn(path, source) {
  const refs = [];
  if (/\.(html|htm)$/i.test(path)) {
    for (const m of source.matchAll(/\b(?:src|href)\s*=\s*["']([^"']+)["']/gi)) refs.push(m[1]);
  } else if (path.endsWith(".js")) {
    for (const m of source.matchAll(/\bfrom\s+["']([^"']+)["']|\bimport\s*\(\s*["']([^"']+)["']|\bimport\s+["']([^"']+)["']/g)) {
      refs.push(m[1] || m[2] || m[3]);
    }
  } else if (path.endsWith(".css")) {
    for (const m of source.matchAll(/url\(\s*["']?([^"')]+)["']?\s*\)|@import\s+["']([^"']+)["']/gi)) refs.push(m[1] || m[2]);
  }
  return refs;
}

function resolveRel(from, ref) {
  if (!ref || /^(https?:|data:|mailto:|tel:|sms:|#|\/)/i.test(ref)) return null;
  const clean = ref.split("#")[0].split("?")[0];
  if (!clean) return null;
  const base = from.split("/").slice(0, -1);
  for (const part of clean.split("/")) {
    if (!part || part === ".") continue;
    if (part === "..") base.pop();
    else base.push(part);
  }
  return base.join("/");
}

// Host paths an app may not claim: the runtime /_deft/ (kept identical to DeftManifestParser.reservedPath).
export function reservedPath(absolute) {
  return absolute.toLowerCase().startsWith("/_deft/");
}

// Kept identical to DeftManifestParser.normalizeModulePath (Kotlin): the absolute path without "." segments, or null.
// The /_deft/ check runs on the normalised form; "%" is outside the allowed characters so encoded forms are refused.
export function normalizeModulePath(value) {
  if (typeof value !== "string" || value.length > 256) return null;
  if (!/^\.?\/[A-Za-z0-9._~\/-]+\.m?js$/.test(value)) return null;
  if (value.includes("..") || value.includes("//")) return null;
  const absolute = value.startsWith(".") ? value.slice(1) : value;
  const normalized = "/" + absolute.split("/").filter((seg) => seg !== "" && seg !== ".").join("/");
  if (reservedPath(normalized) || !/^\/[A-Za-z0-9._~\/-]+\.m?js$/.test(normalized)) return null;
  return normalized;
}
