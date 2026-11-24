# JS bridge: files and media

<!-- generated:methods -->
- `files.pick` (medium): Pick files or folders. `files.pickTree`, `files.pickMedia`
- `files.read` (high): Read files. `files.listVolumes`, `files.listDir`, `files.stat`, `files.read`
- `files.write` (high): Write files. `files.write`, `files.copy`, `files.move`, `files.rename`, `files.delete`, `files.share`, `files.openWith`, `files.saveAs`
- `media.read` (high): Photos, videos and audio files on this phone, while the app is open. `media.query`, `media.thumbnail`, `media.read`
<!-- /generated -->

## Arguments and behaviour

Use `call(method, args)` from `/_deft/v1/bridge.js`. Basics, errors and events: `skill.read name=js-bridge/basics`.

- files: `pickTree`, `pickMedia` `{mime?}`, `listVolumes`, `listDir` `{path}`, `stat` `{path}`, `read` `{path}`, `write` `{path, content? or base64?}`, `copy`, `move`/`rename`, `delete` `{path}`, `share`/`openWith` `{path}`
- files (`files.write`): `saveAs` `{name, mime?, base64}` opens the system "Save as" dialog. `<a download>` links (blob:, data:, same-origin) use it automatically.
- media (`media.read`): `query` `{kind, since?, limit?, offset?}` (`kind` `images`, `video` or `audio`; `since` epoch ms, keeps items added at or after it; `limit` 1 to 500, default 100; `offset` default 0) → `[{id, name, mime, size, width?, height?, durationMs?, dateTaken?, album?}]`, newest first; `thumbnail` `{id, kind, size?}` (`size` 64 to 512, default 256, a square JPEG) → `{base64}`; `read` `{id, kind}` → `{base64, mime}`, up to 7 MB (bigger gives `invalid_args`; use `files.*` for big files). Foreground only. Photos and videos the user picked with Android 14's "selected photos" are the only ones returned in that case. No access gives `os_permission_missing`.
- media controls (`media.session`, medium; needs a running `fgs.start {type: "mediaPlayback"}`, else `unsupported` "start fgs type mediaPlayback first"): with the grant, `navigator.mediaSession` works system-wide (`metadata`, `playbackState`, `setActionHandler`, `setPositionState`); re-set them after `fgs.start` or let the shim resend them. Bridge methods: `setMetadata` `{title, artist?, album?, artworkUrl?, durationMs?}` → `{artwork}` (true when the artwork loaded; `artworkUrl` must be an `https` file on the app's own origin or a `data:image/png|jpeg|webp|gif;base64,` URL, at most 512 KiB, else `invalid_args`); `setState` `{state: playing|paused|stopped|none, positionMs?, rate?}`; `setActions` `{actions: [play, pause, stop, next, previous, seekto, seekforward, seekbackward]}` (only these buttons show and fire); `release`. The notification of the running service becomes a media notification (lock screen, quick settings) with the app's buttons and Stop. Event `media.action` `{action, seekTime?}` (`seekTime` in seconds, for `seekto`) when the user presses a button on the lock screen, the notification, a headset or a Bluetooth device; it reaches only a running page. The session ends with the service.
