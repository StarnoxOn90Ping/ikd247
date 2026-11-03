package ani.dantotsu.home

import android.animation.ObjectAnimator
import android.animation.PropertyValuesHolder
import android.app.Dialog
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.view.HapticFeedbackConstants
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import androidx.appcompat.app.AppCompatActivity
import ani.dantotsu.R
import ani.dantotsu.databinding.DialogOfflineLaunchBinding
import ani.dantotsu.download.hub.DownloadsActivity
import ani.dantotsu.isOnline
import ani.dantotsu.util.setPressBounce

/**
 * Shown when the app is launched with no internet:
 *  - Try again       -> re-checks the connection; if it is back the activity is recreated normally
 *  - Pre-cached home -> closes the popup, leaving the cached Home + "Offline" pill underneath
 *  - Offline mode    -> closes the popup and opens the Downloads page
 */
class OfflineLaunchDialog(private val activity: AppCompatActivity) {

    private var dialog: Dialog? = null
    private var pulse: ObjectAnimator? = null
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
            setOnDismissListener { stopPulse() }
        }
        dialog = d

        b.offlineTryAgain.setPressBounce(0.95f)
        b.offlineCachedOption.setPressBounce(0.96f)
        b.offlineDownloadsOption.setPressBounce(0.96f)

        b.offlineTryAgain.setOnClickListener { tryAgain(b) }
        b.offlineCachedOption.setOnClickListener { d.dismiss() }
        b.offlineDownloadsOption.setOnClickListener {
            d.dismiss()
            activity.startActivity(Intent(activity, DownloadsActivity::class.java))
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

        pulse = ObjectAnimator.ofPropertyValuesHolder(
            b.offlineDialogPulse,
            PropertyValuesHolder.ofFloat(View.SCALE_X, 0.7f, 1.25f),
            PropertyValuesHolder.ofFloat(View.SCALE_Y, 0.7f, 1.25f),
            PropertyValuesHolder.ofFloat(View.ALPHA, 0.7f, 0f)
        ).apply {
            duration = 1800L
            repeatCount = ObjectAnimator.INFINITE
            interpolator = DecelerateInterpolator()
            start()
        }
    }

    fun dismiss() {
        stopPulse()
        runCatching { dialog?.dismiss() }
        dialog = null
    }

    private fun stopPulse() {
        pulse?.cancel()
        pulse = null
    }

    private fun tryAgain(b: DialogOfflineLaunchBinding) {
        if (checking) return
        checking = true
        b.offlineTryAgain.setText(R.string.offline_dialog_checking)
        b.offlineTryAgain.postDelayed({
            checking = false
            if (activity.isFinishing || activity.isDestroyed) return@postDelayed
            if (isOnline(activity)) {
                dismiss()
                // rebuild so the full bar + Anime/Manga pages come up
                activity.recreate()
            } else {
                b.offlineTryAgain.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                b.offlineTryAgain.setText(R.string.offline_dialog_still_offline)
                shake(b.offlineCard)
                b.offlineTryAgain.postDelayed({
                    b.offlineTryAgain.setText(R.string.offline_dialog_try_again)
                }, 1400L)
            }
        }, 700L)
    }

    private fun shake(v: View) {
        val d = 8f * v.resources.displayMetrics.density
        ObjectAnimator.ofFloat(
            v, View.TRANSLATION_X, 0f, -d, d, -d * 0.7f, d * 0.7f, -d * 0.35f, 0f
        ).apply { duration = 380L; start() }
    }
}
