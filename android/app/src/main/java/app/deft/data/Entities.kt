package app.deft.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "web_apps")
data class WebAppEntity(
    @PrimaryKey val id: String,
    val name: String,
    val startUrl: String,
    val origin: String,
    val manifestUrl: String,
    val requiredCapabilitiesJson: String,
    val filesAllAccess: Boolean = false,
    val filesTreeUri: String? = null,
    val confirmBiometric: Boolean = false,
    val workspaceSlug: String? = null,
    val pinned: Boolean = false,
    val lastOpenedAt: Long? = null,
    val catalogHash: String? = null,
    val tier: String = "local",
    val originId: String? = null,
)

@Entity(tableName = "alarms")
data class AlarmEntity(
    @PrimaryKey val id: String,
    val webAppId: String,
    val triggerAt: Long,
    val title: String,
    val body: String,
    val url: String?,
)

/**
 * Network origins the user allowed (see [NetworkConsent]): on the consent screen
 * ([NetworkConsent.SOURCE_MANIFEST]) or typed on the dashboard ([NetworkConsent.SOURCE_USER]),
 * and embed origins approved or refused one by one ([EmbedConsent]).
 */
@Entity(tableName = "network_consent", primaryKeys = ["webAppId", "origin", "source"])
data class NetworkConsentEntity(
    val webAppId: String,
    val origin: String,
    @ColumnInfo(defaultValue = "manifest") val source: String = NetworkConsent.SOURCE_MANIFEST,
)

@Entity(tableName = "grants", primaryKeys = ["webAppId", "capability"])
data class GrantEntity(
    val webAppId: String,
    val capability: String,
    val level: String,
    /** When this row was last written or kept; drives the special-access review. */
    @ColumnInfo(defaultValue = "0") val grantedAt: Long = System.currentTimeMillis(),
    /**
     * Special rows only: the user turned the grant off (dashboard, or the review's removal). A DENIED
     * row without this flag is "asks later" (the consent screen, install), and a bridge call may open the sheet.
     */
    @ColumnInfo(defaultValue = "0") val deniedByUser: Boolean = false,
)

@Entity(tableName = "usage_log")
data class UsageLogEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val timestamp: Long,
    val webAppId: String?,
    val source: String,
    val capability: String,
    val method: String?,
    val outcome: String,
    val metadata: String?,
)

@Entity(tableName = "pending_events")
data class PendingEventEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val webAppId: String,
    val event: String,
    val payloadJson: String,
    val timestamp: Long,
)

@Entity(tableName = "recent_launches")
data class RecentLaunchEntity(
    @PrimaryKey val key: String,
    val kind: String,
    val webAppId: String?,
    val packageName: String?,
    val activityName: String?,
    val label: String,
    val launchedAt: Long,
) {
    companion object {
        const val KIND_WEB = "web"
        const val KIND_NATIVE = "native"
        const val KEEP = 8

        fun webKey(webAppId: String) = "web:$webAppId"
        fun nativeKey(packageName: String, activityName: String) = "native:$packageName/$activityName"
    }
}
