import { expect, test } from "bun:test";

function installDocument() {
  class Node {
    constructor() {
      this.parentNode = null;
      this.childNodes = [];
      this.nodeType = 1;
    }
  }
  class Element extends Node {
    constructor(tag) {
      super();
      this.tagName = String(tag).toUpperCase();
      this.id = "";
      this.className = "";
      this.attrs = {};
      this.style = { setProperty() {} };
      this.scrollTop = 0;
    }
    setAttribute(k, v) {
      this.attrs[k] = String(v);
      if (k === "id") this.id = String(v);
      if (k === "class") this.className = String(v);
    }
    getAttribute(k) {
      return Object.prototype.hasOwnProperty.call(this.attrs, k) ? this.attrs[k] : null;
    }
    removeAttribute(k) {
      delete this.attrs[k];
      if (k === "id") this.id = "";
      if (k === "class") this.className = "";
    }
    appendChild(n) {
      n.parentNode = this;
      this.childNodes.push(n);
      return n;
    }
    append(...ns) {
      ns.forEach((n) => this.appendChild(n));
    }
    replaceChildren(...ns) {
      this.childNodes.forEach((n) => { n.parentNode = null; });
      this.childNodes = [];
      ns.forEach((n) => this.appendChild(n));
    }
    replaceWith(...ns) {
      const p = this.parentNode;
      if (!p) return;
      const i = p.childNodes.indexOf(this);
      this.parentNode = null;
      ns.forEach((n) => { n.parentNode = p; });
      p.childNodes.splice(i, 1, ...ns);
    }
    querySelectorAll(sel) {
      return walk(this).filter((el) => match(el, sel));
    }
    querySelector(sel) {
      return this.querySelectorAll(sel)[0] || null;
    }
    contains(n) {
      let p = n;
      while (p) {
        if (p === this) return true;
        p = p.parentNode;
      }
      return false;
    }
    focus() {
      globalThis.document.activeElement = this;
    }
    setSelectionRange(a, b) {
      this.selectionStart = a;
      this.selectionEnd = b;
    }
  }
  class Input extends Element {
    constructor() {
      super("input");
      this.value = "";
      this.selectionStart = 0;
      this.selectionEnd = 0;
    }
  }
  class Fragment {
    constructor() {
      this.nodeType = 11;
      this.childNodes = [];
      this.parentNode = null;
    }
    appendChild(n) {
      n.parentNode = this;
      this.childNodes.push(n);
      return n;
    }
    querySelectorAll(sel) {
      return walk(this).filter((el) => match(el, sel));
    }
  }
  class Template extends Element {
    constructor() {
      super("template");
      this.content = new Fragment();
    }
    set innerHTML(html) {
      this.content = new Fragment();
      parseHTML(String(html ?? ""), this.content);
    }
  }
  function walk(node, out = []) {
    for (const c of node.childNodes || []) {
      if (c.nodeType === 1) {
        out.push(c);
        walk(c, out);
      }
    }
    return out;
  }
  function match(el, sel) {
    if (sel.startsWith("[") && sel.endsWith("]")) return el.getAttribute(sel.slice(1, -1)) != null;
    if (sel.startsWith(".")) return el.className.split(/\s+/).includes(sel.slice(1));
    if (sel.startsWith("#")) return el.id === sel.slice(1);
    return el.tagName === sel.toUpperCase();
  }
  function parseHTML(html, parent) {
    const re = /<(\/)?([a-zA-Z][\w:-]*)([^>]*)>|([^<]+)/g;
    const stack = [parent];
    const voidEl = new Set(["input", "br", "img", "hr", "meta", "link"]);
    let m;
    while ((m = re.exec(html))) {
      if (m[4]) {
        if (m[4].trim()) stack.at(-1).appendChild(globalThis.document.createTextNode(m[4]));
      } else if (m[1]) {
        if (stack.length > 1) stack.pop();
      } else {
        const el = globalThis.document.createElement(m[2]);
        const attrRe = /([.@?]?[\w:-]+)\s*=\s*(?:"([^"]*)"|'([^']*)'|([^\s"'>]+))/g;
        let a;
        while ((a = attrRe.exec(m[3] || ""))) el.setAttribute(a[1], a[2] ?? a[3] ?? a[4] ?? "");
        stack.at(-1).appendChild(el);
        const selfClose = /\/\s*$/.test(m[3] || "") || voidEl.has(m[2].toLowerCase());
        if (!selfClose) stack.push(el);
      }
    }
  }
  const body = new Element("body");
  const doc = {
    nodeType: 9,
    body,
    activeElement: body,
    createElement(tag) {
      const t = String(tag).toLowerCase();
      if (t === "template") return new Template();
      if (t === "input" || t === "textarea") return new Input();
      return new Element(t);
    },
    createTextNode(text) {
      return { nodeType: 3, textContent: String(text), parentNode: null, childNodes: [] };
    },
    getElementById(id) {
      return walk(body).find((el) => el.id === id) || null;
    },
  };
  globalThis.document = doc;
  globalThis.CSS = { escape: (s) => String(s) };
}

installDocument();
const { signal } = await import("../../android/app/src/main/assets/deft-runtime/v1/core.js");
const { html, render } = await import("../../android/app/src/main/assets/deft-runtime/v1/dom.js");

test("render keeps focus, caret, and scroll", () => {
  const root = document.createElement("div");
  document.body.appendChild(root);
  const q = signal("ab");
  render(root, () => html`<input id="q" .value=${q.value}><div class="deft-scroll" id="sc"></div>`);
  const before = document.getElementById("q");
  before.focus();
  before.selectionStart = 1;
  document.getElementById("sc").scrollTop = 40;
  q.value = "abc";
  const after = document.getElementById("q");
  expect(after).not.toBe(before);
  expect(document.activeElement).toBe(after);
  expect(after.selectionStart).toBe(1);
  expect(after.value).toBe("abc");
  expect(document.getElementById("sc").scrollTop).toBe(40);
});

function routeGlobals() {
  globalThis.location = { hash: "" };
  globalThis.addEventListener = () => {};
  globalThis.matchMedia = () => ({ matches: true });
  globalThis.self = globalThis;
}

test("a route view re-renders when a signal it reads changes, and only the live route's view does", async () => {
  routeGlobals();
  // WebView runs the update callback later, outside the route effect: the view must still track its own reads.
  document.startViewTransition = (cb) => { queueMicrotask(cb); return { ready: Promise.resolve() }; };
  const tick = () => new Promise((r) => setTimeout(r, 0));
  const { routes, go } = await import("../../android/app/src/main/assets/deft-runtime/v1/route.js");
  const root = document.createElement("div");
  const unit = signal("cm");
  let otherRuns = 0;
  const stop = routes({
    "/": () => html`<b id="u">${unit.value}</b>`,
    "/other": () => { otherRuns += 1; return html`<i id="o">${unit.value}</i>`; },
  }, root);
  await tick();
  expect(root.childNodes[0].childNodes[0].textContent).toBe("cm");
  unit.value = "in";
  expect(root.childNodes[0].childNodes[0].textContent).toBe("in");
  go("/other");
  await tick();
  expect(otherRuns).toBe(1);
  go("/");
  await tick();
  unit.value = "cm";
  expect(otherRuns).toBe(1); // the old route's view stopped listening
  expect(root.childNodes[0].childNodes[0].textContent).toBe("cm");
  stop();
  unit.value = "in";
  expect(root.childNodes[0].childNodes[0].textContent).toBe("cm");
  delete document.startViewTransition;
});

test("morph swallows a skipped transition's rejected ready promise", async () => {
  routeGlobals();
  let unhandled = 0;
  const onUnhandled = () => { unhandled += 1; };
  process.on("unhandledRejection", onUnhandled);
  document.startViewTransition = (cb) => {
    cb();
    const ready = Promise.reject(Object.assign(new Error("Transition was skipped"), { name: "AbortError" }));
    return { ready, finished: Promise.resolve(), updateCallbackDone: Promise.resolve() };
  };
  const { morph } = await import("../../android/app/src/main/assets/deft-runtime/v1/route.js");
  let ran = 0;
  morph(() => { ran += 1; });
  await new Promise((r) => setTimeout(r, 10));
  process.off("unhandledRejection", onUnhandled);
  delete document.startViewTransition;
  expect(ran).toBe(1);
  expect(unhandled).toBe(0);
});

test("every binding on one element applies, quoted or not", () => {
  const root = document.createElement("div");
  render(root, html`<input class=${"a"} .value=${"v"} name=${"n"} title="${"t"}" id="x"><p>${"after"}</p>`);
  const el = root.childNodes[0];
  expect(el.className).toBe("a");
  expect(el.value).toBe("v");
  expect(el.getAttribute("name")).toBe("n");
  expect(el.getAttribute("title")).toBe("t");
  expect(el.getAttribute("data-b")).toBe(null);
  expect(root.childNodes[1].childNodes[0].textContent).toBe("after");
});
