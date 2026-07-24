package africa.itthute.mediasplitter.media

import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.provider.OpenableColumns
import java.io.File
import java.io.FileOutputStream

class MediaFileManager(private val context: Context) {

    fun displayName(uri: Uri): String {
        var cursor: Cursor? = null
        return try {
            cursor = context.contentResolver.query(
                uri,
                arrayOf(OpenableColumns.DISPLAY_NAME),
                null,
                null,
                null
            )
            if (cursor != null && cursor.moveToFirst()) {
                val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (index >= 0) cursor.getString(index) else "selected-video"
            } else {
                uri.lastPathSegment ?: "selected-video"
            }
        } finally {
            cursor?.close()
        }
    }

    fun copyInputToCache(uri: Uri): File {
        val extension = displayName(uri).substringAfterLast('.', "mp4")
        val target = File.createTempFile("itthute_input_", ".${extension}", context.cacheDir)
        context.contentResolver.openInputStream(uri).use { input ->
            requireNotNull(input) { "Cannot open selected video." }
            FileOutputStream(target).use { output -> input.copyTo(output) }
        }
        return target
    }

    fun createOutputCacheFile(extension: String): File =
        File.createTempFile("itthute_export_", ".${extension}", context.cacheDir)

    fun copyOutputToUri(source: File, destination: Uri) {
        context.contentResolver.openOutputStream(destination, "w").use { output ->
            requireNotNull(output) { "Cannot open output destination." }
            source.inputStream().use { input -> input.copyTo(output) }
        }
    }

    fun cleanup(vararg files: File?) {
        files.filterNotNull().forEach { file -> runCatching { file.delete() } }
    }
}
