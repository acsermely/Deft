// Clock: one-shot reminders, scheduled and cancelled through the launcher's alarms bridge.
import '/_deft/v1/motion.js';
import { html, render, repeat } from '/_deft/v1/dom.js';
import { ready, call, on } from '/_deft/v1/bridge.js';
import { toast } from '/_deft/v1/app.js';

const $ = (id) => document.getElementById(id);
const err = (m) => { const box = $('err'); box.querySelector('span').textContent = m || ''; box.hidden = !m; };
function asList(v) { return Array.isArray(v) ? v : []; }
let rows = [];
let shown = false;

async function awaitBridge() {
  if (!window.deftBridge) return false;
  try { await ready(); return true; } catch (e) { return false; }
}
function pad(n) { return String(n).padStart(2, '0'); }
function toInput(ms) {
  const d = new Date(ms);
  return d.getFullYear() + '-' + pad(d.getMonth() + 1) + '-' + pad(d.getDate()) + 'T' + pad(d.getHours()) + ':' + pad(d.getMinutes());
}
async function cancel(id) {
  try {
    await call('alarms.cancel', { id });
    await load();
  } catch (e) { err(e.message); }
}
function reminder(r) {
  const d = new Date(r.triggerAt);
  const time = d.toLocaleTimeString([], { hour: 'numeric', minute: '2-digit' });
  const day = d.toLocaleDateString([], { weekday: 'short', month: 'short', day: 'numeric' });
  return html`<div class="deft-cell"><span class="deft-cell-body"><span class="deft-body deft-cell-title">${r.title || 'Reminder'}</span><span class="deft-footnote deft-cell-sub">${time} · ${day}</span></span><button class="deft-btn" data-tone="destructive" type="button" @click=${() => cancel(r.id)}><deft-icon name="x" aria-hidden="true"></deft-icon>Cancel</button></div>`;
}
function paint() {
  const list = $('list');
  if (!window.deftBridge) {
    render($('empty'), html`<div class="deft-empty"><deft-icon name="alarm-clock" aria-hidden="true"></deft-icon><h2 class="deft-title3">Open in Deft</h2><p class="deft-subhead">Reminders are scheduled by the launcher.</p></div>`);
    render(list, '');
    return;
  }
  const upcoming = rows.slice().sort((a, b) => a.triggerAt - b.triggerAt);
  render($('empty'), upcoming.length ? '' : html`<div class="deft-empty"><deft-icon name="alarm-clock" aria-hidden="true"></deft-icon><h2 class="deft-title3">No reminders</h2><p class="deft-subhead">Add a one-shot alarm. It is not a repeating Clock timer.</p></div>`);
  render(list, html`${repeat(upcoming, (r) => r.id, reminder)}`);
  if (upcoming.length && !shown) { shown = true; deft.enter(list); }
}
async function load() {
  err('');
  try {
    rows = asList(await call('alarms.list', {}));
    paint();
  } catch (e) { err(e.message); paint(); }
}
$('add').onclick = () => {
  $('fWhen').value = toInput(Date.now() + 3600000);
  $('fTitle').value = 'Reminder';
  $('sheet').showModal();
};
$('cancel').onclick = () => $('sheet').close();
$('form').onsubmit = async (ev) => {
  ev.preventDefault();
  const triggerAt = new Date($('fWhen').value).getTime();
  try {
    await call('alarms.schedule', {
      triggerAt,
      title: $('fTitle').value.trim(),
      body: $('fBody').value.trim() || 'Reminder',
    });
    $('sheet').close();
    await load();
  } catch (e) { err(e.message); toast(e.message, { tone: 'error' }); }
};
(async () => {
  if (!(await awaitBridge())) { paint(); return; }
  await load();
  on('alarms.fired', load);
})();
