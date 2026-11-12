package nox.kiwami.notifications.subscription

import nox.kiwami.others.calc.CalcActivity
import nox.kiwami.settings.saving.PrefManager
import nox.kiwami.settings.saving.PrefName

object SubscriptionAppLockHelper {
    fun isAppLocked(): Boolean {
        return PrefManager.getVal<String>(PrefName.AppPassword).isNotEmpty() && !CalcActivity.hasPermission
    }
}