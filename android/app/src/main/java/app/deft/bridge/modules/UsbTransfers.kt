package app.deft.bridge.modules

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull

// DEVICE-UNCHECKED (USB transfers (claim/bulk/control/interrupt/release)): built and unit-tested, but the developer
// has not checked it on a real device yet. Check: talk to an Arduino or serial adapter over CDC-ACM from a sample.
// Remove this note once checked.
/**
 * Argument rules for USB transfers, kept free of Android types so they can be tested.
 * Shapes follow WebUSB: OUT transfers carry `base64`, IN transfers ask for a `length`.
 */
object UsbTransfers {
    const val MAX_TRANSFER = 64 * 1024
    const val DEFAULT_TIMEOUT_MS = 1_000
    const val MAX_TIMEOUT_MS = 10_000

    /** USB endpoint address bit 7: set = IN (device to host). */
    fun isInEndpoint(address: Int): Boolean = address and 0x80 != 0

    /** bmRequestType bit 7 decides the control transfer's direction. */
    fun isInRequest(requestType: Int): Boolean = requestType and 0x80 != 0

    sealed interface Plan {
        data class Out(val bytes: ByteArray) : Plan
        data class In(val length: Int) : Plan
    }

    /** Decides OUT (`base64` given) or IN (`length` given) and enforces the size limit. */
    fun plan(args: JsonObject, decode: (String) -> ByteArray?): Result<Plan> {
        val b64 = (args["base64"] as? JsonPrimitive)?.contentOrNull
        val length = (args["length"] as? JsonPrimitive)?.intOrNull
        return when {
            b64 != null && length != null -> Result.failure(IllegalArgumentException("give base64 (OUT) or length (IN), not both"))
            b64 != null -> {
                val bytes = decode(b64) ?: return Result.failure(IllegalArgumentException("base64 is not valid"))
                if (bytes.size > MAX_TRANSFER) Result.failure(IllegalArgumentException("transfer larger than $MAX_TRANSFER bytes"))
                else Result.success(Plan.Out(bytes))
            }
            length != null ->
                if (length !in 0..MAX_TRANSFER) Result.failure(IllegalArgumentException("length must be 0..$MAX_TRANSFER"))
                else Result.success(Plan.In(length))
            else -> Result.failure(IllegalArgumentException("base64 (OUT) or length (IN) required"))
        }
    }

    fun timeout(args: JsonObject): Int =
        ((args["timeout"] as? JsonPrimitive)?.intOrNull ?: DEFAULT_TIMEOUT_MS).coerceIn(0, MAX_TIMEOUT_MS)

    fun int(args: JsonObject, key: String, range: IntRange): Int? =
        (args[key] as? JsonPrimitive)?.intOrNull?.takeIf { it in range }
}
