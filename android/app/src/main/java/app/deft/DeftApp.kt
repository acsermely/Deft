package app.deft

import android.app.Application
import android.content.res.Configuration
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import app.deft.agent.AgentSession
import app.deft.config.ConfigStore
import app.deft.data.AppDatabase
import app.deft.data.AppRepository
import app.deft.ui.home.HomeCanvasStore
import app.deft.ui.home.NativeDrawer
import app.deft.workspace.AgentWorkspace
import app.deft.workspace.BundledApps
import app.deft.workspace.MyAppsStore
import app.deft.workspace.StorageMigration
import java.io.File

class DeftApp : Application() {
    lateinit var database: AppDatabase
        private set
    lateinit var repository: AppRepository
        private set
    lateinit var configStore: ConfigStore
        private set
    lateinit var myApps: MyAppsStore
        private set
    lateinit var homeCanvas: HomeCanvasStore
        private set
    lateinit var agentWorkspace: AgentWorkspace
        private set

    /** Night bit of the uiMode last seen; only a flip of this bit can change a resolved "auto" appearance. */
    private var lastNightMode = -1

    /**
     * Appearance "auto" is resolved against the OS at load time, so an OS light/dark flip needs a reload.
     * Other configuration changes (rotation, font scale, locale) do not, so they are ignored.
     */
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        val night = newConfig.uiMode and Configuration.UI_MODE_NIGHT_MASK
        if (night == lastNightMode) return
        lastNightMode = night
        if (!::configStore.isInitialized) return
        if (configStore.config.value.theme.appearance == "auto") configStore.reload()
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        lastNightMode = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
        // Lets the accessibility service know when a grant or confirm screen shows.
        app.deft.a11y.A11yGuard.install(this)
        // Step totals keep counting after a reboot or a process death (a no-op until an app asked once).
        app.deft.bridge.StepStore.get(this).start()
        database = Room.databaseBuilder(this, AppDatabase::class.java, "deft.db")
            .addMigrations(
                AppDatabase.MIGRATION_1_2,
                AppDatabase.MIGRATION_2_3,
                AppDatabase.MIGRATION_3_4,
                AppDatabase.MIGRATION_4_5,
                AppDatabase.MIGRATION_5_6,
                AppDatabase.MIGRATION_6_7,
                AppDatabase.MIGRATION_7_8,
                AppDatabase.MIGRATION_8_9,
                AppDatabase.MIGRATION_9_10,
                AppDatabase.MIGRATION_10_11,
                AppDatabase.MIGRATION_11_12,
            )
            .addCallback(object : RoomDatabase.Callback() {
                // A brand-new database never held URL apps, so MIGRATION_6_7's notice does not apply.
                override fun onCreate(db: SupportSQLiteDatabase) {
                    StorageMigration.markUrlAppsNoticeSeen(this@DeftApp)
                }
            })
            .build()
        repository = AppRepository(database)
        app.deft.webview.LocalOriginPathHandler.assets = assets
        configStore = ConfigStore(this).also { it.start() }
        // Accent from wallpaper (8.3): re-resolve when the system wallpaper colours change, without a restart.
        // The callback can fire repeatedly, so it is debounced and the reload runs off the main thread.
        if (android.os.Build.VERSION.SDK_INT >= 27) {
            val main = android.os.Handler(android.os.Looper.getMainLooper())
            val reload = Runnable {
                if (configStore.config.value.theme.accent == app.deft.config.ThemeSettings.WALLPAPER) {
                    Thread { runCatching { configStore.reload() } }.start()
                }
            }
            android.app.WallpaperManager.getInstance(this).addOnColorsChangedListener(
                { _, _ ->
                    main.removeCallbacks(reload)
                    main.postDelayed(reload, 500)
                },
                main,
            )
        }
        // Frame-time probe (8.4): about 1 s after launch, off the critical path. Reloads only if the tier changed.
        app.deft.device.DeviceTierProbe.schedule(this) { configStore.reload() }
        Thread { runCatching { app.deft.agent.AgentKeyStore.importProvisioned(this) } }.start()
        StorageMigration.run(this)
        myApps = MyAppsStore(this)
        BundledApps.sync(this, repository)
        Thread { runCatching { app.deft.workspace.DepStore.sweep(this) } }.start()
        kotlinx.coroutines.runBlocking {
            repository.migrateLegacyIds()
            repository.migrateFirstUseGrants()
        }
        homeCanvas = HomeCanvasStore(StorageMigration.canvasRoot(this)).also { it.ensureSeeded() }
        agentWorkspace = AgentWorkspace(File(filesDir, "config/agent.recents"))
        AgentSession.syncPreviewSlug()
        NativeDrawer.warm(this)
        // Daily "still want this?" review of special access (30-day reminder, 90-day removal).
        runCatching { app.deft.special.SpecialReviewRunner.ensureScheduled(this) }
        Thread {
            runCatching { kotlinx.coroutines.runBlocking { app.deft.special.SpecialReviewRunner.runCheck(this@DeftApp) } }
        }.start()
        // Catch up on `periodic:N` work for manifests that changed while no hook ran
        // (bundled apps after an update, overlay edits).
        Thread {
            runCatching {
                kotlinx.coroutines.runBlocking {
                    app.deft.background.PeriodicTriggers.syncAll(this@DeftApp, repository)
                }
            }
        }.start()
        // Geofences are proximity alerts, which an update or a force stop drops.
        Thread {
            runCatching {
                kotlinx.coroutines.runBlocking {
                    app.deft.location.BackgroundLocation.restoreGeofences(this@DeftApp, repository)
                }
            }
        }.start()
        // "Open with" aliases follow the installed apps' `handles` (bundled apps after an update, overlay edits).
        app.deft.ui.handlers.HandlerAliases.syncSoon(this)
        // Role components follow the special role grants.
        app.deft.roles.RoleComponents.syncSoon(this)
    }

    companion object {
        lateinit var instance: DeftApp
            private set
    }
}
