package app.deft.bridge

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WidgetEventsTest {
    @Test
    fun passiveEventsReachAWidget() {
        for (e in listOf("net.data", "net.closed", "bluetooth.bleCharacteristic", "sensors.reading", "contacts.changed", "location.fix")) {
            assertTrue(e, WidgetEvents.allowed(e))
        }
    }

    @Test
    fun inboundAndUserActionEventsNeverReachAWidget() {
        for (e in listOf(
            "sms.received", "sms.mmsReceived", "telephony.incoming", "alarms.fired", "notify.action", "notifications.posted",
            "push.message", "tile.clicked", "intents.shareReceived", "intents.viewReceived", "nfc.tag", "a11y.event",
            "clipboard.changed", "background.trigger", "assist.invoked", "calls.screening", "ime.start", "media.action",
        )) {
            assertFalse(e, WidgetEvents.allowed(e))
        }
    }
}
