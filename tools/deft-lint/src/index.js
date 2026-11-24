/**
 * deft-lint: deterministic checks for Deft apps.
 * Hand-written scanners only — no parser dependencies.
 */

import { designHandlers } from "./design.js";
import { afterCss, afterHtml, afterJs, normalizeModulePath, reservedPath, scanProject, setDependencies } from "./extra.js";

const REQUIRED_MANIFEST = ["deft", "id", "name", "start_url"];

const REGEX_AFTER = new Set([
  "return", "throw", "case", "void", "typeof", "delete", "in", "of",
  "do", "else", "yield", "await", "instanceof", "new",
]);

/**
 * @param {{ files?: Record<string, string>, profile?: string, mode?: string, catalog?: object }} input
 * @returns {{ id: string, severity: string, path: string, line: number, col: number, message: string }[]}
 */
export function run(input) {
  const files = input && input.files && typeof input.files === "object" ? input.files : {};
  const profile = input ? input.profile : undefined;
  const catalog = input ? input.catalog : undefined;
  const mode = input && typeof input.mode === "string" ? input.mode : "";
  const out = [];
  const allows = [];
  setDependencies(dependencyNames(files["manifest.json"]));

  for (const path of Object.keys(files)) {
    if (pathHasDotDotOrFl(path)) {
      out.push(issue("FL003", "error", path, 1, 1, 'path must not contain "..", "_deft/" or "_fl/"'));
    }
    const source = files[path];
    if (typeof source !== "string") continue;
    const base = basename(path).toLowerCase();
    if (base.endsWith(".js")) scanJs(path, source, out, allows, mode);
    else if (base.endsWith(".html") || base.endsWith(".htm")) scanHtml(path, source, out, mode);
    else if (base.endsWith(".css")) scanCss(path, source, out, allows, mode);
    else if (base === "manifest.json") scanManifest(path, source, profile, catalog, out, mode);
    if (base === "palette.json") scanPalette(path, source, mode, out);
  }

  designHandlers(files, out);
  scanProject(files, profile, mode, catalog, out, input && input.limits);
  applyAllows(out, allows);
  out.sort(compareDiag);
  return out;
}

function issue(id, severity, path, line, col, message) {
  return { id, severity, path, line, col, message };
}

function compareDiag(a, b) {
  if (a.path !== b.path) return a.path < b.path ? -1 : 1;
  if (a.line !== b.line) return a.line - b.line;
  if (a.col !== b.col) return a.col - b.col;
  if (a.id !== b.id) return a.id < b.id ? -1 : 1;
  return 0;
}

function basename(path) {
  const slash = Math.max(path.lastIndexOf("/"), path.lastIndexOf("\\"));
  return slash < 0 ? path : path.slice(slash + 1);
}

// Runtime files apps load by absolute URL (docs/spec/PLATFORM.md: one runtime, /_deft/v1/). FL005 checks the module names.
// /_fl/v1/ is the pre-rename runtime path; the launcher still serves it for older apps.
const RUNTIME_REF = /^\/_(?:deft|fl)\/v1\/[a-z0-9._\/-]+\.(?:js|css|svg)(?:[?#].*)?$/;

function pathHasDotDotOrFl(value) {
  if (typeof value !== "string") return false;
  if (RUNTIME_REF.test(value)) return false;
  return value.includes("..") || value.includes("_deft/") || value.includes("_fl/");
}

function locator(source) {
  const starts = [0];
  for (let i = 0; i < source.length; i++) {
    if (source[i] === "\n") starts.push(i + 1);
  }
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

function lineText(source, index) {
  const start = source.lastIndexOf("\n", Math.max(0, index - 1)) + 1;
  let end = source.indexOf("\n", index);
  if (end < 0) end = source.length;
  return source.slice(start, end);
}

function findKey(source, key) {
  const needle = `"${key}"`;
  const at = source.indexOf(needle);
  return at < 0 ? 0 : at;
}

const BTN_MODIFIERS = new Set([
  "primary", "secondary", "accent", "neutral", "info", "success", "warning", "error",
  "xs", "sm", "md", "lg", "xl",
  "outline", "soft", "ghost", "dash", "link",
]);

const PALETTE_ROLES = ["primary", "secondary", "accent", "neutral", "info", "success", "warning", "error"];
const PALETTE_BASES = ["base-100", "base-200", "base-300"];

function noteAllow(allows, path, bodyStart, text, kind, at) {
  const rel = text.search(/deft-allow/i);
  if (rel < 0) return;
  const parsed = parseAllow(text.slice(rel));
  if (!parsed) return;
  const pos = at(bodyStart + rel);
  allows.push({
    path,
    line: pos.line,
    col: pos.col,
    id: parsed.id,
    reason: parsed.reason,
    kind,
  });
}

function parseAllow(text) {
  const match = text.match(/deft-allow\b(?:\s+(FL\d(?:xx|\d{2})))?(?:\s*:\s*([^\n]*))?/i);
  if (!match) return null;
  let id = "";
  if (match[1]) {
    id = /xx$/i.test(match[1]) ? match[1].slice(0, -2).toUpperCase() + "xx" : match[1].toUpperCase();
  }
  return { id, reason: (match[2] ?? "").trim() };
}

function allowMatches(pattern, id) {
  if (!pattern || !id) return false;
  if (pattern.endsWith("xx")) {
    const prefix = pattern.slice(0, -2);
    return id.startsWith(prefix) && id.length === prefix.length + 2 && /^\d{2}$/.test(id.slice(prefix.length));
  }
  return pattern === id;
}

function allowFamily(kind, id) {
  if (kind === "js") return id.startsWith("FL4");
  if (kind === "css") return id.startsWith("FL6");
  return false;
}

function ruleLocked(id) {
  return id.startsWith("FL1") || id.startsWith("FL2") || id.startsWith("FL7");
}

function applyAllows(out, allows) {
  const drop = new Set();
  for (const allow of allows) {
    if (!allow.reason) {
      out.push(issue("FL009", "warn", allow.path, allow.line, allow.col, "suppression without a reason"));
      continue;
    }
    let used = false;
    for (let i = 0; i < out.length; i++) {
      if (used || drop.has(i)) continue;
      const diag = out[i];
      if (diag.path !== allow.path || diag.line !== allow.line + 1) continue;
      if (!allowMatches(allow.id, diag.id) || !allowFamily(allow.kind, diag.id)) continue;
      if (diag.severity === "error" || ruleLocked(diag.id)) continue;
      drop.add(i);
      used = true;
    }
  }
  for (let i = out.length - 1; i >= 0; i--) {
    if (drop.has(i)) out.splice(i, 1);
  }
}

function scanBtnModifiers(path, text, valueStart, out, at) {
  const re = /deft-btn-([a-z0-9-]+)/gi;
  let match;
  while ((match = re.exec(text))) {
    if (BTN_MODIFIERS.has(match[1].toLowerCase())) continue;
    const pos = at(valueStart + match.index);
    out.push(issue(
      "FL302",
      "info",
      path,
      pos.line,
      pos.col,
      `unknown deft-btn modifier "${match[0].toLowerCase()}"`,
    ));
  }
}

/* ------------------------------ JavaScript ------------------------------ */

function scanJs(path, source, out, allows, mode) {
  const at = locator(source);
  const comments = [];
  const tokens = tokenizeJs(source, comments);
  if (allows) {
    for (const comment of comments) noteAllow(allows, path, comment.body, comment.text, "js", at);
  }
  for (let i = 0; i < tokens.length; i++) {
    const t = tokens[i];
    if (t.type === "string" && t.value) {
      const quoted = source[t.index] === '"' || source[t.index] === "'" || source[t.index] === "`";
      scanBtnModifiers(path, t.value, quoted ? t.index + 1 : t.index, out, at);
    }
    if (t.type !== "ident") continue;
    const prev = tokens[i - 1];
    const dotted = prev && prev.type === "punct" && prev.value === ".";
    if (t.value === "map" && dotted && t.expr) {
      const open = tokens[i + 1];
      if (open && open.type === "punct" && open.value === "(") {
        const close = matchingParen(tokens, i + 1);
        if (close > i && argsHaveHtmlTemplate(tokens, i + 2, close)) {
          const pos = at(t.index);
          out.push(issue(
            "FL402",
            "warn",
            path,
            pos.line,
            pos.col,
            "unkeyed .map() of a template; use repeat(items, key, tpl)",
          ));
        }
      }
    }

    if (t.value === "innerHTML" || t.value === "insertAdjacentHTML") {
      const pos = at(t.index);
      out.push(issue("FL100", "error", path, pos.line, pos.col, `${t.value} is not allowed`));
      continue;
    }
    if (t.value === "document") {
      const dot = tokens[i + 1];
      const write = tokens[i + 2];
      if (dot && dot.type === "punct" && dot.value === "." && write && write.type === "ident" && write.value === "write") {
        const pos = at(t.index);
        out.push(issue("FL100", "error", path, pos.line, pos.col, "document.write is not allowed"));
      }
      continue;
    }
    if (t.value === "eval") {
      const next = tokens[i + 1];
      if (next && next.type === "punct" && next.value === "(") {
        const pos = at(t.index);
        out.push(issue("FL101", "error", path, pos.line, pos.col, "eval() is not allowed"));
      }
      continue;
    }
    if (t.value === "new") {
      const fn = tokens[i + 1];
      const paren = tokens[i + 2];
      if (fn && fn.type === "ident" && fn.value === "Function" && paren && paren.type === "punct" && paren.value === "(") {
        const pos = at(t.index);
        out.push(issue("FL101", "error", path, pos.line, pos.col, "new Function() is not allowed"));
      }
      continue;
    }
    if (dotted) continue;
    if (t.value === "import") {
      const spec = importSpecifier(tokens, i);
      if (spec && pathHasDotDotOrFl(spec.value)) {
        const pos = at(spec.index);
        out.push(issue("FL003", "error", path, pos.line, pos.col, 'path must not contain "..", "_deft/" or "_fl/"'));
      }
    } else if (t.value === "export") {
      const spec = exportSpecifier(tokens, i);
      if (spec && pathHasDotDotOrFl(spec.value)) {
        const pos = at(spec.index);
        out.push(issue("FL003", "error", path, pos.line, pos.col, 'path must not contain "..", "_deft/" or "_fl/"'));
      }
    }
  }
  afterJs(path, source, tokens, at, out, mode);
}

function importSpecifier(tokens, i) {
  const n = tokens[i + 1];
  if (!n) return null;
  if (n.type === "punct" && n.value === "(") {
    const s = tokens[i + 2];
    return s && s.type === "string" ? s : null;
  }
  if (n.type === "string") return n;
  return fromSpecifier(tokens, i);
}

function exportSpecifier(tokens, i) {
  return fromSpecifier(tokens, i);
}

function fromSpecifier(tokens, i) {
  const stop = Math.min(tokens.length, i + 60);
  for (let j = i + 1; j < stop; j++) {
    const t = tokens[j];
    if (t.type === "punct" && t.value === ";") return null;
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
  /** @type {("code"|"tmpl")[]} */
  const mode = ["code"];
  const exprDepth = [];

  const push = (type, value, index, extra) => {
    const token = { type, value, index, expr: exprDepth.length > 0 };
    if (extra) Object.assign(token, extra);
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
        if (source[i] === "`" || (source[i] === "$" && source[i + 1] === "{")) break;
        value += source[i++];
      }
      if (value) push("string", value, index, { tmpl: true });
      continue;
    }

    const c = source[i];
    if (c === " " || c === "\t" || c === "\n" || c === "\r" || c === "\f") {
      i++;
      continue;
    }
    if (c === "/" && source[i + 1] === "/") {
      i += 2;
      const body = i;
      while (i < n && source[i] !== "\n") i++;
      if (comments) comments.push({ body, text: source.slice(body, i) });
      continue;
    }
    if (c === "/" && source[i + 1] === "*") {
      i += 2;
      while (i < n && !(source[i] === "*" && source[i + 1] === "/")) i++;
      i = Math.min(n, i + 2);
      continue;
    }
    if (c === "'" || c === '"') {
      const index = i;
      const q = c;
      i++;
      let value = "";
      while (i < n && source[i] !== q && source[i] !== "\n") {
        if (source[i] === "\\") {
          value += source[i + 1] ?? "";
          i += 2;
          continue;
        }
        value += source[i++];
      }
      if (source[i] === q) i++;
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
      while (i < n && source[i] !== "/" && source[i] !== "\n") {
        if (source[i] === "\\") {
          i += 2;
          continue;
        }
        if (source[i] === "[") {
          i++;
          while (i < n && source[i] !== "]" && source[i] !== "\n") {
            if (source[i] === "\\") i += 2;
            else i++;
          }
          if (source[i] === "]") i++;
          continue;
        }
        i++;
      }
      if (source[i] === "/") i++;
      while (i < n && /[a-z]/i.test(source[i])) i++;
      push("regex", source.slice(index, i), index);
      continue;
    }
    if (c === "{") {
      if (exprDepth.length) exprDepth[exprDepth.length - 1]++;
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
      while (i < n && /[A-Za-z0-9_$]/.test(source[i])) i++;
      push("ident", source.slice(index, i), index);
      continue;
    }
    if (c === "." && /[0-9]/.test(source[i + 1] || "")) {
      const index = i;
      i++;
      while (i < n && /[0-9_]/.test(source[i])) i++;
      push("number", source.slice(index, i), index);
      continue;
    }
    if (/[0-9]/.test(c)) {
      const index = i;
      i++;
      while (i < n && /[0-9_]/.test(source[i])) i++;
      if (source[i] === "." && /[0-9]/.test(source[i + 1] || "")) {
        i++;
        while (i < n && /[0-9_]/.test(source[i])) i++;
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
  for (let j = openIdx; j < tokens.length; j++) {
    const t = tokens[j];
    if (t.type !== "punct") continue;
    if (t.value === "(") depth++;
    else if (t.value === ")") {
      depth--;
      if (depth === 0) return j;
    }
  }
  return -1;
}

function argsHaveHtmlTemplate(tokens, from, to) {
  for (let j = from; j < to; j++) {
    const t = tokens[j];
    if (t.type === "string" && t.tmpl && t.value.includes("<")) return true;
  }
  return false;
}

function maybeRegex(tokens) {
  const prev = tokens[tokens.length - 1];
  if (!prev) return true;
  if (prev.type === "ident") return REGEX_AFTER.has(prev.value);
  if (prev.type === "number" || prev.type === "string" || prev.type === "regex") return false;
  if (prev.type === "punct" && (prev.value === ")" || prev.value === "]" || prev.value === "}")) return false;
  return true;
}

/* -------------------------------- HTML --------------------------------- */

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
    while (i < n && /[A-Za-z0-9]/.test(source[i])) i++;
    const name = source.slice(nameStart, i).toLowerCase();
    if (nameStart === i) {
      i++;
      continue;
    }
    const attrs = [];
    while (i < n && source[i] !== ">") {
      if (source[i] === "/" && source[i + 1] === ">") break;
      if (/\s/.test(source[i])) {
        i++;
        continue;
      }
      if (source[i] === "/") {
        i++;
        continue;
      }
      const aStart = i;
      while (i < n && /[^\s=/>]/.test(source[i])) i++;
      const aname = source.slice(aStart, i).toLowerCase();
      if (!aname) {
        i++;
        continue;
      }
      let value = "";
      let valueIndex = aStart;
      let valueStart = aStart;
      while (i < n && /\s/.test(source[i])) i++;
      if (source[i] === "=") {
        i++;
        while (i < n && /\s/.test(source[i])) i++;
        valueIndex = i;
        valueStart = i;
        const q = source[i];
        if (q === '"' || q === "'") {
          i++;
          valueStart = i;
          while (i < n && source[i] !== q) i++;
          value = source.slice(valueStart, i);
          if (source[i] === q) i++;
        } else {
          const vStart = i;
          valueStart = vStart;
          while (i < n && /[^\s>]/.test(source[i])) i++;
          value = source.slice(vStart, i);
        }
      }
      attrs.push({ name: aname, value, index: aStart, valueIndex, valueStart });
    }
    if (source[i] === ">") i++;
    else if (source[i] === "/" && source[i + 1] === ">") i += 2;

    if (name === "script") {
      const src = attrs.find((a) => a.name === "src");
      const type = attrs.find((a) => a.name === "type");
      const typeVal = type ? type.value.trim().toLowerCase() : "";
      const problems = [];
      if (!src) problems.push("inline script without src");
      if (typeVal !== "module") problems.push("script without type=module");
      if (problems.length) {
        const pos = at(tagStart);
        out.push(issue("FL104", "error", path, pos.line, pos.col, problems.join("; ")));
      }
    }
    for (const attr of attrs) {
      if (attr.name === "style") {
        const pos = at(attr.index);
        out.push(issue("FL106", "error", path, pos.line, pos.col, "style attribute is not allowed"));
      }
      if (attr.name === "class" || attr.name === "classname") {
        scanBtnModifiers(path, attr.value, attr.valueStart, out, at);
      }
      if ((attr.name === "src" || attr.name === "href") && pathHasDotDotOrFl(attr.value)) {
        const pos = at(attr.valueIndex);
        out.push(issue("FL003", "error", path, pos.line, pos.col, 'path must not contain "..", "_deft/" or "_fl/"'));
      }
    }
  }
  afterHtml(path, source, out, mode);
}

/* --------------------------------- CSS --------------------------------- */

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
    out.push(issue("FL610", severity, path, pos.line, pos.col, "color literal; use a var(--deft-*) token"));
  };

  while (i < n) {
    if (source.startsWith("/*", i)) {
      const end = source.indexOf("*/", i + 2);
      const commentEnd = end < 0 ? n : end;
      const text = source.slice(i + 2, commentEnd);
      if (allows) noteAllow(allows, path, i + 2, text, "css", at);
      if (!text.includes("var(--deft-")) scanColorSpan(source, i + 2, commentEnd, reportColor);
      i = end < 0 ? n : end + 2;
      continue;
    }
    if (isCssUrl(source, i)) {
      const url = readCssUrl(source, i);
      if (url && pathHasDotDotOrFl(url.value)) {
        const pos = at(url.valueIndex);
        out.push(issue("FL003", "error", path, pos.line, pos.col, 'path must not contain "..", "_deft/" or "_fl/"'));
      }
      i = url ? url.end : i + 3;
      continue;
    }
    if (isCssImport(source, i)) {
      const imported = readImportPath(source, i);
      if (imported && pathHasDotDotOrFl(imported.value)) {
        const pos = at(imported.valueIndex);
        out.push(issue("FL003", "error", path, pos.line, pos.col, 'path must not contain "..", "_deft/" or "_fl/"'));
      }
      i = imported ? imported.end : i + 7;
      continue;
    }
    const q = source[i];
    if (q === '"' || q === "'") {
      i++;
      while (i < n && source[i] !== q && source[i] !== "\n") {
        if (source[i] === "\\") i += 2;
        else i++;
      }
      if (source[i] === q) i++;
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
      if (valueDepth >= 0 && paren < valueDepth) valueDepth = -1;
      i++;
      continue;
    }
    if (q === ";") {
      if (valueDepth >= 0 && paren <= valueDepth) valueDepth = -1;
      i++;
      continue;
    }
    if (q === ":") {
      if (valueDepth < 0) valueDepth = paren;
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
  for (let i = from; i < to; i++) {
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
  if (source[i] !== "#") return 0;
  let j = i + 1;
  while (j < source.length && /[0-9a-fA-F]/.test(source[j])) j++;
  const digits = j - (i + 1);
  if (digits === 3 || digits === 4 || digits === 6 || digits === 8) return digits + 1;
  return 0;
}

function rgbLen(source, i) {
  if (i > 0 && /[A-Za-z0-9_-]/.test(source[i - 1])) return 0;
  if (source.slice(i, i + 3).toLowerCase() !== "rgb") return 0;
  let j = i + 3;
  if (source[j] === "a" || source[j] === "A") j++;
  let k = j;
  while (k < source.length && /\s/.test(source[k])) k++;
  if (source[k] !== "(") return 0;
  return k - i + 1;
}

function isIdentChar(c) {
  return !!c && /[A-Za-z0-9_-]/.test(c);
}

function isCssUrl(source, i) {
  if (isIdentChar(source[i - 1])) return false;
  return source.slice(i, i + 3).toLowerCase() === "url";
}

function isCssImport(source, i) {
  if (isIdentChar(source[i - 1])) return false;
  return source.slice(i, i + 7).toLowerCase() === "@import";
}

function readCssUrl(source, i) {
  let j = i + 3;
  while (j < source.length && /\s/.test(source[j])) j++;
  if (source[j] !== "(") return null;
  j++;
  while (j < source.length && /\s/.test(source[j])) j++;
  let quote = "";
  if (source[j] === '"' || source[j] === "'") quote = source[j++];
  const valueIndex = j;
  let value = "";
  while (j < source.length) {
    if (quote && source[j] === quote) {
      j++;
      break;
    }
    if (!quote && (source[j] === ")" || /\s/.test(source[j]))) break;
    if (source[j] === "\\" && j + 1 < source.length) {
      value += source[j + 1];
      j += 2;
      continue;
    }
    if (source[j] === "\n") break;
    value += source[j++];
  }
  while (j < source.length && source[j] !== ")") j++;
  if (source[j] === ")") j++;
  return { value, valueIndex, end: j };
}

function readImportPath(source, i) {
  let j = i + 7;
  while (j < source.length && /\s/.test(source[j])) j++;
  if (source.slice(j, j + 3).toLowerCase() === "url") return readCssUrl(source, j);
  if (source[j] === '"' || source[j] === "'") {
    const quote = source[j++];
    const valueIndex = j;
    let value = "";
    while (j < source.length && source[j] !== quote && source[j] !== "\n") {
      if (source[j] === "\\") {
        value += source[j + 1] ?? "";
        j += 2;
        continue;
      }
      value += source[j++];
    }
    if (source[j] === quote) j++;
    while (j < source.length && /\s/.test(source[j])) j++;
    if (source[j] === ";") j++;
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
      while (i < n && source[i] !== q && source[i] !== "\n") {
        if (source[i] === "\\") i += 2;
        else i++;
      }
      if (source[i] === q) i++;
      continue;
    }
    if (q === "{") {
      brace++;
      i++;
      continue;
    }
    if (q === "}") {
      if (brace > 0) brace--;
      i++;
      continue;
    }
    if (brace > 0 && (q === "-" || /[A-Za-z_]/.test(q))) {
      const nameStart = i;
      i++;
      while (i < n && /[A-Za-z0-9_-]/.test(source[i])) i++;
      const name = source.slice(nameStart, i).toLowerCase();
      let j = i;
      while (j < n && /\s/.test(source[j])) j++;
      if (source[j] !== ":") continue;
      j++;
      while (j < n && /\s/.test(source[j])) j++;
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
          while (j < n && source[j] !== c && source[j] !== "\n") {
            if (source[j] === "\\") j += 2;
            else j++;
          }
          if (source[j] === c) j++;
          continue;
        }
        if (c === "(") paren++;
        else if (c === ")" && paren > 0) paren--;
        else if (paren === 0 && (c === ";" || c === "}")) break;
        j++;
      }
      reportCssDecl(path, name, source.slice(valueStart, j), nameStart, out, at, focusVisible);
      i = j;
      continue;
    }
    i++;
  }
}

// Documented component hook properties (references/components.md): an app may set these on a component.
const HOOK_PROPS = new Set([
  "--deft-min", "--deft-gap", "--deft-density", "--deft-side", "--deft-measure", "--deft-cols",
  "--deft-pad", "--deft-radius-outer", "--deft-value", "--deft-segs", "--deft-level",
]);

function reportCssDecl(path, name, rawValue, nameStart, out, at, focusVisible) {
  const pos = at(nameStart);
  if (name.startsWith("--deft-") && !HOOK_PROPS.has(name)) {
    out.push(issue("FL614", "warn", path, pos.line, pos.col, "redefines a --deft-* token; define a private token"));
  }
  if (name === "font-family" && !fontFamilyOk(rawValue)) {
    out.push(issue("FL611", "warn", path, pos.line, pos.col, "font-family literal; use var(--deft-font-ui)"));
  }
  if (name === "font-size" && /(?:^|[\s,(])\d*\.?\d+px\b/i.test(rawValue)) {
    out.push(issue("FL612", "info", path, pos.line, pos.col, "px font size; use a type class (.deft-body, .deft-footnote...) or rem"));
  }
  if ((name === "border-radius" || (name.startsWith("border-") && name.endsWith("-radius"))) && !radiusOk(rawValue)) {
    out.push(issue("FL613", "warn", path, pos.line, pos.col, "border-radius literal; use var(--deft-radius-*)"));
  }
  if (name === "outline" && outlineNone(rawValue) && !focusVisible) {
    out.push(issue("FL615", "warn", path, pos.line, pos.col, "outline: none without a :focus-visible style"));
  }
}

function cssKeyword(value) {
  return /^(inherit|initial|unset|revert|revert-layer)$/i.test(value.trim());
}

function fontFamilyOk(raw) {
  const value = raw.replace(/!important/gi, "").trim();
  if (!value || cssKeyword(value)) return true;
  return /^var\(\s*--deft-font-[a-z0-9-]+\s*\)$/i.test(value);
}

function radiusOk(raw) {
  const value = raw.replace(/!important/gi, "").trim();
  if (!value || cssKeyword(value)) return true;
  // var(--deft-radius-*) and 0 (also 0px), alone or in a shorthand; "/" separates the vertical radii.
  const parts = value.split(/\s+/);
  return parts.every((part) => part === "/" || /^0(?:px|rem)?$/.test(part) || /^var\(\s*--deft-radius-[a-z0-9-]+\s*\)$/i.test(part));
}

function outlineNone(raw) {
  const value = raw.replace(/!important/gi, "").trim().toLowerCase();
  return value === "none" || value === "0" || value === "0px";
}

/* ----------------------------- palette.json ---------------------------- */

function scanPalette(path, source, mode, out) {
  let data;
  try {
    data = JSON.parse(source);
  } catch {
    out.push(issue("FL720", "error", path, 1, 1, "palette.json is not valid JSON"));
    return;
  }
  if (!data || typeof data !== "object" || Array.isArray(data)) {
    out.push(issue("FL720", "error", path, 1, 1, "palette.json must be an object"));
    return;
  }
  const severity = mode === "local" ? "warn" : "error";
  const at = locator(source);
  const modes = Array.isArray(data.modes) ? data.modes.filter((name) => typeof name === "string") : [];
  const names = modes.length ? modes : ["light", "dark"].filter((name) => data[name] && typeof data[name] === "object");
  for (const name of names) {
    const colors = data[name];
    if (!colors || typeof colors !== "object" || Array.isArray(colors)) continue;
    const required = ["base-100", "base-200", "base-300", "base-content"];
    for (const role of PALETTE_ROLES) required.push(role, `${role}-content`);
    for (const key of required) {
      if (typeof colors[key] !== "string" || !colors[key].trim()) {
        const regionForKey = objectAfterKey(source, name);
        const keyAt = keyInRegion(source, regionForKey, key);
        const missingAt = at(keyAt);
        out.push(issue("FL720", "error", path, missingAt.line, missingAt.col, `palette missing ${key}`));
      }
    }
    const region = objectAfterKey(source, name);
    const pairs = [];
    for (const role of PALETTE_ROLES) pairs.push([role, `${role}-content`]);
    for (const base of PALETTE_BASES) pairs.push(["base-content", base]);
    for (const [fg, bg] of pairs) {
      if (typeof colors[fg] !== "string" || typeof colors[bg] !== "string") continue;
      const left = colorLuminance(colors[fg]);
      const right = colorLuminance(colors[bg]);
      if (left == null || right == null) continue;
      const ratio = contrastRatio(left, right);
      if (ratio >= 4.5) continue;
      const keyAt = keyInRegion(source, region, bg);
      const pos = at(keyAt);
      out.push(issue(
        "FL721",
        severity,
        path,
        pos.line,
        pos.col,
        `${name} ${fg} / ${bg} contrast ${ratio.toFixed(2)} < 4.5`,
      ));
    }
  }
}

function objectAfterKey(source, key) {
  const needle = `"${key}"`;
  const at = source.indexOf(needle);
  if (at < 0) return null;
  const brace = source.indexOf("{", at + needle.length);
  if (brace < 0) return null;
  let depth = 0;
  for (let i = brace; i < source.length; i++) {
    if (source[i] === "{") depth++;
    else if (source[i] === "}") {
      depth--;
      if (depth === 0) return { start: brace, end: i };
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
  if (at < 0 || at > region.end) return region.start;
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
  if (!oklch) return null;
  let L = Number(oklch[1]);
  if (oklch[2] || L > 1) L /= 100;
  const C = Number(oklch[3]);
  const H = Number(oklch[4]);
  if (!Number.isFinite(L) || !Number.isFinite(C) || !Number.isFinite(H)) return null;
  return luminanceLinear(oklchToLinear(L, C, H));
}

function srgbChannel(c) {
  return c <= 0.04045 ? c / 12.92 : ((c + 0.055) / 1.055) ** 2.4;
}

function luminanceLinear(rgb) {
  return 0.2126 * rgb[0] + 0.7152 * rgb[1] + 0.0722 * rgb[2];
}

function oklchToLinear(L, C, Hdeg) {
  const h = (Hdeg * Math.PI) / 180;
  const a = C * Math.cos(h);
  const b = C * Math.sin(h);
  const l_ = L + 0.3963377774 * a + 0.2158037573 * b;
  const m_ = L - 0.1055613458 * a - 0.0638541728 * b;
  const s_ = L - 0.0894841775 * a - 1.2914855480 * b;
  const l = l_ ** 3;
  const m = m_ ** 3;
  const s = s_ ** 3;
  const r = 4.0767416621 * l - 3.3077115913 * m + 0.2309699292 * s;
  const g = -1.2684380046 * l + 2.6097574011 * m - 0.3413193965 * s;
  const bl = -0.0041960863 * l - 0.7034186147 * m + 1.7076147010 * s;
  const clamp = (x) => (x < 0 ? 0 : x > 1 ? 1 : x);
  return [clamp(r), clamp(g), clamp(bl)];
}

/* ------------------------------ Manifest ------------------------------- */

function dependencyNames(source) {
  const none = { urls: [], aliases: [] };
  if (typeof source !== "string") return none;
  try {
    const deps = JSON.parse(source).dependencies;
    if (!deps || typeof deps !== "object" || Array.isArray(deps)) return none;
    const aliases = Object.values(deps)
      .map((spec) => (spec && typeof spec === "object" ? spec.as : undefined))
      .filter((name) => typeof name === "string");
    return { urls: Object.keys(deps), aliases };
  } catch {
    return none;
  }
}

const DEP_URL = /^https:\/\/[a-z0-9.-]+\.[a-z]{2,}(?::[0-9]{1,5})?\/[A-Za-z0-9@._~+/-]+$/;
const DEP_ALIAS = /^(@[a-z0-9-~][a-z0-9-._~]*\/)?[a-z0-9-~][a-z0-9-._~]*$/;
const SRI = /^sha(256|384|512)-[A-Za-z0-9+/]+={0,2}$/;
const HAS_VERSION = /@v?\d+\.\d+|\/v?\d+\.\d+(\.\d+)?\//;

function scanDependencies(path, source, data, canvas, mode, at, out) {
  if (data.dependencies == null) return;
  const pos = at(findKey(source, "dependencies"));
  const deps = data.dependencies;
  if (typeof deps !== "object" || Array.isArray(deps)) {
    out.push(issue("FL710", "error", path, pos.line, pos.col, "dependencies must be an object of https URL -> SRI hash"));
    return;
  }
  if (canvas && Object.keys(deps).length) {
    out.push(issue("FL706", "error", path, pos.line, pos.col, "canvas profile cannot declare dependencies"));
  }
  for (const [url, spec] of Object.entries(deps)) {
    const where = at(findKey(source, url));
    if (url.length > 2048 || !DEP_URL.test(url) || url.includes("..") || url.indexOf("//", 8) >= 0) {
      out.push(issue("FL710", "error", path, where.line, where.col, `dependency "${url}" must be an https file URL without query or fragment`));
      continue;
    }
    const last = url.slice(url.lastIndexOf("/") + 1);
    if (/\.[a-z]+$/i.test(last) && !/\.(m?js|json|wasm)$/i.test(last)) {
      out.push(issue("FL710", "error", path, where.line, where.col, `dependency ${url} must be a JavaScript module (.js, .mjs), .json or .wasm`));
      continue;
    }
    const long = spec && typeof spec === "object";
    const integrity = long ? spec.integrity : spec;
    // PARITY G2: a WebAssembly dependency is served only once its bytes are pinned by hash.
    if (/\.wasm$/i.test(last) && integrity == null) {
      out.push(issue("FL710", "error", path, where.line, where.col, `WebAssembly dependency ${url} must be hash-pinned`));
    }
    if (integrity != null && (typeof integrity !== "string" || !SRI.test(integrity))) {
      out.push(issue("FL710", "error", path, where.line, where.col, `dependency ${url} hash must be sha256-, sha384- or sha512-<base64>`));
    } else if (integrity == null && mode === "publish") {
      out.push(issue("FL710", "error", path, where.line, where.col, `dependency ${url} needs a hash to publish`));
    }
    if (long && spec.as != null && (typeof spec.as !== "string" || !DEP_ALIAS.test(spec.as))) {
      out.push(issue("FL710", "error", path, where.line, where.col, `dependency alias "${spec.as}" must be a bare package name`));
    }
    if (!HAS_VERSION.test(url)) {
      out.push(issue("FL711", "warn", path, where.line, where.col, `dependency ${url} has no version in its URL; pin one (name@1.2.3)`));
    }
  }
}

function scanManifest(path, source, profile, catalog, out, mode) {
  let data;
  try {
    data = JSON.parse(source);
  } catch {
    out.push(issue("FL701", "error", path, 1, 1, "manifest.json is not valid JSON"));
    return;
  }
  const at = locator(source);
  if (!data || typeof data !== "object" || Array.isArray(data)) {
    for (const key of REQUIRED_MANIFEST) {
      out.push(issue("FL701", "error", path, 1, 1, `manifest.json missing ${key}`));
    }
    return;
  }
  for (const key of REQUIRED_MANIFEST) {
    // "fl" is the pre-rename key; packages made before the rename still install (DeftManifestParser accepts it too).
    if (key === "deft" && !fieldMissing(data, "fl")) continue;
    if (fieldMissing(data, key)) {
      out.push(issue("FL701", "error", path, 1, 1, `manifest.json missing ${key}`));
    }
  }
  if (typeof data.start_url === "string" && pathHasDotDotOrFl(data.start_url)) {
    const pos = at(source.indexOf(data.start_url) < 0 ? 0 : source.indexOf(data.start_url));
    out.push(issue("FL003", "error", path, pos.line, pos.col, 'path must not contain "..", "_deft/" or "_fl/"'));
  }

  const canvas = profile === "canvas" || data.profile === "canvas";
  if (canvas && nonEmpty(data.capabilities)) {
    const pos = at(findKey(source, "capabilities"));
    out.push(issue("FL706", "error", path, pos.line, pos.col, "canvas profile cannot declare capabilities"));
  }
  if (canvas && nonEmpty(data.network)) {
    const pos = at(findKey(source, "network"));
    out.push(issue("FL706", "error", path, pos.line, pos.col, "canvas profile cannot declare network"));
  }
  if (canvas && nonEmpty(data.embeds)) {
    const pos = at(findKey(source, "embeds"));
    out.push(issue("FL706", "error", path, pos.line, pos.col, "canvas profile cannot declare embeds"));
  }
  scanEmbeds(path, source, data, at, out);
  if (canvas && data.background != null) {
    const pos = at(findKey(source, "background"));
    out.push(issue("FL706", "error", path, pos.line, pos.col, "canvas profile cannot declare background"));
  }
  scanBackground(path, source, data, at, out);
  if (canvas && data.handles != null) {
    const pos = at(findKey(source, "handles"));
    out.push(issue("FL706", "error", path, pos.line, pos.col, "canvas profile cannot declare handles"));
  }
  scanHandles(path, source, data, at, out);
  if (canvas && data.wallpaper != null) {
    const pos = at(findKey(source, "wallpaper"));
    out.push(issue("FL706", "error", path, pos.line, pos.col, "canvas profile cannot declare wallpaper"));
  }
  scanWallpaper(path, source, data, at, out);
  if (canvas && data.keyboard != null) {
    const pos = at(findKey(source, "keyboard"));
    out.push(issue("FL706", "error", path, pos.line, pos.col, "canvas profile cannot declare keyboard"));
  }
  scanKeyboard(path, source, data, at, out);
  if (canvas && data.widget != null) {
    const pos = at(findKey(source, "widget"));
    out.push(issue("FL706", "error", path, pos.line, pos.col, "canvas profile cannot declare widget"));
  }
  scanWidget(path, source, data, at, out);
  if (canvas && data.tools != null) {
    const pos = at(findKey(source, "tools"));
    out.push(issue("FL706", "error", path, pos.line, pos.col, "canvas profile cannot declare tools"));
  }
  scanTools(path, source, data, at, out);

  scanDependencies(path, source, data, canvas, mode, at, out);

  if (fieldMissing(data, "version")) {
    out.push(issue("FL703", "error", path, 1, 1, "manifest.json missing version"));
  } else if (typeof data.version !== "string" || !/^\d+\.\d+\.\d+(?:[-+][0-9A-Za-z.-]+)?$/.test(data.version)) {
    const pos = at(findKey(source, "version"));
    out.push(issue("FL703", "error", path, pos.line, pos.col, "version must be semver"));
  }
  if (Array.isArray(data.network)) {
    for (const entry of data.network) {
      if (validNetworkOrigin(entry)) continue;
      const shown = typeof entry === "string" ? entry : "";
      const index = shown ? source.indexOf(shown) : findKey(source, "network");
      const pos = at(index < 0 ? 0 : index);
      out.push(issue("FL704", "error", path, pos.line, pos.col, `invalid network entry "${shown}"`));
    }
  } else if (data.network != null) {
    const pos = at(findKey(source, "network"));
    out.push(issue("FL704", "error", path, pos.line, pos.col, "network must be an array of https origins"));
  }
  if (Array.isArray(data.capabilities)) {
    for (const entry of data.capabilities) {
      const reason = typeof entry === "string" ? "" : entry && typeof entry === "object" ? entry.reason : "";
      if (typeof reason === "string" && reason.length >= 10 && reason.length <= 140) continue;
      const pos = at(findKey(source, "capabilities"));
      out.push(issue("FL708", "error", path, pos.line, pos.col, "capability reason must be 10–140 characters"));
    }
  }
  const knownFields = new Set([
    "deft", "fl", "id", "name", "version", "description", "profile", "start_url", "icon",
    "license", "source", "authors", "capabilities", "network", "categories",
    "forked_from", "min_host", "large", "kind", "dependencies", "shortcuts", "embeds", "background",
    "handles", "wallpaper", "keyboard", "widget", "tools",
  ]);
  // PARITY C3: launcher shortcuts. Keep identical to DeftManifestParser.parseShortcuts (Kotlin).
  if (data.shortcuts != null) {
    const list = Array.isArray(data.shortcuts) ? data.shortcuts : null;
    const pos = at(findKey(source, "shortcuts"));
    if (!list || list.length > 8) {
      out.push(issue("FL708", "error", path, pos.line, pos.col, "shortcuts must be an array of at most 8 {id, name, url}"));
    } else {
      for (const sc of list) {
        if (!validShortcut(sc)) {
          out.push(issue("FL708", "error", path, pos.line, pos.col, `invalid shortcut ${JSON.stringify(sc && sc.id)}: id [a-z0-9-], name 1-30 chars, url a same-origin path like "./#new"`));
        }
      }
    }
  }
  for (const key of Object.keys(data)) {
    if (knownFields.has(key)) continue;
    const pos = at(findKey(source, key));
    out.push(issue("FL705", "warn", path, pos.line, pos.col, `unknown manifest field "${key}"`));
  }

  if (catalog != null && Array.isArray(data.capabilities)) {
    const known = capabilitySet(catalog);
    for (const entry of data.capabilities) {
      const id = typeof entry === "string" ? entry : entry && typeof entry === "object" ? entry.id : undefined;
      if (typeof id === "string" && known.has(id)) continue;
      const shown = typeof id === "string" ? id : "";
      const index = shown ? source.indexOf(shown) : findKey(source, "capabilities");
      const pos = at(index < 0 ? 0 : index);
      out.push(issue("FL702", "error", path, pos.line, pos.col, `unknown capability "${shown}"`));
    }
  }
}

function validShortcut(sc) {
  if (!sc || typeof sc !== "object") return false;
  if (typeof sc.id !== "string" || !/^[a-z0-9-]{1,32}$/.test(sc.id)) return false;
  if (typeof sc.name !== "string" || sc.name.trim().length < 1 || sc.name.length > 30) return false;
  return validShortcutUrl(sc.url);
}

function validShortcutUrl(url) {
  if (typeof url !== "string" || url.length > 512) return false;
  if (!(url.startsWith("./") || url.startsWith("/"))) return false;
  if (url.startsWith("//") || url.includes("..") || url.includes("\\") || /[\s]/.test(url)) return false;
  return !reservedPath(url);
}

// PARITY D1: manifest `background` {module, triggers}. Keep identical to
// DeftManifestParser.parseBackground / validBackgroundModule / validBackgroundTrigger (Kotlin).
const BACKGROUND_TRIGGERS = new Set(["alarms", "sms", "calls", "notify.action", "boot", "network", "charging", "push", "geofence", "sync", "notification", "screening"]);

function scanBackground(path, source, data, at, out) {
  if (data.background == null) return;
  const pos = at(findKey(source, "background"));
  const bg = data.background;
  if (typeof bg !== "object" || Array.isArray(bg)) {
    out.push(issue("FL713", "error", path, pos.line, pos.col, "background must be {module, triggers}"));
    return;
  }
  if (!validBackgroundModule(bg.module)) {
    out.push(issue("FL713", "error", path, pos.line, pos.col,
      'background.module must be a same-origin .js or .mjs path like "./bg.js" (no "..", "//", "/_deft/" or "/_fl/")'));
  }
  if (!Array.isArray(bg.triggers)) {
    out.push(issue("FL713", "error", path, pos.line, pos.col, "background.triggers must be an array"));
  } else {
    for (const entry of bg.triggers) {
      if (validBackgroundTrigger(entry)) continue;
      out.push(issue("FL713", "error", path, pos.line, pos.col,
        `unknown background trigger ${JSON.stringify(entry)}: one of ${[...BACKGROUND_TRIGGERS].join(", ")} or "periodic:N" with N from 15 to 10080 minutes`));
    }
    const periodic = new Set(bg.triggers.filter((t) => typeof t === "string" && t.startsWith("periodic:")));
    if (periodic.size > 1) {
      out.push(issue("FL713", "error", path, pos.line, pos.col, "background.triggers may hold one periodic entry"));
    }
  }
  const declared = Array.isArray(data.capabilities) && data.capabilities.some((entry) =>
    (typeof entry === "string" ? entry : entry && entry.id) === "background");
  if (!declared) {
    out.push(issue("FL713", "error", path, pos.line, pos.col, "background needs the background capability"));
  }
}

function validBackgroundModule(value) {
  return normalizeModulePath(value) !== null;
}

function validBackgroundTrigger(value) {
  if (typeof value !== "string") return false;
  if (BACKGROUND_TRIGGERS.has(value)) return true;
  const m = /^periodic:([0-9]{1,5})$/.exec(value);
  if (!m) return false;
  const minutes = Number(m[1]);
  return minutes >= 15 && minutes <= 10080;
}

// PARITY G11: manifest `wallpaper` {page}, the page the launcher's live wallpaper shows. Keep identical to
// DeftManifestParser.parseWallpaper / validWallpaperPage (Kotlin).
function scanWallpaper(path, source, data, at, out) {
  if (data.wallpaper == null) return;
  const pos = at(findKey(source, "wallpaper"));
  const wp = data.wallpaper;
  if (typeof wp !== "object" || Array.isArray(wp)) {
    out.push(issue("FL715", "error", path, pos.line, pos.col, "wallpaper must be {page}"));
    return;
  }
  if (!validWallpaperPage(wp.page)) {
    out.push(issue("FL715", "error", path, pos.line, pos.col,
      'wallpaper.page must be a same-origin .html path like "./wallpaper.html" (no "..", "//", "/_deft/" or "/_fl/")'));
  }
  const declared = Array.isArray(data.capabilities) && data.capabilities.some((entry) =>
    (typeof entry === "string" ? entry : entry && entry.id) === "wallpaper");
  if (!declared) {
    out.push(issue("FL715", "error", path, pos.line, pos.col, "wallpaper needs the wallpaper capability"));
  }
}

// PARITY G11: manifest `keyboard` {page}, the page the launcher's keyboard (role `ime`) shows. Same path rule
// as wallpaper.page. Keep identical to DeftManifestParser.parseKeyboard (Kotlin).
function scanKeyboard(path, source, data, at, out) {
  if (data.keyboard == null) return;
  const pos = at(findKey(source, "keyboard"));
  const kb = data.keyboard;
  if (typeof kb !== "object" || Array.isArray(kb)) {
    out.push(issue("FL716", "error", path, pos.line, pos.col, "keyboard must be {page}"));
    return;
  }
  if (!validWallpaperPage(kb.page)) {
    out.push(issue("FL716", "error", path, pos.line, pos.col,
      'keyboard.page must be a same-origin .html path like "./keyboard/index.html" (no "..", "//", "/_deft/" or "/_fl/")'));
  }
  const declared = Array.isArray(data.capabilities) && data.capabilities.some((entry) =>
    (typeof entry === "string" ? entry : entry && entry.id) === "ime");
  if (!declared) {
    out.push(issue("FL716", "error", path, pos.line, pos.col, "keyboard needs the ime capability"));
  }
}

// W1: manifest `widget` {page, sizes}, the home-canvas widget page. Keep identical to
// DeftManifestParser.parseWidget / WIDGET_SIZES (Kotlin).
const WIDGET_SIZES = new Set(["1x1", "2x1", "2x2", "4x1", "4x2"]);

function scanWidget(path, source, data, at, out) {
  if (data.widget == null) return;
  const pos = at(findKey(source, "widget"));
  const wg = data.widget;
  if (typeof wg !== "object" || Array.isArray(wg)) {
    out.push(issue("FL717", "error", path, pos.line, pos.col, "widget must be {page, sizes}"));
    return;
  }
  if (!validWallpaperPage(wg.page)) {
    out.push(issue("FL717", "error", path, pos.line, pos.col,
      'widget.page must be a same-origin .html path like "./widget.html" (no "..", "//", "/_deft/" or "/_fl/")'));
  }
  if (!Array.isArray(wg.sizes) || wg.sizes.length === 0) {
    out.push(issue("FL717", "error", path, pos.line, pos.col, "widget.sizes must be a non-empty array"));
    return;
  }
  for (const size of wg.sizes) {
    if (WIDGET_SIZES.has(size)) continue;
    out.push(issue("FL717", "error", path, pos.line, pos.col,
      `unknown widget size ${JSON.stringify(size)}: one of ${[...WIDGET_SIZES].join(", ")}`));
  }
}

// W5: manifest `tools` [{name, description, params, handler}], agent tools. Keep identical to
// DeftToolsParser (registry/DeftTools.kt): names, description length, the small JSON-schema subset, handler refs.
// The handler files themselves (isolate scripts must be plain scripts) are checked in extra.js flagToolFiles.
const MAX_TOOLS_PER_APP = 8;
const MAX_TOOL_DESCRIPTION = 300;
const MAX_TOOL_PROPERTIES = 12;
const MAX_TOOL_SCHEMA_BYTES = 2048;
const MAX_TOOL_ENUM = 24;
const TOOL_PRIMITIVES = new Set(["string", "number", "integer", "boolean"]);
const TOOL_PROP_KEYS = new Set(["type", "description", "enum", "items", "minimum", "maximum", "maxLength"]);

export function parseToolHandler(value) {
  if (typeof value !== "string") return null;
  const at = value.indexOf("#");
  if (at <= 0) return null;
  const path = value.slice(0, at);
  const fn = value.slice(at + 1);
  const normalized = normalizeModulePath(path);
  if (normalized === null || !/^[A-Za-z_$][A-Za-z0-9_$]{0,63}$/.test(fn)) return null;
  return { path: normalized, fn };
}

function toolMatchesType(type, v) {
  if (type === "string") return typeof v === "string";
  if (type === "boolean") return typeof v === "boolean";
  if (type === "number") return typeof v === "number" && Number.isFinite(v);
  if (type === "integer") return Number.isInteger(v);
  return false;
}

function toolSchemaError(schema) {
  if (JSON.stringify(schema).length > MAX_TOOL_SCHEMA_BYTES) return `schema is larger than ${MAX_TOOL_SCHEMA_BYTES} bytes`;
  if (schema === null || typeof schema !== "object" || Array.isArray(schema)) return "params must be a JSON schema object";
  for (const key of Object.keys(schema)) {
    if (!["type", "properties", "required", "description"].includes(key)) return `unsupported schema keyword "${key}"`;
  }
  if (schema.type !== "object") return 'type must be "object"';
  const props = schema.properties == null ? {} : schema.properties;
  if (typeof props !== "object" || Array.isArray(props)) return "properties must be an object";
  if (Object.keys(props).length > MAX_TOOL_PROPERTIES) return `at most ${MAX_TOOL_PROPERTIES} properties`;
  for (const [name, prop] of Object.entries(props)) {
    if (!/^[A-Za-z_][A-Za-z0-9_]{0,31}$/.test(name)) return `property name "${name}" must match [A-Za-z_][A-Za-z0-9_]{0,31}`;
    if (prop === null || typeof prop !== "object" || Array.isArray(prop)) return `property "${name}" must be an object`;
    for (const key of Object.keys(prop)) if (!TOOL_PROP_KEYS.has(key)) return `property "${name}": unsupported keyword "${key}"`;
    const type = prop.type;
    if (typeof type !== "string") return `property "${name}" needs a type`;
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
      if (!TOOL_PRIMITIVES.has(type)) return `property "${name}": enum only on string, number, integer or boolean`;
      if (!Array.isArray(prop.enum) || prop.enum.length === 0 || prop.enum.length > MAX_TOOL_ENUM) return `property "${name}": enum needs 1-${MAX_TOOL_ENUM} values`;
      for (const v of prop.enum) if (!toolMatchesType(type, v)) return `property "${name}": enum value ${JSON.stringify(v)} is not a ${type}`;
    }
    for (const bound of ["minimum", "maximum"]) {
      if (prop[bound] === undefined) continue;
      if (type !== "number" && type !== "integer") return `property "${name}": ${bound} only on number or integer`;
      if (typeof prop[bound] !== "number") return `property "${name}": ${bound} must be a number`;
    }
    if (prop.maxLength !== undefined) {
      if (type !== "string") return `property "${name}": maxLength only on string`;
      if (!Number.isInteger(prop.maxLength) || prop.maxLength < 1) return `property "${name}": maxLength must be a positive integer`;
    }
  }
  if (schema.required !== undefined) {
    if (!Array.isArray(schema.required)) return "required must be an array";
    for (const key of schema.required) {
      if (typeof key !== "string" || !Object.prototype.hasOwnProperty.call(props, key)) return `required names an unknown property ${JSON.stringify(key)}`;
    }
  }
  return null;
}

function scanTools(path, source, data, at, out) {
  if (data.tools == null) return;
  const pos = at(findKey(source, "tools"));
  const bad = (message) => out.push(issue("FL718", "error", path, pos.line, pos.col, message));
  if (!Array.isArray(data.tools)) return bad("tools must be an array of {name, description, params, handler}");
  if (data.tools.length > MAX_TOOLS_PER_APP) bad(`at most ${MAX_TOOLS_PER_APP} tools per app`);
  const seen = new Set();
  for (const tool of data.tools) {
    if (tool === null || typeof tool !== "object" || Array.isArray(tool)) {
      bad("each tool must be {name, description, params, handler}");
      continue;
    }
    const name = tool.name;
    if (typeof name !== "string" || !/^[a-z][a-z0-9_]{0,31}$/.test(name)) bad(`tool name ${JSON.stringify(name)} must match [a-z][a-z0-9_]{0,31}`);
    else if (seen.has(name)) bad(`duplicate tool "${name}"`);
    else seen.add(name);
    const label = typeof name === "string" ? name : "?";
    const d = typeof tool.description === "string" ? tool.description.trim() : "";
    if (d.length === 0 || d.length > MAX_TOOL_DESCRIPTION) bad(`tool "${label}" description must be 1-${MAX_TOOL_DESCRIPTION} characters`);
    const err = tool.params === undefined ? null : toolSchemaError(tool.params);
    if (err) bad(`tool "${label}" params: ${err}`);
    const h = tool.handler;
    let ref = null;
    if (typeof h === "string") ref = h;
    else if (h && typeof h === "object" && !Array.isArray(h) && Object.keys(h).join() === "isolate") ref = h.isolate;
    else {
      bad(`tool "${label}" handler must be "./tools.js#fn" or {"isolate": "./tools.js#fn"}`);
      continue;
    }
    if (!parseToolHandler(ref)) bad(`tool "${label}" handler must be a same-origin .js path and a function, like ./tools.js#run`);
  }
}

function validWallpaperPage(value) {
  if (typeof value !== "string" || value.length > 256) return false;
  if (!/^\.?\/[A-Za-z0-9._~\/-]+\.html?$/.test(value)) return false;
  if (value.includes("..") || value.includes("//")) return false;
  const absolute = value.startsWith(".") ? value.slice(1) : value;
  return !reservedPath(absolute);
}

// PARITY E1: manifest `handles` {mimeTypes?, schemes?, links?} for "Open with" from other apps.
// Keep identical to DeftManifestParser.parseHandles / validHandleMime / validHandleScheme (Kotlin).
const MAX_HANDLES = 16;
// Schemes and links the launcher's fixed Android filters actually deliver.
// web+fl is the pre-rename scheme; the launcher still has an alias for it.
const LIVE_SCHEMES = new Set(["web+deft", "web+fl", "mailto"]);

function validHandleMime(value) {
  return typeof value === "string" && /^[a-z0-9][a-z0-9.+-]{0,62}\/(\*|[a-z0-9][a-z0-9.+-]{0,126})$/.test(value);
}

function validHandleScheme(value) {
  return typeof value === "string" && (value === "mailto" || /^web\+[a-z]{1,32}$/.test(value));
}

function scanHandles(path, source, data, at, out) {
  if (data.handles == null) return;
  const pos = at(findKey(source, "handles"));
  const h = data.handles;
  if (typeof h !== "object" || Array.isArray(h)) {
    out.push(issue("FL714", "error", path, pos.line, pos.col, "handles must be {mimeTypes?, schemes?, links?}"));
    return;
  }
  const rules = [
    ["mimeTypes", validHandleMime, "type/subtype or type/*, lowercase, not */*"],
    ["schemes", validHandleScheme, "web+name (lowercase letters) or mailto"],
    ["links", (v) => validNetworkOrigin(v) && v === v.toLowerCase(), "an https origin like https://example.com"],
  ];
  for (const [key, valid, what] of rules) {
    const list = h[key];
    if (list == null) continue;
    if (!Array.isArray(list)) {
      out.push(issue("FL714", "error", path, pos.line, pos.col, `handles.${key} must be an array`));
      continue;
    }
    if (list.length > MAX_HANDLES) {
      out.push(issue("FL714", "error", path, pos.line, pos.col, `at most ${MAX_HANDLES} handles.${key}`));
    }
    for (const entry of list) {
      if (valid(entry)) continue;
      out.push(issue("FL714", "error", path, pos.line, pos.col, `invalid handles.${key} entry ${JSON.stringify(entry)}: ${what}`));
    }
  }
  const declared = Array.isArray(data.capabilities) && data.capabilities.some((entry) =>
    (typeof entry === "string" ? entry : entry && entry.id) === "share.receive");
  if (Array.isArray(h.mimeTypes) && h.mimeTypes.length > 0 && !declared) {
    out.push(issue("FL714", "error", path, pos.line, pos.col, "handles.mimeTypes need the share.receive capability (files are read with intents.readShare)"));
  }
  if (Array.isArray(h.schemes)) {
    for (const scheme of h.schemes) {
      if (!validHandleScheme(scheme) || LIVE_SCHEMES.has(scheme)) continue;
      out.push(issue("FL714", "warn", path, pos.line, pos.col, `scheme ${JSON.stringify(scheme)} never reaches the app: Android only sends web+deft, web+fl and mailto to Deft`));
    }
  }
  if (Array.isArray(h.links) && h.links.length > 0) {
    out.push(issue("FL714", "warn", path, pos.line, pos.col, "handles.links do nothing yet: Android cannot verify app links for an app origin, so https links open in the browser"));
  }
}

// PARITY G14: manifest `embeds` (third-party scripts and frames for `web.embed`).
// Keep identical to DeftManifestParser.parseEmbeds / validEmbedOrigin (Kotlin).
const MAX_EMBEDS = 16;

function scanEmbeds(path, source, data, at, out) {
  if (data.embeds == null) return;
  const pos = at(findKey(source, "embeds"));
  if (!Array.isArray(data.embeds)) {
    out.push(issue("FL712", "error", path, pos.line, pos.col, "embeds must be an array of https origins"));
    return;
  }
  if (data.embeds.length > MAX_EMBEDS) {
    out.push(issue("FL712", "error", path, pos.line, pos.col, `at most ${MAX_EMBEDS} embeds`));
  }
  for (const entry of data.embeds) {
    if (validEmbedOrigin(entry)) continue;
    const shown = typeof entry === "string" ? entry : "";
    const index = shown ? source.indexOf(JSON.stringify(shown)) : -1;
    const where = index < 0 ? pos : at(index);
    out.push(issue("FL712", "error", path, where.line, where.col,
      `invalid embed "${shown}": https://host[:port], "*." only as the leftmost label and not over a public suffix (*.github.io, *.co.uk), no IPs or localhost`));
  }
  const declared = Array.isArray(data.capabilities) && data.capabilities.some((entry) =>
    (typeof entry === "string" ? entry : entry && entry.id) === "web.embed");
  if (data.embeds.length > 0 && !declared) {
    out.push(issue("FL712", "error", path, pos.line, pos.col, "embeds need the web.embed capability"));
  }
}

function validEmbedOrigin(value) {
  if (typeof value !== "string" || !value.startsWith("https://")) return false;
  const rest = value.slice("https://".length);
  const wildcard = rest.startsWith("*.");
  const plain = wildcard ? "https://" + rest.slice(2) : value;
  if (plain.includes("*")) return false;
  // Lowercase only, as in Kotlin (the network rule above forgives case; embeds do not).
  if (plain !== plain.toLowerCase()) return false;
  if (!validNetworkOrigin(plain)) return false;
  if (wildcard && publicSuffix(plain.slice("https://".length).replace(/:\d+$/, ""))) return false;
  return true;
}

// A wildcard embed must not cover a whole public suffix (*.github.io, *.co.uk). Compact rule, not
// the full Public Suffix List: a generic second level under a two-letter TLD, or a shared-hosting
// suffix. Keep identical to EmbedSuffixes (Kotlin).
const CC_SECOND_LEVELS = new Set([
  "ac", "co", "com", "edu", "go", "gob", "gouv", "gov", "lg", "ltd", "mil",
  "ne", "net", "nhs", "nic", "nom", "or", "org", "plc", "sch",
]);
const SHARED_HOSTING = new Set([
  "amazonaws.com", "appspot.com", "azurestaticapps.net", "azurewebsites.net", "blogspot.com",
  "cloudfront.net", "deno.dev", "firebaseapp.com", "fly.dev", "github.io", "gitlab.io",
  "glitch.me", "herokuapp.com", "myshopify.com", "netlify.app", "ngrok-free.app", "ngrok.io",
  "onrender.com", "pages.dev", "r2.dev", "repl.co", "replit.app", "s3.amazonaws.com",
  "surge.sh", "vercel.app", "web.app", "wixsite.com", "wordpress.com", "workers.dev",
]);

function publicSuffix(host) {
  if (SHARED_HOSTING.has(host)) return true;
  const labels = host.split(".");
  return labels.length === 2 && labels[1].length === 2 && CC_SECOND_LEVELS.has(labels[0]);
}

function validNetworkOrigin(value) {
  if (typeof value !== "string") return false;
  if (!/^https:\/\/[a-z0-9.-]+(?::[0-9]{1,5})?$/i.test(value)) return false;
  const host = value.slice("https://".length).replace(/:\d+$/, "").toLowerCase();
  // Keep identical to DeftManifestParser.validNetworkOrigin (Kotlin).
  if (!host.includes(".") || host.split(".").some((label) => label === "")) return false;
  if (host === "localhost" || host.endsWith(".localhost")) return false;
  if (host === "deft.internal" || host.endsWith(".deft.internal")) return false;
  if (/^\d{1,3}(?:\.\d{1,3}){3}$/.test(host)) return false;
  if (host.includes("*")) return false;
  return true;
}

function fieldMissing(obj, key) {
  if (!Object.prototype.hasOwnProperty.call(obj, key)) return true;
  const value = obj[key];
  if (value === null || value === undefined) return true;
  if (typeof value === "string" && value.trim() === "") return true;
  return false;
}

function nonEmpty(value) {
  if (value == null) return false;
  if (Array.isArray(value) || typeof value === "string") return value.length > 0;
  if (typeof value === "object") return Object.keys(value).length > 0;
  return true;
}

function capabilitySet(catalog) {
  const caps = catalog && catalog.capabilities;
  const set = new Set();
  if (!caps || typeof caps !== "object") return set;
  if (Array.isArray(caps)) {
    for (const entry of caps) {
      if (typeof entry === "string") set.add(entry);
      else if (entry && typeof entry === "object" && typeof entry.id === "string") set.add(entry.id);
    }
  } else {
    for (const key of Object.keys(caps)) set.add(key);
  }
  return set;
}
