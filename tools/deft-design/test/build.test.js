import { expect, test } from "bun:test";
import { readFileSync } from "node:fs";
import { join, resolve } from "node:path";
import { fileURLToPath } from "node:url";
import { lstatSync, readlinkSync } from "node:fs";
import { generate, springSamples, resolveColor, loadTokens, OUTPUTS } from "../src/generate.js";
import { skillOutputs, staleAssets } from "../src/skill.js";
import { oklchToArgb } from "../src/oklch.js";

const root = resolve(fileURLToPath(import.meta.url), "../../../..");
const files = generate(root);

test("generated files are up to date with design/tokens", () => {
  for (const [rel, text] of Object.entries(files)) {
    expect({ rel, text: readFileSync(join(root, rel), "utf8") }).toEqual({ rel, text });
  }
});

test("springs are emitted as 17-point linear() curves ending at 1", () => {
  const css = files["android/app/src/main/assets/deft-runtime/v1/tokens.css"];
  for (const name of ["snappy", "smooth", "bouncy"]) {
    const m = new RegExp(`--deft-spring-${name}: linear\\(([^)]*)\\);`).exec(css);
    expect(m).not.toBeNull();
    const points = m[1].split(",").map((s) => Number(s.trim()));
    expect(points).toHaveLength(17);
    expect(points[0]).toBe(0);
    expect(points[16]).toBe(1);
  }
  const bouncy = springSamples({ dampingRatio: 0.7, stiffness: 144, duration: 660 });
  expect(Math.max(...bouncy)).toBeGreaterThan(1);
  const snappy = springSamples({ dampingRatio: 1, stiffness: 576, duration: 280 });
  expect(Math.max(...snappy)).toBe(1);
});

test("token CSS has radii, pad, the concentric helper and the step-dot curve", () => {
  const css = files["android/app/src/main/assets/deft-runtime/v1/tokens.css"];
  expect(css).toContain("--deft-radius-lg: 20px;");
  expect(css).toContain("--deft-radius-screen: 44px;");
  expect(css).toContain("--deft-pad: 12px;");
  expect(css).toContain("--deft-radius-inner: calc(var(--deft-radius-outer) - var(--deft-pad));");
  expect(css).toContain("--deft-step-dot: steps(8, jump-end);");
  expect(css).toContain("--deft-type-label-mono-transform: uppercase;");
});

test("Kotlin tokens carry the same radii, springs and appearance neutrals", () => {
  const kt = files["android/app/src/main/java/app/deft/ui/theme/Tokens.kt"];
  expect(kt).toContain("val radiusLg: Float = 20.0f");
  expect(kt).toContain("val springSnappy: SpringSpec = SpringSpec(1.0f, 576.0f, 280)");
  const tokens = loadTokens(root);
  const dark = resolveColor(tokens.appearances.dark, "bg");
  const hex = oklchToArgb(dark.l, dark.c, dark.h, 1).toString(16).toUpperCase().padStart(8, "0");
  expect(kt).toContain(`bg = 0x${hex}L`);
});

test("black appearance keeps a true-black base and the info alias resolves to label2", () => {
  const tokens = loadTokens(root);
  const black = resolveColor(tokens.appearances.black, "bg");
  expect(black.l).toBe(0);
  const info = resolveColor(tokens.appearances.light, "info");
  const label2 = resolveColor(tokens.appearances.light, "label2");
  expect(info).toEqual(label2);
});

test("docs table lists every radius and appearance token", () => {
  const md = files["docs/design/tokens.md"];
  expect(md).toContain("`--deft-radius-xl`");
  expect(md).toContain("`--deft-spring-bouncy`");
  expect(md).toContain("| `--deft-bg` |");
  expect(md).toContain("| `--deft-separator` |");
});

test("the deft-design skill folder, its tokens reference and the APK copy match the generated output", () => {
  const outputs = { ...files, ...skillOutputs(root, files[OUTPUTS.docs]) };
  for (const [rel, text] of Object.entries(outputs)) {
    expect({ rel, text: readFileSync(join(root, rel), "utf8") }).toEqual({ rel, text });
  }
  expect(staleAssets(root, outputs)).toEqual([]);
});

test(".claude/skills/deft-design is a relative symlink to the skill folder", () => {
  const link = join(root, ".claude/skills/deft-design");
  expect(lstatSync(link).isSymbolicLink()).toBe(true);
  expect(readlinkSync(link)).toBe("../../skill/deft-design");
});

test("references/runtime-api.md documents every runtime export from index.js and every .deft-* class it names exists", () => {
  const rt = join(root, "android/app/src/main/assets/deft-runtime/v1");
  const doc = readFileSync(join(root, "skill/deft-design/references/runtime-api.md"), "utf8");
  const index = readFileSync(join(rt, "index.js"), "utf8");
  const names = [...index.matchAll(/export\{([^}]*)\}/g)].flatMap((m) => m[1].split(","));
  // Pure helpers used by the runtime tests, not by apps.
  const internal = new Set(["parseMs", "staggerDelay", "motionFor", "snapDetent", "velocity", "haptic", "share", "interval", "frame", "morph"]);
  for (const n of names.filter((x) => !internal.has(x))) expect({ n, doc: doc.includes(n) }).toEqual({ n, doc: true });
  for (const n of ["haptic", "share", "interval", "frame", "morph"]) expect(doc).toContain(n);
  const css = readFileSync(join(rt, "components.css"), "utf8");
  for (const sel of [".deft-keypad", "dialog.deft-fullscreen", ".deft-fab[data-float]"]) expect(css).toContain(sel);
});
