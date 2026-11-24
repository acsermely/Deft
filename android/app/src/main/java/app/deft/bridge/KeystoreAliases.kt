package app.deft.bridge

/**
 * PARITY F2: the key names an app may use and how they map to names in the Android Keystore.
 * Free of Android types so it can be unit tested.
 */
object KeystoreAliases {
    const val MAX_LENGTH = 64
    const val MAX_VALIDITY_SECONDS = 3_600

    /** The key algorithms an app may name for `keystore.generate`. */
    val ALGORITHMS: Set<String> = setOf("EC_P256", "AES_256")

    private val LOCAL = Regex("[A-Za-z0-9_-]{1,$MAX_LENGTH}")

    /** The app's own name for a key, or null when it is not 1 to 64 letters, digits, `_` or `-`. */
    fun localAlias(raw: String?): String? = raw?.takeIf { LOCAL.matches(it) }

    /** The name a key has in the Android Keystore. The owner prefix keeps one app's keys away from another's. */
    fun keystoreAlias(ownerId: String, alias: String): String = "deft.$ownerId.$alias"

    /**
     * The app's name for a Keystore entry, or null when the entry belongs to another owner. A
     * local name cannot hold a dot, so an owner whose id is a dotted extension of another's
     * never matches the shorter owner's entries.
     */
    fun localName(ownerId: String, keystoreAlias: String): String? {
        val prefix = "deft.$ownerId."
        if (!keystoreAlias.startsWith(prefix)) return null
        return localAlias(keystoreAlias.removePrefix(prefix))
    }

    /** Review: the Keystore entries of [ownerId] among [keystoreAliases], to delete on uninstall. */
    fun ownedBy(ownerId: String, keystoreAliases: Iterable<String>): List<String> =
        keystoreAliases.filter { localName(ownerId, it) != null }

    /** Seconds a biometric unlock stays valid. Missing means every use asks; 0 is the same. Null when not a whole number from 0 to the maximum. */
    fun validity(raw: String?): Int? {
        if (raw == null) return 0
        return raw.toIntOrNull()?.takeIf { it in 0..MAX_VALIDITY_SECONDS }
    }

    /** How a key is locked: not at all, asked on every use, or unlocked for a while after one check. */
    enum class Lock { NONE, PER_USE, TIMED }

    /**
     * Review: from the key's `KeyInfo`. A per-use key (validity 0, or -1 for old-style keys) must
     * be given to the prompt before every use; its cipher initialises fine and only fails at the
     * end, so it cannot be "tried first" like a timed key.
     */
    fun lock(authRequired: Boolean, validitySeconds: Int): Lock = when {
        !authRequired -> Lock.NONE
        validitySeconds <= 0 -> Lock.PER_USE
        else -> Lock.TIMED
    }
}
