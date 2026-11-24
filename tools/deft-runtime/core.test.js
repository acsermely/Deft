import { describe, expect, test } from "bun:test";
import { batch, computed, effect, signal } from "../../android/app/src/main/assets/deft-runtime/v1/core.js";
import { readdirSync, readFileSync, statSync } from "node:fs";
import { join } from "node:path";

describe("signals", () => {
  test("effect tracks writes", () => {
    const count = signal(1);
    let seen = 0;
    effect(() => { seen = count.value; });
    expect(seen).toBe(1);
    count.value = 2;
    expect(seen).toBe(2);
  });

  test("batch coalesces", () => {
    const count = signal(0);
    let runs = 0;
    effect(() => { count.value; runs += 1; });
    batch(() => {
      count.value = 1;
      count.value = 2;
    });
    expect(count.value).toBe(2);
    expect(runs).toBe(2);
  });

  test("computed", () => {
    const count = signal(2);
    const doubled = computed(() => count.value * 2);
    expect(doubled.value).toBe(4);
    count.value = 3;
    expect(doubled.value).toBe(6);
  });
});

test("computed does not re-enter itself", () => {
  const n = signal(1);
  let calls = 0;
  const c = computed(() => {
    calls += 1;
    return n.value * 2;
  });
  expect(c.value).toBe(2);
  n.value = 3;
  expect(c.value).toBe(6);
  expect(calls).toBeLessThan(20);
});

// motion.js, sheet.js and elements.js have their own budgets (motion.test.js); tool-runner.js is the agent tool page's
// script, loaded only by the hidden tool runner (its own budget below). The rest of the runtime is the core.
test("core runtime js stays under 11 KB", () => {
  const dir = join(import.meta.dir, "../../android/app/src/main/assets/deft-runtime/v1");
  const own = new Set(["motion.js", "sheet.js", "elements.js", "tool-runner.js"]);
  let bytes = 0;
  for (const name of readdirSync(dir)) {
    if (!name.endsWith(".js") || own.has(name)) continue;
    bytes += statSync(join(dir, name)).size;
  }
  expect(bytes).toBeLessThanOrEqual(11 * 1024);
  expect(readFileSync(join(dir, "index.js"), "utf8")).toContain("core.js");
});

test("tool-runner.js stays tiny and speaks the deftTool port protocol", () => {
  const file = join(import.meta.dir, "../../android/app/src/main/assets/deft-runtime/v1/tool-runner.js");
  const js = readFileSync(file, "utf8");
  expect(statSync(file).size).toBeLessThanOrEqual(1024);
  expect(js).toContain("self.deftTool.postMessage");
  expect(js).toContain("__deftToolRun");
});

test("base.css stays under 16 KB", () => {
  const file = join(import.meta.dir, "../../android/app/src/main/assets/deft-runtime/v1/base.css");
  const css = readFileSync(file, "utf8");
  expect(statSync(file).size).toBeLessThanOrEqual(16 * 1024);
  expect(css).toContain("--deft-ease-enter");
  expect(css).toContain("@layer deft.reset");
});

// Cap is a proxy for parse cost (proposal 3.9): +8 KB of CSS parses in well under a millisecond on the A16, less than one asset read.
test("components.css stays under 32 KB and carries the motion and morph rules", () => {
  const file = join(import.meta.dir, "../../android/app/src/main/assets/deft-runtime/v1/components.css");
  const css = readFileSync(file, "utf8");
  expect(statSync(file).size).toBeLessThanOrEqual(32 * 1024);
  expect(css).toContain("view-transition-name: deft-morph");
  expect(css).toContain("::view-transition-group(deft-morph)");
  expect(css).toContain("@layer deft.components");
});

test("tokens.css is a static layer and carries the spring curves", () => {
  const file = join(import.meta.dir, "../../android/app/src/main/assets/deft-runtime/v1/tokens.css");
  const css = readFileSync(file, "utf8");
  expect(css).toContain("--deft-spring-snappy: linear(");
  expect(css).toContain("--deft-radius-inner:");
});
