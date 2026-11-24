// RSS Reader (PARITY G1): reads any RSS or Atom feed the user types, with network.open.
// The feed server must allow cross-origin reads (CORS); pictures load from any https site.
import '/_deft/v1/motion.js';
import { store } from '/_deft/v1/store.js';
import { httpsUrl, fetchFeed } from './feed.js';
import { row } from './row.js';

const $ = (id) => document.getElementById(id);
const MAX_ITEMS = 50;

// The old raw key is read once as the start value; the new value is kept by store().
function legacyLast() {
  try { return localStorage.getItem('rss.lastFeed') || ''; } catch (e) { return ''; }
}
const last = store('rss.lastFeed', legacyLast(), { version: 1 });

function status(text, error) {
  const box = $('status');
  box.dataset.tone = error ? 'error' : 'info';
  box.querySelector('deft-icon').setAttribute('name', error ? 'circle-alert' : 'info');
  box.querySelector('span').textContent = text;
}

async function load() {
  const url = httpsUrl($('url').value);
  if (!url) {
    status('Use an https:// feed address.', true);
    return;
  }
  status('Reading…');
  $('load').disabled = true;
  try {
    const feed = await fetchFeed(url);
    const list = $('items');
    list.replaceChildren(...feed.items.slice(0, MAX_ITEMS).map(row));
    deft.enter(list);
    $('title').textContent = feed.title || 'RSS Reader';
    status(feed.items.length ? feed.items.length + ' items' : 'The feed has no items.');
    last.value = url;
  } catch (e) {
    status((e && e.message) || 'Could not read the feed.', true);
  } finally {
    $('load').disabled = false;
  }
}

$('load').addEventListener('click', load);
$('url').addEventListener('keydown', (e) => { if (e.key === 'Enter') load(); });
if (last.value) {
  $('url').value = last.value;
  load();
}
