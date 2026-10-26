package ani.dantotsu.home

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ObjectAnimator
import android.animation.PropertyValuesHolder
import android.content.res.ColorStateList
import android.net.ConnectivityManager
import android.net.Network
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.widget.ImageViewCompat
import ani.dantotsu.R
import ani.dantotsu.databinding.ItemNavbarBinding
import ani.dantotsu.isOnline
import ani.dantotsu.util.setPressBounce

/**
 * The pill that stands in for the "Anime | Home | Manga" bar when the app is launched without internet.
 *
 *  - amber, softly pulsing "Offline" badge (Home keeps showing its cached content)
 *  - if the connection returns while the app is still in offline mode, it morphs into a green
 *    "Back online · Tap to reload" pill; tapping it recreates MainActivity with the full bar
 *  - tapping it while still offline gives a little shake instead
 */
class OfflinePill(
    private val activity: AppCompatActivity,
    private val nav: ItemNavbarBinding
) {
    private val density = activity.resources.displayMetrics.density
    private val offlineColor = ContextCompat.getColor(activity, R.color.offline_accent)
    private val onlineColor = ContextCompat.getColor(activity, R.color.online_accent)

    private var pulseAnimator: ObjectAnimator? = null
    private var breatheAnimator: ObjectAnimator? = null
    private var callback: ConnectivityManager.NetworkCallback? = null
    private var recovered = false
    private var released = false

    fun show() {
        val pill = nav.offlinePill
        pill.visibility = View.VISIBLE
        pill.setPressBounce(0.94f)
        pill.setOnClickListener { onTap() }

        // entrance: rises from below with a springy overshoot
        pill.alpha = 0f
        pill.scaleX = 0.6f
        pill.scaleY = 0.6f
        pill.translationY = 24f * density
        pill.animate()
            .alpha(1f).scaleX(1f).scaleY(1f).translationY(0f)
            .setStartDelay(150L)
            .setDuration(480L)
            .setInterpolator(OvershootInterpolator(1.4f))
            .withEndAction { pill.animate().setStartDelay(0L) }
            .start()

        startOfflineAnimations()
        watchNetwork()
    }

    /** Cancels the animators and unregisters the network callback. Call from onDestroy. */
    fun release() {
        released = true
        stopOfflineAnimations()
        nav.offlinePill.animate().cancel()
        callback?.let {
            runCatching {
                activity.getSystemService(ConnectivityManager::class.java)?.unregisterNetworkCallback(it)
            }
        }
        callback = null
    }

    // ---- states -------------------------------------------------------------------------------

    private fun markRecovered() {
        if (released || recovered) return
        recovered = true
        stopOfflineAnimations()
        nav.offlinePulse.alpha = 0f

        nav.offlinePill.setBackgroundResource(R.drawable.bg_online_pill)
        nav.offlineIcon.setImageResource(R.drawable.ic_wifi_on_24)
        ImageViewCompat.setImageTintList(nav.offlineIcon, ColorStateList.valueOf(onlineColor))
        nav.offlineTitle.setTextColor(onlineColor)
        swapText(nav.offlineTitle, R.string.offline_back_title)
        swapText(nav.offlineSubtitle, R.string.offline_back_subtitle)

        // happy little pop
        nav.offlinePill.animate().setStartDelay(0L).scaleX(1.08f).scaleY(1.08f).setDuration(140L)
            .withEndAction {
                nav.offlinePill.animate().setStartDelay(0L).scaleX(1f).scaleY(1f).setDuration(320L)
                    .setInterpolator(OvershootInterpolator(3f)).start()
            }.start()
    }

    private fun markOffline() {
        if (released || !recovered) return
        recovered = false

        nav.offlinePill.setBackgroundResource(R.drawable.bg_offline_pill)
        nav.offlineIcon.setImageResource(R.drawable.ic_wifi_off_24)
        ImageViewCompat.setImageTintList(nav.offlineIcon, ColorStateList.valueOf(offlineColor))
        nav.offlineTitle.setTextColor(offlineColor)
        swapText(nav.offlineTitle, R.string.offline_title)
        swapText(nav.offlineSubtitle, R.string.offline_subtitle)
        startOfflineAnimations()
    }

    private fun onTap() {
        if (recovered || isOnline(activity)) {
            // connection is back: rebuild the activity so the full bar + Anime/Manga pages come up
            activity.recreate()
        } else {
            nav.offlinePill.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            shake()
            swapText(nav.offlineSubtitle, R.string.offline_still_subtitle)
            nav.offlinePill.postDelayed({
                if (!released && !recovered) swapText(nav.offlineSubtitle, R.string.offline_subtitle)
            }, 1600L)
        }
    }

    // ---- animation helpers --------------------------------------------------------------------

    private fun startOfflineAnimations() {
        stopOfflineAnimations()
        // expanding ring behind the icon
        pulseAnimator = ObjectAnimator.ofPropertyValuesHolder(
            nav.offlinePulse,
            PropertyValuesHolder.ofFloat(View.SCALE_X, 0.7f, 1.7f),
            PropertyValuesHolder.ofFloat(View.SCALE_Y, 0.7f, 1.7f),
            PropertyValuesHolder.ofFloat(View.ALPHA, 0.65f, 0f)
        ).apply {
            duration = 1800L
            repeatCount = ObjectAnimator.INFINITE
            interpolator = DecelerateInterpolator()
            start()
        }
        // the icon gently breathes
        breatheAnimator = ObjectAnimator.ofFloat(nav.offlineIcon, View.ALPHA, 1f, 0.55f).apply {
            duration = 1400L
            repeatCount = ObjectAnimator.INFINITE
            repeatMode = ObjectAnimator.REVERSE
            interpolator = AccelerateDecelerateInterpolator()
            start()
        }
    }

    private fun stopOfflineAnimations() {
        pulseAnimator?.cancel()
        breatheAnimator?.cancel()
        pulseAnimator = null
        breatheAnimator = null
        nav.offlineIcon.alpha = 1f
    }

    private fun shake() {
        val d = 8f * density
        ObjectAnimator.ofFloat(
            nav.offlinePill, View.TRANSLATION_X, 0f, -d, d, -d * 0.7f, d * 0.7f, -d * 0.35f, 0f
        ).apply {
            duration = 380L
            start()
        }
    }

    /** Cross-fades a text change instead of snapping it. */
    private fun swapText(view: TextView, resId: Int) {
        val text = activity.getString(resId)
        if (view.text.toString() == text) return
        view.animate().setListener(null).cancel()
        view.animate().alpha(0f).setDuration(110L).setListener(object : AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: Animator) {
                view.animate().setListener(null)
                view.text = text
                view.animate().alpha(1f).setDuration(160L).start()
            }
        }).start()
    }

    // ---- connectivity -------------------------------------------------------------------------

    private fun watchNetwork() {
        val cm = activity.getSystemService(ConnectivityManager::class.java) ?: return
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                // capabilities need a moment to settle before isOnline() reports true
                nav.offlinePill.postDelayed({
                    if (!released && isOnline(activity)) markRecovered()
                }, 500L)
            }

            override fun onLost(network: Network) {
                nav.offlinePill.post {
                    if (!released && !isOnline(activity)) markOffline()
                }
            }
        }
        runCatching { cm.registerDefaultNetworkCallback(cb) }.onSuccess { callback = cb }
    }
}
