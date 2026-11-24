package app.deft.background

import android.content.Context
import app.deft.DeftApp
import app.deft.bridge.BridgeService
import app.deft.catalog.Capability
import app.deft.catalog.isGranted
import app.deft.data.AppRepository
import app.deft.data.InstalledWebApp

/**
 * PARITY D2: finds the apps a trigger wakes and hands it to [BackgroundRunner.trigger]. Apps
 * that do not declare the trigger or lack the `background` grant are skipped here, so a system
 * event does not write an audit row for every installed app; the runner checks again anyway.
 */
object BackgroundWake {
    suspend fun candidate(repository: AppRepository, app: InstalledWebApp): BackgroundTriggers.Candidate {
        val declares = Capability.BACKGROUND in app.requiredCapabilities
        return BackgroundTriggers.Candidate(
            webAppId = app.id,
            declaresCapability = declares,
            granted = declares && repository.grantLevel(app.id, Capability.BACKGROUND).isGranted(),
            triggers = if (declares) repository.backgroundFor(app)?.triggers.orEmpty() else emptyList(),
        )
    }

    /** The apps [name] wakes; with [only], just that app (the SMS or phone holder, an alarm's owner). */
    suspend fun targets(repository: AppRepository, name: String, only: String? = null): List<String> {
        val apps = if (only != null) listOfNotNull(repository.getApp(only)) else repository.allApps()
        val candidates = apps
            .filter { Capability.BACKGROUND in it.requiredCapabilities }
            .map { candidate(repository, it) }
        return BackgroundTriggers.targets(candidates, name)
    }

    /**
     * For receivers outside [BridgeService]: wakes the service if needed (see
     * [BridgeService.backgroundTrigger]) and triggers each app [name] wakes.
     */
    suspend fun wake(context: Context, name: String, payloadJson: String, only: String? = null) {
        val repository = DeftApp.instance.repository
        for (id in targets(repository, name, only)) {
            BridgeService.backgroundTrigger(context, id, name, payloadJson)
        }
    }
}
