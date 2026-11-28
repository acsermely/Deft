// Messages navigation rules, kept free of the DOM so tools/deft-runtime/sms-nav.test.js can run them.
// DEVICE-UNCHECKED (DESIGN, sms Back): built and unit-tested, but the developer
// has not checked it on a real device yet. Check: Back at the list leaves the app in one press; with the app
// open on the list, a new-message notification tap opens the thread and Back then returns to the list.
// Remove this note once checked.

/** Thread id in a `#t/<id>` hash, or '' when the hash is something else. */
export function threadFromHash(hash) {
  const m = /^#t\/(.+)$/.exec(String(hash || ''));
  if (!m) return '';
  try { return decodeURIComponent(m[1]); } catch (e) { return ''; }
}

/**
 * What Android Back does on this page: 'pop' (step back over the entry this view pushed), 'close' (close the
 * sheet in place), 'inbox' (leave the thread in place) or false (at the list: the host leaves the app).
 */
export function backAction({ sheetOpen, chatShown, view }) {
  if (sheetOpen) return view === 'compose' ? 'pop' : 'close';
  if (chatShown) return view === 'chat' ? 'pop' : 'inbox';
  return false;
}

/**
 * A URL the host loads into the open page (a notification tap: `index.html#t/<id>`) arrives as a fragment
 * navigation with no history state. Turn that entry into the thread in place and return its id, so the thread
 * opens and Back steps to the previous view instead of replaying an inbox entry. '' when there is nothing to do.
 */
export function adoptFragmentEntry(history, location) {
  if (history.state) return '';
  const threadId = threadFromHash(location.hash);
  if (!threadId) return '';
  history.replaceState({ view: 'chat', threadId }, '', location.href);
  return threadId;
}
