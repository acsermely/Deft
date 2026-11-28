/**
 * WebAssembly sample. add.wasm is a hand-made module that exports add(a, b).
 * It ships inside the app, so the app declares the wasm capability.
 */

import '/_deft/v1/motion.js';

const out = document.getElementById("result");

async function run() {
  try {
    const response = await fetch("./add.wasm");
    if (!response.ok) throw new Error(`HTTP ${response.status}`);
    const { instance } = await WebAssembly.instantiate(await response.arrayBuffer());
    out.textContent = `add(2, 40) in WebAssembly = ${instance.exports.add(2, 40)}`;
  } catch (err) {
    out.textContent = `WebAssembly failed: ${err.message}`;
  }
}

run();
