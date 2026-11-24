// Files list view: rows, plus the empty, bridge and note states. Text goes in as template values only.
import { html, render, repeat } from '/_deft/v1/dom.js';

function sizeText(r) {
  if (r.isDir) return 'Folder';
  return r.size !== undefined && r.size !== null ? r.size + ' B' : 'File';
}
function row(r, act) {
  return html`<button type="button" class="deft-cell" data-deft-press @click=${() => (r.isDir ? act.open(r) : act.file(r))}><span class="deft-tile-icon"><deft-icon name=${r.isDir ? 'folder' : 'file'} aria-hidden="true"></deft-icon></span><span class="deft-cell-body"><span class="deft-body deft-cell-title">${r.name}</span><span class="deft-footnote deft-cell-sub">${sizeText(r)}</span></span>${r.isDir ? html`<deft-icon class="deft-chevron" name="chevron-right" aria-hidden="true"></deft-icon>` : ''}</button>`;
}
function empty(s, act) {
  if (!s.bridge) {
    return html`<div class="deft-empty"><deft-icon name="folder" aria-hidden="true"></deft-icon><h2 class="deft-title3">Open in Deft</h2><p class="deft-subhead">Files need the launcher bridge.</p></div>`;
  }
  if (s.noteTitle) {
    return html`<div class="deft-empty"><deft-icon name="folder" aria-hidden="true"></deft-icon><h2 class="deft-title3">${s.noteTitle}</h2><p class="deft-subhead">${s.note}</p>${s.pick ? html`<button type="button" class="deft-btn" @click=${act.pick}>Pick folder</button>` : ''}</div>`;
  }
  return html`<div class="deft-empty"><deft-icon name="folder" aria-hidden="true"></deft-icon><h2 class="deft-title3">Empty folder</h2><p class="deft-subhead">Nothing here yet.</p></div>`;
}
// Paints the list (#list, rows) or the empty state (#empty). Returns true when rows were painted.
export function paintList(s, act) {
  const hasRows = s.bridge && !s.noteTitle && s.rows.length > 0;
  render(document.getElementById('empty'), hasRows ? '' : empty(s, act));
  render(document.getElementById('list'), hasRows ? html`${repeat(s.rows, (r) => r.path, (r) => row(r, act))}` : '');
  return hasRows;
}
