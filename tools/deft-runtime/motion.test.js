import { describe, expect, test } from "bun:test";
import { readFileSync, statSync } from "node:fs";
import { join } from "node:path";
import { haptic, motionFor, parseMs, shouldFire, staggerDelay } from "../../android/app/src/main/assets/deft-runtime/v1/motion.js";
import { detentPx, snapDetent, velocity } from "../../android/app/src/main/assets/deft-runtime/v1/sheet.js";

const DIR = join(import.meta.dir, "../../android/app/src/main/assets/deft-runtime/v1");

describe("stagger", () => {
  test("children step 45 ms apart and stop at the cap", () => {
    expect(staggerDelay(0)).toBe(0);
    expect(staggerDelay(1)).toBe(45);
    expect(staggerDelay(3)).toBe(135);
    expect(staggerDelay(10)).toBe(450);
    expect(staggerDelay(40)).toBe(450);
  });

  test("custom step and cap", () => {
    expect(staggerDelay(4, 20, 2)).toBe(40);
  });

  test("CSS time tokens parse to milliseconds", () => {
    expect(parseMs("560ms")).toBe(560);
    expect(parseMs("0.5s")).toBe(500);
    expect(parseMs("")).toBe(0);
  });
});

describe("enter motion", () => {
  test("rise and scale move; fade only changes opacity", () => {
    const rise = motionFor("rise", false, "linear", 560);
    expect(rise.keyframes[0].transform).toBe("translateY(12px)");
    expect(rise.duration).toBe(560);
    expect(motionFor("scale", false).keyframes[0].transform).toBe("scale(.94)");
    expect(motionFor("fade", false).keyframes.every((k) => !("transform" in k))).toBe(true);
  });

  test("reduced motion is an opacity cross-fade with no movement", () => {
    for (const kind of ["rise", "scale", "fade"]) {
      const m = motionFor(kind, true, "cubic-bezier(0,0,1,1)", 900);
      expect(m.keyframes).toEqual([{ opacity: 0 }, { opacity: 1 }]);
      expect(m.duration).toBe(200);
      expect(m.easing).toBe("linear");
    }
  });
});

describe("detent snap", () => {
  const hs = [300, 800];
  test("a slow release goes to the nearest detent", () => {
    expect(snapDetent(hs, 340, 0)).toBe(0);
    expect(snapDetent(hs, 700, 0)).toBe(1);
  });

  test("a fast upward fling goes to the next detent above", () => {
    expect(snapDetent(hs, 320, 0.8)).toBe(1);
    expect(snapDetent(hs, 790, 0.8)).toBe(1);
  });

  test("a fast downward fling drops one detent, and dismisses from the lowest", () => {
    expect(snapDetent(hs, 790, -0.8)).toBe(0);
    expect(snapDetent(hs, 310, -0.8)).toBe(0);
    expect(snapDetent(hs, 300, -0.8)).toBe(-1);
  });

  test("velocity is positive when the finger moves up", () => {
    expect(velocity([[0, 100], [10, 80]])).toBe(2);
    expect(velocity([[0, 80], [10, 100]])).toBe(-2);
    expect(velocity([[0, 100]])).toBe(0);
  });

  test("detent heights follow the viewport", () => {
    expect(detentPx("medium", 1000)).toBe(500);
    expect(detentPx("large", 1000)).toBe(920);
  });
});

describe("haptics", () => {
  test("the rate limit allows one call per 40 ms", () => {
    expect(shouldFire(100, 80)).toBe(false);
    expect(shouldFire(120, 80)).toBe(true);
  });

  test("haptic sends haptics.play through the bridge, rate-limited, and ignores unknown kinds", async () => {
    const calls = [];
    globalThis.self ??= globalThis;
    globalThis.deftBridge = { invoke: (m, a) => { calls.push([m, a]); return Promise.resolve(); } };
    try {
      haptic("light");
      haptic("light");
      haptic("bogus");
      await Bun.sleep(45);
      haptic("success");
      expect(calls).toEqual([["haptics.play", { kind: "light" }], ["haptics.play", { kind: "success" }]]);
    } finally {
      delete globalThis.deftBridge;
    }
  });

  test('haptic is silent under data-deft-haptic="off" on the root (IME pages)', async () => {
    const calls = [];
    globalThis.self ??= globalThis;
    globalThis.deftBridge = { invoke: (m, a) => { calls.push([m, a]); return Promise.resolve(); } };
    globalThis.document = { documentElement: { dataset: { deftHaptic: "off" }, classList: { add() {} } }, addEventListener() {}, querySelectorAll: () => [] };
    globalThis.MutationObserver = class { observe() {} };
    try {
      const fresh = await import("../../android/app/src/main/assets/deft-runtime/v1/motion.js?haptic-off");
      fresh.haptic("light");
      expect(calls).toEqual([]);
    } finally {
      delete globalThis.document;
      delete globalThis.MutationObserver;
      delete globalThis.deftBridge;
    }
  });

  test("haptic is silent outside the launcher", () => {
    delete globalThis.deftBridge;
    expect(() => haptic("medium")).not.toThrow();
  });
});

describe("budgets", () => {
  // motion.js and elements.js grew with the deft- rename (fl- to deft- is 2 bytes per name): 4096 to 4224, 3584 to 3712.
  const budgets = { "motion.js": 4224, "sheet.js": 3584, "elements.js": 3712 };
  for (const [name, max] of Object.entries(budgets)) {
    test(`${name} stays under ${max} bytes`, () => {
      expect(statSync(join(DIR, name)).size).toBeLessThanOrEqual(max);
    });
  }

  test("elements.js defines deft-widget: an iframe of the widget index entry, capped at 6, placeholder otherwise", () => {
    const src = readFileSync(join(DIR, "elements.js"), "utf8");
    expect(src).toContain("customElements.define('deft-widget',DeftWidget)");
    expect(src).toContain("/_deft/v1/widgets.json");
    expect(src).toContain("WIDGET_CAP=6");
    expect(src).toContain("sandbox','allow-scripts allow-same-origin'");
  });

  test("motion.js starts the sheets and elements and publishes deft.haptic and deft.toast, with fl as the pre-rename alias", () => {
    const src = readFileSync(join(DIR, "motion.js"), "utf8");
    expect(src).toContain("./elements.js");
    expect(src).toContain("initSheets");
    expect(src).toContain("self.deft=Object.assign(self.deft||{},");
    expect(src).toContain("{haptic,toast,dialog,confirm,prompt,enter:replay,open}");
    expect(src).toContain("self.fl=self.deft");
    expect(src).toContain("haptics.play");
    expect(src).toContain("__deftMotion");
  });

  test("index.js loads motion and exposes morph", () => {
    const src = readFileSync(join(DIR, "index.js"), "utf8");
    expect(src).toContain("./motion.js");
    expect(src).toContain("morph");
  });
});
