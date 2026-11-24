package app.deft.bridge.modules

import android.content.Context
import android.content.SharedPreferences
import android.util.Base64
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKeys
import app.deft.bridge.BridgeErrors
import app.deft.bridge.BridgeModule
import app.deft.bridge.BridgeOutcome
import app.deft.bridge.BridgeRequest
import app.deft.bridge.DeviceIdSpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonPrimitive
import java.security.SecureRandom

/**
 * `device.id` (PARITY G7): a per-origin id derived from the launcher's secret. Works in the
 * background as well; it does not need the app on screen.
 */
class DeviceModule(private val context: Context) : BridgeModule {
    override suspend fun invoke(request: BridgeRequest): BridgeOutcome = when (request.method) {
        "device.id" -> withContext(Dispatchers.IO) {
            BridgeOutcome.Ok(JsonPrimitive(DeviceIdSpec.derive(LauncherSecret.get(context), request.origin)))
        }
        else -> BridgeOutcome.Err(BridgeErrors.UNSUPPORTED, request.method)
    }
}

/**
 * The launcher's 32-byte secret for [DeviceIdSpec]. Created once, on first use, and kept in
 * encrypted preferences. It never leaves this object.
 */
internal object LauncherSecret {
    private const val PREFS = "launcher_secret"
    private const val KEY = "secret"
    private const val SIZE = 32
    private val lock = Any()
    @Volatile private var cached: ByteArray? = null

    fun get(context: Context): ByteArray {
        cached?.let { return it.copyOf() }
        synchronized(lock) {
            cached?.let { return it.copyOf() }
            val prefs = prefs(context)
            val stored = prefs.getString(KEY, null)
                ?.let { Base64.decode(it, Base64.NO_WRAP) }
                ?.takeIf { it.size == SIZE }
            val secret = stored ?: ByteArray(SIZE).also { SecureRandom().nextBytes(it) }.also { fresh ->
                // commit, not apply: the id must not change if the process dies right after this.
                prefs.edit().putString(KEY, Base64.encodeToString(fresh, Base64.NO_WRAP)).commit()
            }
            cached = secret
            return secret.copyOf()
        }
    }

    private fun prefs(context: Context): SharedPreferences = EncryptedSharedPreferences.create(
        PREFS,
        MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC),
        context.applicationContext,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
    )
}
