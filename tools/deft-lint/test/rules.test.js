import { expect, test } from "bun:test";
import { run } from "../src/index.js";
import "../src/browser.js";

const catalog = {
  capabilities: {
    alarms: { summary: "Schedule reminders" },
    "notifications.post": { summary: "Post notifications" },
  },
};

function lint(files, extra = {}) {
  return run({
    files,
    profile: extra.profile ?? "myapp",
    mode: extra.mode ?? "local",
    catalog: extra.catalog,
    limits: extra.limits,
  });
}

function ofId(diags, id) {
  return diags.filter((d) => d.id === id);
}

test("exposes deftLint.run on the global", () => {
  expect(typeof globalThis.deftLint.run).toBe("function");
  expect(globalThis.deftLint.run).toBe(run);
});

test("sorts diagnostics by path, line, col, id", () => {
  const diags = lint({
    "b.js": "eval(1)\n",
    "a.js": "el.innerHTML = 1\nnew Function('x')\n",
  });
  const keys = diags.map((d) => [d.path, d.line, d.col, d.id]);
  const sorted = [...keys].sort((a, b) => {
    if (a[0] !== b[0]) return a[0] < b[0] ? -1 : 1;
    if (a[1] !== b[1]) return a[1] - b[1];
    if (a[2] !== b[2]) return a[2] - b[2];
    return a[3] < b[3] ? -1 : a[3] > b[3] ? 1 : 0;
  });
  expect(keys).toEqual(sorted);
  expect(diags[0].path).toBe("a.js");
  for (const d of diags) {
    expect(Object.keys(d).sort()).toEqual(["col", "id", "line", "message", "path", "severity"]);
  }
});

test("FL100 flags innerHTML, insertAdjacentHTML, and document.write", () => {
  const diags = lint({
    "main.js": [
      "el.innerHTML = html;",
      "node.insertAdjacentHTML('beforeend', html);",
      "document.write(html);",
      "document",
      "  .write(more);",
    ].join("\n"),
  });
  const hits = ofId(diags, "FL100");
  expect(hits).toHaveLength(4);
  expect(hits.every((d) => d.severity === "error" && d.path === "main.js")).toBe(true);
  expect(hits[0]).toMatchObject({ line: 1, col: 4, message: "innerHTML is not allowed" });
});

test("FL100 ignores sinks in strings and comments", () => {
  const diags = lint({
    "main.js": [
      "const label = 'innerHTML';",
      "const fn = \"insertAdjacentHTML\";",
      "const w = `document.write`;",
      "// el.innerHTML = 1",
      "/* document.write(1); insertAdjacentHTML */",
      "el.textContent = label;",
    ].join("\n"),
  });
  expect(ofId(diags, "FL100")).toHaveLength(0);
});

test("FL101 flags eval( and new Function(", () => {
  const diags = lint({
    "main.js": "const a = eval(code);\nconst b = new Function('x', 'return x');\nconst c = `${eval(1)}`;\n",
  });
  const hits = ofId(diags, "FL101");
  expect(hits.map((d) => d.message)).toEqual([
    "eval() is not allowed",
    "new Function() is not allowed",
    "eval() is not allowed",
  ]);
  expect(hits.every((d) => d.severity === "error")).toBe(true);
  expect(hits[0].col).toBe(11);
});

test("FL101 ignores evaluate, comments, and template text", () => {
  const diags = lint({
    "main.js": [
      "evaluate(1);",
      "const name = 'Function';",
      "// eval(1)",
      "/* new Function('x') */",
      "const s = `eval(1) and new Function(`;",
      "function start() { return 1; }",
    ].join("\n"),
  });
  expect(ofId(diags, "FL101")).toHaveLength(0);
});

test("FL104 flags inline scripts and scripts without type=module", () => {
  const diags = lint({
    "index.html": [
      "<script>bad()</script>",
      "<script src=\"./lib.js\"></script>",
      "<script type=\"module\">stillInline()</script>",
      "<script type=\"text/javascript\" src=\"./lib.js\"></script>",
    ].join("\n"),
  });
  const hits = ofId(diags, "FL104");
  expect(hits).toHaveLength(4);
  expect(hits.every((d) => d.severity === "error")).toBe(true);
  expect(hits[0].message).toContain("inline script without src");
  expect(hits[0].message).toContain("type=module");
  expect(hits[1].message).toBe("script without type=module");
  expect(hits[2].message).toBe("inline script without src");
});

test("FL104 allows an external module script", () => {
  const diags = lint({
    "index.html": [
      "<!doctype html>",
      "<script type=\"module\" src=\"./main.js\"></script>",
      "<script",
      "  type='module'",
      "  src=\"./extra.js\"></script>",
      "<!-- <script>nope()</script> -->",
    ].join("\n"),
  });
  expect(ofId(diags, "FL104")).toHaveLength(0);
});

test("FL106 flags a style attribute", () => {
  const diags = lint({
    "index.html": "<div class=\"card\" style=\"color:red\">Hi</div>\n",
  });
  const hits = ofId(diags, "FL106");
  expect(hits).toEqual([
    {
      id: "FL106",
      severity: "error",
      path: "index.html",
      line: 1,
      col: 19,
      message: "style attribute is not allowed",
    },
  ]);
});

test("FL106 ignores classes, the style tag, and commented markup", () => {
  const diags = lint({
    "index.html": [
      "<link rel=\"stylesheet\" href=\"./app.css\">",
      "<style>.card { color: var(--deft-label); }</style>",
      "<div class=\"style\">ok</div>",
      "<!-- <div style=\"color:red\"></div> -->",
    ].join("\n"),
  });
  expect(ofId(diags, "FL106")).toHaveLength(0);
});

test("FL610 reports hex and rgb colors, warning on var(--deft- lines", () => {
  const diags = lint({
    "app.css": [
      ".title { color: #fff; background: rgb(0, 0, 0); }",
      ".soft { color: var(--deft-label, #abc); }",
      ".wide { border-color: #ccddee; }",
      "/* prefer var(--deft-label) over #fff and rgb(0, 0, 0) */",
      "#app { color: var(--deft-label); }",
    ].join("\n"),
  });
  const hits = ofId(diags, "FL610");
  expect(hits.map((d) => [d.line, d.severity, d.col])).toEqual([
    [1, "error", 17],
    [1, "error", 35],
    [2, "warn", 34],
    [3, "error", 23],
  ]);
});

test("FL610 allows token colors without literals", () => {
  const diags = lint({
    "app.css": [
      "#app {",
      "  color: var(--deft-label);",
      "  background: var(--deft-bg);",
      "  border: 1px solid var(--deft-separator);",
      "}",
    ].join("\n"),
  });
  expect(ofId(diags, "FL610")).toHaveLength(0);
});

test("FL701 flags invalid JSON and missing required fields", () => {
  const broken = lint({ "manifest.json": "{ not json" });
  expect(ofId(broken, "FL701")).toEqual([
    {
      id: "FL701",
      severity: "error",
      path: "manifest.json",
      line: 1,
      col: 1,
      message: "manifest.json is not valid JSON",
    },
  ]);

  const missing = lint({
    "manifest.json": JSON.stringify({ deft: 1, id: "notes" }),
  });
  const hits = ofId(missing, "FL701");
  expect(hits.map((d) => d.message)).toEqual([
    "manifest.json missing name",
    "manifest.json missing start_url",
  ]);
  expect(hits.every((d) => d.severity === "error")).toBe(true);
});

test("FL701 accepts a manifest with deft, id, name, and start_url", () => {
  const diags = lint({
    "manifest.json": JSON.stringify({
      deft: 1,
      id: "notes",
      name: "Notes",
      start_url: "./index.html",
    }),
  });
  expect(ofId(diags, "FL701")).toHaveLength(0);
});

test("FL702 flags capability ids absent from the catalog", () => {
  const diags = lint(
    {
      "manifest.json": JSON.stringify({
        deft: 1,
        id: "notes",
        name: "Notes",
        start_url: "./index.html",
        capabilities: [
          { id: "alarms", reason: "Schedule reminders" },
          { id: "camera", reason: "Take a photo" },
        ],
      }),
    },
    { catalog },
  );
  const hits = ofId(diags, "FL702");
  expect(hits).toHaveLength(1);
  expect(hits[0].severity).toBe("error");
  expect(hits[0].message).toBe('unknown capability "camera"');
});

test("FL702 is silent without a catalog and for known ids", () => {
  const body = JSON.stringify({
    deft: 1,
    id: "notes",
    name: "Notes",
    start_url: "./index.html",
    capabilities: [{ id: "camera", reason: "Take a photo" }],
  });
  expect(ofId(lint({ "manifest.json": body }), "FL702")).toHaveLength(0);

  const known = lint(
    {
      "manifest.json": JSON.stringify({
        deft: 1,
        id: "notes",
        name: "Notes",
        start_url: "./index.html",
        capabilities: ["alarms", { id: "notifications.post", reason: "Ping" }],
      }),
    },
    { catalog },
  );
  expect(ofId(known, "FL702")).toHaveLength(0);
});

test("FL706 flags canvas capabilities and network", () => {
  const manifest = {
    deft: 1,
    id: "home",
    name: "Home",
    start_url: "./index.html",
    profile: "canvas",
    capabilities: [{ id: "alarms", reason: "No" }],
    network: ["https://example.com"],
  };
  const diags = lint({ "manifest.json": JSON.stringify(manifest) }, { catalog });
  const hits = ofId(diags, "FL706");
  expect(hits.map((d) => d.message)).toEqual([
    "canvas profile cannot declare capabilities",
    "canvas profile cannot declare network",
  ]);
  expect(hits.every((d) => d.severity === "error")).toBe(true);

  const fromHost = lint(
    {
      "manifest.json": JSON.stringify({
        deft: 1,
        id: "home",
        name: "Home",
        start_url: "./index.html",
        profile: "myapp",
        capabilities: [{ id: "alarms", reason: "No" }],
      }),
    },
    { profile: "canvas", catalog },
  );
  expect(ofId(fromHost, "FL706").map((d) => d.message)).toEqual([
    "canvas profile cannot declare capabilities",
  ]);
});

test("FL706 allows an empty canvas manifest", () => {
  const diags = lint(
    {
      "manifest.json": JSON.stringify({
        deft: 1,
        id: "home",
        name: "Home",
        start_url: "./index.html",
        profile: "canvas",
        capabilities: [],
        network: [],
      }),
    },
    { profile: "canvas" },
  );
  expect(ofId(diags, "FL706")).toHaveLength(0);
});

test("FL003 flags paths that contain .. or _deft/", () => {
  const diags = lint({
    "ok.js": [
      "import x from '../secret.js';",
      "import '/_deft/v1/dom.js';",
      "export { a } from './lib.js';",
      "const dyn = import('./../other.js');",
    ].join("\n"),
    "_deft/hack.js": "export const n = 1;\n",
    "index.html": "<script type=\"module\" src=\"../main.js\"></script>\n<a href=\"/_deft/v1/x.html\">x</a>\n",
    "app.css": "@import \"../reset.css\";\n.bg { background: url(\"./_deft/x.png\"); }\n",
    "manifest.json": JSON.stringify({
      deft: 1,
      id: "notes",
      name: "Notes",
      start_url: "../index.html",
    }),
  });
  const hits = ofId(diags, "FL003");
  const where = hits.map((d) => `${d.path}:${d.line}`);
  expect(where).toEqual([
    "_deft/hack.js:1",
    "app.css:1",
    "app.css:2",
    "index.html:1",
    "index.html:2",
    "manifest.json:1",
    "ok.js:1",
    "ok.js:4",
  ]);
  expect(hits.every((d) => d.severity === "error")).toBe(true);
});

test("FL003 reserves only _deft/: _fl/ is an ordinary folder", () => {
  const diags = lint({
    "ok.js": "import x from './_fl/x.js';\n",
    "_fl/x.js": "export const n = 1;\n",
  });
  expect(ofId(diags, "FL003")).toHaveLength(0);
  const bg = lint({ "manifest.json": bgManifest({ module: "/_fl/bg.js", triggers: ["sms"] }) });
  expect(ofId(bg, "FL713")).toHaveLength(0);
});

test("FL701 wants the deft key; the old fl key does not count", () => {
  const diags = lint({ "manifest.json": JSON.stringify({ fl: 1, id: "notes", name: "Notes", start_url: "./index.html" }) });
  expect(ofId(diags, "FL701").some((d) => d.message.includes("deft"))).toBe(true);
});

test("FL003 allows relative files that stay inside the app", () => {
  const diags = lint({
    "main.js": "import { start } from './lib.js';\nstart();\n",
    "lib.js": "export function start() {}\n",
    "index.html": "<script type=\"module\" src=\"./main.js\"></script>\n<link rel=\"stylesheet\" href=\"./app.css\">\n",
    "app.css": "@import \"./extra.css\";\n.bg { background: url(\"./icon.png\"); }\n",
    "extra.css": ".bg { color: var(--deft-label); }\n",
    "manifest.json": JSON.stringify({
      deft: 1,
      id: "notes",
      name: "Notes",
      start_url: "./index.html",
    }),
  });
  expect(ofId(diags, "FL003")).toHaveLength(0);
});

test("FL009 flags a suppression that has no reason", () => {
  const diags = lint({
    "main.js": "// deft-allow FL402\nconst view = html`<ul>${items.map((item) => html`<li>${item}</li>`)}</ul>`;\n",
    "app.css": "/* deft-allow FL611 */\nh1 { font-family: system-ui, sans-serif; }\n",
  });
  const hits = ofId(diags, "FL009");
  expect(hits.map((d) => [d.path, d.line, d.severity])).toEqual([
    ["app.css", 1, "warn"],
    ["main.js", 1, "warn"],
  ]);
  expect(ofId(diags, "FL402")).toHaveLength(1);
  expect(ofId(diags, "FL611")).toHaveLength(1);

  const locked = lint({ "main.js": "// deft-allow FL100: not allowed\nel.innerHTML = 1;\n" });
  expect(ofId(locked, "FL100")).toHaveLength(1);
  expect(ofId(locked, "FL009")).toHaveLength(0);
});

test("FL402 warns when an unkeyed template map is passed into a list", () => {
  const bad = lint({
    "main.js": "const view = html`<ul>${items.map((item) => html`<li>${item.name}</li>`)}</ul>`;\n",
  });
  const hits = ofId(bad, "FL402");
  expect(hits).toHaveLength(1);
  expect(hits[0].severity).toBe("warn");
  expect(hits[0].message).toContain("repeat(items, key, tpl)");

  const good = lint({
    "main.js": "const view = html`<ul>${repeat(items, (item) => item.id, (item) => html`<li>${item.name}</li>`)}</ul>`;\nconst names = items.map((item) => item.name);\n",
  });
  expect(ofId(good, "FL402")).toHaveLength(0);

  const allowed = lint({
    "main.js": "// deft-allow FL402: static menu\nconst view = html`<ul>${items.map((item) => html`<li>${item.name}</li>`)}</ul>`;\n",
  });
  expect(ofId(allowed, "FL402")).toHaveLength(0);
  expect(ofId(allowed, "FL009")).toHaveLength(0);
});

test("FL302 reports an unknown deft-btn modifier", () => {
  const diags = lint({
    "index.html": "<button class=\"deft-btn deft-btn-primary deft-btn-blue\">Go</button>\n",
    "main.js": "el.classList.add('deft-btn-huge');\n",
  });
  const hits = ofId(diags, "FL302");
  expect(hits.map((d) => [d.path, d.severity, d.message])).toEqual([
    ["index.html", "info", 'unknown deft-btn modifier "deft-btn-blue"'],
    ["main.js", "info", 'unknown deft-btn modifier "deft-btn-huge"'],
  ]);

  const known = lint({
    "index.html": "<button class=\"deft-btn deft-btn-primary deft-btn-sm deft-btn-outline\">Go</button>\n",
  });
  expect(ofId(known, "FL302")).toHaveLength(0);
});

test("FL611 warns on a font-family literal", () => {
  const diags = lint({
    "app.css": [
      "body { font-family: system-ui, sans-serif; }",
      "code { font-family: var(--deft-font-mono); }",
      "h1 {",
      "  /* deft-allow FL611: matches the host */",
      "  font-family: Georgia, serif;",
      "}",
    ].join("\n"),
  });
  const hits = ofId(diags, "FL611");
  expect(hits).toEqual([
    {
      id: "FL611",
      severity: "warn",
      path: "app.css",
      line: 1,
      col: 8,
      message: "font-family literal; use var(--deft-font-ui)",
    },
  ]);
});

test("FL612 notes a px font-size", () => {
  const diags = lint({
    "app.css": "p { font-size: 16px; }\nh1 { font-size: var(--deft-text-xl); }\nsmall { font-size: 0.85rem; }\n",
  });
  const hits = ofId(diags, "FL612");
  expect(hits).toHaveLength(1);
  expect(hits[0].severity).toBe("info");
  expect(hits[0].line).toBe(1);
  expect(hits[0].message).toContain("px font size");
});

test("FL613 warns on a border-radius literal", () => {
  const diags = lint({
    "app.css": ".card { border-radius: 12px; }\n.btn { border-radius: var(--deft-radius-field); }\n",
  });
  const hits = ofId(diags, "FL613");
  expect(hits).toHaveLength(1);
  expect(hits[0]).toMatchObject({
    severity: "warn",
    line: 1,
    message: "border-radius literal; use var(--deft-radius-*)",
  });
});

test("FL614 warns when an app redefines an --deft-* token", () => {
  const diags = lint({
    "app.css": ":root { --deft-accent: var(--deft-accent); --note: var(--deft-accent); }\n",
  });
  const hits = ofId(diags, "FL614");
  expect(hits).toHaveLength(1);
  expect(hits[0].severity).toBe("warn");
  expect(hits[0].message).toContain("private token");
});

test("FL615 warns on outline none without a focus-visible style", () => {
  const bad = lint({ "app.css": "button { outline: none; }\n" });
  expect(ofId(bad, "FL615")).toHaveLength(1);
  expect(ofId(bad, "FL615")[0].severity).toBe("warn");

  const good = lint({
    "app.css": "button:focus-visible { outline: 2px solid var(--deft-accent); }\nbutton { outline: none; }\n",
  });
  expect(ofId(good, "FL615")).toHaveLength(0);
});

test("FL721 errors when a role and its content fail 4.5:1", () => {
  const bad = {
    modes: ["light"],
    light: { primary: "#ffffff", "primary-content": "#f4f4f4" },
  };
  const diags = lint(
    { "palette.json": JSON.stringify(bad, null, 2) },
    { profile: "theme", mode: "publish" },
  );
  const hits = ofId(diags, "FL721");
  expect(hits).toHaveLength(1);
  expect(hits[0].severity).toBe("error");
  expect(hits[0].message).toContain("primary / primary-content");
  expect(hits[0].message).toContain("< 4.5");

  const local = lint({ "palette.json": JSON.stringify(bad) }, { profile: "theme", mode: "local" });
  expect(ofId(local, "FL721")[0].severity).toBe("warn");

  const good = {
    modes: ["light"],
    light: { primary: "#1a1b26", "primary-content": "#ffffff" },
  };
  expect(ofId(lint({ "palette.json": JSON.stringify(good) }, { profile: "theme", mode: "publish" }), "FL721")).toHaveLength(0);
});

test("a small valid app produces no diagnostics", () => {
  const diags = lint(
    {
      "manifest.json": JSON.stringify({
        deft: 1,
        id: "notes",
        name: "Notes",
        version: "1.0.0",
        profile: "myapp",
        start_url: "./index.html",
        capabilities: [{ id: "alarms", reason: "Schedule reminders" }],
        network: [],
      }),
      "index.html": "<script type=\"module\" src=\"./main.js\"></script>\n<div class=\"app\"></div>\n",
      "main.js": "// Notes screen.\ndocument.body.textContent = 'ok';\n",
      "app.css": "/* Notes screen. */\n@layer deft.components {\n  .app { color: var(--deft-label); }\n}\n",
    },
    { catalog },
  );
  expect(diags).toEqual([]);
});

test("FL001 flags a bare or remote import specifier", () => {
  const diags = lint({ "main.js": "import 'left-pad';\n" });
  const hits = ofId(diags, "FL001");
  expect(hits).toHaveLength(1);
  expect(hits[0]).toMatchObject({ severity: "error", message: "bare or remote import specifier" });
});

const THREE_URL = "https://cdn.jsdelivr.net/npm/three@0.160.0/build/three.module.min.js";
const THREE_SRI = "sha256-PmkKx9GAsKrfCJG+o57sZD4p4tPnXJmxholRhmX2m6Y=";

function depManifest(deps, extra = {}) {
  return JSON.stringify({
    deft: 1, id: "game", name: "Game", version: "1.0.0", start_url: "./index.html", dependencies: deps, ...extra,
  });
}

test("FL001 allows declared CDN URLs and aliases", () => {
  const diags = lint({
    "manifest.json": depManifest({ [THREE_URL]: { integrity: THREE_SRI, as: "three" }, "https://esm.sh/b@2.0.0": null }),
    "main.js": `// Game.\nimport * as A from '${THREE_URL}';\nimport * as B from 'three';\nconst m = await import('${THREE_URL}');\n`,
  });
  expect(ofId(diags, "FL001")).toEqual([]);
  expect(ofId(diags, "FL705")).toEqual([]);
  expect(ofId(diags, "FL711")).toEqual([]);
});

test("FL205 does not treat declared CDN dependencies as network", () => {
  const diags = lint({
    "manifest.json": depManifest({ [THREE_URL]: THREE_SRI }),
    "main.js": `// Game.\nconst THREE = await import('${THREE_URL}');\n`,
    "index.html": `<script type="module" src="${THREE_URL}"></script>`,
  });
  const hits = ofId(diags, "FL205");
  expect(hits).toHaveLength(1);
  expect(hits[0].path).toBe("index.html");
});

test("FL001 flags undeclared CDN URLs with a fix", () => {
  const diags = lint({
    "manifest.json": depManifest({ [THREE_URL]: THREE_SRI }),
    "main.js": "// Game.\nimport 'three';\nimport 'https://unpkg.com/left-pad@1.3.0/index.js';\n",
  });
  const hits = ofId(diags, "FL001");
  expect(hits).toHaveLength(2);
  expect(hits[1].message).toContain("add the exact URL with its hash");
});

test("FL710 flags invalid dependency entries", () => {
  const diags = lint({
    "manifest.json": depManifest({
      "http://cdn.example.com/x@1.0.0/x.js": null,
      "https://cdn.example.com/x@1.0.0/x.js?module": null,
      "https://cdn.example.com/x@1.0.0/x.css": null,
      "https://cdn.example.com/y@1.0.0/y.js": "md5-abc",
      "https://cdn.example.com/z@1.0.0/z.js": { integrity: null, as: "Bad Name" },
    }),
  });
  expect(ofId(diags, "FL710")).toHaveLength(5);
});

test("FL710 requires hashes to publish and FL711 warns on unversioned URLs", () => {
  const unpinned = depManifest({ "https://cdn.example.com/lib.js": null });
  expect(ofId(lint({ "manifest.json": unpinned }, { mode: "publish" }), "FL710")[0].message).toContain("needs a hash to publish");
  expect(ofId(lint({ "manifest.json": unpinned }), "FL710")).toEqual([]);
  expect(ofId(lint({ "manifest.json": unpinned }), "FL711")).toHaveLength(1);
});

test("FL002 flags a relative import without .js", () => {
  const diags = lint({ "main.js": "import x from './util';\n" });
  expect(ofId(diags, "FL002")[0]).toMatchObject({ severity: "error", message: "relative import without .js" });
});

test("FL005 flags an unknown /_deft/v1/ module", () => {
  const diags = lint({ "main.js": "import '/_deft/v1/missing.js';\n" });
  expect(ofId(diags, "FL005")[0]).toMatchObject({
    severity: "error",
    message: 'unknown /_deft/v1/ module "missing.js"',
  });
});

test("FL007 flags import() with a non-literal specifier", () => {
  const diags = lint({ "main.js": "const mod = import(name);\n" });
  expect(ofId(diags, "FL007")[0]).toMatchObject({
    severity: "error",
    message: "import() specifier must be a string literal",
  });
});

test("FL102 flags an on* attribute", () => {
  const diags = lint({ "index.html": "<button onclick=\"go()\">Go</button>\n" });
  expect(ofId(diags, "FL102")[0]).toMatchObject({ severity: "error", message: "onclick attribute is not allowed" });
});

test("FL103 flags inline script and style", () => {
  const diags = lint({ "index.html": "<style>.a{}</style>\n<script>bad()</script>\n" });
  const hits = ofId(diags, "FL103");
  expect(hits.map((d) => d.message)).toEqual([
    "inline style; use ./app.css",
    "inline script; use ./main.js",
  ]);
  expect(hits.every((d) => d.severity === "error")).toBe(true);
});

test("FL105 flags alert, confirm, and prompt", () => {
  const diags = lint({ "main.js": "alert(1);\nconfirm(1);\nprompt(1);\n" });
  expect(ofId(diags, "FL105").map((d) => d.message)).toEqual([
    "alert() is not allowed; use deft.confirm, deft.prompt or dialog() from /_deft/v1/app.js",
    "confirm() is not allowed; use deft.confirm, deft.prompt or dialog() from /_deft/v1/app.js",
    "prompt() is not allowed; use deft.confirm, deft.prompt or dialog() from /_deft/v1/app.js",
  ]);
});

test("FL105 allows confirm and prompt imported from the runtime, still flags alert", () => {
  const src = "import { confirm, prompt as ask } from '/_deft/v1/app.js';\nawait confirm('x');\nawait ask('y');\nprompt('z');\nalert(1);\n";
  const hits = ofId(lint({ "main.js": src }), "FL105").map((d) => d.message.split("(")[0]);
  expect(hits).toEqual(["prompt", "alert"]);
  expect(ofId(lint({ "main.js": "import { confirm } from '/_deft/v1/index.js';\nconfirm('x');\n" }), "FL105")).toHaveLength(0);
});

test("FL107 flags a javascript: URL", () => {
  const diags = lint({ "index.html": "<a href=\"javascript:void(0)\">x</a>\n" });
  expect(ofId(diags, "FL107")[0]).toMatchObject({ severity: "error", message: "javascript: URL" });
});

test("FL108 flags iframe and meta http-equiv", () => {
  const diags = lint({ "index.html": "<iframe src=\"x\"></iframe>\n<meta http-equiv=\"refresh\" content=\"0\">\n" });
  expect(ofId(diags, "FL108").map((d) => d.message)).toEqual([
    "<iframe> is not supported",
    "meta http-equiv is not supported",
  ]);
});

test("FL109 flags service worker registration", () => {
  const diags = lint({ "main.js": "navigator.serviceWorker.register('./sw.js');\n" });
  expect(ofId(diags, "FL109")[0].severity).toBe("error");
});

test("FL110 flags window.open", () => {
  const diags = lint({ "main.js": "window.open('/next');\n" });
  expect(ofId(diags, "FL110")[0]).toMatchObject({ severity: "error", message: "window.open is not allowed" });
});

test("FL112 flags a line over 400 characters in publish mode", () => {
  const line = "a".repeat(401);
  const files = { "main.js": `// app\n${line}\n` };
  expect(ofId(lint(files, { mode: "publish" }), "FL112")).toHaveLength(1);
  expect(ofId(lint(files), "FL112")).toHaveLength(0);
});

test("FL113 flags access to __deft host internals", () => {
  const diags = lint({ "main.js": "window.__deftPort = true;\n" });
  expect(ofId(diags, "FL113")[0]).toMatchObject({ severity: "error", message: "access to host internal __deft*" });
});

test("FL114 flags script inside SVG", () => {
  const diags = lint({ "icon.svg": "<svg xmlns=\"http://www.w3.org/2000/svg\"><script></script></svg>\n" });
  expect(ofId(diags, "FL114")[0]).toMatchObject({ severity: "error", message: "SVG contains script" });
});

test("FL200 warns on direct deftBridge", () => {
  const diags = lint({ "main.js": "window.deftBridge.invoke('alarms.list');\n" });
  expect(ofId(diags, "FL200")[0]).toMatchObject({
    severity: "warn",
    message: "direct deftBridge; use call() from bridge.js",
  });
});

test("FL201 flags an unknown bridge method", () => {
  const diags = lint(
    { "main.js": "deftBridge.invoke('nope.thing');\n" },
    { catalog: { capabilities: { alarms: { methods: ["alarms.list"], events: [] } } } },
  );
  expect(ofId(diags, "FL201")[0]).toMatchObject({
    severity: "error",
    message: 'unknown bridge method "nope.thing"',
  });
});

test("FL202 accepts net.fetch with manifest network, network.user or network.open, and flags it otherwise", () => {
  const catalog = { capabilities: { "network.user": { methods: ["net.fetch"], events: [] } } };
  const run = (extra) => lint(
    {
      "manifest.json": JSON.stringify({
        deft: 1, id: "feed", name: "Feed", version: "1.0.0", start_url: "./index.html", capabilities: [], ...extra,
      }),
      "main.js": "deftBridge.invoke('net.fetch', { url: 'https://example.com/feed' });\n",
    },
    { catalog },
  );
  expect(ofId(run({ network: ["https://example.com"] }), "FL202")).toEqual([]);
  expect(ofId(run({ capabilities: [{ id: "network.user", reason: "Read the feeds the user adds" }] }), "FL202")).toEqual([]);
  expect(ofId(run({ capabilities: [{ id: "network.open", reason: "Read any feed the user adds" }] }), "FL202")).toEqual([]);
  expect(ofId(run({}), "FL202")[0]).toMatchObject({ message: "capability network.user is used but not declared" });
});

test("FL202 flags a capability that is used but not declared", () => {
  const diags = lint(
    {
      "manifest.json": JSON.stringify({
        deft: 1, id: "notes", name: "Notes", version: "1.0.0", start_url: "./index.html", capabilities: [],
      }),
      "main.js": "deftBridge.invoke('alarms.list');\n",
    },
    { catalog: { capabilities: { alarms: { methods: ["alarms.list"], events: [] } } } },
  );
  expect(ofId(diags, "FL202")[0]).toMatchObject({
    severity: "error",
    message: "capability alarms is used but not declared",
  });
});

test("FL203 warns when a declared capability is unused", () => {
  const diags = lint(
    {
      "manifest.json": JSON.stringify({
        deft: 1, id: "notes", name: "Notes", version: "1.0.0", start_url: "./index.html",
        capabilities: [{ id: "alarms", reason: "Schedule reminders" }],
      }),
      "main.js": "// notes\n",
    },
    { catalog: { capabilities: { alarms: { methods: ["alarms.list"], events: ["alarms.fired"] } } } },
  );
  expect(ofId(diags, "FL203")[0]).toMatchObject({
    severity: "warn",
    message: "capability alarms is declared but unused",
  });
});

test("FL204 flags bridge and network use in a canvas", () => {
  const diags = lint({ "main.js": "fetch('/local');\n" }, { profile: "canvas" });
  expect(ofId(diags, "FL204")[0]).toMatchObject({
    severity: "error",
    message: "canvas cannot use the bridge or network",
  });
});

test("FL205 flags a network URL whose origin is not declared", () => {
  const main = "// notes\nconst u = 'https://evil.example/a';\nconst ns = 'http://www.w3.org/2000/svg';\n";
  const bad = lint({
    "manifest.json": JSON.stringify({
      deft: 1, id: "notes", name: "Notes", version: "1.0.0", start_url: "./index.html", network: [],
    }),
    "main.js": main,
  });
  expect(ofId(bad, "FL205")).toHaveLength(1);
  expect(ofId(bad, "FL205")[0].message).toBe("network origin https://evil.example is not in manifest.network");
  const good = lint({
    "manifest.json": JSON.stringify({
      deft: 1, id: "notes", name: "Notes", version: "1.0.0", start_url: "./index.html",
      network: ["https://evil.example"],
    }),
    "main.js": main,
  });
  expect(ofId(good, "FL205")).toHaveLength(0);
});

test("FL206 notes a network URL that is not a literal", () => {
  const diags = lint({ "main.js": "fetch(endpoint);\n" });
  expect(ofId(diags, "FL206")[0]).toMatchObject({ severity: "info", message: "network URL is not statically known" });
});

test("FL207 flags a non-literal call() method name", () => {
  const diags = lint({ "main.js": "deftBridge.invoke(name);\n" });
  expect(ofId(diags, "FL207")[0]).toMatchObject({
    severity: "error",
    message: "call() method name must be a literal",
  });
});

test("FL300 warns when getElementById names a missing id", () => {
  const diags = lint({
    "index.html": "<div id=\"ok\"></div>\n",
    "main.js": "document.getElementById('missing');\n",
  });
  expect(ofId(diags, "FL300")[0]).toMatchObject({ severity: "warn", message: 'id "missing" is not in the HTML' });
});

test("FL301 flags a duplicate static id", () => {
  const diags = lint({ "index.html": "<div id=\"a\"></div><span id=\"a\"></span>\n" });
  expect(ofId(diags, "FL301")[0]).toMatchObject({ severity: "error", message: 'duplicate id "a"' });
});

test("FL303 warns when a script is not reachable from index.html", () => {
  const diags = lint({
    "index.html": "<!doctype html><html><script type=\"module\" src=\"./main.js\"></script></html>\n",
    "main.js": "// app\n",
    "orphan.js": "// leftover\n",
  });
  const hits = ofId(diags, "FL303");
  expect(hits.map((d) => d.path)).toEqual(["orphan.js"]);
  expect(hits[0].severity).toBe("warn");
});

test("FL400 warns on an async forEach callback", () => {
  const diags = lint({ "main.js": "items.forEach(async (item) => {});\n" });
  expect(ofId(diags, "FL400")[0]).toMatchObject({
    severity: "warn",
    message: "async callback in forEach; use for...of + await",
  });
});

test("FL403 warns on raw localStorage and JSON.parse outside try", () => {
  const diags = lint({ "main.js": "localStorage.setItem('a', '1');\nconst data = JSON.parse(text);\n" });
  expect(ofId(diags, "FL403").map((d) => d.message)).toEqual([
    "raw localStorage; use store()",
    "JSON.parse outside try; use store()",
  ]);
});

test("FL500 warns on var", () => {
  const diags = lint({ "main.js": "var count = 1;\n" });
  expect(ofId(diags, "FL500")[0]).toMatchObject({ severity: "warn", message: "var; use let or const" });
});

test("FL501 warns on == and !=", () => {
  const diags = lint({ "main.js": "if (a == b && c != d) {}\n" });
  expect(ofId(diags, "FL501").map((d) => d.message)).toEqual(["== ; use ===", "!= ; use !=="]);
  expect(ofId(lint({ "main.js": "if (a === b && c !== d) {}\n" }), "FL501")).toHaveLength(0);
});

test("FL502 warns when main.js is longer than 80 lines", () => {
  const diags = lint({ "main.js": "// app\n" + "const x = 1;\n".repeat(81) });
  expect(ofId(diags, "FL502")[0]).toMatchObject({ severity: "warn", message: "main.js is longer than 80 lines" });
});

test("FL503 warns when a file has no header comment", () => {
  const diags = lint({ "main.js": "const ready = true;\n" });
  expect(ofId(diags, "FL503")[0]).toMatchObject({ severity: "warn", message: "missing header comment" });
});

test("FL504 notes a leftover console.log", () => {
  const diags = lint({ "main.js": "console.log(1);\n" });
  expect(ofId(diags, "FL504")[0]).toMatchObject({ severity: "info", message: "leftover console.log" });
});

test("FL600 flags a viewport without viewport-fit=cover", () => {
  const diags = lint({ "index.html": "<!doctype html><html><meta name=\"viewport\" content=\"width=device-width\"></html>\n" });
  expect(ofId(diags, "FL600")[0]).toMatchObject({
    severity: "error",
    message: "viewport meta missing viewport-fit=cover",
  });
});

test("FL601 flags a viewport that disables zoom", () => {
  const diags = lint({
    "index.html": "<!doctype html><html><meta name=\"viewport\" content=\"width=device-width, viewport-fit=cover, user-scalable=no\"></html>\n",
  });
  expect(ofId(diags, "FL601")[0]).toMatchObject({ severity: "error", message: "viewport disables zoom" });
  expect(ofId(diags, "FL600")).toHaveLength(0);
});

test("FL602 flags a document without the module entry script", () => {
  const diags = lint({
    "index.html": "<!doctype html><html><meta name=\"viewport\" content=\"width=device-width, viewport-fit=cover\"></html>\n",
  });
  expect(ofId(diags, "FL602")[0]).toMatchObject({
    severity: "error",
    message: 'missing <script type="module" src="./main.js">',
  });
});

test("FL603 flags app CSS that comes before base.css and theme.css", () => {
  const diags = lint({
    "index.html": [
      "<!doctype html><html><head>",
      "<meta name=\"viewport\" content=\"width=device-width, viewport-fit=cover\">",
      "<link rel=\"stylesheet\" href=\"./app.css\">",
      "<link rel=\"stylesheet\" href=\"/_deft/v1/base.css\">",
      "<link rel=\"stylesheet\" href=\"/_deft/v1/theme.css\">",
      "<script type=\"module\" src=\"./main.js\"></script>",
      "</head></html>",
    ].join(""),
  });
  expect(ofId(diags, "FL603")[0]).toMatchObject({
    severity: "error",
    message: "base.css, tokens.css, components.css and theme.css must come before app CSS",
  });
});

test("FL604 flags @import and a remote url()", () => {
  const diags = lint({
    "app.css": "/* screen */\n@import \"./x.css\";\n.a { background: url(https://cdn.example/a.png); }\n",
  });
  expect(ofId(diags, "FL604").map((d) => d.message)).toEqual([
    "@import is not allowed",
    "remote url() is not allowed",
  ]);
});

test("FL605 warns on fixed positioning without a safe-area inset", () => {
  const diags = lint({ "app.css": "/* screen */\n.bar { position: fixed; top: 0; }\n" });
  expect(ofId(diags, "FL605")[0]).toMatchObject({
    severity: "warn",
    message: "fixed or sticky position without a safe-area inset",
  });
});

test("FL606 warns on !important", () => {
  const diags = lint({ "app.css": "/* screen */\n.a { color: var(--deft-label) !important; }\n" });
  expect(ofId(diags, "FL606")[0]).toMatchObject({ severity: "warn", message: "!important" });
});

test("FL607 warns on 100vh", () => {
  const diags = lint({ "app.css": "/* screen */\n.a { height: 100vh; }\n" });
  expect(ofId(diags, "FL607")[0]).toMatchObject({ severity: "warn", message: "100vh; use 100dvh" });
});

test("FL608 warns on 100vw", () => {
  const diags = lint({ "app.css": "/* screen */\n.a { width: 100vw; }\n" });
  expect(ofId(diags, "FL608")[0]).toMatchObject({ severity: "warn", message: "100vw; use 100%" });
});

test("FL609 warns on a fixed width over 480px", () => {
  const bad = lint({ "app.css": "/* screen */\n.a { width: 800px; }\n" });
  expect(ofId(bad, "FL609")[0]).toMatchObject({ severity: "warn", message: "fixed width over 480px" });
  const ok = lint({ "app.css": "/* screen */\n.a { max-width: 800px; }\n" });
  expect(ofId(ok, "FL609")).toHaveLength(0);
});

test("FL620 flags an img without alt", () => {
  const diags = lint({ "index.html": "<img src=\"a.png\">\n" });
  expect(ofId(diags, "FL620")[0]).toMatchObject({ severity: "error", message: "img without alt" });
});

test("FL621 flags a button without an accessible name", () => {
  const diags = lint({ "index.html": "<button type=\"button\"></button>\n" });
  expect(ofId(diags, "FL621")[0]).toMatchObject({ severity: "error", message: "button without an accessible name" });
});

test("FL622 flags a form control without a label", () => {
  const diags = lint({ "index.html": "<input id=\"q\" type=\"search\">\n" });
  expect(ofId(diags, "FL622")[0]).toMatchObject({ severity: "error", message: "input without a label" });
});

test("FL623 warns on a click handler on a div", () => {
  const diags = lint({ "index.html": "<div onclick=\"go()\">x</div>\n" });
  expect(ofId(diags, "FL623")[0]).toMatchObject({ severity: "warn", message: "click handler on div; use button" });
});

test("FL624 warns when html has no lang", () => {
  const diags = lint({ "index.html": "<!doctype html><html><body></body></html>\n" });
  expect(ofId(diags, "FL624")[0]).toMatchObject({ severity: "warn", message: "html element missing lang" });
});

test("FL625 warns when a heading level is skipped", () => {
  const diags = lint({ "index.html": "<!doctype html><html lang=\"en\"><h1>A</h1><h3>C</h3></html>\n" });
  expect(ofId(diags, "FL625")[0]).toMatchObject({ severity: "warn", message: "skipped heading level h1 to h3" });
});

test("FL626 warns on a positive tabindex", () => {
  const diags = lint({ "index.html": "<div tabindex=\"2\"></div>\n" });
  expect(ofId(diags, "FL626")[0]).toMatchObject({ severity: "warn", message: "positive tabindex" });
});

test("FL700 flags a disallowed file type", () => {
  const diags = lint({ "payload.exe": "nope\n" });
  expect(ofId(diags, "FL700")[0]).toMatchObject({ severity: "error", message: 'disallowed file type ".exe"' });
});

test("FL703 flags a missing or non-semver version", () => {
  const missing = lint({
    "manifest.json": JSON.stringify({ deft: 1, id: "notes", name: "Notes", start_url: "./index.html" }),
  });
  expect(ofId(missing, "FL703")[0]).toMatchObject({ severity: "error", message: "manifest.json missing version" });
  const bad = lint({
    "manifest.json": JSON.stringify({ deft: 1, id: "notes", name: "Notes", version: "1.0", start_url: "./index.html" }),
  });
  expect(ofId(bad, "FL703")[0]).toMatchObject({ severity: "error", message: "version must be semver" });
});

test("FL704 flags an invalid network entry", () => {
  const diags = lint({
    "manifest.json": JSON.stringify({
      deft: 1, id: "notes", name: "Notes", version: "1.0.0", start_url: "./index.html",
      network: ["https://example.com/cdn"],
    }),
  });
  expect(ofId(diags, "FL704")[0]).toMatchObject({
    severity: "error",
    message: 'invalid network entry "https://example.com/cdn"',
  });
});

test("FL704 rejects IP literals, localhost, app origins and undotted hosts", () => {
  const bad = [
    "https://127.0.0.1", "https://10.0.0.5:8443", "https://localhost", "https://api.localhost",
    "https://x.app.deft.internal", "https://router", "https://a..b.com", "https://.example.com",
  ];
  for (const entry of bad) {
    const diags = lint({
      "manifest.json": JSON.stringify({
        deft: 1, id: "notes", name: "Notes", version: "1.0.0", start_url: "./index.html", network: [entry],
      }),
    });
    expect(ofId(diags, "FL704").length).toBe(1);
  }
  const ok = lint({
    "manifest.json": JSON.stringify({
      deft: 1, id: "notes", name: "Notes", version: "1.0.0", start_url: "./index.html",
      network: ["https://api.example.com", "https://feeds.example.org:8443"],
    }),
  });
  expect(ofId(ok, "FL704").length).toBe(0);
});

test("FL708 checks launcher shortcuts", () => {
  const base = { deft: 1, id: "notes", name: "Notes", version: "1.0.0", start_url: "./index.html" };
  const good = lint({ "manifest.json": JSON.stringify({ ...base, shortcuts: [{ id: "new", name: "New note", url: "./#new" }] }) });
  expect(ofId(good, "FL708").length).toBe(0);
  expect(ofId(good, "FL705").length).toBe(0);
  for (const url of ["https://evil.example/", "//evil.example", "./../x", "javascript:alert(1)", "/_deft/v1/bridge.js"]) {
    const bad = lint({ "manifest.json": JSON.stringify({ ...base, shortcuts: [{ id: "x", name: "X", url }] }) });
    expect(ofId(bad, "FL708").length).toBe(1);
  }
});

test("FL705 warns on an unknown manifest field", () => {
  const diags = lint({
    "manifest.json": JSON.stringify({
      deft: 1, id: "notes", name: "Notes", version: "1.0.0", start_url: "./index.html", extra: true,
    }),
  });
  expect(ofId(diags, "FL705")[0]).toMatchObject({ severity: "warn", message: 'unknown manifest field "extra"' });
});

test("FL707 flags a publish package without license or source", () => {
  const diags = lint(
    {
      "manifest.json": JSON.stringify({
        deft: 1, id: "notes", name: "Notes", version: "1.0.0", start_url: "./index.html",
      }),
    },
    { mode: "publish" },
  );
  expect(ofId(diags, "FL707").map((d) => d.message)).toEqual([
    "publish requires a LICENSE file",
    "publish requires manifest license",
    "publish requires manifest source",
  ]);
});

test("FL708 flags a capability reason outside 10–140 characters", () => {
  const diags = lint({
    "manifest.json": JSON.stringify({
      deft: 1, id: "notes", name: "Notes", version: "1.0.0", start_url: "./index.html",
      capabilities: [{ id: "alarms", reason: "Short" }],
    }),
  });
  expect(ofId(diags, "FL708")[0]).toMatchObject({
    severity: "error",
    message: "capability reason must be 10–140 characters",
  });
});

test("FL709 flags a file over the text size limit", () => {
  const diags = lint({ "notes.txt": "x".repeat(512 * 1024 + 1) });
  expect(ofId(diags, "FL709")[0]).toMatchObject({ severity: "error", message: "file exceeds the text size limit" });
  const raised = lint(
    { "notes.txt": "x".repeat(512 * 1024 + 1) },
    { limits: { textBytes: 600 * 1024, maxFiles: 256, packageBytes: 10 * 1024 * 1024 } },
  );
  expect(ofId(raised, "FL709")).toEqual([]);
});

test("FL720 flags a palette that is missing required keys", () => {
  const diags = lint({ "palette.json": JSON.stringify({ modes: ["light"], light: {} }) });
  expect(ofId(diags, "FL720").some((d) => d.message === "palette missing base-100")).toBe(true);
  expect(ofId(diags, "FL720").every((d) => d.severity === "error")).toBe(true);
});

test("FL722 flags code files in a theme package", () => {
  const diags = lint({ "app.css": "/* theme */\n.a { color: var(--deft-label); }\n" }, { profile: "theme" });
  expect(ofId(diags, "FL722")[0]).toMatchObject({
    severity: "error",
    message: "theme packages cannot contain code or SVG",
  });
});

// WebAssembly needs the wasm capability; a .wasm file must be in the package or a hash-pinned dependency.
const wasmManifest = (caps, deps = {}) =>
  JSON.stringify({
    deft: 1, id: "wasm-app", name: "Wasm", version: "1.0.0", start_url: "./index.html",
    capabilities: caps, dependencies: deps,
  });
const WASM_CAP = [{ id: "wasm", reason: "Run the add module shipped in this app" }];
const WASM_SRI = "sha384-" + "A".repeat(64);

test("FL700 allows a .wasm file", () => {
  const diags = lint({ "manifest.json": wasmManifest(WASM_CAP), "add.wasm": "" });
  expect(ofId(diags, "FL700")).toEqual([]);
});

test("FL202 flags a .wasm file when wasm is not declared, and allows it when declared", () => {
  const undeclared = lint({ "manifest.json": wasmManifest([]), "add.wasm": "" });
  expect(ofId(undeclared, "FL202")[0]).toMatchObject({
    severity: "error", path: "add.wasm", message: "capability wasm is used but not declared",
  });
  const declared = lint({ "manifest.json": wasmManifest(WASM_CAP), "add.wasm": "" });
  expect(ofId(declared, "FL202")).toEqual([]);
});

test("FL202 flags WebAssembly in a script when wasm is not declared", () => {
  const src = "// Add.\nconst m = await WebAssembly.instantiate(bytes);\n";
  const undeclared = lint({ "manifest.json": wasmManifest([]), "main.js": src });
  expect(ofId(undeclared, "FL202")[0]).toMatchObject({
    severity: "error", path: "main.js", line: 2, message: "capability wasm is used but not declared",
  });
  expect(ofId(lint({ "manifest.json": wasmManifest(WASM_CAP), "main.js": src }), "FL202")).toEqual([]);
});

test("FL202 ignores WebAssembly in comments", () => {
  const diags = lint({ "manifest.json": wasmManifest([]), "main.js": "// WebAssembly is not used here.\nconst x = 1;\n" });
  expect(ofId(diags, "FL202")).toEqual([]);
});

test("FL202 and FL710 check a .wasm dependency: declared and hash-pinned", () => {
  const url = "https://cdn.example.com/sqlite@1.0.0/sql-wasm.wasm";
  const unpinned = lint({ "manifest.json": wasmManifest(WASM_CAP, { [url]: null }) });
  expect(ofId(unpinned, "FL710")[0]).toMatchObject({
    severity: "error", message: `WebAssembly dependency ${url} must be hash-pinned`,
  });
  const pinnedUndeclared = lint({ "manifest.json": wasmManifest([], { [url]: WASM_SRI }) });
  expect(ofId(pinnedUndeclared, "FL202")[0]).toMatchObject({ path: "manifest.json", severity: "error" });
  const ok = lint({ "manifest.json": wasmManifest(WASM_CAP, { [url]: WASM_SRI }) });
  expect(ofId(ok, "FL202")).toEqual([]);
  expect(ofId(ok, "FL710")).toEqual([]);
});

test("FL710 accepts a pinned .wasm dependency loaded from /_deft/deps and rejects an unpinned remote one", () => {
  const url = "https://cdn.example.com/add@1.0.0/add.wasm";
  const served = "/_deft/deps/cdn.example.com/add@1.0.0/add.wasm";
  const network = (deps) => JSON.stringify({
    deft: 1, id: "wasm-app", name: "Wasm", version: "1.0.0", start_url: "./index.html",
    capabilities: WASM_CAP, dependencies: deps, network: ["https://cdn.example.com"],
  });
  const ok = lint({
    "manifest.json": wasmManifest(WASM_CAP, { [url]: WASM_SRI }),
    "main.js": `const r = await fetch("${served}");\nawait WebAssembly.instantiate(await r.arrayBuffer());\n`,
  });
  expect(ofId(ok, "FL710")).toEqual([]);
  expect(ofId(ok, "FL202")).toEqual([]);

  // A remote .wasm in code is not pinned, even when its origin is in manifest.network.
  const remote = lint({
    "manifest.json": network({}),
    "main.js": `WebAssembly.instantiateStreaming(fetch("https://cdn.example.com/other.wasm?v=2"));\n`,
  });
  expect(ofId(remote, "FL710")[0]).toMatchObject({
    severity: "error", path: "main.js", line: 1,
    message: "WebAssembly from https://cdn.example.com/other.wasm must be a hash-pinned dependency in manifest.json",
  });

  // The pinned URL itself in code: the page must load the served copy.
  const direct = lint({
    "manifest.json": wasmManifest(WASM_CAP, { [url]: WASM_SRI }),
    "main.js": `WebAssembly.instantiateStreaming(fetch("${url}"));\n`,
  });
  expect(ofId(direct, "FL710")[0]).toMatchObject({ severity: "warn", message: `load the pinned WebAssembly dependency from ${served}, not from ${url}` });

  // An unpinned dependency stays an error in the manifest, and its URL in code too.
  const unpinned = lint({
    "manifest.json": wasmManifest(WASM_CAP, { [url]: null }),
    "main.js": `fetch("${url}");\n`,
  });
  expect(ofId(unpinned, "FL710").map((d) => d.path).sort()).toEqual(["main.js", "manifest.json"]);
  expect(ofId(unpinned, "FL710").every((d) => d.severity === "error")).toBe(true);
});

// Manifest assetPacks; packs count against the package cap.
const packManifest = (packs, caps = []) =>
  JSON.stringify({ deft: 1, id: "maps", name: "Maps", version: "1.0.0", start_url: "./index.html", capabilities: caps, assetPacks: packs });
const PACK = { name: "europe", url: "https://cdn.example.com/maps@1.0.0/europe.zip", sha256: "a".repeat(64), size: 1000 };

test("FL723 accepts a valid asset pack and rejects bad fields", () => {
  expect(ofId(lint({ "manifest.json": packManifest([PACK]) }), "FL723")).toEqual([]);
  const bad = lint({
    "manifest.json": packManifest([
      { ...PACK, name: "Europe" },
      { ...PACK, name: "b", url: "https://cdn.example.com/b.tar" },
      { ...PACK, name: "c", sha256: "A".repeat(64) },
      { ...PACK, name: "d", size: 0 },
      { ...PACK, name: "d" },
    ]),
  });
  expect(ofId(bad, "FL723").map((d) => d.message)).toEqual([
    "asset pack name must be 1-32 chars [a-z0-9-]",
    "asset pack b url must be an https .zip URL without query or fragment",
    "asset pack c sha256 must be 64 lowercase hex characters",
    "asset pack d size must be 1 to 2147483648 bytes",
    "asset pack d is listed twice",
  ]);
  expect(ofId(lint({ "manifest.json": packManifest({}) }), "FL723")[0].message).toBe("assetPacks must be an array of {name, url, sha256, size}");
});

test("FL723 counts packs against the package cap and points at storage.large", () => {
  const big = { ...PACK, size: 200 * 1024 * 1024 };
  const over = lint({ "manifest.json": packManifest([big]) }, { limits: { packageBytes: 25 * 1024 * 1024 } });
  expect(ofId(over, "FL723")[0].message).toMatch(/over the 26214400 byte cap; declare storage.large for more$/);
  const large = [{ id: "storage.large", reason: "Keep offline maps for the whole region" }];
  const granted = lint({ "manifest.json": packManifest([big], large) }, { limits: { packageBytes: 1024 * 1024 * 1024 } });
  expect(ofId(granted, "FL723")).toEqual([]);
});

// Manifest embeds for web.embed; FL205 accepts approved-list origins only with the capability.
const embedManifest = (embeds, caps = [{ id: "web.embed", reason: "Show the Stripe card form for checkout" }], extra = {}) =>
  JSON.stringify({
    deft: 1, id: "pay", name: "Pay", version: "1.0.0", start_url: "./index.html",
    capabilities: caps, network: [], embeds, ...extra,
  });

test("FL712 accepts valid embeds and FL705 knows the field", () => {
  const diags = lint({ "manifest.json": embedManifest(["https://js.stripe.com", "https://*.js.stripe.com", "https://pay.example.com:8443"]) });
  expect(ofId(diags, "FL712")).toHaveLength(0);
  expect(ofId(diags, "FL705")).toHaveLength(0);
});

test("FL712 rejects bad embed origins", () => {
  const bad = [
    "http://js.stripe.com", "https://js.stripe.com/", "https://js.stripe.com/v3", "https://*.com", "https://*",
    "*", "https://a.*.example.com", "https://*.*.example.com", "https://*example.com", "https://192.168.1.1",
    "https://localhost", "https://*.localhost", "https://a.app.deft.internal", "https://*.deft.internal",
    "https://JS.STRIPE.COM", "wss://js.stripe.com", "", 7,
  ];
  for (const entry of bad) {
    const diags = lint({ "manifest.json": embedManifest([entry]) });
    expect(ofId(diags, "FL712").length).toBe(1);
    expect(ofId(diags, "FL712")[0].severity).toBe("error");
  }
});

test("FL712 rejects a wildcard over a public suffix", () => {
  const bad = [
    "https://*.github.io", "https://*.co.uk", "https://*.com.au", "https://*.co.jp", "https://*.gov.uk",
    "https://*.herokuapp.com", "https://*.s3.amazonaws.com", "https://*.amazonaws.com", "https://*.web.app",
    "https://*.pages.dev:8443",
  ];
  for (const entry of bad) {
    expect(ofId(lint({ "manifest.json": embedManifest([entry]) }), "FL712").length).toBe(1);
  }
  const ok = [
    "https://*.myname.github.io", "https://*.shop.co.uk", "https://*.example.com", "https://*.ad.nl",
    "https://github.io", "https://co.uk", "https://*.co.com", "https://*.paypal.com",
  ];
  expect(ofId(lint({ "manifest.json": embedManifest(ok) }), "FL712")).toHaveLength(0);
});

test("FL712 limits embeds to 16 and requires an array", () => {
  const many = Array.from({ length: 17 }, (_, i) => `https://s${i}.example.com`);
  expect(ofId(lint({ "manifest.json": embedManifest(many) }), "FL712").map((d) => d.message)).toEqual(["at most 16 embeds"]);
  expect(ofId(lint({ "manifest.json": embedManifest(many.slice(0, 16)) }), "FL712")).toHaveLength(0);
  expect(ofId(lint({ "manifest.json": embedManifest("https://js.stripe.com") }), "FL712")[0].message)
    .toBe("embeds must be an array of https origins");
});

test("FL712 requires web.embed when embeds are listed; FL706 forbids them on a canvas", () => {
  const missing = lint({ "manifest.json": embedManifest(["https://js.stripe.com"], []) });
  expect(ofId(missing, "FL712").map((d) => d.message)).toEqual(["embeds need the web.embed capability"]);
  expect(ofId(lint({ "manifest.json": embedManifest([], []) }), "FL712")).toHaveLength(0);
  const canvas = lint({ "manifest.json": embedManifest(["https://js.stripe.com"], [], { profile: "canvas" }) });
  expect(ofId(canvas, "FL706").some((d) => d.message === "canvas profile cannot declare embeds")).toBe(true);
});

test("FL205 accepts embed origins only with web.embed declared", () => {
  const js = 'const s = document.createElement("script");\ns.src = "https://js.stripe.com/v3/";\nfetch("https://m.js.stripe.com/x");\n';
  const good = lint({ "manifest.json": embedManifest(["https://js.stripe.com", "https://*.js.stripe.com"]), "main.js": js });
  expect(ofId(good, "FL205")).toHaveLength(0);
  // Without the capability the embeds do not count.
  const noCap = lint({ "manifest.json": embedManifest(["https://js.stripe.com", "https://*.js.stripe.com"], []), "main.js": js });
  expect(ofId(noCap, "FL205")).toHaveLength(2);
  // A wildcard does not cover the bare domain, a lookalike, or a different port.
  const other = 'fetch("https://js.stripe.com.evil.example/x");\nfetch("https://evilstripe.com/x");\nfetch("https://js.stripe.com:444/x");\n';
  const strict = lint({ "manifest.json": embedManifest(["https://js.stripe.com", "https://*.stripe.com"]), "main.js": other });
  expect(ofId(strict, "FL205")).toHaveLength(3);
});

test("FL205 is skipped when the app declares network.open", () => {
  const js = 'fetch("https://api.example.com/x");\n';
  const open = lint({
    "manifest.json": embedManifest([], [{ id: "network.open", reason: "Read any feed the user adds" }]),
    "main.js": js,
  });
  expect(ofId(open, "FL205")).toHaveLength(0);
  const closed = lint({ "manifest.json": embedManifest([], []), "main.js": js });
  expect(ofId(closed, "FL205")).toHaveLength(1);
});

// Manifest background {module, triggers}; needs the background capability.
const bgManifest = (background, caps = [{ id: "background", reason: "Reply to messages while the app is closed" }], extra = {}) =>
  JSON.stringify({
    deft: 1, id: "bg", name: "Bg", version: "1.0.0", start_url: "./index.html",
    capabilities: caps, network: [], background, ...extra,
  });

test("FL713 accepts a valid background and FL705 knows the field", () => {
  const diags = lint({ "manifest.json": bgManifest({ module: "./bg.js", triggers: ["sms", "periodic:30", "notify.action", "geofence", "sync", "notification", "screening"] }) });
  expect(ofId(diags, "FL713")).toHaveLength(0);
  expect(ofId(diags, "FL705")).toHaveLength(0);
  expect(ofId(lint({ "manifest.json": bgManifest({ module: "/jobs/run.mjs", triggers: [] }) }), "FL713")).toHaveLength(0);
});

test("FL713 rejects bad background modules", () => {
  const bad = ["bg.js", "../bg.js", "./../bg.js", "//cdn.example.com/bg.js", "https://x.example.com/bg.js",
    "/_deft/v1/bg.js", "./_DEFT/x.js", "/bg.ts", "/bg.js?x=1", "/bg.js#a", "/b g.js", "./a//b.js", "/<x>.js", "", 7];
  for (const module of bad) {
    const diags = lint({ "manifest.json": bgManifest({ module, triggers: ["sms"] }) });
    expect(ofId(diags, "FL713").length).toBe(1);
  }
});

test("FL713 rejects bad triggers and periodic under 15 minutes", () => {
  for (const trigger of ["periodic:14", "periodic:", "periodic", "periodic:10081", "manual", "SMS", 3]) {
    const diags = lint({ "manifest.json": bgManifest({ module: "./bg.js", triggers: [trigger] }) });
    expect(ofId(diags, "FL713").length).toBe(1);
  }
  expect(ofId(lint({ "manifest.json": bgManifest({ module: "./bg.js", triggers: ["periodic:15", "periodic:60"] }) }), "FL713").length).toBe(1);
  expect(ofId(lint({ "manifest.json": bgManifest({ module: "./bg.js" }) }), "FL713").length).toBe(1);
  expect(ofId(lint({ "manifest.json": bgManifest("./bg.js") }), "FL713").length).toBe(1);
});

test("FL713 requires the background capability; canvas forbids background", () => {
  const diags = lint({ "manifest.json": bgManifest({ module: "./bg.js", triggers: ["sms"] }, []) });
  expect(ofId(diags, "FL713").map((d) => d.message)).toEqual(["background needs the background capability"]);
  const canvas = lint({ "manifest.json": bgManifest({ module: "./bg.js", triggers: ["sms"] }, [], { profile: "canvas" }) });
  expect(ofId(canvas, "FL706").some((d) => d.message.includes("background"))).toBe(true);
});

// Manifest handles {mimeTypes?, schemes?, links?}; mimeTypes need share.receive.
const handlesManifest = (handles, caps = [{ id: "share.receive", reason: "Open pictures other apps send here" }], extra = {}) =>
  JSON.stringify({
    deft: 1, id: "hh", name: "Hh", version: "1.0.0", start_url: "./index.html",
    capabilities: caps, network: [], handles, ...extra,
  });

test("FL714 accepts valid handles and FL705 knows the field", () => {
  const diags = lint({ "manifest.json": handlesManifest({ mimeTypes: ["image/*", "application/pdf"], schemes: ["web+deft", "mailto"] }) });
  expect(ofId(diags, "FL714")).toHaveLength(0);
  expect(ofId(diags, "FL705")).toHaveLength(0);
  expect(ofId(lint({ "manifest.json": handlesManifest({ schemes: ["web+deft"] }, []) }), "FL714")).toHaveLength(0);
  // The old web+fl scheme no longer reaches the app.
  expect(ofId(lint({ "manifest.json": handlesManifest({ schemes: ["web+fl"] }, []) }), "FL714").map((d) => d.severity)).toEqual(["warn"]);
});

test("FL714 rejects bad entries and more than 16", () => {
  const bad = [
    { mimeTypes: ["*/*"] }, { mimeTypes: ["image"] }, { mimeTypes: ["Image/PNG"] }, { mimeTypes: [3] },
    { mimeTypes: "image/*" }, { schemes: ["https"] }, { schemes: ["web+"] }, { schemes: ["web+Foo"] },
    { links: ["http://a.example"] }, { links: ["https://localhost"] }, { links: ["https://A.example"] },
    { mimeTypes: Array.from({ length: 17 }, (_, i) => `image/x${i}`) },
  ];
  for (const handles of bad) {
    const errors = ofId(lint({ "manifest.json": handlesManifest(handles) }), "FL714").filter((d) => d.severity === "error");
    expect(errors.length).toBe(1);
  }
  expect(ofId(lint({ "manifest.json": handlesManifest(["image/*"]) }), "FL714").length).toBe(1);
});

test("FL714 requires share.receive for mimeTypes and warns about inert schemes and links", () => {
  const missing = lint({ "manifest.json": handlesManifest({ mimeTypes: ["image/*"] }, []) });
  expect(ofId(missing, "FL714").map((d) => d.severity)).toEqual(["error"]);
  const inert = ofId(lint({ "manifest.json": handlesManifest({ schemes: ["web+notes"], links: ["https://notes.example"] }) }), "FL714");
  expect(inert.map((d) => d.severity)).toEqual(["warn", "warn"]);
  const canvas = lint({ "manifest.json": handlesManifest({ schemes: ["web+deft"] }, [], { profile: "canvas" }) });
  expect(ofId(canvas, "FL706").some((d) => d.message === "canvas profile cannot declare handles")).toBe(true);
});

// Manifest wallpaper {page}; needs the wallpaper capability.
const wpManifest = (wallpaper, caps = [{ id: "wallpaper", reason: "Draw a clock as your home screen wallpaper" }], extra = {}) =>
  JSON.stringify({
    deft: 1, id: "wp", name: "Wp", version: "1.0.0", start_url: "./index.html",
    capabilities: caps, network: [], wallpaper, ...extra,
  });

test("FL715 accepts a valid wallpaper and FL705 knows the field", () => {
  const diags = lint({ "manifest.json": wpManifest({ page: "./wallpaper.html" }) });
  expect(ofId(diags, "FL715")).toHaveLength(0);
  expect(ofId(diags, "FL705")).toHaveLength(0);
  expect(ofId(lint({ "manifest.json": wpManifest({ page: "/wp/clock.htm" }) }), "FL715")).toHaveLength(0);
});

test("FL715 rejects bad wallpaper pages", () => {
  const bad = ["wallpaper.html", "../w.html", "./../w.html", "//cdn.example.com/w.html", "https://x.example.com/w.html",
    "/_deft/v1/w.html", "./_DEFT/w.html", "/w.js", "/w.html?x=1", "/w.html#a", "/w x.html", "./a//w.html", "/<x>.html", "", 7];
  for (const page of bad) {
    const diags = lint({ "manifest.json": wpManifest({ page }) });
    expect(ofId(diags, "FL715").length).toBe(1);
  }
  expect(ofId(lint({ "manifest.json": wpManifest({}) }), "FL715").length).toBe(1);
  expect(ofId(lint({ "manifest.json": wpManifest("./w.html") }), "FL715").length).toBe(1);
});

test("FL715 requires the wallpaper capability; canvas forbids wallpaper", () => {
  const diags = lint({ "manifest.json": wpManifest({ page: "./w.html" }, []) });
  expect(ofId(diags, "FL715").map((d) => d.message)).toEqual(["wallpaper needs the wallpaper capability"]);
  const canvas = lint({ "manifest.json": wpManifest({ page: "./w.html" }, [], { profile: "canvas" }) });
  expect(ofId(canvas, "FL706").some((d) => d.message.includes("wallpaper"))).toBe(true);
});

test("FL303 treats the manifest wallpaper page as an entry point", () => {
  const files = {
    "manifest.json": wpManifest({ page: "./wallpaper/index.html" }),
    "index.html": "<!doctype html><html><body></body></html>",
    "wallpaper/index.html": '<!doctype html><html><body><script type="module" src="./main.js"></script></body></html>',
    "wallpaper/main.js": "import { x } from '../clock.js';\n",
    "clock.js": "export const x = 1;\n",
    "stray.js": "export const y = 1;\n",
  };
  expect(ofId(lint(files), "FL303").map((d) => d.path)).toEqual(["stray.js"]);
});

// Manifest keyboard {page}; needs the ime capability.
const kbManifest = (keyboard, caps = [{ id: "ime", reason: "Type with a simple keyboard in any app" }], extra = {}) =>
  JSON.stringify({
    deft: 1, id: "kb", name: "Kb", version: "1.0.0", start_url: "./index.html",
    capabilities: caps, network: [], keyboard, ...extra,
  });

test("FL716 accepts a valid keyboard and FL705 knows the field", () => {
  const diags = lint({ "manifest.json": kbManifest({ page: "./keyboard/index.html" }) });
  expect(ofId(diags, "FL716")).toHaveLength(0);
  expect(ofId(diags, "FL705")).toHaveLength(0);
});

test("FL716 rejects bad keyboard pages", () => {
  const bad = ["keyboard.html", "../k.html", "//cdn.example.com/k.html", "/_deft/v1/k.html", "/k.js", "/k.html?x=1", "", 7];
  for (const page of bad) {
    expect(ofId(lint({ "manifest.json": kbManifest({ page }) }), "FL716").length).toBe(1);
  }
  expect(ofId(lint({ "manifest.json": kbManifest({}) }), "FL716").length).toBe(1);
  expect(ofId(lint({ "manifest.json": kbManifest("./k.html") }), "FL716").length).toBe(1);
});

test("FL716 requires the ime capability; canvas forbids keyboard", () => {
  const diags = lint({ "manifest.json": kbManifest({ page: "./k.html" }, []) });
  expect(ofId(diags, "FL716").map((d) => d.message)).toEqual(["keyboard needs the ime capability"]);
  const canvas = lint({ "manifest.json": kbManifest({ page: "./k.html" }, [], { profile: "canvas" }) });
  expect(ofId(canvas, "FL706").some((d) => d.message.includes("keyboard"))).toBe(true);
});

test("FL303 treats the manifest keyboard page as an entry point", () => {
  const files = {
    "manifest.json": kbManifest({ page: "./keyboard/index.html" }),
    "index.html": "<!doctype html><html><body></body></html>",
    "keyboard/index.html": '<!doctype html><html><body><script type="module" src="./main.js"></script></body></html>',
    "keyboard/main.js": "export const k = 1;\n",
    "stray.js": "export const y = 1;\n",
  };
  expect(ofId(lint(files), "FL303").map((d) => d.path)).toEqual(["stray.js"]);
});

const RUNTIME_HEAD = [
  "<!doctype html><html lang=\"en\"><head>",
  "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1, viewport-fit=cover\">",
  "<link rel=\"stylesheet\" href=\"/_deft/v1/base.css\">",
  "<link rel=\"stylesheet\" href=\"/_deft/v1/tokens.css\">",
  "<link rel=\"stylesheet\" href=\"/_deft/v1/components.css\">",
  "<link rel=\"stylesheet\" href=\"/_deft/v1/theme.css\">",
  "<link rel=\"stylesheet\" href=\"./app.css\">",
  "<script type=\"module\" src=\"./main.js\"></script>",
  "</head><body>",
];

test("FL603 requires base, tokens, components, theme in that order", () => {
  const html = (links) => ["<!doctype html><html lang=\"en\"><head>",
    "<meta name=\"viewport\" content=\"width=device-width, viewport-fit=cover\">",
    ...links.map((h) => `<link rel="stylesheet" href="${h}">`),
    "<script type=\"module\" src=\"./main.js\"></script></head></html>"].join("");
  const ok = lint({ "index.html": html(["/_deft/v1/base.css", "/_deft/v1/tokens.css", "/_deft/v1/components.css", "/_deft/v1/theme.css"]) });
  expect(ofId(ok, "FL603")).toHaveLength(0);
  const swapped = lint({ "index.html": html(["/_deft/v1/tokens.css", "/_deft/v1/base.css", "/_deft/v1/components.css", "/_deft/v1/theme.css"]) });
  expect(ofId(swapped, "FL603")[0]).toMatchObject({ severity: "error", message: "runtime CSS must link base.css, tokens.css, components.css, theme.css in that order" });
  const missingTokens = lint({ "index.html": html(["/_deft/v1/base.css", "/_deft/v1/components.css", "/_deft/v1/theme.css"]) });
  expect(ofId(missingTokens, "FL603")).toHaveLength(1);
});

test("FL616 warns on accent tokens outside the allowed selectors", () => {
  const diags = lint({
    "app.css": "@layer deft.components {\n  .deft-btn[data-primary] { background: var(--deft-accent); color: var(--deft-on-accent); }\n  .note { color: var(--deft-accent); }\n  .deft-tabbar > [aria-current]::after { background: var(--deft-accent); }\n}\n",
  });
  const hits = ofId(diags, "FL616");
  expect(hits.map((d) => [d.line, d.severity])).toEqual([[3, "warn"]]);
});

test("FL619 flags legacy shim tokens and names the replacement", () => {
  const diags = lint({ "app.css": "@layer deft.components {\n  .a { color: var(--deft-color-primary); background: var(--deft-surface); }\n}\n" });
  expect(ofId(diags, "FL619").map((d) => d.message)).toEqual([
    "deprecated token --deft-color-primary; use --deft-accent",
    "deprecated token --deft-surface; use --deft-bg-grouped",
  ]);
});

test("FL638 flags var(--deft-x) that is not a real token and suggests the nearest", () => {
  const diags = lint({ "app.css": "@layer deft.components {\n  .a { padding: var(--deft-space-2); color: var(--deft-on-surface); font-size: var(--deft-font-size-lg); gap: var(--deft-space-s); width: var(--deft-min, 4rem); }\n}\n" });
  const hits = ofId(diags, "FL638");
  expect(hits.map((d) => d.severity)).toEqual(["error", "error", "error"]);
  expect(hits[0].message).toContain("--deft-space-2");
  expect(hits[0].message).toMatch(/var\(--deft-space-/);
  expect(hits[1].message).toContain("--deft-label");
});

test("FL638 also covers HTML and JS strings, and a --deft-x declared in the same file passes", () => {
  const html = lint({ "index.html": '<!DOCTYPE html><html lang="en"><body><div style="padding: var(--deft-nope)"></div></body></html>' });
  expect(ofId(html, "FL638").length).toBe(1);
  const js = lint({ "main.js": 'el.style.padding = "var(--deft-space-9)";\n' });
  expect(ofId(js, "FL638").length).toBe(1);
  const local = lint({ "app.css": "@layer deft.components {\n  .a { --deft-mine: 1px; padding: var(--deft-mine); }\n}\n" });
  expect(ofId(local, "FL638").length).toBe(0);
});

test("FL638 theme token list matches Palette.kt", async () => {
  const { THEME_TOKENS } = await import("../src/design.js");
  const kt = await Bun.file(new URL("../../../android/app/src/main/java/app/deft/config/Palette.kt", import.meta.url)).text();
  const names = [...kt.matchAll(/"(--deft-[a-z0-9-]+):/g)].map((m) => m[1]).filter((n) => !/^--deft-(color-|fg|surface|muted|red)/.test(n));
  for (const n of names) expect(THEME_TOKENS).toContain(n);
});

test("FL617 wants an icon and text next to the error colour", () => {
  const diags = lint({
    "index.html": [...RUNTIME_HEAD,
      "<div class=\"deft-alert\" data-tone=\"error\"><span>Failed</span></div>",
      "<div class=\"deft-alert\" data-tone=\"error\"><svg class=\"deft-icon\"><path d=\"M0 0\"/></svg><span>Failed</span></div>",
      "</body></html>"].join(""),
    "app.css": "@layer deft.components { .a { color: var(--deft-label); } }\n",
  });
  expect(ofId(diags, "FL617")).toHaveLength(1);
});

test("FL618 flags a unicode glyph used as an icon button", () => {
  const diags = lint({
    "index.html": [...RUNTIME_HEAD, "<button type=\"button\" aria-label=\"Back\">‹</button>", "<button type=\"button\" aria-label=\"Close\">&times;</button>", "<button type=\"button\"><deft-icon name=\"x\"></deft-icon></button>", "</body></html>"].join(""),
  });
  expect(ofId(diags, "FL618")).toHaveLength(2);
});

test("FL627, FL628 and FL631 keep motion behind tokens", () => {
  const diags = lint({
    "app.css": "@layer deft.components {\n  .a { transition: opacity 200ms ease; }\n  .b { transition: opacity var(--deft-duration-s) var(--deft-ease); }\n  .c { animation: spin 1s cubic-bezier(.2, 0, 0, 1); }\n}\n@keyframes spin { to { opacity: 0; } }\n",
  });
  expect(ofId(diags, "FL631")).toHaveLength(2);
  expect(ofId(diags, "FL628").map((d) => d.message)).toEqual(["raw cubic-bezier() easing; use var(--deft-ease*) or a spring token"]);
  expect(ofId(diags, "FL627")).toHaveLength(1);
});

test("FL629 flags rules outside a layer, but not @layer statements", () => {
  const diags = lint({
    "app.css": "@layer deft.components, deft.utilities;\n@layer deft.components {\n  .a { color: var(--deft-label); }\n}\n.loose { color: var(--deft-label); }\n",
  });
  expect(ofId(diags, "FL629").map((d) => d.line)).toEqual([5]);
});

test("FL610 flags an oklch literal but not color-mix of tokens", () => {
  const diags = lint({
    "app.css": "@layer deft.components {\n  .a { color: oklch(0.58 0.21 27); background: color-mix(in oklch, var(--deft-bg) 60%, transparent); }\n}\n",
  });
  expect(ofId(diags, "FL610").map((d) => d.severity)).toEqual(["error"]);
});

test("FL630 warns on a second primary button in one screen", () => {
  const diags = lint({
    "index.html": [...RUNTIME_HEAD, "<button class=\"deft-btn\" data-primary>One</button>", "<button class=\"deft-btn\" data-primary>Two</button>", "</body></html>"].join(""),
  });
  expect(ofId(diags, "FL630")).toHaveLength(1);
});

test("FL630 counts a dialog or sheet as its own screen", () => {
  const dlg = "<dialog class=\"deft-sheet\"><button class=\"deft-btn\" data-primary>Save</button></dialog>";
  const ok = lint({ "index.html": [...RUNTIME_HEAD, "<button class=\"deft-btn\" data-primary>One</button>", dlg, "</body></html>"].join("") });
  expect(ofId(ok, "FL630")).toHaveLength(0);
  const bad = lint({ "index.html": [...RUNTIME_HEAD, dlg.replace("</dialog>", "<button class=\"deft-btn\" data-primary>Two</button></dialog>"), "</body></html>"].join("") });
  expect(ofId(bad, "FL630")).toHaveLength(1);
});

test("FL630 counts a [hidden] view as its own screen", () => {
  const view = (inner) => `<section class="a" hidden><div>${inner}</div></section>`;
  const btn = "<button class=\"deft-btn\" data-primary>X</button>";
  const ok = lint({ "index.html": [...RUNTIME_HEAD, btn, view(btn), "</body></html>"].join("") });
  expect(ofId(ok, "FL630")).toHaveLength(0);
  const cls = lint({ "index.html": [...RUNTIME_HEAD, btn, `<section class="x hidden">${btn}</section>`, "</body></html>"].join("") });
  expect(ofId(cls, "FL630")).toHaveLength(1);
  const bad = lint({ "index.html": [...RUNTIME_HEAD, btn, view(btn + btn), "</body></html>"].join("") });
  expect(ofId(bad, "FL630")).toHaveLength(1);
});

test("FL617 accepts an alert whose text JS fills when it is marked data-deft-text or aria-live", () => {
  const alert = (extra) => `<div class="deft-alert" data-tone="error" ${extra}><deft-icon name="circle-alert" aria-hidden="true"></deft-icon><span></span></div>`;
  const bad = lint({ "index.html": [...RUNTIME_HEAD, alert(""), "</body></html>"].join("") });
  expect(ofId(bad, "FL617")).toHaveLength(1);
  for (const mark of ["data-deft-text", "aria-live=\"polite\""]) {
    const ok = lint({ "index.html": [...RUNTIME_HEAD, alert(mark), "</body></html>"].join("") });
    expect(ofId(ok, "FL617")).toHaveLength(0);
  }
});

test("FL303 treats the manifest background module as an entry point", () => {
  const files = {
    "index.html": "<!doctype html><html><head><title>t</title></head><body><script type=\"module\" src=\"./main.js\"></script></body></html>",
    "main.js": "// main\n",
    "bg.js": "// background\nimport './store.js';\n",
    "store.js": "// store\n",
    "stray.js": "// stray\n",
    "manifest.json": JSON.stringify({ deft: 1, id: "x", background: { module: "./bg.js", triggers: ["notification"] } }),
  };
  expect(ofId(lint(files), "FL303").map((d) => d.path)).toEqual(["stray.js"]);
});

test("FL403 accepts a try-guarded legacy getItem read but not writes", () => {
  const ok = "function legacy() {\n  try { return JSON.parse(localStorage.getItem('notes')) ?? []; } catch (e) { return []; }\n}\n";
  expect(ofId(lint({ "main.js": ok }), "FL403")).toHaveLength(0);
  const bad = ok + "localStorage.setItem('notes', '[]');\n";
  expect(ofId(lint({ "main.js": bad }), "FL403").map((d) => d.line)).toEqual([4]);
  expect(ofId(lint({ "main.js": "const v = localStorage.getItem('x');\n" }), "FL403")).toHaveLength(1);
});

test("FL614 accepts documented component hook properties", () => {
  const css = "@layer deft.components {\n  .stats { --deft-min: 8rem; --deft-pad: 8px; --deft-radius-outer: var(--deft-radius-sm); }\n  .bar { --deft-value: 40%; --deft-segs: 4; }\n  :root { --deft-label: red; }\n}\n";
  expect(ofId(lint({ "app.css": css }), "FL614").map((d) => d.line)).toEqual([4]);
});

test("FL613 accepts the full radius token and 0 in shorthands", () => {
  const css = "@layer deft.components {\n  .dot { border-radius: var(--deft-radius-full); }\n  .tab { border-radius: var(--deft-radius-md) var(--deft-radius-md) 0 0; }\n  .flat { border-radius: 0; }\n  .bad { border-radius: 50%; }\n}\n";
  expect(ofId(lint({ "app.css": css }), "FL613").map((d) => d.line)).toEqual([5]);
});

test("FL616 flags an accent read in JS unless the file marks data-live", () => {
  const bare = "const c = getComputedStyle(document.documentElement).getPropertyValue('--deft-accent');\n";
  expect(ofId(lint({ "main.js": bare }), "FL616")).toHaveLength(1);
  const live = bare + "el.toggleAttribute('data-live', true);\n";
  expect(ofId(lint({ "main.js": live }), "FL616")).toHaveLength(0);
});

test("FL632 flags an icon name that is not in the sprite, with the nearest names", () => {
  const diags = lint({
    "index.html": [...RUNTIME_HEAD, "<deft-icon name=\"shiled\" aria-hidden=\"true\"></deft-icon>", "<deft-icon name=\"shield\" aria-hidden=\"true\"></deft-icon>", "</body></html>"].join(""),
    "main.js": "const t = '<deft-icon name=\"flaem\"></deft-icon>';\nconst ok = '<deft-icon name=\"${n}\"></deft-icon>';\nexport { t, ok };\n",
  });
  const hits = ofId(diags, "FL632");
  expect(hits).toHaveLength(2);
  expect(hits[0].message).toContain("Nearest: shield");
  expect(hits.some((d) => d.message.includes("flame"))).toBe(true);
});

test("FL633 warns on a button whose id and class never appear in the JS", () => {
  const html = '<button id="save" class="deft-btn">Save</button><button id="add" class="deft-btn">Add</button><button class="deft-btn" data-action="clear">Clear</button>';
  const diags = lint({ "index.html": html, "main.js": "// h\ndocument.getElementById('save').addEventListener('click', () => {});\n" });
  const hits = ofId(diags, "FL633");
  expect(hits.length).toBe(2);
  expect(hits[0].message).toContain("add");
  expect(hits[1].message).toContain("clear");
  expect(hits.every((h) => h.severity === "warn")).toBe(true);
});

test("FL633 stays quiet when wired, delegated, in a form, or linted without JS", () => {
  const html = '<form><button class="deft-btn">Go</button></form><button id="a" class="deft-btn">A</button><button class="deft-btn">Bare</button>';
  expect(ofId(lint({ "index.html": html, "main.js": "$('#a').onclick = go;" }), "FL633").length).toBe(0);
  expect(ofId(lint({ "index.html": html, "main.js": "root.addEventListener('click', (e) => e.target.closest('button'));" }), "FL633").length).toBe(0);
  expect(ofId(lint({ "index.html": '<button id="x" class="deft-btn">X</button>' }), "FL633").length).toBe(0);
});

// W1: manifest widget {page, sizes}; no capability needed. Canvas forbids it.
const widgetManifest = (widget, extra = {}) =>
  JSON.stringify({
    deft: 1, id: "wg", name: "Wg", version: "1.0.0", start_url: "./index.html",
    capabilities: [], network: [], widget, ...extra,
  });

test("FL717 accepts a valid widget and FL705 knows the field", () => {
  const diags = lint({ "manifest.json": widgetManifest({ page: "./widget.html", sizes: ["2x1", "2x2", "4x2"] }) });
  expect(ofId(diags, "FL717")).toHaveLength(0);
  expect(ofId(diags, "FL705")).toHaveLength(0);
  expect(ofId(lint({ "manifest.json": widgetManifest({ page: "/w/x.htm", sizes: ["1x1", "4x1"] }) }), "FL717")).toHaveLength(0);
});

test("FL717 rejects bad widget pages, sizes and shapes", () => {
  const badPages = ["widget.html", "../w.html", "//cdn.example.com/w.html", "/_deft/w.html", "./w.js"];
  for (const page of badPages) {
    expect(ofId(lint({ "manifest.json": widgetManifest({ page, sizes: ["2x1"] }) }), "FL717").length).toBe(1);
  }
  const badSizes = [[], ["3x3"], ["2X1"], ["2x1", "5x1"], [2], "2x1"];
  for (const sizes of badSizes) {
    expect(ofId(lint({ "manifest.json": widgetManifest({ page: "./w.html", sizes }) }), "FL717").length).toBeGreaterThan(0);
  }
  expect(ofId(lint({ "manifest.json": widgetManifest({ page: "./w.html" }) }), "FL717").length).toBe(1);
  expect(ofId(lint({ "manifest.json": widgetManifest("./w.html") }), "FL717").length).toBe(1);
});

test("FL717 forbids widget on canvas and FL303 treats the widget page as an entry point", () => {
  const canvas = lint({ "manifest.json": widgetManifest({ page: "./w.html", sizes: ["2x1"] }, { profile: "canvas" }) });
  expect(ofId(canvas, "FL706").some((d) => d.message.includes("widget"))).toBe(true);
  const files = {
    "manifest.json": widgetManifest({ page: "./widget/index.html", sizes: ["2x1"] }),
    "index.html": "<!doctype html><html><body></body></html>",
    "widget/index.html": '<!doctype html><html><body><script type="module" src="./main.js"></script></body></html>',
    "widget/main.js": "export const x = 1;\n",
  };
  expect(ofId(lint(files), "FL303")).toHaveLength(0);
});

// W5: manifest `tools` (FL718) and their handler files.
const toolsManifest = (tools, extra = {}) =>
  JSON.stringify({ deft: 1, id: "tool-app", name: "Tool app", version: "1.0.0", start_url: "./index.html", capabilities: [], network: [], tools, ...extra });
const okTool = (over = {}) => ({
  name: "convert",
  description: "Convert a length between units.",
  params: { type: "object", properties: { value: { type: "number" }, to: { type: "string", enum: ["km", "mi"] } }, required: ["value"] },
  handler: "./tools.js#convert",
  ...over,
});
const toolFiles = (tools, handlerSource = "export function convert(p) { return p.value; }\n", extra = {}) => ({
  "manifest.json": toolsManifest(tools, extra),
  "index.html": "<!doctype html><html><body></body></html>",
  "tools.js": handlerSource,
});

test("FL718 accepts valid tools and FL705 knows the field", () => {
  const diags = lint(toolFiles([okTool(), okTool({ name: "calc", handler: { isolate: "./calc.js#calc" } })], undefined));
  expect(ofId(diags, "FL718").filter((d) => d.path === "manifest.json" && !d.message.includes("calc.js"))).toHaveLength(0);
  expect(ofId(diags, "FL705")).toHaveLength(0);
});

test("FL718 rejects bad names, descriptions, duplicates, handlers and shapes", () => {
  const bad = [
    okTool({ name: "Convert" }), okTool({ name: "1x" }), okTool({ name: "a".repeat(33) }), okTool({ name: "a-b" }),
    okTool({ description: "" }), okTool({ description: "x".repeat(301) }),
    okTool({ handler: "tools.js#convert" }), okTool({ handler: "./tools.js" }), okTool({ handler: "./tools.css#convert" }),
    okTool({ handler: "../tools.js#convert" }), okTool({ handler: "./tools.js#1x" }), okTool({ handler: { isolate: "./t.js" } }),
    okTool({ handler: { other: "./t.js#f" } }), okTool({ handler: 5 }), "text",
    okTool({ handler: "/./_deft/v1/store.js#f" }), okTool({ handler: "./a/../_deft/x.js#f" }), okTool({ handler: "./_deft/x.js#f" }),
    okTool({ handler: "/_DEFT/x.js#f" }), okTool({ handler: "./%5fdeft/x.js#f" }), okTool({ handler: "./%2e%2e/x.js#f" }),
    okTool({ handler: { isolate: "/./_deft/x.js#f" } }),
  ];
  for (const tool of bad) {
    expect(ofId(lint({ "manifest.json": toolsManifest([tool]) }), "FL718").length).toBeGreaterThan(0);
  }
  expect(ofId(lint({ "manifest.json": toolsManifest([okTool(), okTool()]) }), "FL718").length).toBe(1);
  expect(ofId(lint({ "manifest.json": toolsManifest("nope") }), "FL718").length).toBe(1);
  const nine = Array.from({ length: 9 }, (_, i) => okTool({ name: `t${i}` }));
  expect(ofId(lint({ "manifest.json": toolsManifest(nine) }), "FL718").length).toBe(1);
});

test("FL713 and FL718 normalise dot segments before the /_deft/ check", () => {
  expect(ofId(lint({ "manifest.json": bgManifest({ module: "/./_deft/x.js", triggers: [] }) }), "FL713").length).toBe(1);
  expect(ofId(lint({ "manifest.json": bgManifest({ module: "./a/./b.js", triggers: [] }) }), "FL713")).toHaveLength(0);
  // "./a/./tools.js" is the same file as a/tools.js
  const files = { ...toolFiles([okTool({ handler: "./a/./tools.js#convert" })]), "a/tools.js": "export function convert(p) { return p.value; }\n" };
  expect(ofId(lint(files), "FL718").filter((d) => d.message.includes("not in the package"))).toHaveLength(0);
});

test("FL718 holds params to the small JSON-schema subset", () => {
  const badParams = [
    { type: "array" }, { type: "object", properties: { a: { type: "object" } } },
    { type: "object", properties: { a: { type: "string", pattern: "x" } } },
    { type: "object", properties: { a: { type: "array", items: { type: "array" } } } },
    { type: "object", properties: { a: { type: "array" } } },
    { type: "object", properties: { "bad name": { type: "string" } } },
    { type: "object", properties: { a: { type: "string" } }, required: ["b"] },
    { type: "object", properties: { a: { type: "string", enum: [1] } } },
    { type: "object", properties: { a: { type: "string", minimum: 1 } } },
    { type: "object", properties: { a: { type: "number", maxLength: 3 } } },
    { type: "object", oneOf: [] },
    { type: "object", properties: Object.fromEntries(Array.from({ length: 13 }, (_, i) => [`p${i}`, { type: "string" }])) },
  ];
  for (const params of badParams) {
    expect(ofId(lint({ "manifest.json": toolsManifest([okTool({ params })]) }), "FL718").length).toBeGreaterThan(0);
  }
  const good = { type: "object", properties: { a: { type: "array", items: { type: "integer" }, description: "ids" }, b: { type: "string", maxLength: 40 }, c: { type: "integer", minimum: 0, maximum: 9 } } };
  expect(ofId(lint({ "manifest.json": toolsManifest([okTool({ params: good })]) }), "FL718")).toHaveLength(0);
  expect(ofId(lint({ "manifest.json": toolsManifest([okTool({ params: undefined })]) }), "FL718")).toHaveLength(0);
});

test("FL718: canvas forbids tools; handler files are entry points, not FL303 orphans", () => {
  const canvas = lint({ "manifest.json": toolsManifest([okTool()], { profile: "canvas" }) });
  expect(ofId(canvas, "FL706").some((d) => d.message.includes("tools"))).toBe(true);
  expect(ofId(lint(toolFiles([okTool()])), "FL303")).toHaveLength(0);
});

test("FL718 checks the handler files of a whole package", () => {
  expect(ofId(lint(toolFiles([okTool()])), "FL718")).toHaveLength(0);
  const noFile = toolFiles([okTool()]);
  delete noFile["tools.js"];
  expect(ofId(lint(noFile), "FL718").some((d) => d.message.includes("not in the package"))).toBe(true);
  // A page handler that never exports its function is a warning.
  const warn = ofId(lint(toolFiles([okTool()], "function convert(p) { return 1; }\n")), "FL718");
  expect(warn.length).toBe(1);
  expect(warn[0].severity).toBe("warn");
  // Manifest alone (manifest.json written before tools.js) does not complain about the missing file.
  expect(ofId(lint({ "manifest.json": toolsManifest([okTool()]) }), "FL718")).toHaveLength(0);
});

test("FL718: an isolate handler must be a plain synchronous script defining its function", () => {
  const iso = (src, name = "calc") => lint(toolFiles([okTool({ name, handler: { isolate: `./tools.js#${name}` } })], src));
  expect(ofId(iso("function calc(p) { return p.value * 2; }\n"), "FL718")).toHaveLength(0);
  expect(ofId(iso("const calc = (p) => ({ x: p.value });\n"), "FL718")).toHaveLength(0);
  const modules = ofId(iso("export function calc(p) { return 1; }\n"), "FL718");
  expect(modules.some((d) => d.severity === "error" && d.message.includes("plain script"))).toBe(true);
  expect(ofId(iso("import { x } from './x.js';\nfunction calc(p) { return x; }\n"), "FL718").some((d) => d.message.includes("plain script"))).toBe(true);
  expect(ofId(iso("// export is a word here\nfunction calc(p) { return 1; }\n"), "FL718")).toHaveLength(0);
  expect(ofId(iso("function other() {}\n"), "FL718").some((d) => d.message.includes("must define"))).toBe(true);
  expect(ofId(iso("async function calc(p) { return 1; }\n"), "FL718").some((d) => d.message.includes("synchronous"))).toBe(true);
});

// FL634-FL636: canvas and widget packages (W6).
function widgetPkg(js, css = "") {
  return {
    "manifest.json": widgetManifest({ page: "./widget.html", sizes: ["2x1"] }),
    "widget.html": "<!doctype html><html lang=\"en\"></html>",
    "widget.js": js,
    ...(css ? { "widget.css": css } : {}),
  };
}

test("FL634 errors on a requestAnimationFrame loop that schedules itself with no cancel", () => {
  const named = "function tick() { draw(); requestAnimationFrame(tick); }\nrequestAnimationFrame(tick);\n";
  expect(ofId(lint(widgetPkg(named)), "FL634")).toHaveLength(1);
  const inline = "requestAnimationFrame(function step() { draw(); requestAnimationFrame(step); });\n";
  expect(ofId(lint(widgetPkg(inline)), "FL634")).toHaveLength(1);
  const arrow = "const loop = () => { draw(); requestAnimationFrame(loop); };\nrequestAnimationFrame(loop);\n";
  expect(ofId(lint(widgetPkg(arrow)), "FL634")).toHaveLength(1);
});

test("FL634 accepts a single frame, a loop with cancelAnimationFrame, and no widget or canvas profile", () => {
  expect(ofId(lint(widgetPkg("requestAnimationFrame(() => draw());\n")), "FL634")).toHaveLength(0);
  const stoppable = "let id;\nfunction tick() { draw(); id = requestAnimationFrame(tick); }\nid = requestAnimationFrame(tick);\n"
    + "function stop() { cancelAnimationFrame(id); }\n";
  expect(ofId(lint(widgetPkg(stoppable)), "FL634")).toHaveLength(0);
  const loop = "function tick() { requestAnimationFrame(tick); }\nrequestAnimationFrame(tick);\n";
  expect(ofId(lint({ "index.html": "<!doctype html><html lang=\"en\"></html>", "main.js": loop }, { profile: "myapp" }), "FL634")).toHaveLength(0);
  expect(ofId(lint({ "index.html": "<!doctype html><html lang=\"en\"></html>", "main.js": loop }, { profile: "canvas" }), "FL634")).toHaveLength(1);
});

test("FL635 warns on a timer under 5000 ms in a widget or canvas, and not on a longer one", () => {
  expect(ofId(lint(widgetPkg("setInterval(refresh, 1000);\n")), "FL635")).toHaveLength(1);
  expect(ofId(lint(widgetPkg("setTimeout(() => go(), 4999);\n")), "FL635")).toHaveLength(1);
  expect(ofId(lint(widgetPkg("setInterval(refresh, 60000);\nsetTimeout(go, 5000);\n")), "FL635")).toHaveLength(0);
  expect(ofId(lint(widgetPkg("setInterval(refresh, delay);\n")), "FL635")).toHaveLength(0);
  expect(ofId(lint({ "index.html": "<!doctype html><html lang=\"en\"></html>", "main.js": "setInterval(f, 100);\n" }), "FL635")).toHaveLength(0);
});

test("FL636 warns on blur over 8px and on backdrop-filter in widget CSS, not on small blur", () => {
  expect(ofId(lint(widgetPkg("", ".a { filter: blur(12px); }\n")), "FL636")).toHaveLength(1);
  expect(ofId(lint(widgetPkg("", ".a { filter: blur(8px); }\n")), "FL636")).toHaveLength(0);
  expect(ofId(lint(widgetPkg("", ".a { backdrop-filter: blur(4px); }\n")), "FL636")).toHaveLength(1);
  expect(ofId(lint(widgetPkg("", ".a { backdrop-filter: none; }\n")), "FL636")).toHaveLength(0);
});

test("FL637 warns on a canvas page with deft-widget that never loads elements.js", () => {
  const page = (head) => ({ "index.html": `<!doctype html><html lang="en"><head>${head}</head><body><deft-widget app="myapp:a" size="2x1"></deft-widget></body></html>` });
  expect(ofId(lint(page(""), { profile: "canvas" }), "FL637")).toHaveLength(1);
  expect(ofId(lint(page('<script type="module" src="/_deft/v1/elements.js"></script>'), { profile: "canvas" }), "FL637")).toHaveLength(0);
});

// Manifest passkeys {rpIds} for the webauthn bridge.
const passkeysManifest = (passkeys, caps = [{ id: "webauthn", reason: "Sign in to your account with a passkey" }], extra = {}) =>
  JSON.stringify({
    deft: 1, id: "pk", name: "Pk", version: "1.0.0", start_url: "./index.html",
    capabilities: caps, network: [], ...(passkeys === undefined ? {} : { passkeys }), ...extra,
  });

test("FL719 accepts valid passkeys and FL705 knows the field", () => {
  const diags = lint({ "manifest.json": passkeysManifest({ rpIds: ["example.com", "login.example.co.uk"] }) });
  expect(ofId(diags, "FL719")).toHaveLength(0);
  expect(ofId(diags, "FL705")).toHaveLength(0);
});

test("FL719 rejects bad rpIds, more than 4, and a missing pair", () => {
  const bad = [
    { rpIds: ["Example.com"] }, { rpIds: ["localhost"] }, { rpIds: ["10.0.0.1"] }, { rpIds: ["example"] },
    { rpIds: ["abc.app.deft.internal"] }, { rpIds: ["printer.local"] }, { rpIds: ["foo.test"] }, { rpIds: ["a.localhost"] },
    { rpIds: ["https://example.com"] }, { rpIds: ["-x.com"] }, { rpIds: [3] }, { rpIds: [] }, { rpIds: "example.com" }, [],
    { rpIds: ["a.com", "b.com", "c.com", "d.com", "e.com"] },
  ];
  for (const passkeys of bad) {
    expect(ofId(lint({ "manifest.json": passkeysManifest(passkeys) }), "FL719").length).toBeGreaterThan(0);
  }
  // passkeys without the capability, and the capability without passkeys.
  expect(ofId(lint({ "manifest.json": passkeysManifest({ rpIds: ["example.com"] }, []) }), "FL719")).toHaveLength(1);
  expect(ofId(lint({ "manifest.json": passkeysManifest(undefined) }), "FL719")).toHaveLength(1);
});

test("FL706 canvas cannot declare passkeys", () => {
  const diags = lint({ "manifest.json": passkeysManifest({ rpIds: ["example.com"] }, [], { profile: "canvas" }) });
  expect(ofId(diags, "FL706").length).toBeGreaterThan(0);
});
