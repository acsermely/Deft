package app.deft.data

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        WebAppEntity::class,
        GrantEntity::class,
        UsageLogEntity::class,
        PendingEventEntity::class,
        RecentLaunchEntity::class,
        AlarmEntity::class,
        NetworkConsentEntity::class,
    ],
    version = 11,
    exportSchema = false,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun webApps(): WebAppDao
    abstract fun grants(): GrantDao
    abstract fun usageLog(): UsageLogDao
    abstract fun pendingEvents(): PendingEventDao
    abstract fun recentLaunches(): RecentLaunchDao
    abstract fun alarms(): AlarmDao
    abstract fun networkConsent(): NetworkConsentDao

    companion object {
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS recent_launches (
                        `key` TEXT NOT NULL PRIMARY KEY,
                        kind TEXT NOT NULL,
                        webAppId TEXT,
                        packageName TEXT,
                        activityName TEXT,
                        label TEXT NOT NULL,
                        launchedAt INTEGER NOT NULL
                    )
                    """.trimIndent(),
                )
            }
        }

        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE web_apps ADD COLUMN filesTreeUri TEXT")
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS alarms (
                        id TEXT NOT NULL PRIMARY KEY,
                        webAppId TEXT NOT NULL,
                        triggerAt INTEGER NOT NULL,
                        title TEXT NOT NULL,
                        body TEXT NOT NULL,
                        url TEXT
                    )
                    """.trimIndent(),
                )
            }
        }

        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE web_apps ADD COLUMN confirmBiometric INTEGER NOT NULL DEFAULT 0",
                )
            }
        }

        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE web_apps ADD COLUMN workspaceSlug TEXT")
                db.execSQL("ALTER TABLE web_apps ADD COLUMN pinned INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE web_apps ADD COLUMN lastOpenedAt INTEGER")
            }
        }
        val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE web_apps ADD COLUMN catalogHash TEXT")
            }
        }

        val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE web_apps ADD COLUMN tier TEXT NOT NULL DEFAULT 'local'")
                db.execSQL("ALTER TABLE web_apps ADD COLUMN originId TEXT")
                db.execSQL(
                    """
                    DELETE FROM grants WHERE webAppId IN (
                        SELECT id FROM web_apps
                        WHERE workspaceSlug IS NULL
                          AND origin NOT LIKE '%.myapps.local'
                          AND origin NOT LIKE '%canvas.local'
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    """
                    DELETE FROM pending_events WHERE webAppId IN (
                        SELECT id FROM web_apps
                        WHERE workspaceSlug IS NULL
                          AND origin NOT LIKE '%.myapps.local'
                          AND origin NOT LIKE '%canvas.local'
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    """
                    DELETE FROM alarms WHERE webAppId IN (
                        SELECT id FROM web_apps
                        WHERE workspaceSlug IS NULL
                          AND origin NOT LIKE '%.myapps.local'
                          AND origin NOT LIKE '%canvas.local'
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    """
                    DELETE FROM recent_launches WHERE webAppId IN (
                        SELECT id FROM web_apps
                        WHERE workspaceSlug IS NULL
                          AND origin NOT LIKE '%.myapps.local'
                          AND origin NOT LIKE '%canvas.local'
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    """
                    DELETE FROM web_apps
                    WHERE workspaceSlug IS NULL
                      AND origin NOT LIKE '%.myapps.local'
                      AND origin NOT LIKE '%canvas.local'
                    """.trimIndent(),
                )
            }
        }

        /** PARITY H4: consented network origins. Existing apps are marked legacy and adopt their manifest once. */
        val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS network_consent (
                        webAppId TEXT NOT NULL,
                        origin TEXT NOT NULL,
                        PRIMARY KEY(webAppId, origin)
                    )
                    """.trimIndent(),
                )
                db.execSQL("INSERT OR IGNORE INTO network_consent (webAppId, origin) SELECT id, '*legacy*' FROM web_apps")
            }
        }

        /** PARITY G0: grant time for the special-access review; existing rows count from the upgrade. */
        val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE grants ADD COLUMN grantedAt INTEGER NOT NULL DEFAULT 0")
                db.execSQL("UPDATE grants SET grantedAt = ?", arrayOf<Any>(System.currentTimeMillis()))
            }
        }

        /**
         * PARITY P3: `source` tells consent-screen rows from servers the user typed. It joins the
         * primary key so the same origin can be both; SQLite cannot change a key in place.
         */
        val MIGRATION_9_10 = object : Migration(9, 10) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS network_consent_new (
                        webAppId TEXT NOT NULL,
                        origin TEXT NOT NULL,
                        source TEXT NOT NULL DEFAULT 'manifest',
                        PRIMARY KEY(webAppId, origin, source)
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    "INSERT OR IGNORE INTO network_consent_new (webAppId, origin, source) " +
                        "SELECT webAppId, origin, 'manifest' FROM network_consent",
                )
                db.execSQL("DROP TABLE network_consent")
                db.execSQL("ALTER TABLE network_consent_new RENAME TO network_consent")
            }
        }

        /**
         * PARITY G0: a special row the user turned off (the dashboard's "Remove", audited as `special` / `revoked`)
         * is told apart from the "asks later" row the consent screen leaves, so a bridge call can ask again only
         * for the latter. Existing rows are marked from that audit entry.
         */
        val MIGRATION_10_11 = object : Migration(10, 11) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE grants ADD COLUMN deniedByUser INTEGER NOT NULL DEFAULT 0")
                db.execSQL(
                    """
                    UPDATE grants SET deniedByUser = 1 WHERE level = 'DENIED' AND EXISTS (
                        SELECT 1 FROM usage_log u WHERE u.webAppId = grants.webAppId AND u.capability = grants.capability
                            AND u.source = 'special' AND u.outcome IN ('revoked', 'removed')
                    )
                    """.trimIndent(),
                )
            }
        }
    }
}
