# Deft

Deft is an Android home screen that runs web apps as real apps. An app is a folder of HTML, CSS and JavaScript, and it can do what a native app does (text, call, read contacts, use Bluetooth and NFC, run in the background), as long as it says what it needs and you allow it.

It is one ordinary Android app: no root, no custom ROM. The launcher holds the Android permissions and gives each web app only the access you approved, one capability at a time. Each app runs in its own sandbox, and the dashboard shows what every app may do and logs what it did.

It is installed by hand (sideloaded), not from the Play Store: Play doesn't accept an app that is both the default SMS app and the default dialer.

## What's in it

- A home screen with your web apps, the regular Android app drawer, widgets and a customizable home page.
- Sample apps built in: Messages, Phone, Contacts, Calendar, Clock, Files, Gallery, a Bluetooth mesh chat, a podcast player, a run tracker and more. Copy one to your apps to change it.
- A consent screen, per-app grants you can revoke, confirmation before an app sends a text or places a call, and a log of every call an app made.
- An assistant on the phone that writes and edits web apps for you. It can't grant an app any access.
- A small runtime and design system that every app can use, and a linter that checks apps the same way on your computer and on the phone.

## A taste of the API

```js
import { ready, call, on } from '/_deft/v1/bridge.js';

await ready();
const people = await call('contacts.list', { limit: 50 });
const stop = on('sms.received', (message) => { /* ... */ });
```

The app must list `contacts.read` and `sms.read` in its manifest, and the user must allow them.

## Docs

- [Getting started](docs/GETTING_STARTED.md): build it, put it on a phone or emulator, try the samples.
- [How it works](docs/how-it-works.md): how apps are kept apart, how access is granted, and what Android doesn't allow.
- [Writing an app](docs/writing-apps.md): the manifest, capabilities, the bridge, the runtime, and getting an app onto the phone.
- [Bridge reference](android/app/src/main/assets/agent/skills/js-bridge.md): every method and event, generated from the launcher's catalog.
- [Design system](skill/deft-design/SKILL.md): building screens that fit in.
- [Security](SECURITY.md): reporting a problem.

## For contributors

`bun scripts/check.ts` runs every test and check; add `--device` to also run the tests on a connected phone (this can wipe the debug app's data). The landing page is in [web/](web/).
