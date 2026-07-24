package africa.itthute.mediasplitter

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class SplitMediaRepository(private val context: Context, private val diagnostics: DiagnosticsStore) {

    suspend fun recent(limit: Int = 10): List<SplitMediaItem> = withContext(Dispatchers.IO) {
        val items = mutableListOf<SplitMediaItem>()
        runCatching { queryCollection(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, true, AUDIO_FOLDER) }
            .onSuccess(items::addAll)
            .onFailure { diagnostics.log("ERROR", "Could not query saved audio", it) }
        runCatching { queryCollection(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, false, VIDEO_FOLDER) }
            .onSuccess(items::addAll)
            .onFailure { diagnostics.log("ERROR", "Could not query saved video", it) }
        items.sortedByDescending { it.dateAddedSeconds }.take(limit)
    }

    suspend fun updateMetadata(item: SplitMediaItem, displayName: String, title: String): Result<Unit> =
        withContext(Dispatchers.IO) {
            runCatching {
                val safeName = ensureExtension(displayName.trim(), item.displayName)
                require(safeName.isNotBlank()) { "File name cannot be empty" }
                val renameValues = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, safeName)
                }
                val changed = context.contentResolver.update(item.uri, renameValues, null, null)
                check(changed > 0) { "Android did not update the media item" }

                val requestedTitle = title.trim().ifBlank { safeName.substringBeforeLast('.') }
                runCatching {
                    context.contentResolver.update(
                        item.uri,
                        ContentValues().apply { put(MediaStore.MediaColumns.TITLE, requestedTitle) },
                        null,
                        null
                    )
                }.onFailure {
                    diagnostics.log("WARN", "Android renamed the file but did not accept the title metadata", it)
                }
                diagnostics.log("INFO", "Updated metadata for ${item.userVisiblePath}")
            }.onFailure { diagnostics.log("ERROR", "Metadata update failed for ${item.userVisiblePath}", it) }
        }

    suspend fun delete(item: SplitMediaItem): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val deleted = context.contentResolver.delete(item.uri, null, null)
            check(deleted > 0) { "Android did not delete the media item" }
            diagnostics.log("INFO", "Deleted media: ${item.userVisiblePath}")
        }.onFailure {
            diagnostics.log("ERROR", "Could not delete ${item.userVisiblePath}", it)
        }
    }

    suspend fun moveToTree(item: SplitMediaItem, treeUri: Uri): Result<Uri> = withContext(Dispatchers.IO) {
        runCatching {
            val documentId = DocumentsContract.getTreeDocumentId(treeUri)
            val parentDocumentUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, documentId)
            val targetUri = requireNotNull(
                DocumentsContract.createDocument(
                    context.contentResolver,
                    parentDocumentUri,
                    item.mimeType,
                    item.displayName
                )
            ) { "The selected destination could not create the file" }

            try {
                context.contentResolver.openInputStream(item.uri).use { input ->
                    requireNotNull(input) { "Android could not open the source file" }
                    context.contentResolver.openOutputStream(targetUri, "w").use { output ->
                        requireNotNull(output) { "Android could not open the destination file" }
                        input.copyTo(output)
                    }
                }
                val deleted = context.contentResolver.delete(item.uri, null, null)
                check(deleted > 0) { "The file was copied, but Android did not remove the original" }
                diagnostics.log("INFO", "Moved ${item.userVisiblePath} to $treeUri")
                targetUri
            } catch (throwable: Throwable) {
                runCatching { DocumentsContract.deleteDocument(context.contentResolver, targetUri) }
                throw throwable
            }
        }.onFailure {
            diagnostics.log("ERROR", "Could not move ${item.userVisiblePath}", it)
        }
    }

    private fun queryCollection(collection: Uri, audio: Boolean, folder: String): List<SplitMediaItem> {
        val projection = arrayOf(
            MediaStore.MediaColumns._ID,
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.RELATIVE_PATH,
            MediaStore.MediaColumns.MIME_TYPE,
            MediaStore.MediaColumns.SIZE,
            MediaStore.MediaColumns.DATE_ADDED,
            MediaStore.MediaColumns.DURATION
        )
        val selection = "${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ?"
        val results = mutableListOf<SplitMediaItem>()
        context.contentResolver.query(
            collection,
            projection,
            selection,
            arrayOf("$folder%"),
            null
        )?.use { cursor ->
            val idIndex = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
            val nameIndex = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
            val pathIndex = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.RELATIVE_PATH)
            val mimeIndex = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.MIME_TYPE)
            val sizeIndex = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE)
            val dateIndex = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_ADDED)
            val durationIndex = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DURATION)
            while (cursor.moveToNext()) {
                val id = cursor.getLong(idIndex)
                results += SplitMediaItem(
                    uri = ContentUris.withAppendedId(collection, id),
                    displayName = cursor.getString(nameIndex) ?: "Unnamed media",
                    relativePath = cursor.getString(pathIndex) ?: folder,
                    mimeType = cursor.getString(mimeIndex) ?: if (audio) "audio/*" else "video/*",
                    sizeBytes = cursor.getLong(sizeIndex),
                    durationMs = cursor.getLong(durationIndex),
                    dateAddedSeconds = cursor.getLong(dateIndex),
                    isAudio = audio
                )
            }
        }
        return results
    }

    private fun ensureExtension(proposed: String, original: String): String {
        if (proposed.isBlank()) return proposed
        if (proposed.substringAfterLast('.', "").isNotBlank()) return proposed
        val extension = original.substringAfterLast('.', "")
        return if (extension.isBlank()) proposed else "$proposed.$extension"
    }

    companion object {
        const val AUDIO_FOLDER = "Music/ITthute Media Splitter/"
        const val VIDEO_FOLDER = "Movies/ITthute Media Splitter/"
        const val DIVIDED_AUDIO_FOLDER = "Music/ITthute Media Splitter/Divided/"
        const val DIVIDED_VIDEO_FOLDER = "Movies/ITthute Media Splitter/Divided/"
    }
}
