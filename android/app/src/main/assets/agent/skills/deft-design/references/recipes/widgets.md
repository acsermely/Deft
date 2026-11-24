# Recipe: a home widget with live data

Use when the user wants a value on the home screen that changes: a feed, a count, a reading. The data comes from a my-app's widget page; the position comes from the canvas. Start from `templates/widget.html` and `templates/widget-main.js` (copy the JS as `main.js`).

## 1. Where does the data come from?

- **Phone data** (steps, battery, memory, storage, temperature status): **no network**. Declare the capability (`sensors` for steps, `system.stats` for device figures) and read it with `call()`. Skip steps 2 and 4. Read `skill.read name=js-bridge/device` for the exact fields.
- **Web data** (a public feed, a price, a weather value): continue at step 2.
- A widget can show both. Each value follows its own branch.

## 2. Find the source (web data only)

`web.fetch` a public, machine-readable endpoint (JSON preferred; RSS, Atom and XML work, see step 7). Prefer one without a key. If it needs a key, ask the user first and never write a key into a file.

`web.fetch` is off until the user allows web reading. Then the call becomes an Apply card ("Turn on Let the agent read the web, then GET <url>") and your turn waits:
- The user taps Apply: the call runs and the setting turns on. Carry on.
- The result is `rejected by user`: do not retry. Say the feed cannot be read this way, and ask for another source or a pasted sample.

## 3. Declare the widget and its origin

`manifest.json`:

```json
{ "deft": 1, "id": "<slug>", "name": "<Name>", "version": "0.1.0", "profile": "myapp", "start_url": "./index.html",
  "capabilities": [], "network": ["https://<feed-host>"],
  "widget": { "page": "./widget.html", "sizes": ["2x1", "2x2"] } }
```

- **`network`** lists the feed's origin (`https://host`, no path). `net.fetch` needs **no capability**: the listed origin is the whole permission. The user allows it on the consent sheet; until then `net.fetch` gives `not_granted`.
- **Do not declare `network.user`** for a feed. It only adds servers the user types. `network.open` (any https origin, high risk) is not needed for a feed.
- **`capabilities`** only for phone data (`sensors`, `system.stats`), each with a `reason` of 10 to 140 characters. The consent screen asks for them.
- **`widget`** `{page, sizes}`. The page gets `?size=`.

Size words, in columns x rows:

| Word | Size |
| --- | --- |
| small | `1x1` |
| medium | `2x1` |
| large | `2x2` |
| wide | `4x1` |
| wide and tall | `4x2` |

If the user's word is not in this table, pick the smallest size that fits the value and say which one you chose. Declare the sizes you want; the template handles all five.

## 4. Fetch on view (web data)

In `main.js`: `call('net.fetch', {url, as: 'json'})` when the page loads and when `visibilitychange` shows it, only if the cache is older than the interval (15 min is a fair default). `net.fetch` resolves for any HTTP status, with `{status, headers, body, url}`. Check `res.status === 200` yourself.

## 5. Phone data (no network)

```js
import { ready, call } from '/_deft/v1/bridge.js';
await ready();
const steps = await call('sensors.stepsToday');   // {today, days: [{date, steps}]}
const device = await call('system.stats');        // battery.level, battery.tempC, thermal.status, memory, storage
```

- **Steps:** the history starts at the first call, so earlier days show 0. The widget must say so (for example "History starts today"), and the reply to the user must too.
- **Permission:** a widget cannot show the OS prompt. If a call rejects with `os_permission_missing`, show "Open the app to allow activity access" and make the tap open the app (step 10). The app's own page asks.
- **Live sensor readings** (light, a sensor stream) are not possible in a widget: `sensors.subscribe` needs the app on screen.

## 6. Live gauges (system.stats)

A gauge that moves refreshes on a timer and pauses while hidden:

```js
let timer;
async function tick() { await ready(); paint(await call('system.stats')); }
function start() { clearInterval(timer); timer = setInterval(tick, 5000); tick(); }
document.addEventListener('visibilitychange', () => (document.hidden ? clearInterval(timer) : start()));
start();
```

- The interval is **5000 ms or more** (a shorter one is lint FL635). `system.stats` allows one call a second per app.
- Never a `requestAnimationFrame` loop (FL634 is an error).
- A feed or step total does not need a timer: refresh on view (step 4) and every 15 minutes or more.

## 7. XML, Atom, RSS

Call `net.fetch` with `as: 'text'` (the default, so `as` can be left out). Then parse the body in the widget with `DOMParser`. `templates/widget-main.js` exports `readXml(text)`, which throws on a parser error.

```js
const res = await call('net.fetch', { url: FEED });
if (res.status !== 200) throw new Error('HTTP ' + res.status);
const doc = readXml(res.body);
const title = doc.querySelector('item > title, entry > title')?.textContent?.trim();
if (!title) throw new Error('unexpected feed shape');   // an error state, not a blank value
```

RSS puts items under `item`; Atom under `entry`. `web.fetch` returns the XML as text (only HTML is stripped), so read the feed there first and write the selector from what you see.

## 8. Cache with `store()`

`store('feed', init)` from `/_deft/v1/store.js`. Raw `localStorage` is FL403. Paint the cached value first, then refresh.

## 9. Design every state

- **States:** empty (never loaded), ok, stale (old value shown), error (refresh failed; cached value still shown; a Retry button). The template's footnote carries the state text.
- **Sizes:** tag an element with `data-show="2x1 2x2"`, and the template hides it at other sizes. An untagged element always shows. At `1x1` only the value and the buttons fit.
- Check every declared size with `apps.verify` (step 12).

## 10. Tap opens the app

`self.deft?.open?.()` needs a real tap. Catch the rejection outside the launcher.

## 11. Pages and the start page

- The widget page is `widget.html` with `main.js`. Lint (FL602) wants `<script type="module" src="./main.js">` in **every** HTML page, so the app's `index.html` loads the same `main.js`. The template starts the widget code only where `#value` exists.
- A widget-only app still has `index.html`. Tapping the widget opens it at `start_url` (default `./index.html`). The manifest key `start_url` is required by lint (FL701). `apps.create` writes `index.html`; make it a real screen (the same value, plus a link back), not a blank page.

## 12. Create, verify, place

1. **Create with purpose.** From Home: `apps.create slug=<slug> purpose="widget"` (no `prompt`). `purpose` is how the launcher knows this is a widget app; always pass it.
2. **Write the files** with `files.write` under `local/<slug>/`: `manifest.json`, `index.html`, `widget.html`, `main.js` (and `app.css` if you use it).
3. **Verify.** `apps.verify` renders each size, checks clipping, and runs `net.fetch` through the declared origins (the user is asked first if the web setting is off). Fix every error and warning.
4. **Place, after Apply.** `canvas.read path=index.html` first. Then `canvas.edit path=index.html old_string="</main>" new_string="  <div class=\"deft-widgets\"><deft-widget app=\"myapp:<slug>\" size=\"2x1\"></deft-widget></div>\n  </main>"`. Always inside a `<div class=\"deft-widgets\">` (the 4-column grid; one wrapper can hold several widgets, and `canvas.verify` warns without it). The bundled page has one `</main>`; if the page changed, use another unique line. Also make sure the page's `<head>` has `<script type="module" src="/_deft/v1/elements.js"></script>` (the launcher adds it when missing; this is belt and braces); without it `deft-widget` never starts. Write the closing tag: the element is a custom element, and a missing one puts the rest of the page inside it.
5. **Check the canvas.** `canvas.verify` warns about an unknown app, an app without a widget, or a size the widget did not declare. At most 6 widget frames per canvas; more show a placeholder. A widget whose app still needs the user's permission (a capability, a network origin) shows "Tap to set up" and `canvas.verify` warns "needs setup": tell the user to tap the widget once and allow it. Never grant for them.

**Cost.** Refresh on view is the default and costs about one radio wake a day per widget. A periodic background run (`background` with `periodic`) only when the user wants alerts or always-fresh data: 60 minutes or more. Each run costs 40 to 50 MB on a low-end phone. Alerts use `notifications.post` from that run.

**Limits.** `net.fetch`: https only, 2 MB a response, 15 s, 60 calls a minute per app. Keep the widget page under 32 KB and one `main.js` under 80 lines (FL502).
