// Automation screen model: the clickable buttons of a window tree, and the list of them on the page.
// Screen text is written with textContent only and not stored.
import { $, setStatus } from './ui.js';

function labelOf(node) {
  return (node.text || node.contentDescription || '').trim();
}

/** Clickable nodes with a label; a label on a child counts for its clickable parent. */
export function clickables(tree) {
  const byId = new Map(tree.nodes.map((n) => [n.id, n]));
  const out = new Map();
  for (const n of tree.nodes) {
    const label = labelOf(n);
    if (!label) continue;
    let target = n;
    while (target && !target.clickable) target = byId.get(target.parentId);
    if (target && !out.has(target.id)) out.set(target.id, { node: target, label });
  }
  return [...out.values()];
}

export function centre(bounds) {
  return [Math.round((bounds[0] + bounds[2]) / 2), Math.round((bounds[1] + bounds[3]) / 2)];
}

function rowFor({ node, label }, pkg) {
  const b = document.createElement('button');
  b.type = 'button';
  b.className = 'deft-cell';
  b.setAttribute('data-deft-press', '');
  const body = document.createElement('span');
  body.className = 'deft-cell-body';
  const name = document.createElement('span');
  name.className = 'deft-body deft-cell-title';
  name.textContent = label;
  body.append(name);
  const where = document.createElement('span');
  where.className = 'deft-subhead deft-cell-value where';
  where.textContent = centre(node.bounds).join(', ');
  b.append(body, where);
  b.addEventListener('click', () => {
    $('target').value = label;
    setStatus(`Chosen: ${label}. Tap "Tap it", then switch to ${pkg}.`);
  });
  return b;
}

export function showRows(items, pkg) {
  const list = $('rows');
  list.replaceChildren(...items.map((i) => rowFor(i, pkg)));
  deft.enter(list);
}
