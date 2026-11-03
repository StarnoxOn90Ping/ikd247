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
 *    "Reconnect" pill; tapping it recreates MainActivity with the full bar
 *  - tapping it while still offline just gives a little shake
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

    /** Called with true when the connection returns, false if it drops again. */
    var onRecoveredChanged: ((Boolean) -> Unit)? = null

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

        startAnimations()
        watchNetwork()
    }

    /** Cancels the animators and unregisters the network callback. Call from onDestroy. */
    fun release() {
        released = true
        stopAnimations()
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
        onRecoveredChanged?.invoke(true)

        nav.offlinePill.setBackgroundResource(R.drawable.bg_online_pill)
        nav.offlinePulse.setBackgroundResource(R.drawable.bg_online_pulse)
        nav.offlineIcon.setImageResource(R.drawable.ic_wifi_on_24)
        ImageViewCompat.setImageTintList(nav.offlineIcon, ColorStateList.valueOf(onlineColor))
        nav.offlineTitle.setTextColor(onlineColor)
        swapTitle(R.string.offline_reconnect_title)
        startAnimations()

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
        onRecoveredChanged?.invoke(false)

        nav.offlinePill.setBackgroundResource(R.drawable.bg_offline_pill)
        nav.offlinePulse.setBackgroundResource(R.drawable.bg_offline_pulse)
        nav.offlineIcon.setImageResource(R.drawable.ic_wifi_off_24)
        ImageViewCompat.setImageTintList(nav.offlineIcon, ColorStateList.valueOf(offlineColor))
        nav.offlineTitle.setTextColor(offlineColor)
        swapTitle(R.string.offline_title)
        startAnimations()
    }

    private fun onTap() {
        if (recovered || isOnline(activity)) {
            // connection is back: rebuild the activity so the full bar + Anime/Manga pages come up
            activity.recreate()
        } else {
            nav.offlinePill.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            shake()
        }
    }

    // ---- animation helpers --------------------------------------------------------------------

    private fun startAnimations() {
        stopAnimations()
        // Expanding ring behind the icon. It starts and ends fully transparent and never grows
        // past the pill's own height (40dp * 1.3 = 52dp < 56dp), so it fades out instead of being cut off.
        pulseAnimator = ObjectAnimator.ofPropertyValuesHolder(
            nav.offlinePulse,
            PropertyValuesHolder.ofFloat(View.SCALE_X, 0.85f, 1.3f),
            PropertyValuesHolder.ofFloat(View.SCALE_Y, 0.85f, 1.3f),
            PropertyValuesHolder.ofFloat(View.ALPHA, 0.7f, 0f)
        ).apply {
            duration = 1800L
            repeatCount = ObjectAnimator.INFINITE
            interpolator = DecelerateInterpolator()
            start()
        }
        // the icon gently breathes
        breatheAnimator = ObjectAnimator.ofFloat(nav.offlineIcon, View.ALPHA, 1f, 0.6f).apply {
            duration = 1400L
            repeatCount = ObjectAnimator.INFINITE
            repeatMode = ObjectAnimator.REVERSE
            interpolator = AccelerateDecelerateInterpolator()
            start()
        }
    }

    private fun stopAnimations() {
        pulseAnimator?.cancel()
        breatheAnimator?.cancel()
        pulseAnimator = null
        breatheAnimator = null
        nav.offlineIcon.alpha = 1f
        nav.offlinePulse.alpha = 0f
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

    /** Cross-fades the title instead of snapping it. */
    private fun swapTitle(resId: Int) {
        val view = nav.offlineTitle
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
