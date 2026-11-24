// Renders the result rows of Screen Time. Tappable rows are buttons; plain rows are divs.
// Names and details are written with textContent only.
export function showRows(list, items, run) {
  list.replaceChildren();
  for (const item of items) {
    const row = document.createElement(item.onTap ? 'button' : 'div');
    row.className = 'deft-cell';
    const body = document.createElement('span');
    body.className = 'deft-cell-body';
    const name = document.createElement('span');
    name.className = 'deft-body deft-cell-title';
    name.textContent = item.name;
    const detail = document.createElement('span');
    detail.className = 'deft-footnote deft-cell-sub';
    detail.textContent = item.detail;
    body.append(name, detail);
    row.append(body);
    if (item.onTap) {
      row.setAttribute('type', 'button');
      row.setAttribute('data-deft-press', '');
      const chevron = document.createElement('deft-icon');
      chevron.className = 'deft-chevron';
      chevron.setAttribute('name', 'chevron-right');
      chevron.setAttribute('aria-hidden', 'true');
      row.append(chevron);
      row.addEventListener('click', () => run(item.onTap));
    }
    list.append(row);
  }
}
