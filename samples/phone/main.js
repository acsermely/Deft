// Phone: keypad dialer and recents. The incoming call screen lives in incoming.js.
import '/_deft/v1/motion.js';
import { ready, call } from '/_deft/v1/bridge.js';
import { toast } from '/_deft/v1/app.js';
import { loadNames, loadRecents } from './recents.js';
import { wireIncoming, watchIncoming } from './incoming.js';
import { fillKeys, KEYS } from './keys.js';

const $ = (id) => document.getElementById(id);
const err = (m) => { const box = $('err'); box.querySelector('span').textContent = m || ''; box.hidden = !m; };
function asList(v) { return Array.isArray(v) ? v : []; }
let number = '';
let subId = null;
let live = false;
const ctx = { get live() { return live; }, err, onPick: (n) => { number = n; renderDial(); showTab(true); } };

async function awaitBridge() {
  if (!window.deftBridge) return false;
  try { await ready(); return true; } catch (e) { return false; }
}
function setCurrent(btn, on) {
  if (on) btn.setAttribute('aria-current', 'page');
  else btn.removeAttribute('aria-current');
}
function showTab(keypad) {
  $('recentsView').hidden = keypad;
  $('keypad').hidden = !keypad;
  setCurrent($('tabRecents'), !keypad);
  setCurrent($('tabKeypad'), keypad);
}
function renderDial() { $('dialNum').textContent = number || ' '; }
async function loadSims() {
  try {
    const sims = asList(await call('telephony.listSims', {}));
    if (sims.length < 2) return;
    const sel = $('sim');
    sel.replaceChildren(...sims.map((s) => {
      const o = document.createElement('option');
      o.value = String(s.subscriptionId);
      o.textContent = s.displayName || s.carrierName || ('SIM ' + (Number(s.slot) + 1));
      return o;
    }));
    subId = Number(sims[0].subscriptionId);
    sel.onchange = () => { subId = Number(sel.value); };
    sel.hidden = false;
  } catch (e) { /* optional: one SIM, or no phone-state permission */ }
}
async function placeCall() {
  const n = number.replace(/\s/g, '');
  if (!n) return;
  err('');
  try {
    const args = subId === null ? { e164: n } : { e164: n, subscriptionId: subId };
    await call('telephony.call', args);
  } catch (e) { err(e.message); }
}
$('tabRecents').onclick = () => { showTab(false); loadRecents(ctx); };
$('tabKeypad').onclick = () => showTab(true);
$('call').onclick = placeCall;
$('backspace').onclick = () => { number = number.slice(0, -1); renderDial(); };
fillKeys($('keys'), KEYS, (k) => { number += k; renderDial(); });
// The call screen is modal and covers the banner, so its failures also toast.
wireIncoming((m) => { err(m); if (m) toast(m, { tone: 'error' }); });
(async () => {
  renderDial();
  live = await awaitBridge();
  if (!live) { loadRecents(ctx); return; }
  await loadNames();
  await loadSims();
  await loadRecents(ctx);
  watchIncoming();
})();
