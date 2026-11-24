# JS bridge: calendar, alarms, notifications, tiles, widgets, shortcuts

<!-- generated:methods -->
- `calendar.read` (high): Read calendar. `calendar.listCalendars`, `calendar.listEvents`
- `calendar.write` (high): Change calendar. `calendar.create`, `calendar.update`, `calendar.delete`
- `alarms` (medium): Schedule reminders. `alarms.schedule`, `alarms.cancel`, `alarms.list`; events `alarms.fired`
- `notifications.post` (medium): Post notifications with channels, actions and replies. `notify.post`, `notify.update`, `notify.cancel`, `notify.list`, `notify.createChannel`, `notify.deleteChannel`, `notify.listChannels`; events `notify.action`
- `tiles` (low): Quick Settings tile. `tiles.request`, `tiles.update`, `tiles.release`; events `tile.clicked`
- `widgets` (low): Home screen widget cards. `widgets.update`, `widgets.remove`, `widgets.list`
<!-- /generated -->

## Arguments and behaviour

Use `call(method, args)` from `/_deft/v1/bridge.js`. Basics, errors and events: `skill.read name=js-bridge/basics`.

- calendar: `listCalendars`, `listEvents` `{start, end, calendarId?, limit?}`, `create` `{calendarId, title, dtStart, dtEnd, description?}`, `update` `{eventId, title?, dtStart?, dtEnd?, description?}`, `delete` `{eventId}`
- alarms: `schedule` `{triggerAt` epoch ms, `title, body, url?}` (url same origin), `list`, `cancel` `{id}` — fires if the WebView is closed
- notify (`notifications.post`): `post` `{title, body, id?, channel?, url?, actions?: [{id, title, open?}], reply?: {actionId?, label?, title?}, progress?: {value, max, indeterminate?}, ongoing?, silent?, group?, image? (base64)}` → `{id}`; `update` (same, `id` required), `cancel` `{id}`, `list` → ids. Channels show under the app's name in Android settings: `createChannel` `{id, name, importance: min|low|default|high, description?}`, `deleteChannel` `{id}`, `listChannels`. Taps, buttons, inline replies and dismissals arrive as `notify.action` `{id, action: tap|dismiss|<action id>, reply?}` (queued if the app is closed; `open: true` buttons open the app first).
- tiles (`tiles`): `request` `{label}` asks Android to add a Quick Settings tile (→ `{slot, added}`); `update` `{label?, subtitle?, active?, open?}`; `release`. Taps arrive as `tile.clicked` `{active}`; if the app is closed (or `open` is set) the tap opens it.
- widgets (`widgets`): `update` `{id?, title, text?, value?, url?}` shows a card on the Home screen (max 3 per app; tap opens the app at `url`); `remove` `{id?}`; `list`.
- shortcuts: manifest `"shortcuts": [{id, name, url}]` (max 8, `url` a same-origin path like `./#new`). Long-press the app on Home to use them.
