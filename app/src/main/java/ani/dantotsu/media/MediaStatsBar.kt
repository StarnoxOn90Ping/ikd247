package ani.dantotsu.media

import android.util.TypedValue
import android.view.View
import androidx.core.view.doOnLayout
import ani.dantotsu.R
import ani.dantotsu.databinding.ActivityMediaBinding
import java.util.Locale

private fun compact(n: Int?): String = when {
    n == null -> "~"
    n >= 1_000_000 -> String.format(Locale.US, "%.1fM", n / 1_000_000.0).replace(".0M", "M")
    n >= 1_000 -> String.format(Locale.US, "%.1fK", n / 1_000.0).replace(".0K", "K")
    else -> n.toString()
}

private fun duration(minutes: Int?): String = when {
    minutes == null || minutes <= 0 -> "~"
    minutes >= 60 -> "${minutes / 60}h" + (if (minutes % 60 > 0) " ${minutes % 60}m" else "")
    else -> "${minutes}m"
}

private fun remaining(seconds: Long): String {
    val d = seconds / 86400
    val h = seconds % 86400 / 3600
    val m = seconds % 3600 / 60
    val s = seconds % 60
    return when {
        d > 0 -> "${d}d ${h}h"
        h > 0 -> "${h}h ${m}m"
        else -> "${m}m ${s}s"
    }
}

/** Fills the score / popularity / favourites / duration bar and the live "airs in" line. */
fun bindMediaStatsBar(binding: ActivityMediaBinding, media: Media) {
    binding.mediaStatScore.text = media.meanScore?.let { (it / 10.0).toString() } ?: "~"
    binding.mediaStatPopularity.text = compact(media.popularity)
    binding.mediaStatFavorites.text = compact(media.favourites)

    // episode length only exists for anime
    if (media.anime != null) {
        binding.mediaStatDurationColumn.visibility = View.VISIBLE
        binding.mediaStatSpacer3.visibility = View.VISIBLE
        binding.mediaStatSpacer4.visibility = View.VISIBLE
        binding.mediaStatDuration.text = duration(media.anime.episodeDuration)
    } else {
        binding.mediaStatDurationColumn.visibility = View.GONE
        binding.mediaStatSpacer3.visibility = View.GONE
        binding.mediaStatSpacer4.visibility = View.VISIBLE
    }

    equalizeStatValues(binding)

    // airing line (anime with a scheduled next episode)
    val row = binding.mediaAiringRow
    val nextEp = media.anime?.nextAiringEpisode
    val nextTime = media.anime?.nextAiringEpisodeTime
    if (nextEp == null || nextTime == null) {
        row.visibility = View.GONE
        return
    }
    val airTick = object : Runnable {
        override fun run() {
            val left = nextTime - System.currentTimeMillis() / 1000
            if (left <= 0) {
                row.visibility = View.GONE
                return
            }
            row.visibility = View.VISIBLE
            binding.mediaAiringText.text = row.context.getString(
                R.string.stats_airing_in, nextEp + 1, remaining(left)
            )
            row.postDelayed(this, 1000)
        }
    }
    row.removeCallbacks(row.getTag(R.id.mediaAiringRow) as? Runnable)
    row.setTag(R.id.mediaAiringRow, airTick)
    airTick.run()
}

/** All four values share one text size: the biggest one at which the longest value still fits. */
private fun equalizeStatValues(binding: ActivityMediaBinding) {
    val card = binding.mediaStatsCard
    val values = listOf(
        binding.mediaStatScore,
        binding.mediaStatPopularity,
        binding.mediaStatFavorites,
        binding.mediaStatDuration
    ).filter { (it.parent as View).visibility == View.VISIBLE }
    fun fit() {
        val available = card.width - card.paddingLeft - card.paddingRight
        if (available <= 0 || values.isEmpty()) return
        val density = card.resources.displayMetrics.density
        val columnWidth = available / values.size - 6 * density
        var size = 18f
        while (size > 11f) {
            values.forEach { it.setTextSize(TypedValue.COMPLEX_UNIT_SP, size) }
            if (values.all { it.paint.measureText(it.text.toString()) <= columnWidth }) break
            size -= 0.5f
        }
    }
    card.doOnLayout { fit() }
    card.post { fit() }
}
