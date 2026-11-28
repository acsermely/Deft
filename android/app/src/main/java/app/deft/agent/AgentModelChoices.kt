package app.deft.agent

/** The models the agent offers. The agent talks only to OpenRouter, so this list is the whole picker. */
object AgentModelChoices {
    data class Choice(val id: String, val label: String, val free: Boolean)

    const val FREE_MODEL = "nvidia/nemotron-3.5-super-vl:free"
    const val DEFAULT_MODEL = "z-ai/glm-5.3-flash"

    val ALL = listOf(
        Choice(FREE_MODEL, "Nemotron 3.5 Super VL", free = true),
        Choice("z-ai/glm-5.3", "GLM 5.3", free = false),
        Choice(DEFAULT_MODEL, "GLM 5.3 Flash", free = false),
    )

    /** OpenRouter's `reasoning.effort` values, lowest first. */
    val EFFORTS = listOf("minimal", "low", "medium", "high")
    const val DEFAULT_EFFORT = "minimal"

    const val FREE_TRAINING_NOTE = "Free model: your prompts may be used for training."
    const val FREE_TRAINING_NOTE_SHORT = "Prompts may be used for training"

    fun find(id: String): Choice? = ALL.find { it.id == id }

    fun isFree(id: String): Boolean = id.endsWith(":free")

    /** The model a newly signed-in account starts on: the free one if it never bought credits. */
    fun defaultFor(freeTier: Boolean): String = if (freeTier) FREE_MODEL else DEFAULT_MODEL
}
