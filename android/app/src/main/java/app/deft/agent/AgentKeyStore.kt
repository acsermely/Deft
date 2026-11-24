package app.deft.agent

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKeys

object AgentKeyStore {
    private const val PREFS = "agent_secrets"
    private const val KEY = "openrouter_api_key"
    private val lock = Any()
    @Volatile private var cached: SharedPreferences? = null

    private fun prefs(context: Context): SharedPreferences {
        cached?.let { return it }
        synchronized(lock) {
            cached?.let { return it }
            val created = EncryptedSharedPreferences.create(
                PREFS,
                MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC),
                context.applicationContext,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
            )
            cached = created
            return created
        }
    }

    fun get(context: Context): String? =
        prefs(context).getString(KEY, null)?.takeIf { it.isNotBlank() }

    fun set(context: Context, key: String) {
        prefs(context).edit().putString(KEY, key.trim()).apply()
    }

    fun clear(context: Context) {
        prefs(context).edit().remove(KEY).apply()
    }

    /** Debug builds: `scripts/push-agent-key.sh` drops the key here over adb. */
    const val IMPORT_FILE = "agent-key.import"

    /**
     * Debug builds only: moves a key pushed by `scripts/push-agent-key.sh` into encrypted storage and
     * deletes the plain file. The encrypted copy is bound to this install's hardware key, so a
     * reinstall (or a build signed with another debug key) needs the key pushed again.
     */
    fun importProvisioned(context: Context) {
        if (!app.deft.BuildConfig.DEBUG) return
        val file = java.io.File(context.filesDir, IMPORT_FILE)
        if (!file.isFile) return
        try {
            val key = file.readText().trim()
            if (key.isNotEmpty() && key.length <= 512 && key.none { it.isWhitespace() }) set(context, key)
        } finally {
            file.delete()
        }
    }
}
