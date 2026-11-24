# Recipes: storage, shared state, long files

## localStorage to store() without orphaning data

`store('key', init)` keeps `{ v, data }` under `deft:key`. It never reads your old raw key, so a plain swap hides what users already saved. Read the old key once as the initial value; the first save then writes the new key and the old one is simply left behind:

```js
import { store } from '/_deft/v1/store.js';
function legacy() {
  try { return JSON.parse(localStorage.getItem('notes')) ?? []; } catch (e) { return []; }
}
const notes = store('notes', legacy(), { version: 1 });
```

FL403 does not fire on this read: a `localStorage.getItem(...)` inside a `try` is the legacy-read pattern. Writes (`setItem`, `removeItem`) and an unguarded `JSON.parse` still warn.

Keep reading the old key until you are sure no install still has it. Leave raw `localStorage` (and the FL403 warning) in place for data whose exact bytes matter, for example ciphertext or IVs the user cannot regenerate (a vault): say so in the report instead of migrating.

## Append-heavy data with store()

`store()` rewrites its whole value on every save (250 ms after a change). That is right for settings and lists of a few hundred small items. A value that only grows (a GPS track, a log, a history of thousands of rows) makes every save slower and can hit the storage quota. Choose one:

- Chunked keys: one store per chunk, a small index store for the chunk count. Only the open chunk is rewritten.

```js
const CHUNK = 200;
const meta = store('run.meta', { chunks: 1 }, { version: 1 });
const chunks = [];   // chunks[i] = store('run.' + i, [], { version: 1 })
const chunk = (i) => (chunks[i] ??= store('run.' + i, [], { version: 1 }));
export function add(point) {
  let box = chunk(meta.value.chunks - 1);
  if (box.value.length >= CHUNK) { meta.value = { chunks: meta.value.chunks + 1 }; box = chunk(meta.value.chunks - 1); }
  box.value = [...box.value, point];
}
export const all = () => Array.from({ length: meta.value.chunks }, (_, i) => chunk(i).value).flat();
```

- Or keep what the app has: IndexedDB (a store per row; see notification-history) has no FL403, and a raw `localStorage` track that is already capped (for example the last 5000 points) is a documented exception. Leave the FL403 warning, say in the report why, and do not rewrite it during a restyle.

## Modules that share state

Put the state in one module that exports the store (or a plain object); every module imports the same instance, because ES modules run once.

```js
// state.js: the single source of truth for the notes screens.
import { store } from '/_deft/v1/store.js';
export const notes = store('notes', [], { version: 1 });
```

```js
// list.js
import { effect } from '/_deft/v1/core.js';
import { notes } from './state.js';
effect(() => render(notes.value));   // re-runs when edit.js assigns notes.value = [...]
```

Assign a new array or object; never mutate in place. Pass elements into render functions instead of reaching for globals.

## FL502: splitting a long main.js

`main.js` stays under 80 lines: the import, the wiring of buttons and events, and calls into modules. Move rendering and pure helpers to a sibling module.

```js
// rows.js: renders result rows. Text is written with textContent only.
export function showRows(list, items, run) {
  list.replaceChildren();
  for (const item of items) {
    const row = document.createElement(item.onTap ? 'button' : 'div');
    row.className = 'deft-cell';
    // build the cell body here
    if (item.onTap) row.addEventListener('click', () => run(item.onTap));
    list.append(row);
  }
}
```

```js
// main.js: wiring only.
import '/_deft/v1/motion.js';
import { showRows } from './rows.js';
document.getElementById('apps').addEventListener('click', async () => {
  showRows(document.getElementById('rows'), await loadApps(), run);
});
```

Pass in what the module needs (the list element, a `run` wrapper) rather than reaching for globals, so it can be tested. Every new file needs its own header comment (FL503) and stays under 200 lines.
