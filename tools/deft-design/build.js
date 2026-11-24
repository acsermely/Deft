// Usage: bun tools/deft-design/build.js [--check]
// Regenerates tokens.css, Tokens.kt, docs/design/tokens.md and the deft-design skill folder
// (skill/deft-design/references/tokens.md, plus its copy in the APK assets) from design/tokens/*.json.
// --check exits non-zero if any generated file is out of date or a stale asset is left behind.
import { existsSync, mkdirSync, readFileSync, rmSync, writeFileSync } from "node:fs";
import { dirname, join, resolve } from "node:path";
import { fileURLToPath } from "node:url";
import { generate, OUTPUTS } from "./src/generate.js";
import { skillOutputs, staleAssets } from "./src/skill.js";

const root = resolve(dirname(fileURLToPath(import.meta.url)), "../..");
const check = process.argv.includes("--check");
const generated = generate(root);
const files = { ...generated, ...skillOutputs(root, generated[OUTPUTS.docs]) };
let stale = 0;
for (const rel of staleAssets(root, files)) {
  if (check) {
    stale++;
    console.error(`stale asset: ${rel}`);
  } else {
    rmSync(join(root, rel));
    console.log(`removed ${rel}`);
  }
}
for (const [rel, text] of Object.entries(files)) {
  const abs = join(root, rel);
  const current = existsSync(abs) ? readFileSync(abs, "utf8") : null;
  if (check) {
    if (current !== text) {
      stale++;
      console.error(`stale: ${rel}`);
    }
    continue;
  }
  if (current === text) continue;
  mkdirSync(dirname(abs), { recursive: true });
  writeFileSync(abs, text);
  console.log(`wrote ${rel}`);
}
if (check && stale > 0) process.exit(1);
