package ani.kiwami.notifications.subscription

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

class SubscriptionNotificationReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        Logger.log("SubscriptionNotificationReceiver: onReceive")
        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                SubscriptionNotificationTask().execute(context)
            } catch (e: Exception) {
                Logger.log(e)
            } finally {
                try {
                    val subscriptionInterval =
                        SubscriptionNotificationWorker.checkIntervals[PrefManager.getVal(PrefName.SubscriptionNotificationInterval)]
                    AlarmManagerScheduler(context).scheduleRepeatingTask(
                        TaskScheduler.TaskType.SUBSCRIPTION_NOTIFICATION,
                        subscriptionInterval
                    )
                } catch (e: Exception) {
                    Logger.log(e)
                }
                pendingResult.finish()
            }
        }
    }
}
