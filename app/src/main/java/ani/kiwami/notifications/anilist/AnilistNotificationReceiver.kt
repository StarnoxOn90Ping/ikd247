package ani.kiwami.notifications.anilist

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import ani.kiwami.notifications.AlarmManagerScheduler
import ani.kiwami.notifications.TaskScheduler
import ani.kiwami.settings.saving.PrefManager
import ani.kiwami.settings.saving.PrefName
import ani.kiwami.util.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class AnilistNotificationReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        Logger.log("AnilistNotificationReceiver: onReceive")
        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                AnilistNotificationTask().execute(context)
                val anilistInterval =
                    AnilistNotificationWorker.checkIntervals[PrefManager.getVal(PrefName.AnilistNotificationInterval)]
                AlarmManagerScheduler(context).scheduleRepeatingTask(
                    TaskScheduler.TaskType.ANILIST_NOTIFICATION,
                    anilistInterval
                )
            } catch (e: Exception) {
                Logger.log(e)
            } finally {
                pendingResult.finish()
            }
        }
    }
}
