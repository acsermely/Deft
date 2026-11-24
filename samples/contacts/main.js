// Contacts: list with search, a detail drill-down, and the new or edit sheet.
import '/_deft/v1/motion.js';
import { ready, call } from '/_deft/v1/bridge.js';
import { listView, paint } from './view.js';
import { openForm, wireForm } from './form.js';
import { showList, openDetail } from './detail.js';

const $ = (id) => document.getElementById(id);
const err = (m) => { const box = $('err'); box.querySelector('span').textContent = m || ''; box.hidden = !m; };
function asList(v) { return Array.isArray(v) ? v : []; }
let people = [];
let openId = null;
let listNote = '';
let bridge = true;
let shown = false;

async function awaitBridge() {
  if (!window.deftBridge) return false;
  try { await ready(); return true; } catch (e) { return false; }
}
function filtered() {
  const q = $('q').value.trim().toLowerCase();
  if (!q) return people;
  return people.filter((c) => {
    const hay = [c.name, c.org, ...(c.phones || []), ...(c.emails || [])].join(' ').toLowerCase();
    return hay.includes(q);
  });
}
function paintList() {
  const rows = filtered().slice().sort((a, b) => (a.name || '').localeCompare(b.name || ''));
  paint('list', listView({ bridge, note: listNote, rows }, openContact));
  if (rows.length && !shown) { shown = true; deft.enter($('list')); }
}
function openContact(id) {
  const c = people.find((x) => x.id === id);
  if (!c) return;
  openId = id;
  openDetail(c);
}
async function load() {
  err('');
  try {
    people = asList(await call('contacts.list', { query: $('q').value.trim(), limit: 400 }));
    listNote = '';
    paintList();
    if (openId) openContact(openId);
  } catch (e) {
    people = [];
    err(e.message);
    listNote = e.message || 'Allow Contacts in the launcher, then reopen this app.';
    paintList();
  }
}
wireForm({ err, reload: load, showList: () => { showList(); openId = null; } });
$('q').addEventListener('input', () => {
  if (!people.length) load();
  else paintList();
});
$('add').onclick = () => openForm(null);
$('back').onclick = () => { showList(); openId = null; };
$('edit').onclick = () => {
  const c = people.find((x) => x.id === openId);
  if (c) openForm(c);
};
(async () => {
  if (!(await awaitBridge())) { bridge = false; paintList(); return; }
  await load();
})();
