// Recent calls list and the number-to-name map used for labels. Rows are built with DOM calls, not HTML strings.
import { call } from '/_deft/v1/bridge.js';

const $ = (id) => document.getElementById(id);
const names = new Map();
const TYPE_ICON = { 1: 'phone-incoming', 2: 'phone-outgoing', 3: 'phone-missed' };

function asList(v) { return Array.isArray(v) ? v : []; }
function digits(s) { return String(s || '').replace(/\D/g, ''); }
export function nameFor(addr) {
  const d = digits(addr);
  if (!d) return addr || 'Unknown';
  return names.get(d) || names.get(d.slice(-10)) || addr;
}
export function typeLabel(t) {
  return ({ 1: 'Incoming', 2: 'Outgoing', 3: 'Missed', 4: 'Voicemail', 5: 'Rejected' })[t] || 'Call';
}
function el(tag, cls, text) {
  const n = document.createElement(tag);
  if (cls) n.className = cls;
  if (text !== undefined) n.textContent = text;
  return n;
}
function icon(name) {
  const i = el('deft-icon');
  i.setAttribute('name', name);
  i.setAttribute('aria-hidden', 'true');
  return i;
}
function emptyState(iconName, title, text) {
  const box = el('div', 'deft-empty');
  box.append(icon(iconName), el('h2', 'deft-title3', title), el('p', 'deft-subhead', text));
  return box;
}
function recentRow(c, onPick) {
  const b = el('button', 'deft-cell');
  b.type = 'button';
  b.dataset.deftPress = '';
  const lead = el('span', 'deft-tile-icon');
  lead.append(icon(TYPE_ICON[c.type] || 'phone'));
  const body = el('span', 'deft-cell-body');
  body.append(el('span', 'deft-body deft-cell-title', nameFor(c.number)), el('span', 'deft-footnote deft-cell-sub', typeLabel(c.type)));
  const when = el('span', 'deft-subhead deft-cell-value', c.date ? new Date(c.date).toLocaleDateString() : '');
  b.append(lead, body, when);
  b.onclick = () => onPick(c.number || '');
  return b;
}
export async function loadNames() {
  try {
    const rows = await call('contacts.list', { limit: 200 });
    names.clear();
    (rows || []).forEach((c) => (c.phones || []).forEach((p) => {
      const d = digits(p);
      if (d) { names.set(d, c.name); if (d.length > 10) names.set(d.slice(-10), c.name); }
    }));
  } catch (e) { /* optional */ }
}
// ctx: { live, onPick(number), err(message) }. Fills #recents with one group (it enters once).
export async function loadRecents(ctx) {
  const box = $('recents');
  if (!ctx.live) {
    box.replaceChildren(emptyState('phone', 'Open in Deft', 'Phone needs the dialer role.'));
    return;
  }
  ctx.err('');
  try {
    const rows = asList(await call('telephony.listCalls', { limit: 80 }));
    if (!rows.length) {
      box.replaceChildren(emptyState('history', 'No recents', 'Place a call from the keypad.'));
      return;
    }
    const group = el('div', 'deft-group');
    group.dataset.deftEnter = 'rise';
    rows.forEach((c) => group.append(recentRow(c, ctx.onPick)));
    box.replaceChildren(group);
  } catch (e) {
    ctx.err(e.message);
    box.replaceChildren(emptyState('phone', "Can't load recents", e.message || 'Set Deft as the default Phone app, then reopen Phone.'));
  }
}
