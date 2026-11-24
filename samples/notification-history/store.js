// History store shared by the page and the background module: one IndexedDB object store,
// one row per posted notification (key + time), newest kept, at most MAX rows.
const DB = 'notification-history';
const STORE = 'posts';
const MAX = 500;

function open() {
  return new Promise((resolve, reject) => {
    const req = indexedDB.open(DB, 1);
    req.onupgradeneeded = () => {
      const s = req.result.createObjectStore(STORE, { keyPath: 'id' });
      s.createIndex('time', 'time');
    };
    req.onsuccess = () => resolve(req.result);
    req.onerror = () => reject(req.error);
  });
}

function done(tx) {
  return new Promise((resolve, reject) => {
    tx.oncomplete = () => resolve();
    tx.onerror = () => reject(tx.error);
    tx.onabort = () => reject(tx.error);
  });
}

/** Saves one `notifications.posted` payload; the same key and time is stored once. */
export async function save(p) {
  if (!p || typeof p.key !== 'string') return;
  const db = await open();
  const tx = db.transaction(STORE, 'readwrite');
  const s = tx.objectStore(STORE);
  s.put({
    id: p.key + '@' + p.time,
    key: p.key,
    time: Number(p.time) || Date.now(),
    appLabel: String(p.appLabel || p.package || ''),
    title: p.title === null || p.title === undefined ? null : String(p.title),
    text: p.text === null || p.text === undefined ? null : String(p.text),
    redacted: !!p.redacted,
  });
  const count = await new Promise((resolve) => {
    const r = s.count();
    r.onsuccess = () => resolve(r.result);
    r.onerror = () => resolve(0);
  });
  if (count > MAX) {
    let extra = count - MAX;
    const cur = s.index('time').openCursor();
    cur.onsuccess = () => {
      const c = cur.result;
      if (!c || extra <= 0) return;
      c.delete();
      extra -= 1;
      c.continue();
    };
  }
  await done(tx);
  db.close();
}

/** All saved rows, newest first. */
export async function list() {
  const db = await open();
  const tx = db.transaction(STORE, 'readonly');
  const rows = await new Promise((resolve, reject) => {
    const r = tx.objectStore(STORE).getAll();
    r.onsuccess = () => resolve(r.result || []);
    r.onerror = () => reject(r.error);
  });
  db.close();
  return rows.sort((a, b) => b.time - a.time);
}

export async function clear() {
  const db = await open();
  const tx = db.transaction(STORE, 'readwrite');
  tx.objectStore(STORE).clear();
  await done(tx);
  db.close();
}
