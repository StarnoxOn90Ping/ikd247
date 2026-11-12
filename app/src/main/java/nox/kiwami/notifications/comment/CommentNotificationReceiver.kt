package nox.kiwami.notifications.comment

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import nox.kiwami.notifications.AlarmManagerScheduler
import nox.kiwami.notifications.TaskScheduler
import nox.kiwami.settings.saving.PrefManager
import nox.kiwami.settings.saving.PrefName
import nox.kiwami.util.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class CommentNotificationReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        Logger.log("CommentNotificationReceiver: onReceive")
        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                CommentNotificationTask().execute(context)
                val commentInterval =
                    CommentNotificationWorker.checkIntervals[PrefManager.getVal(PrefName.CommentNotificationInterval)]
                AlarmManagerScheduler(context).scheduleRepeatingTask(
                    TaskScheduler.TaskType.COMMENT_NOTIFICATION,
                    commentInterval
                )
            } catch (e: Exception) {
                Logger.log(e)
            } finally {
                pendingResult.finish()
            }
        }
    }
}
