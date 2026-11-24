package app.deft.bridge

/** PARITY F5b: argument rules for `dnd.*`, free of Android types so they can be unit tested. */
object DndSpec {
    /** The interruption filters an app may name for `dnd.set`, as the method spells them. */
    val FILTERS: Set<String> = setOf("all", "priority", "alarms", "none")
}
