package app.deft.bridge

/**
 * W2: the events a home-widget frame may receive. Only passive, self-started ones: results of what the widget
 * itself opened (sockets, radios, sensors) and data that changed. Anything the user acts on or that carries
 * inbound content (sms, calls, notification actions, alarms, shares, tiles, pushes, NFC) reaches the app's own
 * page or background run, never a frame on the Home screen. A widget copy is live only; it is never queued
 * and never takes a queued event.
 */
object WidgetEvents {
    private val prefixes = listOf("net.", "bluetooth.", "usb.")
    private val names = setOf("sensors.reading", "contacts.changed", "location.fix")

    fun allowed(event: String): Boolean = event in names || prefixes.any { event.startsWith(it) }
}
