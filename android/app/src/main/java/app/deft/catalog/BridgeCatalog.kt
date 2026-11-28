package app.deft.catalog

object BridgeCatalog {
    data class CapInfo(
        val id: String,
        val kind: String,
        val risk: String,
        val methods: List<String>,
        val events: List<String>,
        val summary: String,
        val confirmEach: Boolean = false,
    )

    data class CatalogFile(
        val minWebView: Int = 124,
        val capabilities: Map<String, CapInfo> = emptyMap(),
    )

    const val MIN_WEBVIEW_MAJOR = 124
    const val ASSET_PATH = "agent/bridge-catalog.json"

    private val methodToCap: Map<String, Capability> = buildMap {
        fun add(cap: Capability, vararg methods: String) {
            for (m in methods) put(m, cap)
        }
        add(Capability.CONTACTS_READ, "contacts.list", "contacts.search", "contacts.get")
        add(Capability.CONTACTS_WRITE, "contacts.create", "contacts.update", "contacts.delete")
        add(Capability.SMS_READ, "sms.listThreads", "sms.listMessages")
        add(Capability.SMS_WRITE, "sms.markRead", "sms.delete")
        add(Capability.SMS_SEND, "sms.send", "sms.sendMms")
        add(Capability.CALLS_PLACE, "telephony.call", "telephony.listSims")
        add(Capability.CALLS_BLOCK, "telephony.blockedList", "telephony.block", "telephony.unblock")
        add(Capability.USSD, "telephony.ussd")
        add(Capability.CALLS_CONTROL, "telephony.answer", "telephony.reject", "telephony.end", "telephony.hold", "telephony.dtmf")
        add(Capability.CALLLOG_READ, "telephony.listCalls")
        add(Capability.CALLLOG_WRITE, "telephony.deleteCall")
        add(
            Capability.FILES_PICK,
            "files.pickTree",
            "files.pickMedia",
        )
        add(
            Capability.FILES_READ,
            "files.listVolumes",
            "files.listDir",
            "files.stat",
            "files.read",
        )
        add(
            Capability.FILES_WRITE,
            "files.write",
            "files.copy",
            "files.move",
            "files.rename",
            "files.delete",
            "files.share",
            "files.openWith",
            "files.saveAs",
        )
        add(Capability.CALENDAR_READ, "calendar.listCalendars", "calendar.listEvents")
        add(Capability.CALENDAR_WRITE, "calendar.create", "calendar.update", "calendar.delete")
        add(Capability.ALARMS, "alarms.schedule", "alarms.cancel", "alarms.list")
        add(
            Capability.NOTIFICATIONS_POST,
            "notify.post", "notify.update", "notify.cancel", "notify.list",
            "notify.createChannel", "notify.deleteChannel", "notify.listChannels",
        )
        add(Capability.INTENTS_OPEN, "intents.open", "intents.share")
        add(Capability.INTENTS_START, "intents.start", "intents.startForResult")
        add(Capability.DISPLAY, "display.keepScreenOn", "display.setOrientation", "display.print", "display.secure", "display.hideOverlays")
        add(Capability.TORCH, "torch.set", "torch.state")
        add(Capability.PIP, "pip.enter", "pip.setAuto")
        add(Capability.SENSORS, "sensors.list", "sensors.subscribe", "sensors.unsubscribe", "sensors.stepsToday")
        add(Capability.SYSTEM_STATS, "system.stats")
        add(
            Capability.AUDIO_CONTROL,
            "audio.getVolumes", "audio.setVolume", "audio.getRingerMode", "audio.setRingerMode", "audio.devices", "audio.route",
        )
        add(Capability.DND, "dnd.state", "dnd.set", "dnd.requestAccess")
        add(
            Capability.NET_LOCAL,
            "net.discover", "net.discoverStop", "net.resolve", "net.tcpConnect", "net.tcpSend",
            "net.udpSend", "net.udpListen", "net.close",
        )
        add(Capability.WIFI_SCAN, "wifi.scan", "wifi.info")
        add(Capability.WIFI_CONTROL, "wifi.openPanel", "wifi.suggest")
        add(Capability.APPS_LIST, "apps.list", "apps.icon")
        add(Capability.APPS_LAUNCH, "apps.launch")
        add(Capability.USAGE_READ, "usage.stats")
        add(Capability.DEVICE_ID, "device.id")
        add(Capability.KEYSTORE, "keystore.generate", "keystore.sign", "keystore.encrypt", "keystore.decrypt", "keystore.list", "keystore.delete")
        add(Capability.MEDIA_READ, "media.query", "media.thumbnail", "media.read")
        add(Capability.TTS, "speech.speak", "speech.stop", "speech.voices")
        add(Capability.SPEECH_RECOGNIZE, "speech.recognize", "speech.cancel")
        add(Capability.CLIPBOARD_READ, "clipboard.readText")
        add(Capability.AUTH_SESSION, "auth.prepare", "auth.start")
        add(Capability.WEBAUTHN, "passkeys.status", "passkeys.create", "passkeys.get")
        add(Capability.BACKGROUND, "background.done")
        add(Capability.FOREGROUND, "fgs.start", "fgs.update", "fgs.stop")
        add(Capability.MEDIA_SESSION, "media.setMetadata", "media.setState", "media.setActions", "media.release")
        add(Capability.PUSH, "push.register", "push.unregister", "push.status")
        add(
            Capability.LOCATION_BACKGROUND,
            "location.watchBackground", "location.watchStop",
            "location.geofenceAdd", "location.geofenceRemove", "location.geofenceList",
        )
        add(
            Capability.ACCOUNTS_OWN,
            "accounts.add", "accounts.list", "accounts.remove", "accounts.requestSync", "accounts.setPeriodicSync",
        )
        add(Capability.ACCOUNTS_READ, "accounts.listAll")
        add(
            Capability.NOTIFICATIONS_LISTEN,
            "notifications.active", "notifications.dismiss", "notifications.reply", "notifications.open",
        )
        add(Capability.ACCESSIBILITY, "a11y.windowTree", "a11y.perform", "a11y.gesture", "a11y.global")
        add(Capability.DEVICE_ADMIN, "admin.lockNow", "admin.status")
        add(
            Capability.IME,
            "ime.commitText", "ime.deleteBefore", "ime.sendKey", "ime.setComposing", "ime.finishComposing",
            "ime.hide", "ime.switchKeyboard", "ime.setHeight",
        )
        add(Capability.CALLS_SCREEN, "calls.screenRespond")
        add(Capability.CLIPBOARD_WATCH, "clipboard.watchStart", "clipboard.watchStop")
        add(Capability.TILES, "tiles.request", "tiles.update", "tiles.release")
        add(Capability.WIDGETS, "widgets.update", "widgets.remove", "widgets.list")
        add(Capability.SHARE_RECEIVE, "intents.readShare")
        add(Capability.NFC_READ, "nfc.read", "nfc.scanStart", "nfc.scanStop")
        add(Capability.NFC_WRITE, "nfc.writeNdef", "nfc.makeReadOnly")
        add(Capability.NFC_TECH, "nfc.transceive")
        add(Capability.NFC_HCE, "nfc.hceRegister", "nfc.hceUnregister", "nfc.hceRespond")
        add(Capability.NFC_HCE_PAYMENT, "nfc.paymentRegister", "nfc.paymentUnregister", "nfc.paymentRespond")
        add(
            Capability.USB,
            "usb.list", "usb.requestPermission", "usb.open", "usb.close",
            "usb.claimInterface", "usb.releaseInterface", "usb.controlTransfer", "usb.bulkTransfer", "usb.interruptTransfer",
        )
        add(
            Capability.BLUETOOTH_SERIAL,
            "bluetooth.sppConnect",
            "bluetooth.sppWrite",
            "bluetooth.sppClose",
            "bluetooth.sppStatus",
            "bluetooth.sppListen",
            "bluetooth.sppListenStop",
        )
        add(
            Capability.BLUETOOTH_BLE_PERIPHERAL,
            "bluetooth.bleAdvertiseStart",
            "bluetooth.bleAdvertiseStop",
            "bluetooth.bleServerAddService",
            "bluetooth.bleServerRemoveService",
            "bluetooth.bleServerClear",
            "bluetooth.bleServerNotify",
            "bluetooth.bleServerRespond",
        )
        val bleCentral = listOf(
            "bluetooth.bleState", "bluetooth.bleEnable", "bluetooth.bleScanStart", "bluetooth.bleScanStop",
            "bluetooth.bleBonded", "bluetooth.bleCreateBond", "bluetooth.bleRemoveBond",
            "bluetooth.bleConnect", "bluetooth.bleDisconnect", "bluetooth.bleDiscover", "bluetooth.bleServices",
            "bluetooth.bleRead", "bluetooth.bleWrite", "bluetooth.bleReadDescriptor", "bluetooth.bleWriteDescriptor",
            "bluetooth.bleNotifyStart", "bluetooth.bleNotifyStop", "bluetooth.bleRequestMtu", "bluetooth.bleReadRssi",
            "bluetooth.bleConnectionPriority", "bluetooth.bleSetPhy", "bluetooth.bleRefreshCache",
            "bluetooth.bleReliableWriteBegin", "bluetooth.bleReliableWriteExecute", "bluetooth.bleReliableWriteAbort",
        )
        for (m in bleCentral) put(m, Capability.BLUETOOTH_BLE_CENTRAL)
    }

    /**
     * Review: which capability an event needs, the same lists as `events` in bridge-catalog.json
     * (BridgeCatalogTest keeps them equal). A queued event is purged when the app loses the
     * capability and is dropped on delivery unless the capability is still granted and declared.
     */
    private val eventToCaps: Map<String, Set<Capability>> = buildMap<String, MutableSet<Capability>> {
        fun add(cap: Capability, vararg events: String) {
            for (e in events) getOrPut(e) { mutableSetOf() }.add(cap)
        }
        add(Capability.CONTACTS_READ, "contacts.changed")
        add(Capability.SMS_READ, "sms.received", "sms.mmsReceived", "sms.composeRequested")
        add(Capability.CALLS_CONTROL, "telephony.incoming", "telephony.state", "telephony.ended")
        add(Capability.ALARMS, "alarms.fired")
        add(Capability.NOTIFICATIONS_POST, "notify.action")
        add(Capability.SHARE_RECEIVE, "intents.shareReceived", "intents.viewReceived")
        add(Capability.NFC_READ, "nfc.tag")
        add(Capability.NFC_HCE, "nfc.hceCommand", "nfc.hceDeactivated")
        add(Capability.USB, "usb.disconnected")
        add(Capability.BLUETOOTH_SERIAL, "bluetooth.sppData", "bluetooth.sppConnection")
        add(
            Capability.BLUETOOTH_BLE_CENTRAL,
            "bluetooth.bleScanResult", "bluetooth.bleConnection", "bluetooth.bleCharacteristic", "bluetooth.bleMtu",
            "bluetooth.blePhy", "bluetooth.bleAdapter", "bluetooth.bleBond",
        )
        add(
            Capability.BLUETOOTH_BLE_PERIPHERAL,
            "bluetooth.bleAdvertise", "bluetooth.bleServerConnection", "bluetooth.bleServerService",
            "bluetooth.bleServerReadRequest", "bluetooth.bleServerWriteRequest",
        )
        add(Capability.NOTIFICATIONS_LISTEN, "notifications.posted", "notifications.removed")
        add(Capability.ACCESSIBILITY, "a11y.event")
        add(Capability.CLIPBOARD_WATCH, "clipboard.changed")
        add(Capability.LOCATION_BACKGROUND, "location.fix", "location.geofence")
        add(Capability.NFC_HCE_PAYMENT, "nfc.paymentCommand", "nfc.paymentDeactivated")
        add(Capability.ROLE_BROWSER, "intents.viewReceived")
        add(Capability.ROLE_ASSISTANT, "assist.invoked")
        add(Capability.WALLPAPER, "wallpaper.touch", "wallpaper.offsets")
        add(Capability.IME, "ime.start", "ime.finish")
        add(Capability.CALLS_SCREEN, "calls.screening")
        add(Capability.PIP, "pip.changed")
        add(Capability.SENSORS, "sensors.reading")
        add(Capability.TTS, "speech.done", "speech.error")
        add(Capability.SPEECH_RECOGNIZE, "speech.partial")
        add(Capability.TILES, "tile.clicked")
        add(Capability.NET_LOCAL, "net.found", "net.lost", "net.data", "net.closed")
        add(Capability.BACKGROUND, "background.trigger")
        add(Capability.FOREGROUND, "fgs.stopped")
        add(Capability.MEDIA_SESSION, "media.action")
        add(Capability.PUSH, "push.message", "push.endpointChanged", "push.unregistered")
    }

    /**
     * Events an app may get without any capability. `intents.viewReceived` is listed under
     * `share.receive` and `role.browser`, but a `mailto:` / `web+deft:` link the app declares in
     * `handles` reaches it with neither.
     */
    val UNGATED_EVENTS: Set<String> = setOf("intents.viewReceived")

    fun capabilityFor(method: String): Capability? = methodToCap[method]

    /** The capabilities that let an app receive [event]; empty for an event the catalog does not list. */
    fun capabilitiesForEvent(event: String): Set<Capability> = eventToCaps[event].orEmpty()

    fun allEvents(): Set<String> = eventToCaps.keys

    /** Whether a queued [event] may still reach an app whose granted-and-declared capabilities are [allowed]. */
    fun eventAllowed(event: String, allowed: Set<Capability>): Boolean =
        event in UNGATED_EVENTS || capabilitiesForEvent(event).any { it in allowed }

    fun methodsFor(cap: Capability): List<String> =
        methodToCap.filter { it.value == cap }.keys.sorted()

    fun allMethods(): Set<String> = methodToCap.keys
}
