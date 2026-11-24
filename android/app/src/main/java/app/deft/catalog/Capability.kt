package app.deft.catalog

enum class CapabilityKind {
    STANDARD,
    BRIDGE,
}

enum class GrantLevel {
    DENIED,
    WHILE_USING,
    ONCE,
    CONFIRM_EACH,
}

fun GrantLevel.isGranted(): Boolean = this != GrantLevel.DENIED

/**
 * True when the user said no for good: a DENIED row with the user-denial flag (the dashboard's off switch, or
 * "Don't ask again" at the point of use). No row, and a DENIED row without the flag ("not now" or an unticked
 * consent box), are never a user denial, so the app can ask later.
 */
fun isUserDenial(rowLevel: String?, rowDeniedByUser: Boolean): Boolean =
    rowLevel == GrantLevel.DENIED.name && rowDeniedByUser

enum class Risk {
    LOW,
    MEDIUM,
    HIGH,

    /**
     * PARITY G0: Android-style "special access". Never granted at install or from the consent
     * screen; only from the launcher's own sheet after the screen lock: from the app's card on the
     * dashboard, or opened at the point of use when the app asks.
     */
    SPECIAL,
}

enum class Capability(
    val id: String,
    val kind: CapabilityKind,
    val title: String,
    val description: String,
    val defaultGrant: GrantLevel,
    val risk: Risk,
    val confirmEach: Boolean = false,
    val exclusiveGroup: String? = null,
    /** PARITY G0: plain warning shown on the special-access sheet. Required for [Risk.SPECIAL]. */
    val warning: String = "",
    /** PARITY G0: an ongoing "<App> is using <title>" notification shows while the power is in use. */
    val persistentWhileActive: Boolean = false,
    /** PARITY G0: false until the module exists; the dashboard shows "Not available yet". */
    val available: Boolean = true,
) {
    LOCATION(
        id = "location",
        kind = CapabilityKind.STANDARD,
        title = "Your location",
        description = "Browser geolocation API. The launcher intercepts the permission prompt.",
        defaultGrant = GrantLevel.WHILE_USING,
        risk = Risk.HIGH,
    ),
    CAMERA(
        id = "camera",
        kind = CapabilityKind.STANDARD,
        title = "Camera",
        description = "getUserMedia camera. The launcher intercepts the WebView permission prompt.",
        defaultGrant = GrantLevel.WHILE_USING,
        risk = Risk.HIGH,
    ),
    MICROPHONE(
        id = "microphone",
        kind = CapabilityKind.STANDARD,
        title = "Microphone",
        description = "getUserMedia microphone. The launcher intercepts the WebView permission prompt.",
        defaultGrant = GrantLevel.WHILE_USING,
        risk = Risk.HIGH,
    ),
    CONTACTS_READ(
        id = "contacts.read",
        kind = CapabilityKind.BRIDGE,
        title = "Read contacts",
        description = "List and search entries in your address book.",
        defaultGrant = GrantLevel.WHILE_USING,
        risk = Risk.HIGH,
    ),
    CONTACTS_WRITE(
        id = "contacts.write",
        kind = CapabilityKind.BRIDGE,
        title = "Change contacts",
        description = "Create, update, or delete entries in your address book.",
        defaultGrant = GrantLevel.WHILE_USING,
        risk = Risk.HIGH,
    ),
    SMS_READ(
        id = "sms.read",
        kind = CapabilityKind.BRIDGE,
        title = "Read SMS",
        description = "Read SMS threads and messages. Deft must be the default SMS app.",
        defaultGrant = GrantLevel.WHILE_USING,
        risk = Risk.HIGH,
        exclusiveGroup = "sms",
    ),
    SMS_WRITE(
        id = "sms.write",
        kind = CapabilityKind.BRIDGE,
        title = "Change SMS",
        description = "Mark messages read or delete them.",
        defaultGrant = GrantLevel.WHILE_USING,
        risk = Risk.HIGH,
        exclusiveGroup = "sms",
    ),
    SMS_SEND(
        id = "sms.send",
        kind = CapabilityKind.BRIDGE,
        title = "Send SMS",
        description = "Send SMS or MMS. Sends you tap for go straight out; any other send asks you first.",
        defaultGrant = GrantLevel.WHILE_USING,
        risk = Risk.HIGH,
        confirmEach = true,
        exclusiveGroup = "sms",
    ),
    CALLS_PLACE(
        id = "calls.place",
        kind = CapabilityKind.BRIDGE,
        title = "Place calls",
        description = "Place a cellular call. Calls you tap for go straight out; any other call asks you first. Deft must be the default dialer.",
        defaultGrant = GrantLevel.WHILE_USING,
        risk = Risk.HIGH,
        confirmEach = true,
        exclusiveGroup = "calls",
    ),
    CALLS_CONTROL(
        id = "calls.control",
        kind = CapabilityKind.BRIDGE,
        title = "Control calls",
        description = "Answer, reject, hold, or send DTMF on an in-progress call.",
        defaultGrant = GrantLevel.WHILE_USING,
        risk = Risk.HIGH,
        exclusiveGroup = "calls",
    ),
    CALLLOG_READ(
        id = "calllog.read",
        kind = CapabilityKind.BRIDGE,
        title = "Read call log",
        description = "Read a limited call history.",
        defaultGrant = GrantLevel.WHILE_USING,
        risk = Risk.HIGH,
        exclusiveGroup = "calls",
    ),
    CALLLOG_WRITE(
        id = "calllog.write",
        kind = CapabilityKind.BRIDGE,
        title = "Change call log",
        description = "Delete call-log entries.",
        defaultGrant = GrantLevel.WHILE_USING,
        risk = Risk.HIGH,
        exclusiveGroup = "calls",
    ),
    FILES_PICK(
        id = "files.pick",
        kind = CapabilityKind.BRIDGE,
        title = "Pick files and folders",
        description = "Open the system picker for a folder or media items.",
        defaultGrant = GrantLevel.WHILE_USING,
        risk = Risk.MEDIUM,
    ),
    FILES_READ(
        id = "files.read",
        kind = CapabilityKind.BRIDGE,
        title = "Read files",
        description = "List and read files in folders you have allowed.",
        defaultGrant = GrantLevel.WHILE_USING,
        risk = Risk.HIGH,
    ),
    FILES_WRITE(
        id = "files.write",
        kind = CapabilityKind.BRIDGE,
        title = "Write files",
        description = "Create, change, delete, or share files in folders you have allowed.",
        defaultGrant = GrantLevel.WHILE_USING,
        risk = Risk.HIGH,
    ),
    CALENDAR_READ(
        id = "calendar.read",
        kind = CapabilityKind.BRIDGE,
        title = "Read calendar",
        description = "List calendars and events.",
        defaultGrant = GrantLevel.WHILE_USING,
        risk = Risk.HIGH,
    ),
    CALENDAR_WRITE(
        id = "calendar.write",
        kind = CapabilityKind.BRIDGE,
        title = "Change calendar",
        description = "Create, update, or delete events.",
        defaultGrant = GrantLevel.WHILE_USING,
        risk = Risk.HIGH,
    ),
    ALARMS(
        id = "alarms",
        kind = CapabilityKind.BRIDGE,
        title = "Schedule reminders",
        description = "Schedule launcher-owned reminders that fire when the WebView is closed.",
        defaultGrant = GrantLevel.WHILE_USING,
        risk = Risk.MEDIUM,
    ),
    NOTIFICATIONS_POST(
        id = "notifications.post",
        kind = CapabilityKind.BRIDGE,
        title = "Post notifications",
        description = "Post a notification that opens this app when tapped. Not Web Push.",
        defaultGrant = GrantLevel.WHILE_USING,
        risk = Risk.MEDIUM,
    ),
    INTENTS_OPEN(
        id = "intents.open",
        kind = CapabilityKind.BRIDGE,
        title = "Open links",
        description = "Open http(s), mailto, geo, tel, and sms links via the system chooser.",
        defaultGrant = GrantLevel.WHILE_USING,
        risk = Risk.LOW,
    ),
    SHARE_RECEIVE(
        id = "share.receive",
        kind = CapabilityKind.BRIDGE,
        title = "Receive shares",
        description = "Receive Android shares into this app.",
        defaultGrant = GrantLevel.WHILE_USING,
        risk = Risk.LOW,
    ),
    NFC_READ(
        id = "nfc.read",
        kind = CapabilityKind.BRIDGE,
        title = "Read NFC tags",
        description = "One-shot read of an NFC tag ID and optional NDEF text.",
        defaultGrant = GrantLevel.WHILE_USING,
        risk = Risk.MEDIUM,
    ),
    NFC_WRITE(
        id = "nfc.write",
        kind = CapabilityKind.BRIDGE,
        title = "Write NFC tags",
        description = "Write NDEF records to a tag you tap, and lock a tag read-only (always asks first).",
        defaultGrant = GrantLevel.WHILE_USING,
        risk = Risk.MEDIUM,
    ),
    NFC_TECH(
        id = "nfc.tech",
        kind = CapabilityKind.BRIDGE,
        title = "Talk to NFC cards",
        description = "Send raw commands to a tapped card (IsoDep, MIFARE, NfcA/B/F/V), e.g. transit or ID cards.",
        defaultGrant = GrantLevel.WHILE_USING,
        risk = Risk.HIGH,
    ),
    NFC_HCE(
        id = "nfc.hce",
        kind = CapabilityKind.BRIDGE,
        title = "Act as an NFC card",
        description = "Answer NFC readers as a contactless card while the app is open. One app at a time.",
        defaultGrant = GrantLevel.WHILE_USING,
        risk = Risk.HIGH,
        exclusiveGroup = "nfc.hce",
    ),
    USB(
        id = "usb",
        kind = CapabilityKind.BRIDGE,
        title = "USB host devices",
        description = "List attached USB devices and open one you choose.",
        defaultGrant = GrantLevel.WHILE_USING,
        risk = Risk.HIGH,
    ),
    BLUETOOTH_SERIAL(
        id = "bluetooth.serial",
        kind = CapabilityKind.BRIDGE,
        title = "Bluetooth Classic serial",
        description = "Connect over Bluetooth Classic SPP.",
        defaultGrant = GrantLevel.WHILE_USING,
        risk = Risk.HIGH,
    ),
    BLUETOOTH_BLE_CENTRAL(
        id = "bluetooth.ble.central",
        kind = CapabilityKind.BRIDGE,
        title = "Bluetooth LE central",
        description = "Scan, connect, and talk to BLE peripherals. Scan results include nearby addresses.",
        defaultGrant = GrantLevel.WHILE_USING,
        risk = Risk.HIGH,
    ),
    BLUETOOTH_BLE_PERIPHERAL(
        id = "bluetooth.ble.peripheral",
        kind = CapabilityKind.BRIDGE,
        title = "Bluetooth LE peripheral",
        description = "Advertise and run a GATT server. One app at a time for advertise/server/bonding.",
        defaultGrant = GrantLevel.WHILE_USING,
        risk = Risk.HIGH,
    ),
    TILES(
        id = "tiles",
        kind = CapabilityKind.BRIDGE,
        title = "Quick Settings tile",
        description = "Offer a tile in Quick Settings; Android asks you before adding it.",
        defaultGrant = GrantLevel.WHILE_USING,
        risk = Risk.LOW,
    ),
    WIDGETS(
        id = "widgets",
        kind = CapabilityKind.BRIDGE,
        title = "Home widgets",
        description = "Show up to three small cards on your Home screen.",
        defaultGrant = GrantLevel.WHILE_USING,
        risk = Risk.LOW,
    ),
    TORCH(
        id = "torch",
        kind = CapabilityKind.BRIDGE,
        title = "Flashlight",
        description = "Turn the camera flashlight on and off while the app is open.",
        defaultGrant = GrantLevel.WHILE_USING,
        risk = Risk.LOW,
    ),
    PIP(
        id = "pip",
        kind = CapabilityKind.BRIDGE,
        title = "Picture-in-picture",
        description = "Keep showing the app in a small window when you leave it.",
        defaultGrant = GrantLevel.WHILE_USING,
        risk = Risk.LOW,
    ),
    SENSORS(
        id = "sensors",
        kind = CapabilityKind.BRIDGE,
        title = "Motion and environment sensors",
        description = "Read step count, light, pressure, proximity, compass and humidity while the app is open.",
        defaultGrant = GrantLevel.WHILE_USING,
        risk = Risk.MEDIUM,
    ),
    SYSTEM_STATS(
        id = "system.stats",
        kind = CapabilityKind.BRIDGE,
        title = "Device status",
        description = "Read memory, battery, temperature and storage figures of this phone.",
        defaultGrant = GrantLevel.WHILE_USING,
        risk = Risk.LOW,
    ),
    TTS(
        id = "speech.tts",
        kind = CapabilityKind.BRIDGE,
        title = "Read text aloud",
        description = "Speak text with the phone's voice.",
        defaultGrant = GrantLevel.WHILE_USING,
        risk = Risk.LOW,
    ),
    SPEECH_RECOGNIZE(
        id = "speech.recognize",
        kind = CapabilityKind.BRIDGE,
        title = "Speech to text",
        description = "Turn what you say into text. Uses the microphone while listening.",
        defaultGrant = GrantLevel.WHILE_USING,
        risk = Risk.MEDIUM,
    ),
    // PARITY F5a: DND (F5b) is a separate capability.
    AUDIO_CONTROL(
        id = "audio.control",
        kind = CapabilityKind.BRIDGE,
        title = "Volume and audio output",
        description = "Read and change volumes, the ringer mode and whether sound plays on the speaker.",
        defaultGrant = GrantLevel.WHILE_USING,
        risk = Risk.MEDIUM,
    ),
    // PARITY F2: keys never leave the phone; the app only gets signatures and ciphertext back.
    KEYSTORE(
        id = "keystore",
        kind = CapabilityKind.BRIDGE,
        title = "Secure keys",
        description = "Create keys that never leave this phone, optionally locked by your fingerprint or face.",
        defaultGrant = GrantLevel.WHILE_USING,
        risk = Risk.MEDIUM,
    ),
    // PARITY F6: all photos, videos and audio on the phone, not only what the user picks.
    MEDIA_READ(
        id = "media.read",
        kind = CapabilityKind.BRIDGE,
        title = "Photos, videos and music",
        description = "See all photos, videos and audio files on this phone.",
        defaultGrant = GrantLevel.WHILE_USING,
        risk = Risk.HIGH,
    ),
    // PARITY E2: the app asks another app for something; the user picks which app, every time.
    INTENTS_START(
        id = "intents.start",
        kind = CapabilityKind.BRIDGE,
        title = "Use other apps",
        description = "Ask another app to do something, like take a photo, pick a contact or add an event. You choose the app each time.",
        defaultGrant = GrantLevel.WHILE_USING,
        risk = Risk.MEDIUM,
    ),
    // PARITY E5: web checkout that hands payment to an installed payment app.
    PAYMENTS_REQUEST(
        id = "payments.request",
        kind = CapabilityKind.STANDARD,
        title = "Pay with installed apps",
        description = "Let the app ask an installed payment app on this phone to pay.",
        defaultGrant = GrantLevel.WHILE_USING,
        risk = Risk.MEDIUM,
    ),
    // PARITY F7a: blocked numbers need the dialer role (exclusive with the other call capabilities).
    CALLS_BLOCK(
        id = "calls.block",
        kind = CapabilityKind.BRIDGE,
        title = "Blocked numbers",
        description = "See, block and unblock phone numbers. Deft must be the default phone app.",
        defaultGrant = GrantLevel.WHILE_USING,
        risk = Risk.HIGH,
    ),
    // PARITY F7a: carrier codes such as *100# can cost money, so every one asks first.
    USSD(
        id = "telephony.ussd",
        kind = CapabilityKind.BRIDGE,
        title = "Carrier codes",
        description = "Send codes such as *100# to your mobile carrier and read the reply. Each code asks you first.",
        defaultGrant = GrantLevel.WHILE_USING,
        risk = Risk.HIGH,
        confirmEach = true,
    ),
    // PARITY G0: the first special-access powers. Entries only; their modules come in later items.
    APPS_LIST(
        id = "apps.list",
        kind = CapabilityKind.BRIDGE,
        title = "See your installed apps",
        description = "See the list of apps installed on this phone.",
        defaultGrant = GrantLevel.WHILE_USING,
        risk = Risk.SPECIAL,
        warning = "The list of your apps says a lot about you, such as your bank, health or dating apps. Only allow this for an app you trust.",
        available = true,
    ),
    USAGE_READ(
        id = "usage.read",
        kind = CapabilityKind.BRIDGE,
        title = "See how you use your phone",
        description = "See which apps you used, when, and for how long.",
        defaultGrant = GrantLevel.WHILE_USING,
        risk = Risk.SPECIAL,
        exclusiveGroup = "usage",
        warning = "This shows when and how long you use each app, day by day. Only one app at a time can have this.",
        available = true,
    ),
    NOTIFICATIONS_LISTEN(
        id = "notifications.listen",
        kind = CapabilityKind.BRIDGE,
        title = "Read all your notifications",
        description = "Read, dismiss and reply to notifications from all your apps.",
        defaultGrant = GrantLevel.WHILE_USING,
        risk = Risk.SPECIAL,
        exclusiveGroup = "notif-listener",
        warning = "This app will see every notification you get, including private messages and sign-in codes from your bank. Only one app at a time can have this, and you see a notification while it is in use.",
        persistentWhileActive = true,
    ),
    ACCESSIBILITY(
        id = "accessibility",
        kind = CapabilityKind.BRIDGE,
        title = "See and control your screen",
        description = "See what is on screen in any app, and tap, type and scroll for you.",
        defaultGrant = GrantLevel.WHILE_USING,
        risk = Risk.SPECIAL,
        exclusiveGroup = "a11y",
        warning = "This is the strongest power on the phone. The app can read everything you see, including what you type, and act for you in any app. Only allow it for an app made to help you use the phone. Only one app at a time can have this, and you see a notification while it is on.",
        persistentWhileActive = true,
    ),
    CLIPBOARD_WATCH(
        id = "clipboard.watch",
        kind = CapabilityKind.BRIDGE,
        title = "Watch what you copy",
        description = "See each new thing you copy while the app is open, without you pasting it.",
        defaultGrant = GrantLevel.WHILE_USING,
        risk = Risk.SPECIAL,
        warning = "Anything you copy while the app is open, such as passwords or codes, goes to this app at once.",
    ),
    ACCOUNTS_READ(
        id = "accounts.read",
        kind = CapabilityKind.BRIDGE,
        title = "See accounts on this phone",
        description = "See the accounts added on this phone, such as email addresses.",
        defaultGrant = GrantLevel.WHILE_USING,
        risk = Risk.SPECIAL,
        warning = "This shows which accounts and email addresses are on this phone. That can be enough to know who you are.",
    ),
    LOCATION_BACKGROUND(
        id = "location.background",
        kind = CapabilityKind.BRIDGE,
        title = "Your location all the time",
        description = "Get your location after you leave the app: follow a route while it keeps running, and know when you arrive at or leave places it marks.",
        defaultGrant = GrantLevel.WHILE_USING,
        risk = Risk.SPECIAL,
        warning = "The app can follow where you go all day, not only while you look at it. You see a notification while it does this.",
        persistentWhileActive = true,
    ),
    NFC_HCE_PAYMENT(
        id = "nfc.hce.payment",
        kind = CapabilityKind.BRIDGE,
        title = "Tap to pay",
        description = "Act as a contactless payment card at card readers while the app is open and the phone is unlocked.",
        defaultGrant = GrantLevel.WHILE_USING,
        risk = Risk.SPECIAL,
        exclusiveGroup = "hce-payment",
        // PARITY B4: Android also asks to make Deft the default payment app.
        warning = "The app can act as a contactless card at payment terminals, and tapping your phone may pay with it. " +
            "Only one app at a time can have this. Android also asks you to make Deft your default payment app; " +
            "to undo it, remove the permission here, or pick another app in Android Settings under Contactless payments.",
        available = true,
    ),
    // PARITY F5b: Do Not Disturb, a separate switch from volume. Reading and setting it needs the
    // system's notification policy access, which the launcher holds for all apps.
    DND(
        id = "dnd",
        kind = CapabilityKind.BRIDGE,
        title = "Do Not Disturb",
        description = "Turn Do Not Disturb on or off and read whether it is on.",
        defaultGrant = GrantLevel.WHILE_USING,
        risk = Risk.HIGH,
    ),
    // PARITY G1: network grants only widen the app's CSP (CspBuilder); there is no bridge method.
    NETWORK_OPEN(
        id = "network.open",
        kind = CapabilityKind.STANDARD,
        title = "Any website",
        description = "Connect to any website or server on the internet, and show pictures and videos from it.",
        defaultGrant = GrantLevel.WHILE_USING,
        risk = Risk.HIGH,
    ),
    NETWORK_USER(
        id = "network.user",
        kind = CapabilityKind.STANDARD,
        title = "Servers you add",
        description = "Connect to servers you add yourself in Settings, such as your own home server.",
        defaultGrant = GrantLevel.WHILE_USING,
        risk = Risk.MEDIUM,
    ),
    // PARITY G2: the app may compile the .wasm files it ships (CspFlag.WASM); there is no bridge method.
    WASM(
        id = "wasm",
        kind = CapabilityKind.STANDARD,
        title = "Run WebAssembly",
        description = "Run compiled code (WebAssembly) shipped inside the app.",
        defaultGrant = GrantLevel.WHILE_USING,
        risk = Risk.MEDIUM,
    ),
    // PARITY G14: approved manifest `embeds` origins join script/frame/connect/img-src (CspBuilder);
    // each origin is approved on its own on the consent screen and the dashboard. No bridge method.
    WEB_EMBED(
        id = "web.embed",
        kind = CapabilityKind.STANDARD,
        title = "Code from other sites",
        description = "Load code and embedded pages from the sites listed below, such as a payment form. " +
            "That code runs inside this app with all of its permissions.",
        defaultGrant = GrantLevel.WHILE_USING,
        risk = Risk.HIGH,
    ),
    // PARITY G3: a larger package cap and 200 MiB binaries (DeftPaths.largeCaps). No bridge method.
    STORAGE_LARGE(
        id = "storage.large",
        kind = CapabilityKind.STANDARD,
        title = "More storage",
        description = "Let this app store more than the usual limit, up to the free space on the phone.",
        defaultGrant = GrantLevel.WHILE_USING,
        risk = Risk.LOW,
    ),
    // PARITY F8: devices on the local network only (RFC 1918, link-local, IPv6 ULA); no internet.
    NET_LOCAL(
        id = "net.local",
        kind = CapabilityKind.BRIDGE,
        title = "Devices on your network",
        description = "Find devices and services on your Wi-Fi or home network, and talk to them over TCP or UDP.",
        defaultGrant = GrantLevel.WHILE_USING,
        risk = Risk.HIGH,
    ),
    // PARITY G4: sockets to servers on the internet. Needs this grant and network.open, both declared.
    NET_SOCKETS(
        id = "net.sockets",
        kind = CapabilityKind.STANDARD,
        title = "Internet connections",
        description = "Open direct TCP or UDP connections to servers on the internet, not only web pages. Also needs Any website.",
        defaultGrant = GrantLevel.WHILE_USING,
        risk = Risk.HIGH,
    ),
    // PARITY G6: scan results name nearby networks, so the app also needs Your location.
    WIFI_SCAN(
        id = "wifi.scan",
        kind = CapabilityKind.BRIDGE,
        title = "Nearby Wi-Fi networks",
        description = "List the Wi-Fi networks in range and the name and signal of the one this phone is using. Also needs Your location.",
        defaultGrant = GrantLevel.WHILE_USING,
        risk = Risk.HIGH,
    ),
    // PARITY G6: opens the Wi-Fi panel and suggests networks. Android does not let apps switch Wi-Fi on or off.
    WIFI_CONTROL(
        id = "wifi.control",
        kind = CapabilityKind.BRIDGE,
        title = "Wi-Fi networks to join",
        description = "Open the Wi-Fi settings and suggest Wi-Fi networks for this phone to join. You confirm each network first.",
        defaultGrant = GrantLevel.WHILE_USING,
        risk = Risk.HIGH,
    ),
    // PARITY G7: opens another launchable app. The launcher itself is refused.
    APPS_LAUNCH(
        id = "apps.launch",
        kind = CapabilityKind.BRIDGE,
        title = "Open other apps",
        description = "Open an app installed on this phone from this app.",
        defaultGrant = GrantLevel.WHILE_USING,
        risk = Risk.MEDIUM,
    ),
    // PARITY G7: an id that is the same every time this app opens, unique per web origin.
    DEVICE_ID(
        id = "device.id",
        kind = CapabilityKind.BRIDGE,
        title = "A device id for this app",
        description = "A number that stays the same each time this app opens on this phone. Other apps cannot see it.",
        defaultGrant = GrantLevel.WHILE_USING,
        risk = Risk.MEDIUM,
    ),
    // PARITY E3: sign in with a website in a Custom Tab; the result comes back to this app only.
    AUTH_SESSION(
        id = "auth.session",
        kind = CapabilityKind.BRIDGE,
        title = "Sign in with a website",
        description = "Open a sign-in page in your browser and bring the result back to this app.",
        defaultGrant = GrantLevel.WHILE_USING,
        risk = Risk.MEDIUM,
    ),
    // PARITY D1: host-scheduled, budgeted runs of the manifest `background` module.
    BACKGROUND(
        id = "background",
        kind = CapabilityKind.BRIDGE,
        title = "Run in the background",
        description = "Run short tasks when something happens, even when the app is closed (up to 30 seconds each).",
        defaultGrant = GrantLevel.WHILE_USING,
        risk = Risk.MEDIUM,
    ),
    // PARITY D3: a typed foreground service keeps the app's page running after the user leaves it.
    FOREGROUND(
        id = "foreground",
        kind = CapabilityKind.BRIDGE,
        title = "Keep running",
        description = "Keep running with a notification after you leave the app.",
        defaultGrant = GrantLevel.WHILE_USING,
        risk = Risk.HIGH,
    ),
    // PARITY D4: lock-screen, headset and Bluetooth controls for audio kept running by `foreground`.
    MEDIA_SESSION(
        id = "media.session",
        kind = CapabilityKind.BRIDGE,
        title = "Media controls",
        description = "Show what is playing on the lock screen and react to headphone and Bluetooth buttons.",
        defaultGrant = GrantLevel.WHILE_USING,
        risk = Risk.MEDIUM,
    ),
    // PARITY D6: push messages through a UnifiedPush distributor app (no Google services).
    PUSH(
        id = "push",
        kind = CapabilityKind.BRIDGE,
        title = "Push messages",
        description = "Get messages from the app's server through a push app such as ntfy, even when the app is closed.",
        defaultGrant = GrantLevel.WHILE_USING,
        risk = Risk.MEDIUM,
    ),
    // PARITY G10: the app's own accounts in system Settings, with background sync.
    ACCOUNTS_OWN(
        id = "accounts.own",
        kind = CapabilityKind.BRIDGE,
        title = "Its own accounts",
        description = "Add accounts for this app to the phone's account list, and sync them in the background now and then.",
        defaultGrant = GrantLevel.WHILE_USING,
        risk = Risk.MEDIUM,
    ),
    // PARITY G11: the launcher becomes the phone's browser on behalf of one app; links reach it as data.
    ROLE_BROWSER(
        id = "role.browser",
        kind = CapabilityKind.BRIDGE,
        title = "Open web links",
        description = "Be the phone's browser: web links you tap in other apps go to this app, which gets the address.",
        defaultGrant = GrantLevel.WHILE_USING,
        risk = Risk.SPECIAL,
        exclusiveGroup = "role-browser",
        warning = "Every web link you open from other apps, such as links in messages and email, goes to this app, so it sees those addresses. Only one app at a time can have this. Android also asks you to make Deft your browser; to undo that, choose another browser in Android Settings under Default apps.",
    ),
    // PARITY G11: the launcher becomes the phone's assistant on behalf of one app, which opens on long-press Home.
    ROLE_ASSISTANT(
        id = "role.assistant",
        kind = CapabilityKind.BRIDGE,
        title = "Be your assistant",
        description = "Open this app when you hold the Home button or use the assistant gesture.",
        defaultGrant = GrantLevel.WHILE_USING,
        risk = Risk.SPECIAL,
        exclusiveGroup = "role-assistant",
        warning = "This app opens each time you call the assistant, instead of the phone's own assistant. It does not see your screen or hear you. Only one app at a time can have this. Android also asks you to make Deft your digital assistant; to undo that, choose another one in Android Settings under Default apps.",
    ),
    // PARITY G11: the launcher's device admin, for one app; it can only lock the screen (no wipe).
    DEVICE_ADMIN(
        id = "device.admin",
        kind = CapabilityKind.BRIDGE,
        title = "Lock your phone",
        description = "Lock the screen at once, also when the app is closed, through Deft's device admin. It cannot erase the phone or change your screen lock.",
        defaultGrant = GrantLevel.WHILE_USING,
        risk = Risk.SPECIAL,
        exclusiveGroup = "device-admin",
        warning = "This app can lock your screen whenever it wants, at most every 30 seconds. Only one app at a time can have this. Android also asks you to activate Deft as a device admin app; while that is on, Android will not uninstall Deft. Removing this permission turns it off; you can also turn it off in Android Settings under Security, Device admin apps.",
    ),
    // PARITY G11: the launcher's live wallpaper shows one app's wallpaper page. No ongoing
    // notification: the wallpaper itself shows that the app is running.
    WALLPAPER(
        id = "wallpaper",
        kind = CapabilityKind.BRIDGE,
        title = "Be your wallpaper",
        description = "Draw the phone's wallpaper with a page of this app, which also sees where you touch the home screen.",
        defaultGrant = GrantLevel.WHILE_USING,
        risk = Risk.SPECIAL,
        exclusiveGroup = "role-wallpaper",
        warning = "This app runs whenever your home screen or lock screen is visible, also when the app is closed, and that uses battery. It sees where you touch the home screen, but not what other apps show. Only one app at a time can have this. Android also asks you to set Deft's live wallpaper. Removing this permission turns it off and Android shows its default wallpaper; to get your own picture back, choose it again in Android Settings under Wallpaper.",
    ),
    // PARITY G11: the launcher's keyboard (an Android input method) shows one app's keyboard page.
    // Not persistentWhileActive: Android itself makes the keyboard visible whenever it is in use
    // (the keyboard is on screen, the keyboard switcher shows it) and warns when the user turns it
    // on; an ongoing notification would be there all day for nothing new.
    IME(
        id = "ime",
        kind = CapabilityKind.BRIDGE,
        title = "Be your keyboard",
        description = "Show this app's keyboard page as the phone's keyboard, which types into other apps.",
        defaultGrant = GrantLevel.WHILE_USING,
        risk = Risk.SPECIAL,
        exclusiveGroup = "role-ime",
        warning = "This app sees everything you type with this keyboard in any app, such as messages, searches, addresses and card numbers, and it can type for you. Password fields are the exception: there Deft shows its own simple keyboard and the app sees nothing. It also learns which app you are typing in. Only one app at a time can have this. Android also asks you to turn on and choose Deft's keyboard. Removing this permission turns the keyboard off and Android goes back to another one; you can also switch keyboards in Android Settings under Keyboard.",
    ),
    // PARITY F7b: the launcher's call screening service asks one app about each incoming call.
    CALLS_SCREEN(
        id = "calls.screen",
        kind = CapabilityKind.BRIDGE,
        title = "Screen your calls",
        description = "Decide about each incoming call before your phone rings: let it ring, ring silently, or reject it. Also when the app is closed.",
        defaultGrant = GrantLevel.WHILE_USING,
        risk = Risk.SPECIAL,
        exclusiveGroup = "role-call-screening",
        warning = "This app sees who calls you and can silently reject calls, so you may never know someone called. If it also may read your contacts or call log, it sees the caller's number; otherwise it only learns that a call came. If it does not answer within 4 seconds, the call rings as usual. Emergency calls are never sent to it. Only one app at a time can have this. If Deft is not your phone app, Android also asks you to make it your caller ID and spam app; to undo that, choose another one in Android Settings under Default apps.",
    ),
    HAPTICS(
        id = "haptics",
        kind = CapabilityKind.STANDARD,
        title = "Vibration",
        description = "navigator.vibrate. Short vibrations for feedback.",
        defaultGrant = GrantLevel.WHILE_USING,
        risk = Risk.LOW,
    ),
    DISPLAY(
        id = "display",
        kind = CapabilityKind.BRIDGE,
        title = "Screen control",
        description = "Keep the screen on, lock orientation, print this page, and hide the screen from screenshots and overlays while the app is open.",
        defaultGrant = GrantLevel.WHILE_USING,
        risk = Risk.LOW,
    ),
    CLIPBOARD_READ(
        id = "clipboard.read",
        kind = CapabilityKind.BRIDGE,
        title = "Read the clipboard",
        description = "Paste text from the clipboard, only right after you tap in the app.",
        defaultGrant = GrantLevel.WHILE_USING,
        risk = Risk.MEDIUM,
    ),
    MEDIA_DRM(
        id = "media.drm",
        kind = CapabilityKind.STANDARD,
        title = "Protected video",
        description = "Play DRM-protected media (Widevine). The device identifies itself to the license server.",
        defaultGrant = GrantLevel.WHILE_USING,
        risk = Risk.MEDIUM,
    ),
    MIDI(
        id = "midi",
        kind = CapabilityKind.STANDARD,
        title = "MIDI devices",
        description = "Web MIDI with system-exclusive messages, for instruments and controllers.",
        defaultGrant = GrantLevel.WHILE_USING,
        risk = Risk.MEDIUM,
    );

    companion object {
        fun fromId(id: String): Capability? = entries.find { it.id == id }

        fun parseManifestList(ids: List<String>): Result<List<Capability>> {
            val out = mutableListOf<Capability>()
            for (id in ids) {
                val cap = fromId(id) ?: return Result.failure(
                    IllegalArgumentException(
                        "Unknown capability '$id'. Allowed: ${entries.joinToString { it.id }}",
                    ),
                )
                out += cap
            }
            return Result.success(out)
        }

        /**
         * One-time rewrite of coarse ids already stored on device.
         * New manifests must use catalog ids. Unknown and removed ids expand to nothing.
         */
        fun migrateStoredId(id: String): List<Capability> = when (id) {
            "contacts" -> listOf(CONTACTS_READ, CONTACTS_WRITE)
            "sms" -> listOf(SMS_READ, SMS_WRITE, SMS_SEND)
            "telephony" -> listOf(CALLS_PLACE, CALLS_CONTROL, CALLLOG_READ, CALLLOG_WRITE)
            "files" -> listOf(FILES_PICK, FILES_READ, FILES_WRITE)
            "calendar" -> listOf(CALENDAR_READ, CALENDAR_WRITE)
            "alarms" -> listOf(ALARMS)
            "notify" -> listOf(NOTIFICATIONS_POST)
            "intents" -> listOf(INTENTS_OPEN, SHARE_RECEIVE)
            "nfc" -> listOf(NFC_READ)
            "usb" -> listOf(USB)
            "bluetooth" -> listOf(BLUETOOTH_SERIAL, BLUETOOTH_BLE_CENTRAL, BLUETOOTH_BLE_PERIPHERAL)
            "geolocation" -> listOf(LOCATION)
            "media" -> listOf(CAMERA, MICROPHONE)
            "notifications" -> listOf(NOTIFICATIONS_POST)
            "mail", "mail.wakeup", "storage" -> emptyList()
            else -> fromId(id)?.let { listOf(it) } ?: emptyList()
        }
    }
}

fun Capability.methodPrefix(): String = id.substringBefore('.')
