package ani.kiwami.home

import android.animation.ObjectAnimator
import android.animation.PropertyValuesHolder
import android.app.Dialog
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.view.HapticFeedbackConstants
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.view.animation.LinearInterpolator
import android.view.animation.OvershootInterpolator
import androidx.appcompat.app.AppCompatActivity
import ani.kiwami.R
import ani.kiwami.databinding.DialogOfflineLaunchBinding
import ani.kiwami.isOnline
import ani.kiwami.util.setPressBounce

/**
 * Shown when the app is launched with no internet:
 *  - Try again       -> re-checks the connection; if it is back the activity is recreated normally
 *  - Pre-cached home -> closes the popup, leaving the cached Home + "Offline" pill underneath
 *  - Offline mode    -> same as above, plus [onOfflineMode] (MainActivity shows the offline banner)
 */
class OfflineLaunchDialog(
    private val activity: AppCompatActivity,
    private val onOfflineMode: () -> Unit
) {

    private var dialog: Dialog? = null
    private val animators = mutableListOf<ObjectAnimator>()
    private var spinner: ObjectAnimator? = null
    private var checking = false

    fun show() {
        if (activity.isFinishing || activity.isDestroyed) return
        val b = DialogOfflineLaunchBinding.inflate(LayoutInflater.from(activity))
        val d = Dialog(activity).apply {
            setContentView(b.root)
            setCancelable(true) // back / outside tap == "Pre-cached home" (it is already behind us)
            window?.apply {
                setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
                setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                setDimAmount(0.6f)
            }
            setOnDismissListener { stopAnimations() }
        }
        dialog = d

        b.offlineTryAgain.setPressBounce(0.95f)
        b.offlineCachedOption.setPressBounce(0.95f)
        b.offlineDownloadsOption.setPressBounce(0.95f)

        b.offlineTryAgain.setOnClickListener { tryAgain(b) }
        b.offlineCachedOption.setOnClickListener { d.dismiss() }
        b.offlineDownloadsOption.setOnClickListener {
            d.dismiss()
            onOfflineMode()
        }

        // entrance: card rises with a little overshoot
        b.offlineCard.apply {
            alpha = 0f
            scaleX = 0.88f
            scaleY = 0.88f
            translationY = 40f * resources.displayMetrics.density
        }
        d.show()
        b.offlineCard.animate()
            .alpha(1f).scaleX(1f).scaleY(1f).translationY(0f)
            .setDuration(420L)
            .setInterpolator(OvershootInterpolator(1.2f))
            .start()

        startAnimations(b)
    }

    fun dismiss() {
        stopAnimations()
        runCatching { dialog?.dismiss() }
        dialog = null
    }

    // ---- animations ---------------------------------------------------------------------------

    private fun startAnimations(b: DialogOfflineLaunchBinding) {
        // Two staggered rings. They grow from the disc's size (104dp) to 1.45x (~151dp), which still
        // fits the 160dp icon box, and fade out completely before reaching its edge.
        listOf(b.offlineRingOne to 0L, b.offlineRingTwo to 900L).forEach { (ring, delay) ->
            ring.alpha = 0f
            animators += ObjectAnimator.ofPropertyValuesHolder(
                ring,
                PropertyValuesHolder.ofFloat(View.SCALE_X, 1f, 1.45f),
                PropertyValuesHolder.ofFloat(View.SCALE_Y, 1f, 1.45f),
                PropertyValuesHolder.ofFloat(View.ALPHA, 0.65f, 0f)
            ).apply {
                duration = 1800L
                startDelay = delay
                repeatCount = ObjectAnimator.INFINITE
                interpolator = DecelerateInterpolator()
                start()
            }
        }
        // the disc + icon gently breathe
        animators += ObjectAnimator.ofPropertyValuesHolder(
            b.offlineIconDisc,
            PropertyValuesHolder.ofFloat(View.SCALE_X, 1f, 1.06f),
            PropertyValuesHolder.ofFloat(View.SCALE_Y, 1f, 1.06f)
        ).apply {
            duration = 1500L
            repeatCount = ObjectAnimator.INFINITE
            repeatMode = ObjectAnimator.REVERSE
            interpolator = AccelerateDecelerateInterpolator()
            start()
        }
        animators += ObjectAnimator.ofFloat(b.offlineDialogIcon, View.ALPHA, 1f, 0.65f).apply {
            duration = 1500L
            repeatCount = ObjectAnimator.INFINITE
            repeatMode = ObjectAnimator.REVERSE
            interpolator = AccelerateDecelerateInterpolator()
            start()
        }
    }

    private fun stopAnimations() {
        animators.forEach { it.cancel() }
        animators.clear()
        spinner?.cancel()
        spinner = null
    }

    // ---- try again ----------------------------------------------------------------------------

    private fun tryAgain(b: DialogOfflineLaunchBinding) {
        if (checking) return
        checking = true
        b.offlineTryAgainText.setText(R.string.offline_dialog_checking)
        // the refresh icon spins while we "check"
        spinner = ObjectAnimator.ofFloat(b.offlineTryAgainIcon, View.ROTATION, 0f, 360f).apply {
            duration = 700L
            repeatCount = ObjectAnimator.INFINITE
            interpolator = LinearInterpolator()
            start()
        }
        b.offlineTryAgain.postDelayed({
            checking = false
            spinner?.cancel()
            spinner = null
            b.offlineTryAgainIcon.rotation = 0f
            if (activity.isFinishing || activity.isDestroyed) return@postDelayed
            if (isOnline(activity)) {
                dismiss()
                // rebuild so the full bar + Anime/Manga pages come up
                activity.recreate()
            } else {
                b.offlineTryAgain.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                b.offlineTryAgainText.setText(R.string.offline_dialog_still_offline)
                shake(b.offlineCard)
                b.offlineTryAgain.postDelayed({
                    b.offlineTryAgainText.setText(R.string.offline_dialog_try_again)
                }, 1500L)
            }
        }, 900L)
    }

    /** Medium-speed shake; the card has room to swing because nothing above it clips. */
    private fun shake(v: View) {
        val d = 10f * v.resources.displayMetrics.density
        ObjectAnimator.ofFloat(
            v, View.TRANSLATION_X, 0f, -d, d, -d * 0.75f, d * 0.75f, -d * 0.4f, d * 0.4f, 0f
        ).apply {
            duration = 750L
            interpolator = AccelerateDecelerateInterpolator()
            start()
        }
    }
}
