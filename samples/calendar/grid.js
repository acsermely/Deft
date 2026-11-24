// Month grid: a weekday row and one button per day. main.js passes the state in.
const DOW = ['S', 'M', 'T', 'W', 'T', 'F', 'S'];

function sameDay(a, b) {
  return a.getFullYear() === b.getFullYear() && a.getMonth() === b.getMonth() && a.getDate() === b.getDate();
}

export function renderGrid(grid, view, selected, onPick) {
  const cells = DOW.map((d) => {
    const el = document.createElement('div');
    el.className = 'deft-caption deft-cal-dow';
    el.textContent = d;
    return el;
  });
  const first = new Date(view.getFullYear(), view.getMonth(), 1);
  for (let i = 0; i < first.getDay(); i++) {
    const spacer = document.createElement('div');
    spacer.className = 'deft-cal-spacer';
    cells.push(spacer);
  }
  const days = new Date(view.getFullYear(), view.getMonth() + 1, 0).getDate();
  const today = new Date();
  for (let day = 1; day <= days; day++) {
    const d = new Date(view.getFullYear(), view.getMonth(), day);
    const on = sameDay(d, selected);
    const b = document.createElement('button');
    b.type = 'button';
    b.className = 'deft-cal-day deft-body';
    b.textContent = String(day);
    b.setAttribute('aria-label', d.toLocaleDateString(undefined, { weekday: 'long', month: 'long', day: 'numeric' }));
    b.setAttribute('aria-pressed', String(on));
    b.classList.toggle('today', sameDay(d, today));
    b.classList.toggle('on', on);
    b.addEventListener('click', () => onPick(d));
    cells.push(b);
  }
  grid.replaceChildren(...cells);
}
