// Simple Keyboard keyboard page (PARITY G11 ime). The launcher shows this page as the phone's
// keyboard. It types with ime.commitText / ime.deleteBefore / ime.sendKey, and learns the kind of
// field from ime.start. It never sees password fields: the launcher shows its own keyboard there.
// Outside the launcher (opened in a browser) there is no bridge; the keys then do nothing.
import '/_deft/v1/motion.js';
const bridge = window.deftBridge || { invoke: () => Promise.resolve(null), subscribe: () => {} };
const keysEl = document.getElementById('keys');

const LETTERS = ['qwertyuiop', 'asdfghjkl', 'zxcvbnm'];
const SYMBOLS = ['1234567890', '@#$%&-+()/', '*"\':;!?_='];
const ENTER_LABELS = { go: 'Go', search: 'Search', send: 'Send', next: 'Next', done: 'Done', none: 'Enter' };

let shift = false;
let symbols = false;
let enterLabel = 'Enter';

const ignore = () => null;

function key(label, cls, onPress) {
  const b = document.createElement('button');
  b.type = 'button';
  b.className = 'deft-btn key' + (cls ? ' ' + cls : '');
  b.textContent = label;
  // pointerdown types at once, like a real keyboard; it also keeps focus where it is.
  b.addEventListener('pointerdown', (e) => {
    e.preventDefault();
    onPress();
  });
  return b;
}

function render() {
  keysEl.textContent = '';
  const rows = symbols ? SYMBOLS : LETTERS;
  rows.forEach((chars, i) => {
    const row = document.createElement('div');
    row.className = 'row';
    if (!symbols && i === rows.length - 1) {
      row.appendChild(key('Shift', 'wide' + (shift ? ' on' : ''), () => { shift = !shift; render(); }));
    }
    for (const c of chars) {
      const text = shift && !symbols ? c.toUpperCase() : c;
      row.appendChild(key(text, '', () => {
        bridge.invoke('ime.commitText', { text }).catch(ignore);
        if (shift) { shift = false; render(); }
      }));
    }
    if (i === rows.length - 1) row.appendChild(key('Del', 'wide', () => bridge.invoke('ime.deleteBefore', { n: 1 }).catch(ignore)));
    keysEl.appendChild(row);
  });
  const bottom = document.createElement('div');
  bottom.className = 'row';
  bottom.appendChild(key(symbols ? 'ABC' : '?123', 'wide', () => { symbols = !symbols; shift = false; render(); }));
  bottom.appendChild(key('Switch', 'wide', () => bridge.invoke('ime.switchKeyboard', {}).catch(ignore)));
  bottom.appendChild(key(',', '', () => bridge.invoke('ime.commitText', { text: ',' }).catch(ignore)));
  bottom.appendChild(key('Space', 'space', () => bridge.invoke('ime.commitText', { text: ' ' }).catch(ignore)));
  bottom.appendChild(key('.', '', () => bridge.invoke('ime.commitText', { text: '.' }).catch(ignore)));
  bottom.appendChild(key(enterLabel, 'wide', () => bridge.invoke('ime.sendKey', { key: 'enter' }).catch(ignore)));
  keysEl.appendChild(bottom);
}

bridge.subscribe('ime.start', (e) => {
  // Numbers and phone fields start on the digits page; the Enter key shows the field's action.
  symbols = !!e && (e.inputType === 'number' || e.inputType === 'phone');
  shift = !!e && e.inputType === 'text';
  enterLabel = (e && ENTER_LABELS[e.action]) || 'Enter';
  render();
});
bridge.subscribe('ime.finish', () => { shift = false; });
bridge.invoke('ime.setHeight', { dp: 240 }).catch(ignore);

render();
