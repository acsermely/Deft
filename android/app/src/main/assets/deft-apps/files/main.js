// Files: browse the picked folder through the files bridge; actions on one file.
import '/_deft/v1/motion.js';
import { ready, call } from '/_deft/v1/bridge.js';
import { toast } from '/_deft/v1/app.js';
import { paintList } from './view.js';
import { wireSheet } from './sheet.js';

const $ = (id) => document.getElementById(id);
const err = (m) => { const box = $('err'); box.querySelector('span').textContent = m || ''; box.hidden = !m; };
function asList(v) { return Array.isArray(v) ? v : []; }
const state = { bridge: true, rows: [], noteTitle: '', note: '', pick: false };
let path = '';
let target = null;
let shown = false;

async function awaitBridge() {
  if (!window.deftBridge) return false;
  try { await ready(); return true; } catch (e) { return false; }
}
function parentPath(p) {
  const parts = p.split('/').filter(Boolean);
  parts.pop();
  return parts.join('/');
}
function paint() {
  const filled = paintList(state, act);
  if (filled && !shown) { shown = true; deft.enter($('list')); }
}
async function listDir(next) {
  err('');
  path = next ?? path;
  $('up').hidden = !path;
  $('crumb').textContent = path ? path : 'Folder';
  try {
    state.rows = asList(await call('files.listDir', { path }));
    Object.assign(state, { noteTitle: '', note: '', pick: false });
    paint();
  } catch (e) {
    state.rows = [];
    Object.assign(state, { noteTitle: "Can't open files", note: e.message || 'Pick a folder. Without one, files stay in Downloads.', pick: true });
    paint();
    err(e.message);
  }
}
function openSheet(r) {
  target = r;
  $('sheetName').textContent = r.name;
  $('sheet').showModal();
}
async function pickFolder() {
  err('');
  try {
    await call('files.pickTree', {});
    path = '';
    await listDir('');
  } catch (e) { err(e.message); }
}
const act = { open: (r) => listDir(r.path), file: openSheet, pick: pickFolder };
// The file sheet is modal and covers the banner, so its failures also toast.
wireSheet({ target: () => target, dir: () => path, reload: listDir, err: (m) => { err(m); if (m) toast(m, { tone: 'error' }); } });
$('folder').onclick = pickFolder;
$('up').onclick = () => listDir(parentPath(path));
$('photo').onclick = async () => {
  err('');
  try { await call('files.pickMedia', { mime: 'image/*' }); }
  catch (e) { err(e.message); }
};
(async () => {
  if (!(await awaitBridge())) {
    state.bridge = false;
    paint();
    return;
  }
  await listDir('');
})();
