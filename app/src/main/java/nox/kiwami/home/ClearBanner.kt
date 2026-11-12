package nox.kiwami.home

import android.app.Activity
import android.content.Context
import android.widget.ImageView
import androidx.core.net.toUri
import nox.kiwami.R
import nox.kiwami.others.CustomMediaImages
import nox.kiwami.settings.saving.PrefManager
import nox.kiwami.settings.saving.PrefName
import com.bumptech.glide.Glide
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.bumptech.glide.load.model.GlideUrl
import java.io.File

/**
 * The Home banner exactly as it is: no blur, no 400px downscale, no Ken Burns movement.
 * Same sources as the old blurred banner (custom picture override, custom image url,
 * content:// uris, local files), just untouched.
 */
fun loadClearBanner(imageView: ImageView, originalBanner: String?) {
    val banner = CustomMediaImages.overrideFor(originalBanner)?.absolutePath ?: originalBanner
    if (banner == null) {
        imageView.setImageResource(R.drawable.linear_gradient_bg)
        return
    }
    val context = imageView.context
    if ((context as? Activity)?.isDestroyed == true) return
    val url = PrefManager.getVal<String>(PrefName.ImageUrl).ifEmpty { banner }
    Glide.with(context as Context)
        .load(
            if (banner.startsWith("http")) GlideUrl(url)
            else if (banner.startsWith("content://")) url.toUri()
            else File(url)
        )
        .diskCacheStrategy(DiskCacheStrategy.RESOURCE)
        .into(imageView)
}
