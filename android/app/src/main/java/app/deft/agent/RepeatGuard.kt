package app.deft.agent

/**
 * Catches a turn that keeps re-running one tool call whose answer does not change: `apps.verify` after edits that do
 * not touch the finding, or `apps.debug.clear` → `apps.verify` cycles. A call is the tool name plus its arguments; a
 * repeat is the same call returning the same result as the last time it ran in this turn. Calls in between (edits,
 * other reads) do not reset it, because they did not change the answer.
 *
 * The [noteAt]th identical result gets a note appended so the model sees retrying is pointless; the [stopAt]th ends
 * the turn.
 */
internal class RepeatGuard(private val noteAt: Int = 2, private val stopAt: Int = 4) {
    sealed interface Verdict {
        data object Ok : Verdict
        data class Note(val text: String) : Verdict
        data class Stop(val text: String) : Verdict
    }

    private class Seen(val result: String, var times: Int)

    private val seen = HashMap<String, Seen>()

    fun check(name: String, args: String, result: String): Verdict {
        val key = name + '\u0000' + normalize(args)
        val prev = seen[key]
        val times = if (prev != null && prev.result == result) prev.times + 1 else 1
        seen[key] = Seen(result, times)
        return when {
            times >= stopAt -> Verdict.Stop(stopText(name, times))
            times >= noteAt -> Verdict.Note(noteText(name, times))
            else -> Verdict.Ok
        }
    }

    private fun normalize(args: String): String = args.filterNot { it.isWhitespace() }.ifEmpty { "{}" }

    companion object {
        fun noteText(name: String, times: Int): String =
            "note: $name returned exactly this result $times times in a row this turn; calling it again will not " +
                "change it. Clearing logs does not change apps.verify. If your edits are not changing a finding, " +
                "the cause is not in the code you edited (it may be in the runtime or the launcher): stop retrying, " +
                "make a different change, or tell the user what still fails."

        fun stopText(name: String, times: Int): String =
            "I stopped: `$name` gave the same result $times times in a row, so retrying would not help. " +
                "The findings above are still open; tell me how to continue."
    }
}
