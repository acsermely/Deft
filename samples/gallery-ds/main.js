// Design Gallery. Reads tokens from the live theme so it shows what the page actually renders.
// Re-renders on deft:theme, which the launcher dispatches after it refetches theme.css.
import { haptic } from '/_deft/v1/motion.js';
import { morph } from '/_deft/v1/route.js';
const root = document.documentElement;
const $ = (id) => document.getElementById(id);
const tok = (name) => getComputedStyle(root).getPropertyValue(name).trim() || '(unset)';
const el = (tag, cls, text) => {
  const n = document.createElement(tag);
  if (cls) n.className = cls;
  if (text !== undefined) n.textContent = text;
  return n;
};

const ACCENT = ['accent', 'on-accent'];
const SURFACES = ['bg', 'bg-grouped', 'bg-elevated'];
const FILLS = ['fill', 'fill-2', 'fill-3', 'fill-4', 'separator'];
const TYPE = ['largeTitle', 'title1', 'title2', 'title3', 'headline', 'body', 'callout', 'subhead', 'footnote', 'caption', 'labelMono'];
const RADII = ['xs', 'sm', 'md', 'lg', 'xl', 'screen'];
const SPRINGS = ['snappy', 'smooth', 'bouncy'];
const STATUS = [
  { tone: 'error', label: 'Error: the request failed' },
  { tone: 'warning', label: 'Warning: storage is nearly full' },
  { tone: 'success', label: 'Success: changes saved' },
  { tone: 'info', label: 'Info: sync runs in the background' },
];

function swatch(name, bgVar, labelVar) {
  const d = el('div', 'ds-swatch');
  d.style.background = `var(--deft-${bgVar})`;
  if (labelVar) d.style.color = `var(--deft-${labelVar})`;
  d.append(el('span', 'ds-cap', `--deft-${name}`));
  return d;
}

function renderReadout() {
  $('readout').textContent = `appearance ${tok('--deft-appearance')} · tier ${tok('--deft-tier')} · accent from the theme`;
}

function renderTokens() {
  // Both accent swatches sit on the accent fill; --deft-on-accent is the text drawn on it.
  $('accent').replaceChildren(...ACCENT.map((n) => swatch(n, 'accent', 'on-accent')));
  $('surfaces').replaceChildren(...SURFACES.map((n) => swatch(n, n, 'label')));
  $('fills').replaceChildren(...FILLS.map((n) => swatch(n, n, 'label')));

  // Status: always an icon and text, the tone only tints the icon (8.1).
  $('status').replaceChildren(...STATUS.map((s) => {
    const row = el('div', 'deft-alert');
    row.dataset.tone = s.tone;
    row.append(el('span', 'ds-cap', `--deft-${s.tone}`), el('span', 'deft-body', s.label));
    return row;
  }));

  $('type').replaceChildren(...TYPE.map((k) => {
    const row = el('div', 'ds-stack');
    row.append(el('span', 'ds-cap', `.deft-${k}`), el('p', `deft-${k}`, `${k}: The quick brown fox 0123`));
    return row;
  }));

  $('radii').replaceChildren(...RADII.map((r) => {
    const box = el('div', '', r);
    box.style.borderRadius = `var(--deft-radius-${r})`;
    return box;
  }));
  $('pad').textContent = `--deft-pad ${tok('--deft-pad')} · card inner radius = outer minus pad`;

  $('springs').replaceChildren(...SPRINGS.map((s) => el('li', 'deft-body', `${s}: ${tok(`--deft-spring-${s}-duration`)}`)));
}

// Values that cannot be written as attributes (lint forbids inline style), so they are set here.
function wireValues() {
  for (const s of document.querySelectorAll('[data-slider]')) {
    const put = () => s.style.setProperty('--deft-value', `${((s.value - s.min) / (s.max - s.min)) * 100}%`);
    s.addEventListener('input', put);
    put();
  }
  for (const p of document.querySelectorAll('.deft-progress')) {
    p.style.setProperty('--deft-value', `${p.dataset.value}%`);
    if (p.dataset.segs) p.style.setProperty('--deft-segs', p.dataset.segs);
  }
}

// Segmented and chip selection: one selected item per group, neutral (no accent).
function wireSelection() {
  for (const group of document.querySelectorAll('.deft-segmented, [data-group]')) {
    const attr = group.classList.contains('deft-segmented') ? 'aria-selected' : 'aria-pressed';
    group.addEventListener('click', (e) => {
      const item = e.target.closest('button');
      if (!item || !group.contains(item)) return;
      for (const b of group.querySelectorAll('button')) b.setAttribute(attr, String(b === item));
    });
  }
}

function wireControls() {
  const dot = $('spring-dot');
  const track = dot.parentElement;
  let moved = false;
  $('spring-demo').addEventListener('click', () => {
    moved = !moved;
    const travel = Math.max(0, track.clientWidth - dot.offsetWidth - 12);
    dot.style.transform = moved ? `translateX(${travel}px)` : 'translateX(0)';
  });

  let count = 3;
  for (const b of document.querySelectorAll('.deft-stepper [data-step]')) {
    b.addEventListener('click', () => {
      count = Math.max(0, Math.min(9, count + Number(b.dataset.step)));
      $('stepper-value').textContent = String(count);
    });
  }

  for (const [openId, dialogId] of [['open-dialog', 'dialog'], ['open-sheet', 'sheet']]) {
    $(openId).addEventListener('click', () => $(dialogId).showModal());
  }
  for (const d of document.querySelectorAll('dialog')) {
    d.addEventListener('click', (e) => { if (e.target === d || e.target.hasAttribute('data-close')) d.close(); });
  }
}

wireControls();
wireSelection();
wireValues();
renderReadout();
renderTokens();
window.addEventListener('deft:theme', () => { renderReadout(); renderTokens(); });

// Motion and icon demos (deft-design motion.md, icons.md). The imports above start motion.js, which defines deft.toast.
document.querySelectorAll('[data-haptic]').forEach((b) => b.addEventListener('click', () => haptic(b.dataset.haptic)));
$('open-detent').addEventListener('click', () => $('detent').showModal());
const num = $('num');
document.querySelectorAll('[data-num]').forEach((b) => b.addEventListener('click', () => num.setAttribute('value', String(Number(num.getAttribute('value')) + Number(b.dataset.num)))));
const [morphA, morphB] = [$('morph-a'), $('morph-b')];
$('morph-go').addEventListener('click', () => morph(() => { morphA.hidden = !morphA.hidden; morphB.hidden = !morphB.hidden; }));
document.querySelectorAll('[data-toast]').forEach((b) => b.addEventListener('click', () => deft.toast(b.dataset.toast, { tone: b.dataset.tone })));
fetch('/_deft/v1/icons.svg').then((r) => r.text()).then((svg) => {
  const ids = [...new DOMParser().parseFromString(svg, 'image/svg+xml').querySelectorAll('symbol')].map((x) => x.id);
  $('icons').replaceChildren(...ids.map((id) => {
    const cell = el('div'), icon = document.createElement('deft-icon');
    icon.setAttribute('name', id);
    cell.append(icon, el('span', null, id));
    return cell;
  }));
});
