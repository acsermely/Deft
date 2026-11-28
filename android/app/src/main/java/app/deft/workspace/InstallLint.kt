package app.deft.workspace

import android.content.Context
import app.deft.webview.DeftLintEngine
import app.deft.webview.DeftLintFormat

/**
 * Lint in memory before anything is written. A lint error is an install
 * error and nothing on disk changes. Same rule the dashboard applies to signed packages.
 */
object InstallLint {
    suspend fun requireClean(context: Context, prepared: MyAppsStore.PreparedInstall) {
        val raw = DeftLintEngine.run(context, prepared.textFiles(), "app", packageBytes = prepared.verified.caps.packageBytes)
        val errors = runCatching { DeftLintFormat.parse(raw) }.getOrNull().orEmpty().filter { it.severity == "error" }
        if (errors.isNotEmpty()) {
            throw IllegalArgumentException("Not installed: ${errors.size} lint error(s)\n" + DeftLintFormat.lines(errors, 10))
        }
    }
}
