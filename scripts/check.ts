#!/usr/bin/env bun
/**
 * Local gate. No CI.
 *
 *   bun scripts/check.ts            unit, lint, runtime and sample checks
 *   bun scripts/check.ts --device   also instrumented tests on the connected device
 *
 * Instrumented tests replace the debug app on the device, and after the run Gradle can
 * uninstall it, wiping its private storage (local apps, grants, config). That is why
 * they never run just because a phone is plugged in.
 * From PR3, also validates sample manifests. From PR8, the hostile suite is
 * part of the instrumented tests.
 */

import { existsSync, mkdtempSync, readdirSync, readFileSync, rmSync, statSync } from "node:fs";
import { tmpdir } from "node:os";
import { join, relative, resolve, sep } from "node:path";
import { spawnSync } from "node:child_process";
import { run as deftLintRun } from "../tools/deft-lint/src/index.js";

const root = resolve(import.meta.dir, "..");
const android = join(root, "android");
const failures: string[] = [];

function run(label: string, cmd: string[], cwd: string, allowFail = false): { ok: boolean; stdout: string } {
  console.log(`\n==> ${label}`);
  const result = spawnSync(cmd[0], cmd.slice(1), {
    cwd,
    encoding: "utf8",
    stdio: ["ignore", "pipe", "pipe"],
  });
  const stdout = `${result.stdout ?? ""}${result.stderr ?? ""}`;
  process.stdout.write(stdout);
  const ok = result.status === 0;
  if (!ok && !allowFail) failures.push(label);
  return { ok, stdout };
}

function adbBinary(): string {
  const fromEnv = [process.env.ANDROID_HOME, process.env.ANDROID_SDK_ROOT]
    .filter((dir): dir is string => !!dir)
    .map((dir) => join(dir, "platform-tools", "adb"));
  const props = join(android, "local.properties");
  const fromProps: string[] = [];
  if (existsSync(props)) {
    const match = readFileSync(props, "utf8").match(/^sdk\.dir=(.+)$/m);
    if (match) fromProps.push(join(match[1].trim(), "platform-tools", "adb"));
  }
  const home = process.env.HOME ?? "";
  const candidates = [
    ...fromProps,
    ...fromEnv,
    join(home, "Library/Android/sdk/platform-tools/adb"),
    "adb",
  ];
  return candidates.find((bin) => bin === "adb" || existsSync(bin)) ?? "adb";
}

function adbDevices(): string[] {
  const result = spawnSync(adbBinary(), ["devices"], { encoding: "utf8" });
  if (result.status !== 0) return [];
  return (result.stdout ?? "")
    .split("\n")
    .slice(1)
    .map((line) => line.trim())
    .filter((line) => line.endsWith("\tdevice"))
    .map((line) => line.split("\t")[0]);
}

function gradle(): string {
  const wrapper = join(android, "gradlew");
  return existsSync(wrapper) ? wrapper : "gradle";
}

run("unit tests", [gradle(), ":app:testDebugUnitTest"], android);
const lintDir = join(root, "tools/deft-lint");
run("deft-lint", ["bun", "test"], lintDir);
checkLintBundle(lintDir);
checkSampleLintSnapshot();
run("runtime", ["bun", "test"], join(root, "tools/deft-runtime"));
run("deft-design generated files", ["bun", "tools/deft-design/build.js", "--check"], root);
run("bridge docs generated", ["bun", "scripts/build-bridge-docs.ts", "--check"], root);

const devices = adbDevices();
if (!process.argv.includes("--device")) {
  console.log("\n==> instrumented tests skipped (pass --device; it can wipe the debug app's data)");
} else if (devices.length === 0) {
  console.log("\n==> instrumented tests skipped (no adb device)");
} else {
  console.log(`\n==> adb devices: ${devices.join(", ")}`);
  run(
    "instrumented tests",
    [gradle(), "connectedDebugAndroidTest", "-Pandroid.injected.androidTest.leaveApksInstalledAfterRun=true"],
    android,
  );
}

const samplesDir = join(root, "samples");
const skipNames = new Set(["catalog.json", "deft-bridge.js"]);
const apps = existsSync(samplesDir)
  ? readdirSync(samplesDir).filter((name) => {
      if (skipNames.has(name) || name.startsWith(".")) return false;
      const full = join(samplesDir, name);
      return statSync(full).isDirectory();
    })
  : [];

const catalogPath = join(root, "android/app/src/main/assets/agent/bridge-catalog.json");
if (existsSync(catalogPath)) {
  console.log("\n==> sample manifests");
  let catalog: { capabilities?: Record<string, unknown> };
  try {
    catalog = JSON.parse(readFileSync(catalogPath, "utf8"));
  } catch (err) {
    failures.push("bridge-catalog.json parse");
    console.log(`FAIL: cannot parse ${catalogPath}: ${err}`);
    catalog = {};
  }
  const known = new Set(Object.keys(catalog.capabilities ?? {}));
  for (const id of apps) {
    if (id === "hostile") continue;
    const manifestPath = join(samplesDir, id, "manifest.json");
    if (!existsSync(manifestPath)) {
      failures.push(`${id}: missing manifest.json`);
      console.log(`FAIL ${id}: missing manifest.json`);
      continue;
    }
    let manifest: Record<string, unknown>;
    try {
      manifest = JSON.parse(readFileSync(manifestPath, "utf8"));
    } catch (err) {
      failures.push(`${id}: invalid JSON`);
      console.log(`FAIL ${id}: ${err}`);
      continue;
    }
    if (manifest.requiredCapabilities !== undefined) {
      failures.push(`${id}: requiredCapabilities is rejected`);
      console.log(`FAIL ${id}: requiredCapabilities is rejected; use capabilities: [{id, reason}]`);
      continue;
    }
    if (manifest.deft !== 1) {
      failures.push(`${id}: deft != 1`);
      console.log(`FAIL ${id}: deft must be 1`);
    }
    const caps = Array.isArray(manifest.capabilities) ? manifest.capabilities : [];
    for (const cap of caps) {
      const capId = typeof cap === "object" && cap && "id" in cap ? String((cap as { id: unknown }).id) : "";
      if (!capId || (known.size > 0 && !known.has(capId))) {
        failures.push(`${id}: unknown capability ${capId}`);
        console.log(`FAIL ${id}: unknown capability '${capId}'`);
      }
    }
    if (failures.every((f) => !f.startsWith(`${id}:`))) {
      console.log(`ok ${id}`);
    }
  }
} else {
  console.log("\n==> sample manifests skipped (no bridge-catalog.json yet)");
}

function sampleTextFiles(dir: string): Record<string, string> {
  const text = new Set(["js", "html", "htm", "css", "json", "svg", "md", "txt"]);
  const files: Record<string, string> = {};
  const walk = (current: string) => {
    for (const name of readdirSync(current)) {
      if (name.startsWith(".")) continue;
      const full = join(current, name);
      const info = statSync(full);
      if (info.isDirectory()) {
        walk(full);
        continue;
      }
      if (!info.isFile()) continue;
      const ext = name.includes(".") ? name.slice(name.lastIndexOf(".") + 1).toLowerCase() : "";
      if (!text.has(ext)) continue;
      files[relative(dir, full).split(sep).join("/")] = readFileSync(full, "utf8");
    }
  };
  if (existsSync(dir)) walk(dir);
  return files;
}

function checkLintBundle(lintDir: string) {
  console.log("\n==> deft-lint bundle");
  const committedPath = join(root, "android/app/src/main/assets/lint/deft-lint.js");
  if (!existsSync(committedPath)) {
    failures.push("deft-lint bundle missing");
    console.log("FAIL: committed deft-lint.js is missing");
    return;
  }
  const freshDir = mkdtempSync(join(tmpdir(), "deft-lint-"));
  const freshPath = join(freshDir, "deft-lint.js");
  const built = run(
    "deft-lint bundle",
    ["bun", "build", "src/browser.js", "--outfile", freshPath, "--target", "browser"],
    lintDir,
  );
  try {
    if (!built.ok) return;
    const committed = readFileSync(committedPath);
    const fresh = readFileSync(freshPath);
    if (!committed.equals(fresh)) {
      failures.push("deft-lint bundle differs from a fresh build");
      console.log("FAIL: committed android/app/src/main/assets/lint/deft-lint.js differs from a fresh build");
    } else {
      console.log("ok deft-lint bundle matches");
    }
  } finally {
    rmSync(freshDir, { recursive: true, force: true });
  }
}

function checkSampleLintSnapshot() {
  console.log("\n==> sample lint snapshot");
  const snapPath = join(root, "tools/deft-lint/samples.snap.json");
  if (!existsSync(snapPath)) {
    failures.push("sample lint snapshot missing");
    console.log("FAIL: tools/deft-lint/samples.snap.json is missing");
    return;
  }
  let snap: { findings?: { id?: string; severity?: string }[] };
  try {
    snap = JSON.parse(readFileSync(snapPath, "utf8"));
  } catch (err) {
    failures.push("sample lint snapshot parse");
    console.log(`FAIL: cannot parse samples.snap.json: ${err}`);
    return;
  }
  const allowed = new Set(
    (snap.findings ?? [])
      .filter((finding) => finding.severity === "error" && finding.id)
      .map((finding) => finding.id as string),
  );
  const sampleRoot = join(root, "samples");
  const catalogFile = join(root, "android/app/src/main/assets/agent/bridge-catalog.json");
  let catalog: object | undefined;
  if (existsSync(catalogFile)) {
    try {
      catalog = JSON.parse(readFileSync(catalogFile, "utf8"));
    } catch {
      catalog = undefined;
    }
  }
  const sampleApps = existsSync(sampleRoot)
    ? readdirSync(sampleRoot).filter((name) => {
        if (name.startsWith(".")) return false;
        return statSync(join(sampleRoot, name)).isDirectory();
      })
    : [];
  const fresh = new Set<string>();
  for (const id of sampleApps) {
    if (id === "hostile") continue;
    const diags = deftLintRun({
      files: sampleTextFiles(join(sampleRoot, id)),
      profile: "myapp",
      mode: "local",
      catalog,
    });
    for (const diag of diags) {
      if (diag.severity === "error") fresh.add(diag.id);
    }
  }
  const novel = [...fresh].filter((id) => !allowed.has(id)).sort();
  if (novel.length > 0) {
    failures.push(`new sample lint error ids: ${novel.join(", ")}`);
    console.log(`FAIL: new sample lint error ids: ${novel.join(", ")}`);
  } else {
    console.log(`ok sample lint error ids (${[...allowed].sort().join(", ") || "none"})`);
  }
}

if (failures.length === 0) {
  console.log("\nPASS");
  process.exit(0);
}
console.log("\nFAIL");
for (const f of failures) console.log(`  - ${f}`);
process.exit(1);
