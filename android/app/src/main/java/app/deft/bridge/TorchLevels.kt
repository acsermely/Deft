package app.deft.bridge

/** The strength a `torch.set` call asks for. */
object TorchLevels {
    /**
     * A missing level means the strongest light. A level that is not a whole number from 1 to
     * [max] gives null, which the caller reports as invalid args.
     */
    fun clamp(level: String?, max: Int): Int? {
        if (level == null) return max
        val n = level.toIntOrNull() ?: return null
        return n.takeIf { it in 1..max }
    }
}
