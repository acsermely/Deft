// Step count and light level (PARITY F1). Results go to #log and console ("PD ...").

import '/_deft/v1/motion.js';
const $ = (id) => document.getElementById(id);
const subs = new Map(); // type -> subscription id
let stepBase = null;

function log(text) {
  $('log').textContent = text + '\n' + $('log').textContent;
  console.log('PD ' + text);
}
async function start(type, rateHz) {
  if (subs.has(type)) return;
  const r = await window.deftBridge.invoke('sensors.subscribe', { type, rateHz: String(rateHz) });
  subs.set(type, r.id);
  log(`${type} started (id ${r.id})`);
}
async function stop(type) {
  const id = subs.get(type);
  if (!id) return;
  subs.delete(type);
  await window.deftBridge.invoke('sensors.unsubscribe', { id });
}

window.deftBridge.subscribe('sensors.reading', (p) => {
  if (p.type === 'stepCounter') {
    const total = p.values[0];
    if (stepBase === null) stepBase = total;
    $('stepCount').setAttribute('value', String(Math.round(total - stepBase)));
  } else if (p.type === 'light') {
    $('lux').setAttribute('value', p.values[0].toFixed(0));
  }
});

$('steps').addEventListener('click', async () => {
  try { await start('stepCounter', 2); } catch (e) { log('steps FAIL ' + (e.message || e)); }
});
$('light').addEventListener('click', async () => {
  try { await start('light', 5); } catch (e) { log('light FAIL ' + (e.message || e)); }
});
$('stop').addEventListener('click', async () => {
  for (const type of [...subs.keys()]) await stop(type);
  log('stopped');
});
log('ready, deftBridge=' + !!window.deftBridge);
