// Calendar: month grid, day agenda and the event sheet. Logic is the legacy page's.
import '/_deft/v1/motion.js';
import { ready, call, inLauncher } from '/_deft/v1/bridge.js';
import { renderGrid } from './grid.js';
import { renderAgenda, emptyState } from './agenda.js';
import { openSheet, initSheet } from './sheet.js';

const $ = (id) => document.getElementById(id);
let view = new Date();
view.setDate(1);
let selected = new Date();
let events = [];
let calendars = [];

function err(m) {
  $('errText').textContent = m || '';
  $('err').hidden = !m;
}
function asList(v) { return Array.isArray(v) ? v : []; }
function startOfDay(d) { const x = new Date(d); x.setHours(0, 0, 0, 0); return x.getTime(); }
function endOfDay(d) { const x = new Date(d); x.setHours(23, 59, 59, 999); return x.getTime(); }
function monthBounds() {
  const start = new Date(view.getFullYear(), view.getMonth(), 1);
  const end = new Date(view.getFullYear(), view.getMonth() + 1, 0, 23, 59, 59, 999);
  return { start: start.getTime(), end: end.getTime() };
}
async function awaitBridge() {
  if (!inLauncher()) return false;
  try { await ready(); return true; } catch (e) { return false; }
}

function renderAll() {
  $('monthTitle').textContent = view.toLocaleString(undefined, { month: 'long', year: 'numeric' });
  renderGrid($('grid'), view, selected, (d) => { selected = d; renderAll(); });
  $('dayLabel').textContent = selected.toLocaleDateString(undefined, { weekday: 'long', day: 'numeric', month: 'long' });
  if (!inLauncher()) {
    $('agenda').replaceChildren(emptyState('smartphone', 'Open in Deft', 'Calendar needs the launcher bridge.'));
    return;
  }
  const day = events.filter((e) => e.dtStart >= startOfDay(selected) && e.dtStart <= endOfDay(selected));
  renderAgenda($('agenda'), day, (e) => openSheet(e, selected.getTime()));
}

async function load() {
  err('');
  try {
    if (!calendars.length) calendars = asList(await call('calendar.listCalendars', {}));
    const { start, end } = monthBounds();
    events = asList(await call('calendar.listEvents', { start, end, limit: 100 }));
    renderAll();
    const group = $('agenda').querySelector('.deft-group');
    if (group) deft.enter(group);
  } catch (e) {
    err(e.message);
    events = [];
    renderAll();
    $('agenda').replaceChildren(emptyState('circle-alert', "Can't load calendar",
      e.message || 'Allow Calendar for this app, and Calendar access for Deft.'));
  }
}

initSheet({ calendar: () => calendars[0], err, reload: load });
$('prev').addEventListener('click', () => { view.setMonth(view.getMonth() - 1); load(); });
$('next').addEventListener('click', () => { view.setMonth(view.getMonth() + 1); load(); });
$('add').addEventListener('click', () => openSheet(null, selected.getTime()));

(async () => {
  renderAll();
  if (!(await awaitBridge())) return;
  await load();
})();
