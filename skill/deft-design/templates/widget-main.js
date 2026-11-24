// Copy as main.js beside widget.html (widget template). One live value from a public JSON feed: paints the cached value first, refreshes on view, designs every state. The app's index.html loads this file too (lint FL602), so the widget runs only where #value exists.
import '/_deft/v1/motion.js';
import { ready, call, inLauncher } from '/_deft/v1/bridge.js';
import { store } from '/_deft/v1/store.js';

// Placeholders: the feed URL (https; its origin must be in manifest network) and the label. Key-free feed only.
const FEED = 'https://feed.example.com/v1/value.json';
const LABEL = 'Example value';
const MAX_AGE_MS = 15 * 60 * 1000;
const $ = (id) => document.getElementById(id);

// XML, Atom or RSS: call net.fetch with as 'text' (the default), then parse here. A parser error is a refresh error.
export function readXml(text) {
  const doc = new DOMParser().parseFromString(text, 'application/xml');
  if (doc.querySelector('parsererror')) throw new Error('feed is not XML');
  return doc;
}

if ($('value')) startWidget();

function startWidget() {
  const size = new URLSearchParams(location.search).get('size') || '2x1';
  const cache = store('feed', { at: 0, data: null });

  // Only the elements tagged for this size show (data-show lists sizes; no tag means always).
  for (const el of document.querySelectorAll('[data-show]')) el.hidden = !el.dataset.show.split(' ').includes(size);

  // States: empty (never loaded), ok, stale (old cached value), error (refresh failed; cached value still shown).
  function paint(data, at, state) {
    $('w-label').textContent = LABEL;
    $('value').textContent = data ? String(data.value) : '--';
    const note = { stale: 'Showing older data', error: 'Could not refresh', empty: 'No data yet', ok: '' }[state];
    const age = at ? `Updated ${Math.round((Date.now() - at) / 60000)} min ago` : 'Not loaded yet';
    $('updated').textContent = [note, age].filter(Boolean).join(' · ');
    $('retry').hidden = state !== 'error' && state !== 'empty';
  }

  // Validates the feed shape before anything is saved; a bad shape is an error, not a blank value.
  function parse(body) {
    if (!body || typeof body.value !== 'number') throw new Error('unexpected feed shape');
    return { value: body.value };
  }

  async function refresh(force = false) {
    const { at, data } = cache.value;
    if (!force && Date.now() - at < MAX_AGE_MS) return;
    if (!inLauncher()) return paint(data, at, data ? 'stale' : 'empty');
    try {
      await ready();
      const res = await call('net.fetch', { url: FEED, as: 'json' });
      if (res.status !== 200) throw new Error(`HTTP ${res.status}`);
      const next = parse(res.body);
      cache.value = { at: Date.now(), data: next };
      paint(next, cache.value.at, 'ok');
    } catch (e) {
      paint(data, at, data ? 'error' : 'empty');
    }
  }

  paint(cache.value.data, cache.value.at, cache.value.data ? 'stale' : 'empty');
  refresh();
  document.addEventListener('visibilitychange', () => { if (!document.hidden) refresh(); });
  $('retry').addEventListener('click', () => refresh(true));
  $('open').addEventListener('click', () => { Promise.resolve(self.deft?.open?.()).catch(() => {}); });
}
