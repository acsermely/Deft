// rows.js: renders the discovered devices as tappable cells. Text is written with textContent only.
export function showRows(box, names, onTap) {
  box.replaceChildren();
  for (const name of names) {
    const row = document.createElement('button');
    row.type = 'button';
    row.className = 'deft-cell';
    row.setAttribute('data-deft-press', '');
    const icon = document.createElement('span');
    icon.className = 'deft-tile-icon';
    const wifi = document.createElement('deft-icon');
    wifi.setAttribute('name', 'wifi');
    wifi.setAttribute('aria-hidden', 'true');
    icon.append(wifi);
    const body = document.createElement('span');
    body.className = 'deft-cell-body';
    const title = document.createElement('span');
    title.className = 'deft-body deft-cell-title';
    title.textContent = name;
    body.append(title);
    const chevron = document.createElement('deft-icon');
    chevron.className = 'deft-chevron';
    chevron.setAttribute('name', 'chevron-right');
    chevron.setAttribute('aria-hidden', 'true');
    row.append(icon, body, chevron);
    row.addEventListener('click', () => onTap(name));
    box.append(row);
  }
}
