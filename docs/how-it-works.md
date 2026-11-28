# How Deft works

Deft is one ordinary Android app (no root, no custom ROM) that you set as your home screen. It runs web apps (plain HTML, CSS and JavaScript) and lets them do what native apps do: read contacts, send texts, place calls, use Bluetooth and NFC, run in the background. The web apps never get Android permissions themselves. The launcher holds them and hands out access one capability at a time, after you agree.

```
Home screen (web apps, Android app drawer, settings, home canvas)
  -> one Android task per web app, each in its own WebView
       -> app files and the /_deft/v1/ runtime, served from inside the launcher
       -> the bridge, which only that app's page can talk to
  -> BridgeService: contacts, SMS, calls, files, calendar, alarms, notifications, NFC, Bluetooth, USB, ...
  -> Dashboard: what each app may do, and a log of what it did
  -> Agent: an AI assistant inside the launcher that writes and edits web apps
```

## Each app is kept apart

- **Its own address.** Every app is served from `https://<id>.app.deft.internal`. Nothing at that address exists on the internet: the launcher answers those requests itself (`LocalOriginPathHandler`). The id comes from the author's signing key and the app id, so it stays the same across updates. A copy you edit gets a new id and starts with its own data.
- **Its own storage.** Each app gets a separate WebView profile: its own cookies, local storage and cache (`ProfileStore`).
- **A strict content security policy.** Every response carries one (`CspBuilder`). By default an app can run only its own script files: no inline script, no `eval`, no frames, no network. It reaches a website only if its manifest lists it and you approved it.
- **It stays on its own page.** A link to another website asks you first; other link types are dropped, except phone, mail and SMS links, which open the matching app (`DeftNavigation`). File access, pop-up windows and downloads are off (`DeftWebViewConfig`).
- **The bridge answers one page only.** The connection between a page and the launcher is locked to that app's exact address, and replies go only to the frame that asked (`DeftJsBridge`).

## How an app gets access

An app lists what it needs in its `manifest.json`, with a short reason for each item, such as "Show your SMS inbox". When you install it, the consent screen shows that list and you tick what to allow. You can take any of it back later on the dashboard. If an app drops a capability from its manifest, it loses that access the next time it opens.

Every bridge call is checked in the same order (`BridgeService`): the page's address, then whether the method exists, then its arguments, then whether the manifest declares it, then whether you granted it. A call that fails any step is refused, and the refusal is logged too.

Some access is more sensitive:

- **Sends and calls.** Sending a text or placing a call goes straight through only right after you tapped something in that app. Otherwise a sheet shows which app wants to send what, and you confirm. You can also require your fingerprint for these.
- **Special access.** The most powerful things, such as reading your notifications or acting as an accessibility service, never appear on the consent screen. The app asks when it needs one, and you grant it on a separate launcher screen after unlocking the phone. Some can be held by only one app at a time. Special access you don't use gets a reminder after 30 days and is removed after 90.
- **Default SMS and phone app.** Android lets only one app be the default for texts and for calls. Deft takes that role, and passes it on to one web app of your choice.

The dashboard keeps a log of every call each app made. It stays on the phone.

### Running in the background

An app can register a background script, for example to react to an incoming text. It runs in a hidden WebView with limits: at most 3 at once, 60 runs an hour, 30 seconds each, and each call still needs its grant. Anything that would show a screen or ask you something waits until the app is open. An app that needs to keep running, such as a run tracker, shows a notification with a Stop button.

### Things that stay standard web APIs

Location, camera and microphone use the normal browser APIs (`navigator.geolocation`, `getUserMedia`), allowed by the same grant screen. There is no separate bridge method for them. Browser notifications are turned off; apps post notifications through the bridge instead, so they look and behave like Android ones.

## Installing and updating apps

Apps are shared as signed zip packages. A package must be signed by the same key as the installed version, and it can't be older than it. Paths that try to escape the app's folder are refused. The new version is unpacked next to the old one and only swapped in when everything checks out, so a failed update leaves the old version working. Every install is also checked by the same linter developers run, and errors stop it.

The sample apps are built into the launcher and can't be changed. To edit one, copy it to your apps first.

## The agent

The agent is part of the launcher, not a web app. It uses an OpenRouter-compatible API; the key is stored encrypted on the phone. It can write and edit apps and the launcher's settings, but you apply each change, and it can never grant an app any access.

## What Deft can't do

These are limits of stock Android, not missing features:

- **Separate Android identity per app.** All web apps live inside one Android app, so Android sees them as one. They are kept apart by address, storage, grants and the WebView profile instead.
- **Verified links for each app.** Android reads the list of links an app handles once, at install. Deft can't add a new website for every web app you install.
- **Two default SMS or phone apps.** Android allows one of each.
- **Phone identifiers.** Android doesn't give the IMEI, SIM serial or the real network hardware address to regular apps.
- **Other apps' private data.** Deft can't read, for example, the Gmail app's database.
- **Google-only services without Google.** Push through Google, the advertising ID and Play Integrity need Google Play services, which Deft doesn't use.

Some things were left out on purpose:

- **Wiping the phone or setting password rules.** Device admin access only lets an app lock the screen. One web app should never be able to erase your phone.
- **Access to all files.** An app gets the folders you pick, one at a time.
- **Opening every web link.** Deft doesn't claim all `https` links, which would make Android ask which app to use for every link. An app can instead ask to become your browser.
