// The dialer keys: 1-9, +, 0. Builds one grid of buttons into box; onTap(key) on each press.
export const KEYS = ['1', '2', '3', '4', '5', '6', '7', '8', '9', '+', '0'];
export function fillKeys(box, keys, onTap) {
  box.replaceChildren();
  keys.forEach((k) => {
    const b = document.createElement('button');
    b.type = 'button';
    b.textContent = k;
    b.onclick = () => onTap(k);
    box.appendChild(b);
  });
}
