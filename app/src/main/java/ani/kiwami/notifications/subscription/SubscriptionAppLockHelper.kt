package ani.kiwami.notifications.subscription

import ani.kiwami.others.calc.CalcActivity
import ani.kiwami.settings.saving.PrefManager
import ani.kiwami.settings.saving.PrefName

object SubscriptionAppLockHelper {
    fun isAppLocked(): Boolean {
        return PrefManager.getVal<String>(PrefName.AppPassword).isNotEmpty() && !CalcActivity.hasPermission
    }
}