// Usage: bun tools/deft-lint/cli.js <appDir> [--profile app|canvas] [--json]
// Lints every text file under appDir with the same rules the launcher runs on the device.
// Prints "path:line:col severity ID message", then a count line. Exit 1 if any error, else 0.
import { readdirSync, readFileSync, statSync } from "node:fs";
import { join, relative, resolve, sep } from "node:path";
import { run } from "./src/index.js";

const TEXT = /\.(html?|css|js|mjs|json|toml|md|txt|svg)$/i;
const args = process.argv.slice(2);
const json = args.includes("--json");
const pi = args.indexOf("--profile");
const profile = pi >= 0 ? args[pi + 1] : undefined;
const dir = args.find((a, i) => !a.startsWith("--") && args[i - 1] !== "--profile");
if (!dir || !statSync(dir, { throwIfNoEntry: false })?.isDirectory()) {
  console.error("usage: bun tools/deft-lint/cli.js <appDir> [--profile app|canvas] [--json]");
  process.exit(2);
}

const root = resolve(dir);
const files = {};
const walk = (d) => {
  for (const e of readdirSync(d, { withFileTypes: true })) {
    const p = join(d, e.name);
    if (e.isDirectory()) walk(p);
    else if (TEXT.test(e.name)) files[relative(root, p).split(sep).join("/")] = readFileSync(p, "utf8");
  }
};
walk(root);

const diags = run({ files, profile });
const errors = diags.filter((d) => d.severity === "error").length;
const warns = diags.filter((d) => d.severity === "warn").length;
if (json) console.log(JSON.stringify(diags, null, 2));
else {
  for (const d of diags) console.log(`${d.path}:${d.line}:${d.col} ${d.severity} ${d.id} ${d.message}`);
  console.log(`${errors} errors, ${warns} warnings, ${diags.length - errors - warns} info`);
}
process.exit(errors ? 1 : 0);
