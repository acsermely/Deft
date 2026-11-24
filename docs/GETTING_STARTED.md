# Getting started

This repo builds a **debug APK you sideload**. It is not on the Play Store. Run it on an **emulator** or a **physical phone** with USB debugging. Android Studio is the easiest way in; `./gradlew` is the same build without the GUI.

## 1. Prerequisites

- **Android Studio** with the Android SDK. Install from the SDK Manager:
  - **SDK Platforms:** Android 15 (API 35). The project compiles and targets SDK 35, min SDK 30.
  - **SDK Tools:** Build-Tools 35+, Platform-Tools (`adb`), Android Emulator, and an API 35 system image if you want an emulator ("Google APIs" images have fewer GMS surprises than "Google Play").
- **JDK 17 or 21** for Gradle. Studio's bundled JBR works. Newer JDKs (e.g. 26) can fail the Gradle 8.9 build.
- **Bun** for `scripts/check.ts`, `tools/deft-lint` and the other repo scripts.

`android/local.properties` must point at your SDK. Studio writes it on first open; it is gitignored.

```
sdk.dir=/path/to/Android/Sdk
```

Typical paths: Linux `~/Android/Sdk`, macOS `~/Library/Android/sdk`.

## 2. Open and build

1. Android Studio → **Open** → select the **`android`** folder, not the repo root.
2. Wait for Gradle sync. The first one downloads Gradle 8.9 and dependencies.
3. If sync complains about Java: **Settings → Build, Execution, Deployment → Build Tools → Gradle → Gradle JDK** → a 17 or 21 runtime.

From a terminal:

```bash
cd android && ./gradlew :app:assembleDebug
```

If the terminal build picks the wrong JDK, set `org.gradle.java.home=/path/to/jdk-21` in `~/.gradle/gradle.properties`.

## 3. Something to run on

**Emulator.** Tools → Device Manager → Create Device → a Pixel with an API 35 image. SMS, real calls, NFC and Bluetooth do not work on a typical emulator; the launcher UI, contacts, files, calendar and geolocation do.

**Phone** (needed for SMS, calls, NFC, Bluetooth):

1. Settings → About phone → tap **Build number** seven times.
2. Developer options → **USB debugging** on.
3. Plug in, unlock, accept the prompt, then check:

```bash
adb devices
```

You want `device`, not `unauthorized`. (`adb` is in `platform-tools/` under your SDK if it is not on your PATH.)

## 4. Run and set as Home

Click **Run** (▶) with the `app` configuration, or:

```bash
adb install -r android/app/build/outputs/apk/debug/app-debug.apk
```

The debug package is `app.deft.debug`.

Make it the Home app: press **Home** and choose Deft, or Settings → Apps → Default apps → Home app. The **Apps** view still lists Settings and other Android apps; to undo, pick the stock launcher in the same setting.

## 5. Open a sample

Samples ship inside the APK as bundled, read-only apps (`samples/` is copied to `assets/deft-apps/` at build). There is no store and no install by URL.

1. Open the **Apps** view; bundled samples are listed under **Web apps**.
2. Open one and read the consent list. Bridge items (contacts, SMS, files, calls…) are launcher powers, not normal browser APIs. Tap **Allow** and accept the Android dialogs:
   - **Messages** asks to become the default SMS app. **Phone** asks to become the default dialer. Only one app can hold each. Switch back in Settings → Default apps.
   - **Files**: pick a folder (`files.pickTree`), otherwise it stays in Downloads.
   - **Mesh**: Bluetooth (needs a real radio).
   - **Calendar** / **Clock**: calendar and reminders.
3. To edit a sample: Settings → Apps → **Copy to my apps**. The copy lives in private storage (`filesDir/deft/local`). Or ask the agent to create or change an app.

What to try:

| Sample | Check |
| --- | --- |
| Contacts | List, search, create, edit, delete; revoke the grant on the Dashboard and the list fails |
| Files | Pick a folder; browse, open with, share, rename, copy, delete; photo picker |
| Messages (SIM phone) | Threads, send SMS, MMS with a photo; incoming SMS notification opens the app |
| Phone (SIM phone) | Recents, keypad, incoming-call screen with answer/reject/hold/DTMF |
| Calendar | Month grid, create/edit/delete events |
| Clock | One-shot reminders that fire after the app is closed |
| Mesh (two phones) | BLE flood chat; `./scripts/mesh-cli chat` talks to it from a computer |
| Three TPS | Three.js touch game |

The **Dashboard** shows grants and a chronological log of bridge calls, WebView permission requests and inbound SMS.

## 6. Developing apps

- `bun scripts/deft-link.ts` pulls and pushes a debug app folder over `adb run-as`.
- `bun scripts/deft.ts` creates keys, packs and verifies signed packages.
- `bun scripts/check.ts` is the local gate: unit tests, deft-lint and runtime tests, lint bundle freshness. Add `--device` to run instrumented tests on a connected phone (this can wipe the debug app's data).
- Bridge reference: [`android/app/src/main/assets/agent/skills/js-bridge.md`](../android/app/src/main/assets/agent/skills/js-bridge.md).
- Design system: [`skill/deft-design/SKILL.md`](../skill/deft-design/SKILL.md) is the one skill for app and canvas UI (templates, components, motion, tokens, rules). On the device the agent reads it with `skill.read name=deft-design`; the APK copy under `android/app/src/main/assets/agent/skills/deft-design/` is generated by `bun tools/deft-design/build.js` (`--check` fails if it drifts). External harnesses that read Agent Skills folders can load `skill/deft-design` directly. Claude Code finds it through the relative symlink `.claude/skills/deft-design`. Any other harness can point at the same folder, or copy it.
- Lint for app UI: the rules live in `tools/deft-lint/src` (`bun test` in that folder), and the same bundle ships on the device. The design rules (accent placement, legacy tokens, raw motion, unlayered CSS, glyph icons, error colour, one primary) are in `tools/deft-lint/src/design.js`.

## 7. Debugging a crash

Open **Logcat** in Studio (View → Tool Windows → Logcat), filter to `app.deft.debug` and `level:ERROR`, reproduce, and look for `FATAL EXCEPTION`. The first `at app.deft…` line is usually the cause. From a terminal: `adb logcat -s AndroidRuntime`.

## 8. Common failures

| Symptom | Fix |
| --- | --- |
| Gradle: SDK 35 missing | SDK Manager → Android 15 (API 35) |
| Gradle: unsupported Java | Gradle JDK 17 or 21 |
| `adb devices` empty | Data-capable cable, unlock, accept the prompt |
| `unauthorized` | Revoke USB debugging authorizations on the phone, replug |
| No SMS / calls | Use a real phone with a SIM |
| Home button opens the old launcher | Set the default Home app (step 4) |

## 9. Uninstall

If Deft is the default SMS app or dialer, switch those back first, then:

```bash
adb uninstall app.deft.debug
```
