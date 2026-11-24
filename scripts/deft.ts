#!/usr/bin/env bun
/**
 * deft pack | sign | verify
 *
 *   bun scripts/deft.ts keygen secrets/author.key
 *   bun scripts/deft.ts pack samples/clock build/clock.deftpkg secrets/author.key [--no-deps]
 *
 * pack adds every manifest dependency as deps/<algo>-<hex> (spec 10.10),
 * downloaded from its CDN URL and checked against its SRI hash.
 *   bun scripts/deft.ts verify build/clock.deftpkg
 */
import { createHash, generateKeyPairSync, sign, verify } from "node:crypto";
import { existsSync, mkdirSync, readFileSync, readdirSync, statSync, writeFileSync } from "node:fs";
import { join, relative } from "node:path";
import { Archive } from "bun";
import { jcs } from "./jcs.ts";

const [, , cmd, ...args] = process.argv;

function fail(message: string): never {
  console.error(message);
  process.exit(1);
}

function walk(dir: string): string[] {
  const out: string[] = [];
  for (const name of readdirSync(dir)) {
    if (name.startsWith(".")) continue;
    const full = join(dir, name);
    if (statSync(full).isDirectory()) out.push(...walk(full));
    else out.push(full);
  }
  return out.sort();
}

function keygen(path: string) {
  const { publicKey, privateKey } = generateKeyPairSync("ed25519");
  const priv = privateKey.export({ type: "pkcs8", format: "der" }) as Buffer;
  const pub = publicKey.export({ type: "spki", format: "der" }) as Buffer;
  writeFileSync(path, JSON.stringify({
    private: Buffer.from(priv).toString("base64"),
    public: Buffer.from(pub).toString("base64"),
  }, null, 2));
  console.log(`wrote ${path}`);
}

function loadKey(path: string) {
  return JSON.parse(readFileSync(path, "utf8")) as { private: string; public: string };
}

async function dependencyFiles(dir: string) {
  const app = JSON.parse(readFileSync(join(dir, "manifest.json"), "utf8"));
  const deps = (app.dependencies ?? {}) as Record<string, string | null | { integrity?: string | null }>;
  const out: { path: string; bytes: Buffer }[] = [];
  for (const [url, spec] of Object.entries(deps)) {
    const integrity = spec && typeof spec === "object" ? spec.integrity : spec;
    if (!integrity) fail(`dependency ${url} has no hash; packages must pin every dependency`);
    const [algo, b64] = [integrity.slice(0, integrity.indexOf("-")), integrity.slice(integrity.indexOf("-") + 1)];
    const res = await fetch(url, { redirect: "manual" });
    if (res.status >= 300 && res.status < 400) fail(`${url} redirects to ${res.headers.get("location")}; declare the final URL`);
    if (!res.ok) fail(`${url} returned HTTP ${res.status}`);
    const bytes = Buffer.from(await res.arrayBuffer());
    if (createHash(algo).update(bytes).digest("base64") !== b64) fail(`${url} does not match its hash`);
    out.push({ path: `deps/${algo}-${Buffer.from(b64, "base64").toString("hex")}`, bytes });
  }
  return out;
}

async function pack(dir: string, out: string, keyPath: string, withDeps = true) {
  if (!existsSync(join(dir, "manifest.json"))) fail("manifest.json missing");
  const key = loadKey(keyPath);
  const files = walk(dir);
  const blob: Record<string, Uint8Array> = {};
  const entries = files.map((file) => {
    const rel = relative(dir, file).replaceAll("\\", "/");
    const bytes = readFileSync(file);
    blob[rel] = bytes;
    return { path: rel, sha256: createHash("sha256").update(bytes).digest("hex") };
  });
  if (withDeps) {
    for (const dep of await dependencyFiles(dir)) {
      blob[dep.path] = dep.bytes;
      entries.push({ path: dep.path, sha256: createHash("sha256").update(dep.bytes).digest("hex") });
    }
  }
  const manifest = { deft: 1, files: entries };
  const payload = Buffer.from(jcs(manifest));
  const signature = sign(null, payload, { key: Buffer.from(key.private, "base64"), format: "der", type: "pkcs8" });
  blob["deft-package.json"] = Buffer.from(JSON.stringify({
    manifest,
    publicKey: key.public,
    signature: Buffer.from(signature).toString("base64"),
  }));
  mkdirSync(join(out, ".."), { recursive: true });
  await Archive.write(out, blob);
  console.log(`packed ${out}`);
}

async function fileBytes(entry: unknown): Promise<Buffer> {
  const file = entry as { bytes?: () => Promise<Uint8Array>; arrayBuffer?: () => Promise<ArrayBuffer> };
  if (file.bytes) return Buffer.from(await file.bytes());
  if (file.arrayBuffer) return Buffer.from(await file.arrayBuffer());
  fail("unreadable zip entry");
}

async function verifyPkg(file: string) {
  const zip = new Archive(readFileSync(file));
  const files = await zip.files();
  // fl-package.json: packages made before the Deft rename.
  const raw = files.get("deft-package.json") ?? files.get("fl-package.json");
  if (!raw) fail("missing deft-package.json");
  const doc = JSON.parse((await fileBytes(raw)).toString("utf8"));
  const payload = Buffer.from(jcs(doc.manifest));
  const ok = verify(
    null,
    payload,
    { key: Buffer.from(doc.publicKey, "base64"), format: "der", type: "spki" },
    Buffer.from(doc.signature, "base64"),
  );
  if (!ok) fail("bad signature");
  for (const entry of doc.manifest.files) {
    const found = files.get(entry.path);
    if (!found) fail(`missing ${entry.path}`);
    const bytes = await fileBytes(found);
    const hash = createHash("sha256").update(bytes).digest("hex");
    if (hash !== entry.sha256) fail(`hash mismatch ${entry.path}`);
  }
  console.log("verify ok");
}

if (cmd === "keygen") keygen(args[0] || "secrets/author.key");
else if (cmd === "pack") await pack(args[0], args[1], args[2], !args.includes("--no-deps"));
else if (cmd === "verify") await verifyPkg(args[0]);
else fail("usage: deft.ts keygen|pack|verify");
