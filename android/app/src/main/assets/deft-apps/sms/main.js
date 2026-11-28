// Messages: conversation list, one thread and the compose sheet. Logic is the legacy page's.
import '/_deft/v1/motion.js';
import { ready, call, on, inLauncher } from '/_deft/v1/bridge.js';
import { back } from '/_deft/v1/app.js';
import { threadFromHash, backAction, adoptFragmentEntry } from './nav.js';

const $ = (id) => document.getElementById(id);
const sheet = $('sheet');
const errBox = $('err');
const chatErrBox = $('chatErr');
const names = new Map();
let threads = [];
let addrByThread = {};
let openThread = null;
let openAddr = '';
let pendingMms = null;

function el(tag, cls, text) {
  const node = document.createElement(tag);
  node.className = cls;
  if (text !== undefined && text !== null) node.textContent = text;
  return node;
}
function emptyState(iconName, title, text) {
  const box = el('div', 'deft-empty');
  const ic = el('deft-icon');
  ic.setAttribute('name', iconName);
  ic.setAttribute('aria-hidden', 'true');
  box.append(ic, el('h2', 'deft-title3', title), el('p', 'deft-subhead', text));
  return box;
}
// Shows (or clears, when m is empty) an alert box. tone: 'error' or 'success'.
function say(box, m, tone = 'error') {
  box.dataset.tone = tone;
  box.querySelector('deft-icon').setAttribute('name', tone === 'error' ? 'circle-alert' : 'circle-check');
  box.querySelector('span').textContent = m || '';
  box.hidden = !m;
}
function err(m, box = errBox) { say(box, m, 'error'); }

function asList(v) { return Array.isArray(v) ? v : []; }
async function awaitBridge() {
  if (!inLauncher()) return false;
  try { await ready(); return true; } catch (e) { return false; }
}
function digits(s) { return String(s || '').replace(/\D/g, ''); }
function nameFor(addr) {
  const d = digits(addr);
  if (!d) return addr || 'Unknown';
  return names.get(d) || names.get(d.slice(-10)) || addr;
}
function fmtTime(ms) {
  if (!ms) return '';
  return new Date(ms).toLocaleTimeString([], { hour: 'numeric', minute: '2-digit' });
}

function openSheet() { if (!sheet.open) sheet.showModal(); }
function closeSheet() { if (sheet.open) sheet.close(); }
function showInbox() {
  closeSheet();
  $('inbox').hidden = false;
  $('chat').hidden = true;
  openThread = null;
}
function showChat() {
  closeSheet();
  $('inbox').hidden = true;
  $('chat').hidden = false;
}
function sameState(a, b) {
  return !!a && !!b && a.view === b.view && String(a.threadId || '') === String(b.threadId || '');
}
function inboxUrl() { return location.pathname + location.search; }
function pushView(state) {
  if (sameState(history.state, state)) return;
  const url = state.view === 'chat' && state.threadId
    ? '#t/' + encodeURIComponent(state.threadId)
    : (state.view === 'compose' ? '#new' : inboxUrl());
  history.pushState(state, '', url);
}

async function loadNames() {
  try {
    const rows = await call('contacts.list', { limit: 200 });
    names.clear();
    (rows || []).forEach((c) => (c.phones || []).forEach((p) => {
      const d = digits(p);
      if (d) { names.set(d, c.name); if (d.length > 10) names.set(d.slice(-10), c.name); }
    }));
  } catch (e) { /* optional */ }
}
async function loadInbox() {
  err('');
  const box = $('threads');
  if (!inLauncher()) {
    box.replaceChildren(emptyState('smartphone', 'Open in Deft', 'Messages need the SMS role.'));
    return;
  }
  try {
    threads = asList(await call('sms.listThreads', {}));
    let msgs = [];
    try { msgs = asList(await call('sms.listMessages', { limit: 80 })); }
    catch (e) { /* snippet fallback */ }
    addrByThread = {};
    msgs.forEach((m) => {
      if (m.threadId && m.address && !addrByThread[m.threadId]) addrByThread[m.threadId] = m;
    });
    const group = renderInbox();
    if (group) deft.enter(group);
  } catch (e) {
    threads = [];
    err(e.message);
    box.replaceChildren(emptyState('circle-alert', "Can't load messages",
      e.message || 'Allow SMS and confirm the sheet. Reinstall Messages if it was installed before this update.'));
  }
}
// Fills the thread list (filtered by the search box). Returns the group, or null when empty.
function renderInbox() {
  const q = $('q').value.trim().toLowerCase();
  const box = $('threads');
  const rows = threads.filter((t) => {
    const addr = (addrByThread[t.threadId] || {}).address || '';
    const hay = (nameFor(addr) + ' ' + (t.snippet || '') + ' ' + addr).toLowerCase();
    return !q || hay.includes(q);
  });
  if (!rows.length) {
    box.replaceChildren(emptyState('message-square', 'No conversations', 'Tap the pencil button to start a message.'));
    return null;
  }
  const group = el('div', 'deft-group');
  rows.forEach((t) => {
    const last = addrByThread[t.threadId] || {};
    const label = nameFor(last.address || t.threadId);
    const b = el('button', 'deft-cell');
    b.type = 'button';
    b.setAttribute('data-deft-press', '');
    const body = el('span', 'deft-cell-body');
    body.append(el('span', 'deft-body deft-cell-title', label), el('span', 'deft-footnote deft-cell-sub', t.snippet || (last.isMms ? 'MMS' : '')));
    b.append(el('span', 'deft-tile-icon deft-subhead', (label[0] || '?').toUpperCase()), body, el('span', 'deft-caption deft-cell-value', fmtTime(last.date)));
    b.addEventListener('click', () => openChat(t.threadId));
    group.append(b);
  });
  box.replaceChildren(group);
  return group;
}
async function openChat(threadId, fromHistory) {
  err('', chatErrBox);
  if (!fromHistory) pushView({ view: 'chat', threadId });
  openThread = threadId;
  showChat();
  const msgsEl = $('msgs');
  msgsEl.replaceChildren();
  try {
    const list = asList(await call('sms.listMessages', { threadId, limit: 80 })).slice().reverse();
    openAddr = '';
    list.forEach((m) => {
      if (!openAddr && m.address) openAddr = m.address;
      msgsEl.appendChild(el('div', 'bubble' + (m.type === 2 ? ' me' : ''), m.body || (m.isMms ? 'MMS' : '')));
    });
    $('chatTitle').textContent = nameFor(openAddr);
    msgsEl.scrollTop = msgsEl.scrollHeight;
    if (list[0] && list[0].id) {
      call('sms.markRead', { id: list[list.length - 1].id }).catch(() => {});
    }
  } catch (e) { err(e.message, chatErrBox); }
}
async function sendTo(to, body) {
  if (pendingMms && pendingMms.base64) {
    await call('sms.sendMms', { to, body: body || undefined, base64: pendingMms.base64, mime: pendingMms.mime });
    pendingMms = null;
  } else {
    await call('sms.send', { to, body });
  }
}

$('q').addEventListener('input', renderInbox);
$('compose').addEventListener('click', () => {
  openSheet();
  pushView({ view: 'compose' });
});
$('cancel').addEventListener('click', () => {
  if (history.state && history.state.view === 'compose') history.back();
  else closeSheet();
});
$('back').addEventListener('click', () => {
  if (history.state && history.state.view === 'chat') history.back();
  else { showInbox(); loadInbox(); }
});
// A notification tap while the app is open loads `#t/<id>` into this page (no state): open that thread in place.
function adoptFragment() {
  const threadId = adoptFragmentEntry(history, location);
  if (threadId) openChat(threadId, true);
  return !!threadId;
}
window.addEventListener('hashchange', adoptFragment);
window.addEventListener('popstate', () => {
  if (adoptFragment()) return;
  const state = history.state || { view: 'inbox' };
  if (state.view === 'compose') {
    showInbox();
    openSheet();
    return;
  }
  if (state.view === 'chat' && state.threadId) {
    openChat(state.threadId, true);
    return;
  }
  showInbox();
  loadInbox();
});
// Android Back: close the sheet first, then leave a thread for the list. Each step pops the history entry
// it pushed (popstate then redraws), so Back at the list leaves the app instead of replaying stale entries.
back(() => {
  const action = backAction({
    sheetOpen: sheet.open,
    chatShown: !$('chat').hidden,
    view: history.state && history.state.view,
  });
  if (action === 'pop') history.back();
  else if (action === 'close') closeSheet();
  else if (action === 'inbox') { showInbox(); loadInbox(); }
  return action !== false;
});
$('newForm').addEventListener('submit', async (ev) => {
  ev.preventDefault();
  try {
    await sendTo($('to').value.trim(), $('newBody').value.trim());
    $('newBody').value = '';
    if (history.state && history.state.view === 'compose') history.back();
    else closeSheet();
    await loadInbox();
  } catch (e) { err(e.message); }
});
$('send').addEventListener('click', async () => {
  if (!openAddr) { err('No number on this thread', chatErrBox); return; }
  const body = $('body').value.trim();
  if (!body && !pendingMms) return;
  try {
    await sendTo(openAddr, body);
    $('body').value = '';
    await openChat(openThread);
  } catch (e) { err(e.message, chatErrBox); }
});
$('attach').addEventListener('click', async () => {
  err('', chatErrBox);
  try {
    const r = await call('files.pickMedia', { mime: 'image/*' });
    if (r && r.uri) {
      try {
        const file = await call('files.read', { path: r.uri });
        pendingMms = { base64: file.base64, mime: file.mime || r.mime };
      } catch (e) {
        pendingMms = null;
        err('Could not read that photo. Pick a file from your Files folder.', chatErrBox);
        return;
      }
    }
    say(chatErrBox, pendingMms ? 'Photo attached' : '', 'success');
  } catch (e) { err(e.message, chatErrBox); }
});

(async () => {
  const pendingHash = location.hash;
  history.replaceState({ view: 'inbox' }, '', inboxUrl());
  if (!(await awaitBridge())) { loadInbox(); return; }
  await loadNames();
  await loadInbox();
  const threadId = threadFromHash(pendingHash);
  if (threadId) await openChat(threadId);
  on('sms.received', () => (openThread ? openChat(openThread) : loadInbox()));
  on('sms.mmsReceived', () => (openThread ? openChat(openThread) : loadInbox()));
  on('sms.composeRequested', (msg) => {
    $('to').value = msg.to || '';
    $('newBody').value = msg.body || '';
    openSheet();
    pushView({ view: 'compose' });
  });
})();
