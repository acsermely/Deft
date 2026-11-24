#!/usr/bin/env bun
/**
 * Debug sync between this Mac and a debuggable Deft install.
 *
 *   bun scripts/deft-link.ts pull clock ./tmp/clock
 *   bun scripts/deft-link.ts push clock ./tmp/clock
 *
 * Uses `adb exec-out run-as` against app.deft.debug. Release
 * installs are not reachable this way.
 */
import { spawnSync } from "node:child_process";
import { existsSync } from "node:fs";
import { join } from "node:path";

const android = join(import.meta.dir, "..", "android");
const props = existsSync(join(android, "local.properties"))
  ? (await Bun.file(join(android, "local.properties")).text())
  : "";
const sdk = props.match(/^sdk\.dir=(.+)$/m)?.[1]?.trim();
const adb = sdk ? join(sdk, "platform-tools", "adb") : "adb";
const pkg = "app.deft.debug";
const [, , cmd, slug, dest] = process.argv;
if (!cmd || !slug || !dest) {
  console.error("usage: deft-link.ts pull|push <slug> <dir>");
  process.exit(1);
}
if (!/^[a-z0-9-]{1,40}$/.test(slug)) {
  console.error("bad slug");
  process.exit(1);
}
const remote = `files/deft/local/${slug}`;
if (cmd === "pull") {
  const result = spawnSync(adb, ["exec-out", "run-as", pkg, "tar", "-C", remote, "-c", "."], { stdio: ["ignore", "pipe", "inherit"] });
  if (result.status !== 0) process.exit(result.status ?? 1);
  const unpack = spawnSync("tar", ["-C", dest, "-x"], { input: result.stdout });
  process.exit(unpack.status ?? 1);
}
if (cmd === "push") {
  const packed = spawnSync("tar", ["-C", dest, "-c", "."], { stdio: ["ignore", "pipe", "inherit"] });
  if (packed.status !== 0) process.exit(packed.status ?? 1);
  const mkdir = spawnSync(adb, ["shell", "run-as", pkg, "mkdir", "-p", remote], { stdio: "inherit" });
  if (mkdir.status !== 0) process.exit(mkdir.status ?? 1);
  const result = spawnSync(adb, ["shell", "run-as", pkg, "tar", "-C", remote, "-x"], { input: packed.stdout });
  process.exit(result.status ?? 1);
}
console.error("unknown command");
process.exit(1);
