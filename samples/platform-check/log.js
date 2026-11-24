// Platform Check log: one line per test in #log (newest first) and in the console as "PC ...".
const $ = (id) => document.getElementById(id);

export function log(item, ok, detail) {
  const line = `${item} ${ok ? 'OK' : 'FAIL'} ${detail ?? ''}`;
  $('log').textContent = line + '\n' + $('log').textContent;
  console.log('PC ' + line);
}

export async function step(item, fn) {
  try { log(item, true, await fn()); } catch (e) { log(item, false, (e && (e.name + ': ' + e.message)) || String(e)); }
}
