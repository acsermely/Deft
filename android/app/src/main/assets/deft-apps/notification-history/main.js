// Notification History : lists other apps' notifications showing now (open, clear,
// reply) and a history of everything posted, kept in IndexedDB. The background module (bg.js)
// adds to the history while the app is closed.
import '/_deft/v1/motion.js';
import { call, on } from '/_deft/v1/bridge.js';
import { save, list, clear } from './store.js';
import { row, toolsFor } from './rows.js';

const $ = (id) => document.getElementById(id);
let mode = 'now';

function setStatus(text, tone = 'info') {
  const box = $('status');
  box.dataset.tone = tone;
  box.querySelector('deft-icon').setAttribute('name', tone === 'error' ? 'circle-alert' : 'info');
  box.querySelector('span').textContent = text;
}

function explain(e) {
  const code = e && e.code;
  if (code === 'not_granted') return 'Allow "Read all your notifications" for this app on its card in the launcher dashboard.';
  if (code === 'os_permission_missing') return 'Turn on Notification access for Deft in system settings.';
  return (e && (e.code ? e.code + ': ' : '') + (e.message || e.name)) || String(e);
}

async function run(fn) {
  try { await fn(); } catch (e) { setStatus(explain(e), 'error'); }
}

function markMode() {
  $('showNow').setAttribute('aria-pressed', String(mode === 'now'));
  $('showHistory').setAttribute('aria-pressed', String(mode === 'history'));
}

const ctx = { run, report: setStatus, refresh: () => showNow() };

async function showNow() {
  mode = 'now';
  markMode();
  const rows = await call('notifications.active', {});
  for (const n of rows) await save(n);
  $('rows').replaceChildren(...rows.map((n) => row(n, toolsFor(n, ctx))));
  setStatus(rows.length ? `${rows.length} showing now` : 'No notifications from other apps right now');
}

async function showHistory() {
  mode = 'history';
  markMode();
  const rows = await list();
  $('rows').replaceChildren(...rows.map((n) => row(n, null)));
  setStatus(rows.length ? `${rows.length} in the history` : 'The history is empty');
}

function refresh() {
  run(mode === 'now' ? showNow : showHistory);
}

$('showNow').addEventListener('click', () => run(showNow));
$('showHistory').addEventListener('click', () => run(showHistory));
$('clear').addEventListener('click', () => run(async () => {
  await clear();
  if (mode === 'history') await showHistory();
  setStatus('History cleared');
}));

if (window.deftBridge) {
  on('notifications.posted', async (p) => {
    try { await save(p); } catch (e) { /* history is best effort */ }
    refresh();
  });
  on('notifications.removed', () => {
    if (mode === 'now') refresh();
  });
  run(showNow);
} else {
  setStatus('No deftBridge: open this app in Deft.');
}
