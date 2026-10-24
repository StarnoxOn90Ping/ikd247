package ani.dantotsu.notifications.airing

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import ani.dantotsu.notifications.subscription.SubscriptionHelper
import ani.dantotsu.settings.saving.PrefManager
import ani.dantotsu.util.Logger
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Schedules a precise alarm for the moment the next episode of a subscribed anime airs.
 * Alarms are plain [AlarmManager] alarms, so they fire even when the app is closed / in Doze.
 *
 * The alarm data is mirrored into preferences so it can be restored after a reboot or an app update.
 */
object AiringAlarmScheduler {

    private const val STORE_KEY = "airing_alarms_v1"
    const val EXTRA_MEDIA_ID = "airing_media_id"
    const val ACTION_AIRING = "ani.dantotsu.action.AIRING_ALARM"

    data class Alarm(
        val mediaId: Int,
        val name: String,
        val image: String?,
        val banner: String?,
        /** The episode that is about to air */
        val episode: Int,
        /** Epoch seconds at which [episode] airs */
        val airingAtSec: Long
    )

    // region store

    @Synchronized
    private fun load(): MutableMap<Int, Alarm> {
        val raw = PrefManager.getCustomVal(STORE_KEY, "")
        val map = mutableMapOf<Int, Alarm>()
        if (raw.isBlank()) return map
        try {
            val arr = JSONArray(raw)
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val alarm = Alarm(
                    mediaId = o.getInt("id"),
                    name = o.getString("name"),
                    image = o.optString("image").takeIf { it.isNotEmpty() },
                    banner = o.optString("banner").takeIf { it.isNotEmpty() },
                    episode = o.getInt("ep"),
                    airingAtSec = o.getLong("at")
                )
                map[alarm.mediaId] = alarm
            }
        } catch (e: Exception) {
            Logger.log(e)
        }
        return map
    }

    @Synchronized
    private fun save(map: Map<Int, Alarm>) {
        val arr = JSONArray()
        map.values.forEach {
            arr.put(
                JSONObject()
                    .put("id", it.mediaId)
                    .put("name", it.name)
                    .put("image", it.image ?: "")
                    .put("banner", it.banner ?: "")
                    .put("ep", it.episode)
                    .put("at", it.airingAtSec)
            )
        }
        PrefManager.setCustomVal(STORE_KEY, arr.toString())
    }

    fun getAlarm(mediaId: Int): Alarm? = load()[mediaId]

    fun removeFromStore(mediaId: Int) {
        val map = load()
        if (map.remove(mediaId) != null) save(map)
    }

    // endregion

    private fun pendingIntent(context: Context, mediaId: Int): PendingIntent {
        val intent = Intent(context, AiringAlarmReceiver::class.java).apply {
            action = ACTION_AIRING
            putExtra(EXTRA_MEDIA_ID, mediaId)
        }
        return PendingIntent.getBroadcast(
            context,
            // keep the request code away from the TaskType ordinals used by the other schedulers
            100_000 + mediaId,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    fun canScheduleExact(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        return am.canScheduleExactAlarms()
    }

    /**
     * Stores and arms the alarm. Returns false when the airing time already passed.
     */
    fun schedule(context: Context, alarm: Alarm): Boolean {
        val triggerAt = alarm.airingAtSec * 1000
        if (triggerAt <= System.currentTimeMillis()) {
            return false
        }
        val map = load()
        map[alarm.mediaId] = alarm
        save(map)
        arm(context, alarm.mediaId, triggerAt)
        Logger.log("AiringAlarm: scheduled ${alarm.name} ep ${alarm.episode} at $triggerAt")
        return true
    }

    private fun arm(context: Context, mediaId: Int, triggerAtMillis: Long) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val pi = pendingIntent(context, mediaId)
        try {
            if (canScheduleExact(context)) {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMillis, pi)
            } else {
                // Still fires while the device is idle, just not to the second.
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMillis, pi)
            }
        } catch (e: SecurityException) {
            Logger.log(e)
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMillis, pi)
        }
    }

    fun cancel(context: Context, mediaId: Int) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        am.cancel(pendingIntent(context, mediaId))
        removeFromStore(mediaId)
        WorkManager.getInstance(context).cancelUniqueWork(AiringSyncWorker.workName(mediaId))
    }

    /**
     * Re-arms every stored alarm (after boot, app update, or when exact alarm permission changes)
     * and asks AniList for fresh data for every subscribed anime.
     */
    fun restoreAll(context: Context) {
        try {
            PrefManager.init(context)
            val now = System.currentTimeMillis()
            val subscribed = SubscriptionHelper.getSubscriptions().filterValues { it.isAnime }
            val stored = load()
            // drop alarms of media we are no longer subscribed to
            stored.keys.filter { !subscribed.containsKey(it) }.forEach { cancel(context, it) }
            subscribed.keys.forEach { id ->
                val alarm = stored[id]
                if (alarm != null && alarm.airingAtSec * 1000 > now) {
                    arm(context, id, alarm.airingAtSec * 1000)
                }
                // always refresh: airing times shift
                enqueueSync(context, id, delayMinutes = 0)
            }
        } catch (e: Exception) {
            Logger.log(e)
        }
    }

    /** Fetches the next airing time from AniList and arms the alarm. */
    fun enqueueSync(context: Context, mediaId: Int, delayMinutes: Long = 0) {
        val request = OneTimeWorkRequestBuilder<AiringSyncWorker>()
            .setInputData(workDataOf(EXTRA_MEDIA_ID to mediaId))
            .setConstraints(
                Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
            )
            .setBackoffCriteria(BackoffPolicy.LINEAR, 2, TimeUnit.MINUTES)
            .apply { if (delayMinutes > 0) setInitialDelay(delayMinutes, TimeUnit.MINUTES) }
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            AiringSyncWorker.workName(mediaId),
            ExistingWorkPolicy.REPLACE,
            request
        )
    }
}
