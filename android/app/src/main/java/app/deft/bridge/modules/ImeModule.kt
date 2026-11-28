package app.deft.bridge.modules

import app.deft.bridge.BridgeErrors
import app.deft.bridge.BridgeModule
import app.deft.bridge.BridgeOutcome
import app.deft.bridge.BridgeRequest
import app.deft.catalog.Capability
import app.deft.ime.DeftInputMethodService
import app.deft.ime.ImeSpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

// DEVICE-UNCHECKED (extra roles (browser, assistant, IME, wallpaper, device admin)): built and unit-tested, but the developer
// has not checked it on a real device yet. Check: each role can be granted to one origin and reverted in system settings.
// Remove this note once checked.
/**
 * `ime.*` (SPECIAL exclusive `ime`): the keyboard page types into the focused field
 * of another app. Only the page the launcher's keyboard shows gets here (`BridgeService.invoke`
 * refuses `ime.*` from any other page; checked again below), and only while a normal field is
 * focused: in password fields the launcher's own keyboard shows and every typing call fails.
 * Nothing typed is logged.
 */
class ImeModule : BridgeModule {
    override suspend fun invoke(request: BridgeRequest): BridgeOutcome {
        if (request.runCapability != Capability.IME) {
            return BridgeOutcome.Err(BridgeErrors.UNSUPPORTED, "Only the app's keyboard page can do this")
        }
        val args = request.args
        // Arguments first, off the main thread.
        val text = if (request.method == "ime.commitText" || request.method == "ime.setComposing") {
            ImeSpec.textArg(args) ?: return invalid("text must be a string of at most ${ImeSpec.TEXT_MAX} characters")
        } else {
            null
        }
        val count = if (request.method == "ime.deleteBefore") {
            ImeSpec.deleteCount(args) ?: return invalid("n must be a whole number from 1 to ${ImeSpec.DELETE_MAX}")
        } else {
            0
        }
        val key = if (request.method == "ime.sendKey") {
            ImeSpec.keyCode((args["key"] as? JsonPrimitive)?.content)
                ?: return invalid("key must be one of ${ImeSpec.KEYS.keys.joinToString(", ")}")
        } else {
            0
        }
        val height = if (request.method == "ime.setHeight") {
            ImeSpec.heightDp(args)
                ?: return invalid("dp must be a whole number from ${ImeSpec.HEIGHT_MIN_DP} to ${ImeSpec.HEIGHT_MAX_DP}")
        } else {
            0
        }
        return withContext(Dispatchers.Main) {
            val ime = DeftInputMethodService.forPage(request.webAppId)
                ?: return@withContext BridgeOutcome.Err(BridgeErrors.UNSUPPORTED, "The keyboard is not showing this app")
            val refused: String? = when (request.method) {
                "ime.commitText" -> ime.commitText(text!!)
                "ime.setComposing" -> ime.setComposing(text!!)
                "ime.finishComposing" -> ime.finishComposing()
                "ime.deleteBefore" -> ime.deleteBefore(count)
                "ime.sendKey" -> ime.sendKey(key)
                "ime.hide" -> ime.hide()
                "ime.switchKeyboard" -> return@withContext BridgeOutcome.Ok(
                    buildJsonObject { put("switched", JsonPrimitive(ime.switchFromPage())) },
                )
                "ime.setHeight" -> {
                    ime.setHeight(height)
                    return@withContext BridgeOutcome.Ok(buildJsonObject { put("dp", JsonPrimitive(height)) })
                }
                else -> return@withContext BridgeOutcome.Err(BridgeErrors.UNSUPPORTED, request.method)
            }
            if (refused != null) BridgeOutcome.Err(BridgeErrors.UNSUPPORTED, refused) else BridgeOutcome.Ok()
        }
    }

    private fun invalid(message: String) = BridgeOutcome.Err(BridgeErrors.INVALID_ARGS, message)
}
