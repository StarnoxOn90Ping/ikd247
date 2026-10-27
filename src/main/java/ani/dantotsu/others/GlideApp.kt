package ani.dantotsu.others

import android.annotation.SuppressLint
import android.content.Context
import ani.dantotsu.okHttpClient
import com.bumptech.glide.Glide
import com.bumptech.glide.GlideBuilder
import com.bumptech.glide.Registry
import com.bumptech.glide.annotation.GlideModule
import com.bumptech.glide.integration.okhttp3.OkHttpUrlLoader
import com.bumptech.glide.load.engine.cache.InternalCacheDiskCacheFactory
import com.bumptech.glide.load.model.GlideUrl
import com.bumptech.glide.module.AppGlideModule
import com.bumptech.glide.Priority
import com.bumptech.glide.load.DataSource
import com.bumptech.glide.load.Options
import com.bumptech.glide.load.data.DataFetcher
import com.bumptech.glide.load.model.ModelLoader
import com.bumptech.glide.load.model.ModelLoaderFactory
import com.bumptech.glide.load.model.MultiModelLoaderFactory
import com.bumptech.glide.signature.ObjectKey
import ani.dantotsu.others.offline.OfflineCache
import java.io.File
import java.io.FileInputStream
import java.io.InputStream


@GlideModule
class DantotsuGlideApp : AppGlideModule() {
    @SuppressLint("CheckResult")
    override fun applyOptions(context: Context, builder: GlideBuilder) {
        super.applyOptions(context, builder)
        val diskCacheSizeBytes = SmartCache.glideBudgetBytes()
        builder.apply {
            setDiskCache(InternalCacheDiskCacheFactory(context, "img", diskCacheSizeBytes))
        }
    }

    override fun registerComponents(context: Context, glide: Glide, registry: Registry) {
        OfflineCache.init(context)
        registry.replace(
            GlideUrl::class.java,
            InputStream::class.java,
            OfflineFirstLoaderFactory(OkHttpUrlLoader.Factory(okHttpClient))
        )
        super.registerComponents(context, glide, registry)
    }
}

/**
 * Every network image goes through this. If the offline preload holds a permanent copy of the URL
 * it is served from disk (works with no connection and saves data); otherwise the normal OkHttp
 * loader runs. Because it sits at the Glide level, covers, banners, character/staff photos and
 * episode thumbnails are all covered without touching the screens that show them.
 */
private class OfflineFirstLoader(
    private val delegate: ModelLoader<GlideUrl, InputStream>,
) : ModelLoader<GlideUrl, InputStream> {
    override fun buildLoadData(
        model: GlideUrl, width: Int, height: Int, options: Options,
    ): ModelLoader.LoadData<InputStream>? {
        val file = runCatching { OfflineCache.imageFile(model.toStringUrl()) }.getOrNull()
        return if (file != null) {
            ModelLoader.LoadData(ObjectKey("offline:" + file.name), LocalFileFetcher(file))
        } else {
            delegate.buildLoadData(model, width, height, options)
        }
    }

    override fun handles(model: GlideUrl): Boolean = delegate.handles(model)
}

private class OfflineFirstLoaderFactory(
    private val delegate: ModelLoaderFactory<GlideUrl, InputStream>,
) : ModelLoaderFactory<GlideUrl, InputStream> {
    override fun build(multiFactory: MultiModelLoaderFactory): ModelLoader<GlideUrl, InputStream> =
        OfflineFirstLoader(delegate.build(multiFactory))

    override fun teardown() = delegate.teardown()
}

private class LocalFileFetcher(private val file: File) : DataFetcher<InputStream> {
    private var stream: InputStream? = null

    override fun loadData(priority: Priority, callback: DataFetcher.DataCallback<in InputStream>) {
        try {
            stream = FileInputStream(file)
            callback.onDataReady(stream)
        } catch (e: Exception) {
            callback.onLoadFailed(e)
        }
    }

    override fun cleanup() {
        runCatching { stream?.close() }
    }

    override fun cancel() {}
    override fun getDataClass(): Class<InputStream> = InputStream::class.java
    override fun getDataSource(): DataSource = DataSource.LOCAL
}
