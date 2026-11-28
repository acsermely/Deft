package app.deft.alarms

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import app.deft.data.AlarmEntity
import app.deft.special.SpecialAccessFlow
import java.util.UUID

object AlarmScheduler {
    fun schedule(context: Context, alarm: AlarmEntity): Boolean {
        val am = context.getSystemService(AlarmManager::class.java) ?: return false
        val intent = AlarmReceiver.intent(context, alarm.webAppId, alarm.id)
        val pi = pendingIntent(context, alarm.id, intent)
        val exact = canScheduleExact(am)
        if (exact) {
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, alarm.triggerAt, pi)
        } else {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, alarm.triggerAt, pi)
        }
        return exact
    }

    fun canScheduleExact(am: AlarmManager): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            am.canScheduleExactAlarms()
        } else {
            true
        }
    }

    /** The exact-alarm screen for this app (the app's own page when that screen is missing). */
    fun requestExactAlarmAccess(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        val am = context.getSystemService(AlarmManager::class.java) ?: return
        if (am.canScheduleExactAlarms()) return
        SpecialAccessFlow.start(context, SpecialAccessFlow.exactAlarmIntents(context))
    }

    fun cancel(context: Context, alarmId: String) {
        val am = context.getSystemService(AlarmManager::class.java) ?: return
        val intent = Intent(context, AlarmReceiver::class.java)
        val pi = pendingIntent(context, alarmId, intent)
        am.cancel(pi)
        pi.cancel()
    }

    fun requestCode(alarmId: String): Int {
        return runCatching {
            val uuid = UUID.fromString(alarmId)
            (uuid.mostSignificantBits xor uuid.leastSignificantBits).toInt()
        }.getOrDefault(alarmId.hashCode())
    }

    private fun pendingIntent(context: Context, alarmId: String, intent: Intent): PendingIntent =
        PendingIntent.getBroadcast(
            context,
            requestCode(alarmId),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
}
