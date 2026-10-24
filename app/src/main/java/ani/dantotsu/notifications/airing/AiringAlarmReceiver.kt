package ani.dantotsu.notifications.airing

import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import ani.dantotsu.MainActivity
import ani.dantotsu.R
import ani.dantotsu.hasNotificationPermission
import ani.dantotsu.notifications.subscription.SubscriptionHelper
import ani.dantotsu.notifications.subscription.SubscriptionStore
import ani.dantotsu.settings.saving.PrefManager
import ani.dantotsu.settings.saving.PrefName
import ani.dantotsu.util.Logger
import eu.kanade.tachiyomi.data.notification.Notifications.CHANNEL_AIRING
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Fired by [android.app.AlarmManager] the moment the countdown of a subscribed anime hits zero.
 * Works while the app is closed: the manifest-registered receiver wakes the process up.
 */
class AiringAlarmReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        val mediaId = intent?.getIntExtra(AiringAlarmScheduler.EXTRA_MEDIA_ID, -1) ?: -1
        if (mediaId < 0) return
        val appContext = context.applicationContext
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                PrefManager.init(appContext)
                handle(appContext, mediaId)
            } catch (e: Exception) {
                Logger.log(e)
            } finally {
                pending.finish()
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun handle(context: Context, mediaId: Int) {
        val alarm = AiringAlarmScheduler.getAlarm(mediaId) ?: return
        val subscription = SubscriptionHelper.getSubscriptions()[mediaId]
        if (subscription == null) {
            AiringAlarmScheduler.removeFromStore(mediaId)
            return
        }
        // consumed, so a late restoreAll() won't fire it a second time
        AiringAlarmScheduler.removeFromStore(mediaId)

        val text = context.getString(R.string.airing_notification_text, alarm.episode)

        // keep it in the in-app subscription feed as well
        val store = PrefManager.getNullableVal<List<SubscriptionStore>>(
            PrefName.SubscriptionNotificationStore, null
        )?.toMutableList() ?: mutableListOf()
        if (store.size >= 100) store.remove(store.minByOrNull { it.time })
        if (store.none { it.title == alarm.name && it.content == text }) {
            store.add(
                SubscriptionStore(
                    alarm.name, text, alarm.mediaId,
                    image = alarm.image, banner = alarm.banner
                )
            )
            PrefManager.setVal(PrefName.SubscriptionNotificationStore, store)
            PrefManager.setVal(
                PrefName.UnreadSubscriptionNotifications,
                PrefManager.getVal<Int>(PrefName.UnreadSubscriptionNotifications) + 1
            )
        }

        if (hasNotificationPermission(context)) {
            ensureChannel(context)
            val builder = NotificationCompat.Builder(context, CHANNEL_AIRING)
                .setSmallIcon(R.drawable.ic_round_movie_filter_24)
                .setContentTitle(alarm.name)
                .setContentText(text)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setCategory(NotificationCompat.CATEGORY_RECOMMENDATION)
                .setContentIntent(contentIntent(context, alarm))
                .setAutoCancel(true)
            alarm.image?.let { loadBitmap(it) }?.let { builder.setLargeIcon(it) }
            NotificationManagerCompat.from(context).notify(
                CHANNEL_AIRING,
                alarm.mediaId,
                builder.build()
            )
        } else {
            Logger.log("AiringAlarmReceiver: notification permission missing")
        }

        // Line up the following episode (AniList needs a moment to roll over, the worker retries).
        AiringAlarmScheduler.enqueueSync(context, mediaId, delayMinutes = 2)
    }

    private fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (nm.getNotificationChannel(CHANNEL_AIRING) == null) {
                nm.createNotificationChannel(
                    NotificationChannel(
                        CHANNEL_AIRING,
                        context.getString(R.string.airing_channel_name),
                        NotificationManager.IMPORTANCE_HIGH
                    )
                )
            }
        }
    }

    private fun contentIntent(context: Context, alarm: AiringAlarmScheduler.Alarm): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            putExtra("mediaId", alarm.mediaId)
            putExtra("media", alarm.mediaId)
            putExtra("mediaType", "ANIME")
            putExtra("continue", true)
            action = "SUBSCRIPTION_${alarm.mediaId}"
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        return PendingIntent.getActivity(
            context,
            alarm.mediaId,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
    }

    private fun loadBitmap(url: String): Bitmap? = try {
        java.net.URL(url).openStream().use { BitmapFactory.decodeStream(it) }
    } catch (e: Exception) {
        null
    }
}
