# Writing an app

A Deft app is a folder of plain web files: an `index.html`, your scripts and styles, and a `manifest.json` that says what the app is and what it needs. There is no build step and no package manager. Look at `samples/` for working examples; `samples/clock` is a small one.

## The manifest

A small one, from `samples/clock`:

```json
{
  "deft": 1,
  "id": "clock",
  "name": "Clock",
  "version": "1.0.0",
  "start_url": "./index.html",
  "capabilities": [
    { "id": "alarms", "reason": "Schedule reminders that fire when the app is closed" },
    { "id": "notifications.post", "reason": "Show a notification when a reminder fires" }
  ]
}
```

| Field | What it is |
| --- | --- |
| `deft` | Always `1`. |
| `id` | A short name: lowercase letters, digits and `-`, 2 to 40 characters. |
| `name` | What the home screen shows, up to 30 characters. |
| `version` | `1.2.3` style. Updates must not go backwards. |
| `start_url` | The page that opens first, usually `./index.html`. |
| `capabilities` | What the app wants to do, each with an `id` and a `reason` (10 to 140 characters) shown to the user. |
| `network` | Websites the app may fetch from, as `https://` origins. |
| `profile` | `canvas` for a home-screen page instead of an app. Canvas pages get no capabilities and no network. |

Optional extras, each needing its own capability where it gives the app more power:

| Field | What it adds |
| --- | --- |
| `background` | A script that runs when something happens while the app is closed, such as an incoming text (`samples/sms`). |
| `shortcuts` | Up to 8 entries in the icon's long-press menu. |
| `handles` | File types and link types the app can open from other apps (`samples/gallery`). |
| `widget`, `wallpaper`, `keyboard` | A home-screen widget, a live wallpaper or a keyboard, each its own HTML page. |
| `dependencies` | Libraries from a CDN, each pinned to a hash so a changed file is refused (`samples/three-tps`). |
| `embeds` | Third-party sites the app may frame, such as a payment form (`samples/stripe-checkout`). |
| `passkeys` | Websites the app may use passkeys for (`samples/passkey-check`). |
| `assetPacks` | Large downloads, such as maps or models, checked against a hash. |
| `tools` | Functions the on-device agent may call in your app. |
| `license`, `source` | Shown to the user. |

## Capabilities

A capability is one kind of access, such as reading contacts or sending texts. The user sees your reason for each one when they install the app, and can turn it off later. Ask only for what the app uses. The full list, with every method and its arguments, is the bridge reference: [js-bridge.md](../android/app/src/main/assets/agent/skills/js-bridge.md). It is generated from the launcher's own catalog, so it matches the build.

Roughly, there are capabilities for people (contacts, texts, calls, call history), files and media, calendar, alarms and notifications, opening other apps, device features (sensors, torch, haptics, clipboard, keys stored in the phone's secure hardware, speech), radios (NFC, Bluetooth, USB, local network, Wi-Fi), the system (installed apps, accounts, sign-in, passkeys, push), and running in the background.

Location, camera and microphone are capabilities too, but your code uses the normal web APIs for them: `navigator.geolocation` and `navigator.mediaDevices.getUserMedia`.

## Calling the bridge

```js
import { ready, call, on } from '/_deft/v1/bridge.js';

await ready();
const people = await call('contacts.list', { limit: 50 });
const stop = on('sms.received', (message) => { /* ... */ });
```

Every call returns a promise. When it fails, check `error.code`, not the message:

| Code | Meaning |
| --- | --- |
| `not_granted` | The manifest doesn't declare it, or the user didn't allow it. |
| `os_permission_missing`, `role_missing` | Android hasn't given the launcher the permission or role yet. |
| `invalid_args` | The arguments are wrong. The message says which. |
| `cancelled` | The user closed the sheet or said no. |
| `unsupported` | This phone can't do it. |
| `in_use`, `rate_limited` | Try again later. |
| `needs_foreground` | Only works while the app is on screen. |
| `bridge_not_ready` | The page isn't running inside Deft, for example in a desktop browser. |

Sending a text or placing a call right after the user taps something goes straight through. Otherwise the launcher asks the user to confirm.

## The runtime

The launcher serves a small library at `/_deft/v1/` to every app. Use it instead of writing your own:

- `bridge.js`: the bridge.
- `core.js`, `dom.js`, `route.js`, `store.js`, `events.js`: reactive values, HTML templates, routing, saved state and event handling.
- `app.js`: dialogs, toasts, the back button, sharing.
- `motion.js`, `sheet.js`, `elements.js`: animations, bottom sheets, and ready-made elements such as icons and big numbers.
- `base.css`, `tokens.css`, `components.css`, `theme.css`: the design system. `theme.css` follows the user's chosen colour and light or dark mode.

Link the stylesheets in this order, before your own:

```html
<link rel="stylesheet" href="/_deft/v1/base.css" />
<link rel="stylesheet" href="/_deft/v1/tokens.css" />
<link rel="stylesheet" href="/_deft/v1/components.css" />
<link rel="stylesheet" href="/_deft/v1/theme.css" />
<link rel="stylesheet" href="./app.css" />
```

How to build screens with it (templates, components, colour, motion) is in [skill/deft-design/SKILL.md](../skill/deft-design/SKILL.md).

## Checking your app

The linter runs the same checks as the phone does when it installs an app:

```bash
bun tools/deft-lint/cli.js samples/clock
```

It prints `file:line:column severity rule message` and exits with an error if anything must be fixed. Every rule is explained in [lint.md](../skill/deft-design/references/lint.md).

## Getting it onto the phone

- **Ask the agent.** It writes the app on the phone and checks it as it goes.
- **Edit on your computer.** `bun scripts/deft-link.ts pull <id> <folder>` copies an app from the phone (debug build only), and `push <id> <folder>` copies it back.
- **Share it as a package.** `bun scripts/deft.ts keygen` makes a signing key, `pack <folder> <out.deftpkg> <key>` builds a signed package, and `verify <package>` checks one. Install it from the launcher. Keep the key: updates must be signed by it.
- **Add it to the launcher itself.** Folders in `samples/` are built into the APK as read-only sample apps.
