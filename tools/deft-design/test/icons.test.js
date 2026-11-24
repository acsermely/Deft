import { describe, expect, test } from "bun:test";
import { existsSync, readFileSync, statSync } from "node:fs";
import { join } from "node:path";
import { ICONS, buildSprite, symbolOf } from "../icons.js";

const root = join(import.meta.dir, "../../..");
const vendor = join(import.meta.dir, "../vendor/lucide");
const sprite = join(root, "android/app/src/main/assets/deft-runtime/v1/icons.svg");
const read = (name) => readFileSync(join(vendor, `${name}.svg`), "utf8");

describe("icon sprite", () => {
  test("every listed icon has a vendored Lucide source", () => {
    for (const name of ICONS) expect(existsSync(join(vendor, `${name}.svg`))).toBe(true);
  });

  test("the list holds about 120 common UI icons with no duplicates", () => {
    expect(ICONS.length).toBeGreaterThanOrEqual(120);
    expect(new Set(ICONS).size).toBe(ICONS.length);
    for (const must of ["chevron-right", "plus", "x", "search", "settings", "phone", "message-circle", "calendar", "clock", "camera", "play", "heart", "trash-2", "wifi", "battery", "bell", "user", "users", "house", "map-pin", "lock", "check", "alert-triangle", "info", "ellipsis", "menu", "filter", "mic", "volume-2", "sun", "moon"]) {
      expect(ICONS).toContain(must);
    }
  });

  test("the committed sprite is exactly what the script builds", () => {
    expect(readFileSync(sprite, "utf8")).toBe(buildSprite(ICONS, read));
  });

  test("the sprite has one symbol per icon, styled with currentColor and the stroke token", () => {
    const text = readFileSync(sprite, "utf8");
    const ids = [...text.matchAll(/<symbol id="([a-z0-9-]+)"/g)].map((m) => m[1]);
    expect(ids).toEqual(ICONS);
    expect(text).toContain('stroke="currentColor"');
    expect(text).toContain("stroke-width:var(--deft-icon-stroke,1.5)");
  });

  test("the sprite stays under 64 KB so the first icon use is cheap", () => {
    expect(statSync(sprite).size).toBeLessThanOrEqual(64 * 1024);
  });

  test("the ISC licence ships with the sprite and the vendored set", () => {
    const runtimeLicense = readFileSync(join(root, "android/app/src/main/assets/deft-runtime/v1/icons-LICENSE.txt"), "utf8");
    expect(runtimeLicense).toContain("ISC License");
    expect(readFileSync(join(vendor, "LICENSE"), "utf8")).toContain("ISC License");
  });
});

describe("sprite build", () => {
  test("symbolOf keeps the viewBox and the paths and drops the wrapper and comments", () => {
    const s = symbolOf("demo", '<!-- @license -->\n<svg viewBox="0 0 24 24" stroke="currentColor">\n  <path d="M1 1"/>\n</svg>');
    expect(s.viewBox).toBe("0 0 24 24");
    expect(s.body).toBe('<path d="M1 1"/>');
  });

  test("a file that is not an svg is rejected", () => {
    expect(() => symbolOf("bad", "<div/>")).toThrow("not a Lucide svg");
  });

  test("duplicate names are rejected", () => {
    expect(() => buildSprite(["x", "x"], read)).toThrow("duplicate icon: x");
  });

  test("the build is deterministic", () => {
    expect(buildSprite(["plus", "x"], read)).toBe(buildSprite(["plus", "x"], read));
  });
});
