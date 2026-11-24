// Automation actions: read the buttons of the app in front, and tap the one whose text is chosen.
import { call } from '/_deft/v1/bridge.js';
import { $, setStatus } from './ui.js';
import { clickables, centre, showRows } from './screen.js';

const DELAY_S = 5;

function countdown(what) {
  return new Promise((resolve) => {
    let left = DELAY_S;
    setStatus(`Switch to the other app now: ${what} in ${left} s`);
    const timer = setInterval(() => {
      left -= 1;
      if (left > 0) {
        setStatus(`Switch to the other app now: ${what} in ${left} s`);
        return;
      }
      clearInterval(timer);
      resolve();
    }, 1000);
  });
}

export async function readScreen() {
  await countdown('reading the screen');
  const tree = await call('a11y.windowTree', { maxNodes: 2000 });
  const items = clickables(tree);
  showRows(items, tree.package);
  setStatus(`${items.length} buttons in ${tree.package}${tree.truncated ? ' (screen too large, some left out)' : ''}`);
}

export async function tapChosen() {
  const want = $('target').value.trim().toLowerCase();
  if (!want) {
    setStatus('Type or choose the button text first');
    return;
  }
  await countdown(`tapping "${want}"`);
  const tree = await call('a11y.windowTree', { maxNodes: 2000 });
  const items = clickables(tree);
  const hit = items.find((i) => i.label.toLowerCase() === want) || items.find((i) => i.label.toLowerCase().includes(want));
  if (!hit) {
    setStatus(`No button "${want}" in ${tree.package}`);
    return;
  }
  const res = await call('a11y.perform', { nodeId: hit.node.id, action: 'click' });
  if (res.performed) {
    setStatus(`Tapped "${hit.label}" in ${tree.package}`);
    return;
  }
  const done = await call('a11y.gesture', { path: [centre(hit.node.bounds)], durationMs: 50 });
  setStatus(done.completed ? `Tapped "${hit.label}" at its centre in ${tree.package}` : `Could not tap "${hit.label}"`);
}
