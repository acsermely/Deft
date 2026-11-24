// Automation (PARITY G9): reads the clickable buttons of the app in front (a11y.windowTree) and taps
// the one whose text you choose (a11y.perform, or a tap at its centre). Screen text is not stored.
import '/_deft/v1/motion.js';
import { call, on } from '/_deft/v1/bridge.js';
import { $, setStatus, run } from './ui.js';
import { readScreen, tapChosen } from './actions.js';

$('read').addEventListener('click', () => run(readScreen));
$('tap').addEventListener('click', () => run(tapChosen));
$('back').addEventListener('click', () => run(() => call('a11y.global', { action: 'back' })));

if (window.deftBridge) {
  let count = 0;
  on('a11y.event', (e) => {
    count += 1;
    if (e.type === 'windowStateChanged') $('last').textContent = `Last window: ${e.package} (${count} events)`;
  });
  setStatus('Ready. Tap "Read screen" to start.');
} else {
  setStatus('No deftBridge: open this app in Deft.');
}
