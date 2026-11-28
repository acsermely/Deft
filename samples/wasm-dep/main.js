/**
 * WebAssembly dependency sample. The manifest pins add.wasm by its SRI hash; the
 * launcher downloads it on the consent screen, checks the hash and serves it same-origin under
 * /_deft/deps/<host>/<path>. The CDN URL itself is never fetched by the page.
 */

import '/_deft/v1/motion.js';

const out = document.getElementById("result");

async function run() {
  try {
    const response = await fetch("/_deft/deps/app.acsermely.hu/deps/add@1.0.0.wasm");
    if (!response.ok) throw new Error(`HTTP ${response.status}`);
    const { instance } = await WebAssembly.instantiate(await response.arrayBuffer());
    out.textContent = `add(2, 40) from the pinned module = ${instance.exports.add(2, 40)}`;
  } catch (err) {
    out.textContent = `WebAssembly failed: ${err.message}`;
  }
}

run();
