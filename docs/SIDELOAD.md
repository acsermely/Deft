# Sideload and roles

This APK requests default SMS (`ROLE_SMS`) and default dialer (`ROLE_DIALER`). Google Play will reject that combination without a special program. Distribute by sideload.

File access is a user-picked Storage Access Framework (SAF) folder via `files.pickTree`, or the Downloads directory. The launcher does not request all-files access.

After install:

1. Set Deft as the default Home app.
2. When installing the **Messages** sample, accept the system prompt to make Deft the default SMS app. That **replaces** your previous messaging app as the default. Only one web origin can hold `sms` — use Messages (`samples/sms`), not Phone. Switch back in Settings → Default apps if needed.
3. When installing the **Phone** sample (telephony capability), accept the system prompt to make Deft the default **Phone** app. That replaces the stock dialer for incoming/outgoing calls. Only one origin can hold telephony. Switch back in Settings → Default apps if needed.
4. When installing the **Files** sample, pick a folder after install (`files.pickTree`). Without a pick, file calls stay inside Downloads.
5. **Mesh** (`samples/mesh`) may request **Bluetooth connect** (Android 12+) at install. Scan / advertise / GATT server are one origin at a time (dashboard shows who holds the radio). GATT clients may overlap. Needs a physical radio (emulator is usually useless).

Web apps never receive those roles. They only get a mediated JS API after per-origin consent.
