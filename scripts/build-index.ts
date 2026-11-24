#!/usr/bin/env bun
/**
 * Build a signed source index from already-packed .deftpkg files.
 *
 *   bun scripts/build-index.ts build/*.deftpkg secrets/author.key dist/index.json
 */
import { createHash, sign } from "node:crypto";
import { readFileSync, writeFileSync, mkdirSync } from "node:fs";
import { basename, dirname } from "node:path";
import { jcs } from "./jcs.ts";

const args = process.argv.slice(2);
const keyPath = args.at(-2);
const out = args.at(-1);
const packages = args.slice(0, -2);
if (!keyPath || !out || packages.length === 0) {
  console.error("usage: build-index.ts <pkg...> <key> <out.json>");
  process.exit(1);
}
const key = JSON.parse(readFileSync(keyPath, "utf8")) as { private: string; public: string };
const entries = packages.map((file) => {
  const bytes = readFileSync(file);
  return {
    name: basename(file),
    sha256: createHash("sha256").update(bytes).digest("hex"),
    bytes: bytes.length,
  };
});
const body = {
  format: 1,
  source: { id: "local", name: "This phone", key: `ed25519:${key.public}` },
  serial: 1,
  expires: "2099-01-01T00:00:00Z",
  packages: entries,
};
const payload = Buffer.from(jcs(body));
const signature = sign(null, payload, {
  key: Buffer.from(key.private, "base64"),
  format: "der",
  type: "pkcs8",
});
mkdirSync(dirname(out), { recursive: true });
writeFileSync(out, JSON.stringify({
  body,
  publicKey: key.public,
  signature: Buffer.from(signature).toString("base64"),
}, null, 2));
console.log(`wrote ${out}`);
