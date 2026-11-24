# Runtime JS API

Signatures for `/_deft/v1/*.js`, checked against the source. Import from the module that owns the name, or from `/_deft/v1/index.js` (canvas pages; it has everything except `bridge.js`). After `import '/_deft/v1/motion.js'` the globals `deft.toast`, `deft.dialog`, `deft.confirm`, `deft.prompt`, `deft.haptic` and `deft.enter` exist.

## dom.js: `html`, `render`, `repeat`, `ref`, `classes`

```js
import { html, render, repeat, ref, classes } from '/_deft/v1/dom.js';
const box = ref();
const row = (c) => html`<button type="button" class=${classes({ 'deft-cell': 1, sel: c.on })} @click=${() => open(c.id)}>${c.name}</button>`;
const view = () => html`<div class="deft-group" ref=${box}>${repeat(items, (c) => c.id, row)}</div>`;
render(document.getElementById('list'), view);   // a function view re-renders when signals it reads change
```

- `` html`...` `` returns a template. Values may be strings, numbers, templates, arrays of these, DOM nodes, or `null`/`false` (nothing). Text is always escaped; there is no raw HTML.
- Attribute bindings, one per attribute, the placeholder must be the whole value (`class="a ${b}"` does not work; use `classes()`): `name=${v}` sets the attribute (`null`/`false` removes it), `.prop=${v}` sets a property, `?attr=${bool}` toggles a boolean attribute, `@event=${fn}` adds a listener, `ref=${r}` stores the element in `r.current`, `style=${'a:b'}` or `style=${{ marginTop: '4px' }}`. `href`/`src`/`action` values starting with `javascript:` (or `data:`, except image `src`) become `#`.
- `repeat(items, keyFn, tpl)`: `tpl(item, index)` returns a template. `keyFn(item)` is accepted for readability and ignored: there is no keyed diffing. `items` may be null.
- `render(el, view)`: `view` is a template (painted once, returns a no-op) or a function returning a template (re-run by an effect; returns `dispose`). Each paint replaces all children of `el`, so listeners added by hand to inner nodes are lost: use `@event` bindings, or delegate from `el` with `on()`. Focus (by element `id`, with caret), `el.scrollTop` and the scroll of `.deft-scroll` children with an `id` are restored.
- `classes({ a: true, b: false })` returns `'a'`. `ref()` returns `{ current, set }`.

## app.js: `dialog`, `confirm`, `prompt`, `toast`, `back`, `interval`, `frame`, `share`

```js
import { confirm, prompt, dialog, toast, back } from '/_deft/v1/app.js';
if (await confirm('Delete Ada?', { destructive: true, ok: 'Delete' })) remove();   // Promise<boolean>
const name = await prompt('New name', { value: file.name, ok: 'Rename' });          // Promise<string | null>
const pick = await dialog({ title: 'Sort by', body: 'Choose an order.',
  actions: [{ label: 'Name', value: 'name', primary: true }, { label: 'Date', value: 'date' }] });   // the clicked value, or false
toast('Saved', { tone: 'success', ms: 3200, action: { label: 'Undo', run: undo } });   // tone: 'success' | 'error'; one at a time
const off = back(() => (sheetOpen ? (closeSheet(), true) : false));   // return true to consume Back; off() restores
```

- `confirm(message, { title, ok, destructive })`: Cancel and an OK button. `destructive: true` gives OK the error tone and no accent fill. Use it before deleting data. Esc or Cancel resolves `false`.
- `prompt(label, { value, ok })`: a dialog with one text input and a form submit. Cancel or Esc resolves `null`; an empty string is returned as `''`.
- `dialog({ title, body, actions })`: modal with text and buttons only (no input). Each action is `{ label, value, primary, tone }`; `tone: 'destructive'` tints the label. Resolves the clicked `value`, or `false` on Esc. Dialogs remove themselves from the page after closing.
- `back(fn)`: the replacement for `window.__deftOnBack = fn`. Android Back (hardware, gesture, toolbar) calls the newest handler first; it must return exactly `true` to consume Back. Any other value falls through to the previous handler, and finally leaves the app. `back()` returns `off()`, which restores the previous one. `routes()` registers one itself.
- `interval(fn, ms)` and `frame(fn)` skip ticks while the page is hidden and return a stop function.
- `share({ url, title, text })` uses the Web Share API, else copies `url`.

## route.js: `routes`, `go`, `current`, `link`, `morph`

```js
import { routes, go, link } from '/_deft/v1/route.js';
routes({
  '/': () => html`<a href=${link('/note/7')}>Open</a>`,
  '/note/:id': ({ id }) => html`<button @click=${() => go('/')}>Back</button> ${id}`,
}, document.getElementById('app'));
```

- Hash routes. `:name` captures a path segment (decoded); a `?query` part is ignored when matching. A view receives the captures and returns a template. Changing routes re-renders the whole root inside a view transition (`morph`); while a route is shown, its view also re-renders (no transition) when a signal it reads changes, like a function view passed to `render`. So write the view from signals (`${unit.value}`, `${measuring.value ? stop : start}`) instead of patching the DOM by id. A view must not write a signal it reads.
- `go('/x')` sets `location.hash`. `link('/x')` returns `'#/x'`. `current` is a signal holding the hash path. `routes()` returns a dispose function and makes Back return to `/` before leaving the app.
- Every route is its own screen for FL630, but lint cannot see that (see `references/lint.md`).

## Others

- `signal(v)`, `computed(fn)`, `effect(fn)`, `batch(fn)`, `untracked(fn)` (core.js): read and write `.value`; effects re-run when a signal they read changes.
- `on(target, type, selector?, fn)` (events.js): delegated listener, `fn(event, matchedElement)`; returns an off function. `scope()` collects `on`, `interval` and `effect` and disposes them together.
- `store(key, init, { version, migrate })` (store.js): a signal saved to localStorage 250 ms after a change.
- `ready()`, `call(method, args)`, `on(event, fn)`, `has(prefix)`, `inLauncher()` (bridge.js): see `skill.read name=js-bridge`. `call` rejects with `code: 'bridge_not_ready'` outside the launcher. `inLauncher()` is true when the host bridge exists (use it for a page with no capabilities, or to pick a demo path); `has('sms')` is true when a granted method equals or starts with that prefix. Both replace `if (!window.deftBridge)` without FL200.
- `deft.enter(el, kind?)`: replay the enter stagger on `el`'s children, for lists filled after load.
