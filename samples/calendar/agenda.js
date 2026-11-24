// Day agenda: the selected day's events as tappable cells, and the empty states.
function el(tag, cls, text) {
  const node = document.createElement(tag);
  node.className = cls;
  if (text !== undefined && text !== null) node.textContent = text;
  return node;
}

function icon(name, cls) {
  const node = document.createElement('deft-icon');
  node.setAttribute('name', name);
  node.setAttribute('aria-hidden', 'true');
  if (cls) node.className = cls;
  return node;
}

export function emptyState(iconName, title, text) {
  const box = el('div', 'deft-empty');
  box.append(icon(iconName), el('h2', 'deft-title3', title), el('p', 'deft-subhead', text));
  return box;
}

const timeOf = (ms) => new Date(ms).toLocaleTimeString([], { hour: 'numeric', minute: '2-digit' });

// Fills box with a group of rows, one per event. Returns the group, or null when empty.
export function renderAgenda(box, rows, onOpen) {
  if (!rows.length) {
    box.replaceChildren(emptyState('calendar', 'Nothing today', 'Tap + to add an event.'));
    return null;
  }
  const group = el('div', 'deft-group');
  rows.forEach((e) => {
    const b = el('button', 'deft-cell');
    b.type = 'button';
    b.setAttribute('data-deft-press', '');
    const body = el('span', 'deft-cell-body');
    body.append(el('span', 'deft-body deft-cell-title', e.title || '(No title)'), el('span', 'deft-footnote deft-cell-sub', e.description || ''));
    b.append(body, el('span', 'deft-subhead deft-cell-value', timeOf(e.dtStart)), icon('chevron-right', 'deft-chevron'));
    b.addEventListener('click', () => onOpen(e));
    group.append(b);
  });
  box.replaceChildren(group);
  return group;
}
