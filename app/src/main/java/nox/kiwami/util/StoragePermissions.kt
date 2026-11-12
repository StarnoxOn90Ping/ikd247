package nox.kiwami.util

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import nox.kiwami.R
import nox.kiwami.download.DownloadsManager
import nox.kiwami.settings.saving.PrefManager
import nox.kiwami.settings.saving.PrefName
import nox.kiwami.toast
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

class StoragePermissions {
    companion object {
        fun downloadsPermission(activity: AppCompatActivity): Boolean {
            if (Build.VERSION.SDK_INT > Build.VERSION_CODES.Q) return true
            val permissions = arrayOf(
                Manifest.permission.WRITE_EXTERNAL_STORAGE,
                Manifest.permission.READ_EXTERNAL_STORAGE
            )

            val requiredPermissions = permissions.filter {
                ContextCompat.checkSelfPermission(activity, it) != PackageManager.PERMISSION_GRANTED
            }.toTypedArray()

            return if (requiredPermissions.isNotEmpty()) {
                ActivityCompat.requestPermissions(
                    activity,
                    requiredPermissions,
                    DOWNLOADS_PERMISSION_REQUEST_CODE
                )
                false
            } else {
                true
            }
        }

        fun hasDirAccess(context: Context, path: String): Boolean {
            val uri = pathToUri(path)
            return context.contentResolver.persistedUriPermissions.any {
                it.uri == uri && it.isReadPermission && it.isWritePermission
            }

        }

        fun hasDirAccess(context: Context, uri: Uri): Boolean {
            return context.contentResolver.persistedUriPermissions.any {
                it.uri == uri && it.isReadPermission && it.isWritePermission
            }
        }

        fun hasDirAccess(context: Context): Boolean {
            val path = PrefManager.getVal<String>(PrefName.DownloadsDir)
            return hasDirAccess(context, path)
        }

        fun AppCompatActivity.accessAlertDialog(
            launcher: LauncherWrapper,
            force: Boolean = false,
            complete: (Boolean) -> Unit
        ) {
            if (hasDirAccess(this) && !force) {
                complete(true)
                return
            }
            customAlertDialog().apply {
                setTitle(getString(R.string.dir_access))
                setMessage(getString(R.string.dir_access_msg))
                setPosButton(getString(R.string.ok)) {
                    launcher.registerForCallback(complete)
                    launcher.launch()
                }
                setNegButton(getString(R.string.cancel)) {
                    complete(false)
                }
            }.show()
        }

        private fun pathToUri(path: String): Uri {
            return Uri.parse(path)
        }

        private const val DOWNLOADS_PERMISSION_REQUEST_CODE = 100
    }
}


class LauncherWrapper(
    activity: AppCompatActivity,
    contract: ActivityResultContracts.OpenDocumentTree
) {
    private var launcher: ActivityResultLauncher<Uri?>
    var complete: (Boolean) -> Unit = {}

    init {
        launcher = activity.registerForActivityResult(contract) { uri ->
            if (uri != null) {
                activity.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )

                if (StoragePermissions.hasDirAccess(activity, uri)) {
                    val previous = PrefManager.getVal<String>(PrefName.DownloadsDir)
                    PrefManager.setVal(PrefName.DownloadsDir, uri.toString())
                    DownloadsManager.addNoMedia(activity)
                    if (previous != uri.toString()) {
                        // New download location: rebuild the index from what is in the new folder.
                        // (Flows that also move the old downloads rebuild once more after the move.)
                        CoroutineScope(Dispatchers.IO).launch {
                            try {
                                Injekt.get<DownloadsManager>().rebuildIndexFromDisk(replace = true)
                            } catch (e: Exception) {
                                Logger.log(e)
                            }
                        }
                    }
                    complete(true)
                } else {
                    toast(activity.getString(R.string.dir_error))
                    complete(false)
                }
            } else {
                toast(activity.getString(R.string.dir_error))
                complete(false)
            }
        }
    }

    fun registerForCallback(callback: (Boolean) -> Unit) {
        complete = callback
    }

    fun launch() {
        launcher.launch(null)
    }
}