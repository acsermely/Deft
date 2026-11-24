// Photo grid from media.query and media.thumbnail (PARITY F6). Status goes to #log and the console.
// "Open with → Gallery" from another app (PARITY E1) shows the picture at the top of the grid.
import '/_deft/v1/motion.js';
import { call, on } from '/_deft/v1/bridge.js';
const $ = (id) => document.getElementById(id);

function status(text) {
  $('log').textContent = text;
  console.log('GAL ' + text);
}

async function loadPhotos() {
  status('loading');
  const items = await call('media.query', { kind: 'images', limit: '60' });
  const grid = $('grid');
  grid.textContent = '';
  for (const item of items) {
    const cell = document.createElement('figure');
    const img = document.createElement('img');
    img.alt = item.name;
    const caption = document.createElement('figcaption');
    caption.className = 'deft-caption deft-truncate';
    caption.textContent = item.name;
    cell.append(img, caption);
    grid.appendChild(cell);
    try {
      const { base64 } = await call('media.thumbnail', { id: item.id, kind: 'images', size: '256' });
      img.src = 'data:image/jpeg;base64,' + base64;
    } catch (e) {
      caption.textContent = 'no preview';
    }
  }
  status(`${items.length} photos`);
}

$('load').addEventListener('click', () => {
  loadPhotos().catch((e) => status('FAIL ' + ((e && (e.name + ': ' + e.message)) || String(e))));
});
// PARITY E1: a picture another app opened with Gallery. The file was copied into the share inbox;
// read it once by id.
async function showOpened(event) {
  for (const item of event.items || []) {
    const { mime, base64 } = await call('intents.readShare', { id: item.id });
    const cell = document.createElement('figure');
    const img = document.createElement('img');
    img.alt = item.name;
    img.src = `data:${mime};base64,${base64}`;
    const caption = document.createElement('figcaption');
    caption.className = 'deft-caption deft-truncate';
    caption.textContent = 'Opened: ' + item.name;
    cell.append(img, caption);
    $('grid').prepend(cell);
    status(`opened ${item.name} (${event.type}, ${item.size} bytes)`);
  }
}

on('intents.viewReceived', (event) => {
  showOpened(event).catch((e) => status('FAIL ' + ((e && (e.name + ': ' + e.message)) || String(e))));
});
status('ready');
