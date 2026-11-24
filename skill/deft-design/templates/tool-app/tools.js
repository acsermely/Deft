// Tool handler (isolate): a plain synchronous script. Define the function named in manifest handler.isolate.
// No import or export, no promises, no bridge, no network. For a page handler (needs the bridge), use an ES module
// with `export function ...` and handler "./tools.js#fn" instead.
function countWords(params) {
  const text = String(params.text || "").trim();
  return { characters: text.length, words: text ? text.split(/\s+/).length : 0 };
}
