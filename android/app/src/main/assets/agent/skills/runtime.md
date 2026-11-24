# Runtime `/_deft/v1/`

Canvas code imports `/_deft/v1/index.js`. Import `bridge.js` only from a my-app. Do not assign `innerHTML`.

## core.js
```js
import { signal, computed, effect } from '/_deft/v1/core.js';
const n = signal(1);
const doubled = computed(() => n.value * 2);
effect(() => { document.title = String(doubled.value); });
```

## dom.js
```js
import { html, render } from '/_deft/v1/dom.js';
render(document.querySelector('#app'), () => html`<input id="q" .value=${n.value} @input=${e => { n.value = e.target.value; }}>`);
```

## events.js
```js
import { scope } from '/_deft/v1/events.js';
const screen = scope();
screen.on(document.body, 'click', 'button.save', () => {});
screen.dispose();
```

## store.js
```js
import { store } from '/_deft/v1/store.js';
const notes = store('notes', [], { version: 1 });
```

## app.js
```js
import { back, toast } from '/_deft/v1/app.js';
back(() => false);
toast('Saved');
```

## route.js
```js
import { html } from '/_deft/v1/dom.js';
import { go, routes } from '/_deft/v1/route.js';
routes({ '/': () => html`<button @click=${() => go('/edit')}>Edit</button>` }, document.querySelector('#app'));
```

## bridge.js
```js
import { call, has, ready } from '/_deft/v1/bridge.js';
if (has('contacts.read')) { await ready(); await call('contacts.list', { limit: 20 }); }
```

## index.js
```js
import { html, render, signal, store } from '/_deft/v1/index.js';
```


## CDN libraries
My-apps may import libraries from CDN URLs declared in `manifest.dependencies`. Read `skill.read name=cdn` first: there are rules about which URLs work.
