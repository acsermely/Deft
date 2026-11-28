package app.deft.bridge.modules

import android.content.Context
import app.deft.DeftApp
import app.deft.a11y.DeftAccessibilityService
import app.deft.a11y.DeftAccessibilityService.ActResult
import app.deft.bridge.A11ySpec
import app.deft.bridge.BridgeErrors
import app.deft.bridge.BridgeModule
import app.deft.bridge.BridgeOutcome
import app.deft.bridge.BridgeRequest
import app.deft.special.SystemAccess
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

// DEVICE-UNCHECKED (accessibility service): built and unit-tested, but the developer
// has not checked it on a real device yet. Check: an automation sample taps a button in another app; grant screens stay unreadable.
// Remove this note once checked.
/**
 * `a11y.*` (SPECIAL exclusive `accessibility`): read the screen of other apps and act
 * on it through [DeftAccessibilityService]. Only the holder of group `a11y` gets here (the grant
 * check in `BridgeService.invoke`; checked again). None of the methods needs the app on screen:
 * acting on another app means the holder is not in front. Background runs may use them too.
 * Android's permission dialogs and the launcher's grant and confirm screens are refused.
 */
class A11yModule(private val context: Context) : BridgeModule {
    override suspend fun invoke(request: BridgeRequest): BridgeOutcome = withContext(Dispatchers.IO) {
        val holder = DeftApp.instance.repository.specialHolder(A11ySpec.GROUP)
        if (holder != request.webAppId) {
            return@withContext BridgeOutcome.Err(BridgeErrors.NOT_GRANTED, "accessibility is revoked")
        }
        if (!SystemAccess.ACCESSIBILITY.isGranted(context)) {
            return@withContext BridgeOutcome.Err(BridgeErrors.OS_PERMISSION_MISSING, "Accessibility")
        }
        val service = DeftAccessibilityService.instance
            ?: return@withContext BridgeOutcome.Err(BridgeErrors.UNSUPPORTED, "Accessibility is starting; try again in a moment")
        when (request.method) {
            "a11y.windowTree" -> when (val a = A11ySpec.parseTree(request.args)) {
                is A11ySpec.Parsed.Bad -> bad(a.message)
                is A11ySpec.Parsed.Ok -> when (val t = service.windowTree(request.webAppId, a.value)) {
                    is DeftAccessibilityService.Tree.Ok -> BridgeOutcome.Ok(t.json, metadata = """{"count":${t.count}}""")
                    DeftAccessibilityService.Tree.Blocked -> blocked()
                    DeftAccessibilityService.Tree.NoWindow -> BridgeOutcome.Err(BridgeErrors.UNSUPPORTED, "No window to read right now")
                }
            }
            "a11y.perform" -> when (val a = A11ySpec.parsePerform(request.args)) {
                is A11ySpec.Parsed.Bad -> bad(a.message)
                is A11ySpec.Parsed.Ok -> when (service.perform(request.webAppId, a.value)) {
                    ActResult.OK -> BridgeOutcome.Ok(buildJsonObject { put("performed", true) })
                    ActResult.FAILED -> BridgeOutcome.Ok(buildJsonObject { put("performed", false) })
                    ActResult.STALE -> bad("That node is from an older a11y.windowTree; read the tree again")
                    ActResult.GONE -> bad("That node is no longer on screen")
                    ActResult.BLOCKED -> blocked()
                }
            }
            "a11y.gesture" -> when (val a = A11ySpec.parseGesture(request.args)) {
                is A11ySpec.Parsed.Bad -> bad(a.message)
                is A11ySpec.Parsed.Ok -> when (val done = service.gesture(a.value)) {
                    null -> blocked()
                    else -> BridgeOutcome.Ok(buildJsonObject { put("completed", done) })
                }
            }
            "a11y.global" -> when (val a = A11ySpec.parseGlobal(request.args)) {
                is A11ySpec.Parsed.Bad -> bad(a.message)
                is A11ySpec.Parsed.Ok -> BridgeOutcome.Ok(buildJsonObject { put("performed", service.global(a.value)) })
            }
            else -> BridgeOutcome.Err(BridgeErrors.UNSUPPORTED, request.method)
        }
    }

    private fun bad(message: String) = BridgeOutcome.Err(BridgeErrors.INVALID_ARGS, message)

    private fun blocked() = BridgeOutcome.Err(
        BridgeErrors.UNSUPPORTED,
        "This screen asks the user to allow or confirm something; apps cannot read it or act on it",
    )
}
