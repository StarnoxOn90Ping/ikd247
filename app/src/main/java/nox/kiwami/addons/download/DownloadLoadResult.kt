package nox.kiwami.addons.download

import nox.kiwami.addons.LoadResult

open class DownloadLoadResult : LoadResult() {
    class Success(val extension: DownloadAddon.Installed) : DownloadLoadResult()
}