// The incoming call screen: a modal dialog that stays up until the call ends (Esc does not dismiss it).
import { call, on } from '/_deft/v1/bridge.js';
import { nameFor } from './recents.js';
import { fillKeys, KEYS } from './keys.js';

const $ = (id) => document.getElementById(id);
export function setIncoming(show) {
  const d = $('incoming');
  if (show && !d.open) d.showModal();
  if (!show && d.open) d.close();
}
// Wires the call-control buttons. err(message) shows a failed control.
export function wireIncoming(err) {
  $('incoming').addEventListener('cancel', (ev) => ev.preventDefault());
  $('answer').onclick = () => call('telephony.answer', {}).catch((e) => err(e.message));
  $('reject').onclick = () => call('telephony.reject', {}).catch((e) => err(e.message));
  $('end').onclick = () => {
    call('telephony.end', {}).catch((e) => err(e.message));
    setIncoming(false);
  };
  $('hold').onclick = () => call('telephony.hold', { hold: true }).catch((e) => err(e.message));
  fillDtmf(err);
}
function fillDtmf(err) {
  fillKeys($('dtmf'), KEYS, (k) => {
    if ('*#'.includes(k) || k === '+') return;
    call('telephony.dtmf', { digit: k }).catch((e) => err(e.message));
  });
}
// Keeps the screen in step with the call: shown on incoming, hidden when it ends.
export function watchIncoming() {
  on('telephony.incoming', (p) => {
    $('callfrom').textContent = nameFor(p.handle || p.e164 || 'Unknown');
    setIncoming(true);
  });
  on('telephony.ended', () => setIncoming(false));
  on('telephony.state', (p) => {
    if (p && p.state === 'disconnected') setIncoming(false);
  });
}
