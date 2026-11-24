# Unimplemented — impossible, parked, or rejected designs

Planned work lives in [PARITY.md](PARITY.md), the single forward plan. This file keeps only what is **not** planned, so those decisions are not lost:

- **Cannot** — Android, the web platform or the missing GMS makes it impossible for a sideloaded app. Do not retry.
- **Parked** — valid, not a capability, no owner yet.
- **Rejected designs** — approaches that were considered and dropped in favour of a planned one.

Decision D7 (2026-11-17, [spec/DECISIONS.md](spec/DECISIONS.md)) lifted the former "Never (tracking)" list. Those powers are planned as opt-in capabilities in [PARITY.md](PARITY.md) section 2.2, most in the `special` tier. The former "Host gaps" and "Discussed" tables moved there too (Phases A, D, E, F).

If you ship or plan something listed here, delete the row in the same PR.

## Cannot

| Item | Reason |
| --- | --- |
| Verified App Links for every user-installed origin (manifest `handles.links`) | Intent filters are static; Digital Asset Links are per domain. `handles.links` is accepted but inert: https links open in the browser, or reach an app only through the system "Open with" chooser when another app offers one ([PARITY.md](PARITY.md) E1) |
| Custom link schemes per app (`handles.schemes` such as `web+notes`) | Intent filters are static. The launcher has one fixed `web+deft` filter (plus the pre-rename `web+fl`) and `SENDTO mailto`; other declared `web+` schemes are accepted but never delivered (E1) |
| Dynamic runtime registration of new `https` hosts as verified links | The manifest cannot grow per install |
| Two web origins both default SMS, or both default dialer | One OS role per app; the launcher holds it and routes to one granted origin |
| RCS / Jibe / iMessage as a public SDK | No stable third-party API |
| Read Gmail / Outlook / Proton **app** databases | Private stores of other apps |
| Chrome-style Web Push (`PushManager`) in WebView | Chrome uses FCM; WebView does not inherit it. Push comes from UnifiedPush or the optional `gms` flavor ([PARITY.md](PARITY.md) D6, P19) |
| Separate Android UID or signature per web origin | One APK, one uid; isolation is origin + grants + WebView profile |
| eSIM `EuiccManager`, baseband, OEM privileged APIs | Not available to a sideloaded, non-system app |
| IMEI, IMSI, ICCID, device serial, real hardware MAC | Android 10+ withholds them from non-privileged apps |
| Advertising ID, App Set ID, Play Integrity / SafetyNet | Need GMS; not in the default build (a future `gms` flavor could revisit) |
| WebAuthn / passkeys inside an FL app's WebView (`navigator.credentials` for any site, or for the app's own `<id>.app.deft.internal` origin) | WebView app mode reports one origin for the whole launcher (`android:apk-key-hash:` of its cert) and needs the RP domain's `assetlinks.json` to name the launcher, which other sites never do; `.deft.internal` names have no public DNS for asset links and no owner. Browser mode needs every passkey provider to trust the launcher as a privileged browser. Real sites use E3 `auth.start` (passkeys in the user's browser); an app's own backend can opt in to the Step 37b bridge ([PARITY.md](PARITY.md) E4) |

## Parked

| Item | Why parked |
| --- | --- |
| Device verification of this tree on a real phone | Engineering QA, not a feature |
| App icon and metadata refresh on package update | Belongs to the M4/M5 package and source work ([spec/ROADMAP.md](spec/ROADMAP.md)), not a capability |

## Rejected designs

| Design | Replaced by |
| --- | --- |
| IMAP wakeup mailbox (`MailModule`, `FLWAKE` poll, a Freedom server that mints mailboxes) | Removed. Google-free wake is UnifiedPush ([PARITY.md](PARITY.md) D6) |
| Catch-all unverified `VIEW https` intent filter | Android 12+ won't silently take links from the browser; causes chooser spam. Use `role.browser` (P17) or host-routed handlers (E1) |
| A `geolocation.*` or `camera.*` bridge method | `navigator.geolocation` and `getUserMedia` behind the install-time grant. Do not add a parallel API |
| Device wipe, password rules or camera switch through `device.admin` (`DevicePolicyManager.wipeData` and other policies) | Refused by design: one web app must never be able to erase the phone or lock the user out. The launcher's device admin declares only `force-lock`; `admin.lockNow` (at most every 30 s) and `admin.status` are the whole API ([PARITY.md](PARITY.md) G11) |
| All-files access (`MANAGE_EXTERNAL_STORAGE`) | Persistable SAF trees per origin (`files.pickTree`) |
