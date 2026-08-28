package de.x0bubbuff.needlebub.developer

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.Binder
import android.os.ParcelFileDescriptor
import de.x0bubbuff.needlebub.NeedleBubApplication
import java.io.FileNotFoundException
import kotlin.concurrent.thread

class AdbCaptureProvider : ContentProvider() {
    override fun onCreate(): Boolean = true

    override fun getType(uri: Uri): String? =
        if (uri.path == CAPTURE_PATH) MIME_TYPE else null

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        if (uri.path != CAPTURE_PATH || mode != "r") {
            throw FileNotFoundException("Unsupported NeedleBub developer capture request")
        }
        val app = context?.applicationContext as? NeedleBubApplication
            ?: throw FileNotFoundException("NeedleBub is unavailable")
        val callerUid = Binder.getCallingUid()
        if (!app.adbCaptureAccess.canRead(
                callerUid,
                app.developerDataSettings.unlocked,
                app.developerDataSettings.labAuthenticated,
            )
        ) {
            throw FileNotFoundException("Enable the temporary ADB pull grant in the authenticated Notification Lab")
        }

        val pipe = ParcelFileDescriptor.createPipe()
        thread(name = "NeedleBubAdbCapture", isDaemon = true) {
            ParcelFileDescriptor.AutoCloseOutputStream(pipe[1]).bufferedWriter(Charsets.UTF_8).use { writer ->
                app.developerDataStore.writePlaintextJsonl(writer) {
                    app.adbCaptureAccess.canRead(
                        callerUid,
                        app.developerDataSettings.unlocked,
                        app.developerDataSettings.labAuthenticated,
                    )
                }
            }
        }
        return pipe[0]
    }

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor? = null

    override fun insert(uri: Uri, values: ContentValues?): Uri? =
        throw SecurityException("NeedleBub developer captures are read-only")

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Int = throw SecurityException("NeedleBub developer captures are read-only")

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int =
        throw SecurityException("NeedleBub developer captures are read-only")

    private companion object {
        const val CAPTURE_PATH = "/captures"
        const val MIME_TYPE = "application/vnd.needlebub.capture+jsonl"
    }
}
