package app.deft.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface WebAppDao {
    @Query("SELECT * FROM web_apps ORDER BY name")
    fun observeAll(): Flow<List<WebAppEntity>>

    @Query("SELECT * FROM web_apps ORDER BY name")
    suspend fun all(): List<WebAppEntity>

    @Query("SELECT * FROM web_apps WHERE id = :id")
    suspend fun get(id: String): WebAppEntity?

    @Query("SELECT * FROM web_apps WHERE origin = :origin LIMIT 1")
    suspend fun byOrigin(origin: String): WebAppEntity?

    @Query("SELECT * FROM web_apps WHERE workspaceSlug = :slug LIMIT 1")
    suspend fun bySlug(slug: String): WebAppEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(app: WebAppEntity)

    @Query("DELETE FROM web_apps WHERE id = :id")
    suspend fun delete(id: String)
}

@Dao
interface GrantDao {
    @Query("SELECT * FROM grants WHERE webAppId = :webAppId")
    fun observeForApp(webAppId: String): Flow<List<GrantEntity>>

    @Query("SELECT * FROM grants")
    fun observeAll(): Flow<List<GrantEntity>>

    @Query("SELECT * FROM grants WHERE webAppId = :webAppId")
    suspend fun forApp(webAppId: String): List<GrantEntity>

    @Query("SELECT * FROM grants WHERE capability = :capability AND level != 'DENIED'")
    suspend fun holdersOf(capability: String): List<GrantEntity>

    @Query("SELECT * FROM grants WHERE webAppId = :webAppId AND capability = :capability")
    suspend fun get(webAppId: String, capability: String): GrantEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(grant: GrantEntity)

    @Query("UPDATE grants SET level = 'DENIED' WHERE webAppId = :webAppId AND capability = :capability")
    suspend fun deny(webAppId: String, capability: String)

    @Query("DELETE FROM grants WHERE webAppId = :webAppId AND capability = :capability")
    suspend fun delete(webAppId: String, capability: String)

    /** Rows from the removed first-use prompt: ASK was a consent tick, so it is a grant. */
    @Query("UPDATE grants SET level = 'WHILE_USING' WHERE level = 'ASK'")
    suspend fun askToGranted()

    /** "Only this time" no longer exists; the consent screen asks again for these. */
    @Query("DELETE FROM grants WHERE level = 'ONCE'")
    suspend fun deleteOnce()

    @Query("DELETE FROM grants WHERE webAppId = :webAppId")
    suspend fun deleteForApp(webAppId: String)

    @Query("SELECT * FROM grants WHERE level != 'DENIED'")
    suspend fun allGranted(): List<GrantEntity>

    /** PARITY G0: "Keep" on the review reminder restarts the unused clock. */
    @Query("UPDATE grants SET grantedAt = :at WHERE webAppId = :webAppId AND capability = :capability")
    suspend fun touch(webAppId: String, capability: String, at: Long)
}

@Dao
interface NetworkConsentDao {
    /** Consent-screen rows only (manifest origins and markers); user servers are separate. */
    @Query("SELECT origin FROM network_consent WHERE webAppId = :webAppId AND source = 'manifest'")
    suspend fun forApp(webAppId: String): List<String>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(rows: List<NetworkConsentEntity>)

    /** Every row of the app, user servers included (uninstall). */
    @Query("DELETE FROM network_consent WHERE webAppId = :webAppId")
    suspend fun deleteForApp(webAppId: String)

    @Query("DELETE FROM network_consent WHERE webAppId = :webAppId AND source = 'manifest'")
    suspend fun deleteManifestRows(webAppId: String)

    /** PARITY P3: servers the user typed on the dashboard, oldest first. */
    @Query("SELECT origin FROM network_consent WHERE webAppId = :webAppId AND source = 'user' ORDER BY rowid")
    suspend fun userFor(webAppId: String): List<String>

    @Query("DELETE FROM network_consent WHERE webAppId = :webAppId AND origin = :origin AND source = 'user'")
    suspend fun deleteUser(webAppId: String, origin: String)

    /** PARITY G14: embed decisions ([EmbedConsent.SOURCE_APPROVED] / [EmbedConsent.SOURCE_REFUSED]). */
    @Query("SELECT origin FROM network_consent WHERE webAppId = :webAppId AND source = :source ORDER BY rowid")
    suspend fun bySource(webAppId: String, source: String): List<String>

    @Query("DELETE FROM network_consent WHERE webAppId = :webAppId AND source = :source")
    suspend fun deleteSource(webAppId: String, source: String)
}

@Dao
interface UsageLogDao {
    @Query("SELECT * FROM usage_log ORDER BY timestamp DESC LIMIT :limit")
    fun observeRecent(limit: Int = 200): Flow<List<UsageLogEntity>>

    @Insert
    suspend fun insert(row: UsageLogEntity)

    /** PARITY G0: last successful use of a capability by an app, for the special-access review. */
    @Query("SELECT MAX(timestamp) FROM usage_log WHERE webAppId = :webAppId AND capability = :capability AND outcome = 'ok'")
    suspend fun lastOk(webAppId: String, capability: String): Long?

    /** PARITY D1: the app's recent rows for one method, e.g. `background.run`. */
    @Query("SELECT * FROM usage_log WHERE webAppId = :webAppId AND method = :method ORDER BY timestamp DESC LIMIT :limit")
    fun observeForMethod(webAppId: String, method: String, limit: Int): Flow<List<UsageLogEntity>>

    /** PARITY D1: start times are not stored, so the hourly limit counts finished runs. */
    @Query("SELECT timestamp FROM usage_log WHERE webAppId = :webAppId AND method = :method AND timestamp >= :since AND outcome IN (:outcomes)")
    suspend fun timestampsSince(webAppId: String, method: String, since: Long, outcomes: List<String>): List<Long>
}

@Dao
interface PendingEventDao {
    @Query("SELECT * FROM pending_events WHERE webAppId = :webAppId ORDER BY timestamp ASC")
    suspend fun forApp(webAppId: String): List<PendingEventEntity>

    @Insert
    suspend fun insert(row: PendingEventEntity): Long

    @Query("DELETE FROM pending_events WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("DELETE FROM pending_events WHERE id IN (:ids)")
    suspend fun deleteIds(ids: List<Long>)

    @Query("DELETE FROM pending_events WHERE webAppId = :webAppId")
    suspend fun deleteForApp(webAppId: String)

    @Query(
        """
        DELETE FROM pending_events WHERE id IN (
            SELECT id FROM pending_events WHERE webAppId = :webAppId ORDER BY timestamp DESC LIMIT -1 OFFSET :keep
        )
        """,
    )
    suspend fun trim(webAppId: String, keep: Int)
}

@Dao
interface RecentLaunchDao {
    @Query("SELECT * FROM recent_launches ORDER BY launchedAt DESC LIMIT :limit")
    fun observe(limit: Int = 4): Flow<List<RecentLaunchEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(row: RecentLaunchEntity)

    @Query(
        """
        DELETE FROM recent_launches WHERE `key` NOT IN (
            SELECT `key` FROM recent_launches ORDER BY launchedAt DESC LIMIT :keep
        )
        """,
    )
    suspend fun trim(keep: Int)

    @Query("DELETE FROM recent_launches WHERE webAppId = :webAppId")
    suspend fun deleteWeb(webAppId: String)
}

@Dao
interface AlarmDao {
    @Query("SELECT * FROM alarms WHERE webAppId = :webAppId ORDER BY triggerAt ASC")
    suspend fun forApp(webAppId: String): List<AlarmEntity>

    @Query("SELECT * FROM alarms WHERE id = :id")
    suspend fun get(id: String): AlarmEntity?

    @Query("SELECT * FROM alarms ORDER BY triggerAt ASC")
    suspend fun all(): List<AlarmEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(row: AlarmEntity)

    @Query("DELETE FROM alarms WHERE id = :id")
    suspend fun delete(id: String)

    @Query("DELETE FROM alarms WHERE webAppId = :webAppId")
    suspend fun deleteForApp(webAppId: String)
}
