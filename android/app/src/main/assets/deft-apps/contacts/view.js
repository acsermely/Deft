// Contacts views: the grouped list and the contact detail. Text goes in as template values only.
import { html, render, repeat } from '/_deft/v1/dom.js';

export function initials(name) {
  const p = String(name || '').trim().split(/\s+/).filter(Boolean);
  return ((p[0] || '?')[0] + (p[1] ? p[1][0] : '')).toUpperCase();
}
export function letter(name) {
  const c = String(name || '').trim().charAt(0).toUpperCase();
  return c >= 'A' && c <= 'Z' ? c : '#';
}
// Groups sorted contacts by their first letter, keeping order.
export function sections(rows) {
  const out = [];
  rows.forEach((c) => {
    const L = letter(c.name);
    const last = out[out.length - 1];
    if (last && last.letter === L) last.rows.push(c);
    else out.push({ letter: L, rows: [c] });
  });
  return out;
}
function contactRow(c, onOpen) {
  const sub = (c.phones && c.phones[0]) || (c.emails && c.emails[0]) || c.org || '';
  return html`<button type="button" class="deft-cell" data-deft-press @click=${() => onOpen(c.id)}><span class="initials deft-headline">${initials(c.name)}</span><span class="deft-cell-body"><span class="deft-body deft-cell-title">${c.name || 'Unnamed'}</span><span class="deft-footnote deft-cell-sub">${sub}</span></span><deft-icon class="deft-chevron" name="chevron-right" aria-hidden="true"></deft-icon></button>`;
}
// The list: a state card, or one group per letter. Returns the template.
export function listView(s, onOpen) {
  if (!s.bridge) {
    return html`<div class="deft-empty"><deft-icon name="user" aria-hidden="true"></deft-icon><h2 class="deft-title3">Open in Deft</h2><p class="deft-subhead">Contacts need the launcher bridge.</p></div>`;
  }
  if (s.note) {
    return html`<div class="deft-empty"><deft-icon name="user" aria-hidden="true"></deft-icon><h2 class="deft-title3">Can't load contacts</h2><p class="deft-subhead">${s.note}</p></div>`;
  }
  if (!s.rows.length) {
    return html`<div class="deft-empty"><deft-icon name="user" aria-hidden="true"></deft-icon><h2 class="deft-title3">No contacts</h2><p class="deft-subhead">Add someone, or search a different name.</p></div>`;
  }
  return html`${repeat(sections(s.rows), (sec) => sec.letter, (sec) => html`<section class="deft-stack"><h2 class="deft-labelMono">${sec.letter}</h2><div class="deft-group">${repeat(sec.rows, (c) => c.id, (c) => contactRow(c, onOpen))}</div></section>`)}`;
}
function cell(label, value) {
  return html`<div class="deft-cell"><span class="deft-cell-body"><span class="deft-footnote deft-cell-sub">${label}</span><span class="deft-body deft-cell-title">${value}</span></span></div>`;
}
// The detail for one contact. act = { call(phone), message(phone) }.
export function detailView(c, act) {
  const phone = (c.phones && c.phones[0]) || '';
  const fields = [];
  (c.phones || []).forEach((p) => fields.push({ label: 'Phone', value: p }));
  (c.emails || []).forEach((e) => fields.push({ label: 'Email', value: e }));
  if (c.org) fields.push({ label: 'Organization', value: c.org });
  return html`<div class="deft-stack"><div class="detail-head deft-stack"><span class="initials initials-lg deft-title1">${initials(c.name)}</span></div>${phone ? html`<div class="deft-cluster"><button type="button" class="deft-btn" @click=${() => act.call(phone)}><deft-icon name="phone" aria-hidden="true"></deft-icon>Call</button><button type="button" class="deft-btn" @click=${() => act.message(phone)}><deft-icon name="message-circle" aria-hidden="true"></deft-icon>Message</button></div>` : ''}<div class="deft-group">${repeat(fields, (f) => f.label + f.value, (f) => cell(f.label, f.value))}</div></div>`;
}
export function paint(id, tpl) { render(document.getElementById(id), tpl); }
