package africa.itthute.mediasplitter

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.FFmpegSession
import com.arthenica.ffmpegkit.ReturnCode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.resume

class MediaProcessor(
    private val context: Context,
    private val diagnostics: DiagnosticsStore
) {
    @Volatile
    private var activeSessionId: Long? = null

    suspend fun export(
        sourceUri: Uri,
        startSeconds: Double,
        endSeconds: Double,
        kind: MediaKind,
        extension: String,
        codecArguments: List<String>
    ): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            require(endSeconds > startSeconds) { "End time must be after start time" }
            val input = copyToCache(sourceUri)
            val output = File(context.cacheDir, "output_${System.currentTimeMillis()}.$extension")
            try {
                val duration = endSeconds - startSeconds
                val arguments = mutableListOf(
                    "-hide_banner",
                    "-y",
                    "-ss", formatNumber(startSeconds),
                    "-i", input.absolutePath,
                    "-t", formatNumber(duration)
                )
                arguments += codecArguments
                arguments += output.absolutePath

                diagnostics.log(
                    "INFO",
                    "Starting ${kind.name.lowercase()} export: ${arguments.joinToString(" ") { redactCachePath(it) }}"
                )
                val session = execute(arguments)
                val logs = session.allLogsAsString.orEmpty()
                if (!ReturnCode.isSuccess(session.returnCode)) {
                    val details = session.failStackTrace?.takeIf { it.isNotBlank() }
                        ?: logs.takeLast(12_000).takeIf { it.isNotBlank() }
                        ?: "FFmpeg returned ${session.returnCode}"
                    throw MediaProcessingException(details)
                }
                check(output.exists() && output.length() > 0L) { "The media engine did not create an output file" }
                val savedPath = saveResult(output, kind, extension)
                diagnostics.log("INFO", "Export completed: $savedPath (${output.length()} bytes)")
                savedPath
            } catch (throwable: Throwable) {
                diagnostics.log("ERROR", "Media export failed", throwable)
                throw throwable
            } finally {
                activeSessionId = null
                input.delete()
                output.delete()
            }
        }
    }

    fun cancel() {
        val id = activeSessionId ?: return
        runCatching { FFmpegKit.cancel(id) }
            .onSuccess { diagnostics.log("INFO", "Cancellation requested for session $id") }
            .onFailure { diagnostics.log("ERROR", "Could not cancel session $id", it) }
    }

    fun engineStatus(probeNativeCode: Boolean): String {
        val lines = mutableListOf<String>()
        val loader = context.classLoader
        runCatching {
            Class.forName("com.arthenica.ffmpegkit.FFmpegKit", false, loader)
            Class.forName("com.arthenica.ffmpegkit.FFmpegKitConfig", false, loader)
        }.onSuccess {
            lines += "FFmpegKit Java API: present"
        }.onFailure {
            lines += "FFmpegKit Java API: unavailable (${it::class.java.name}: ${it.message})"
            return lines.joinToString("\n")
        }

        val nativeFiles = File(context.applicationInfo.nativeLibraryDir).listFiles().orEmpty()
        lines += "libffmpegkit.so: ${if (nativeFiles.any { it.name == "libffmpegkit.so" }) "present" else "missing"}"
        lines += "libavcodec.so: ${if (nativeFiles.any { it.name == "libavcodec.so" }) "present" else "missing"}"

        if (probeNativeCode) {
            runCatching {
                val clazz = Class.forName("com.arthenica.ffmpegkit.FFmpegKitConfig", true, loader)
                val method = clazz.getMethod("getFFmpegVersion")
                method.invoke(null)?.toString() ?: "Unknown"
            }.onSuccess {
                lines += "FFmpeg native initialization: successful"
                lines += "FFmpeg version: $it"
            }.onFailure {
                lines += "FFmpeg native initialization: failed"
                lines += "Failure: ${it::class.java.name}: ${it.message}"
                diagnostics.log("ERROR", "FFmpeg native initialization probe failed", it)
            }
        } else {
            lines += "FFmpeg native initialization: not probed"
        }
        return lines.joinToString("\n")
    }

    private suspend fun execute(arguments: List<String>): FFmpegSession =
        suspendCancellableCoroutine { continuation ->
            try {
                val command = arguments.joinToString(" ") { quoteArgument(it) }
                val session = FFmpegKit.executeAsync(command) { completed ->
                    if (continuation.isActive) continuation.resume(completed)
                }
                activeSessionId = session.sessionId
                continuation.invokeOnCancellation {
                    runCatching { FFmpegKit.cancel(session.sessionId) }
                }
            } catch (throwable: Throwable) {
                diagnostics.log("ERROR", "FFmpegKit could not start", throwable)
                if (continuation.isActive) {
                    continuation.resumeWith(Result.failure(throwable))
                }
            }
        }

    private fun copyToCache(uri: Uri): File {
        val name = queryDisplayName(uri)
        val extension = name.substringAfterLast('.', "media").filter { it.isLetterOrDigit() }.ifBlank { "media" }
        val target = File(context.cacheDir, "input_${System.currentTimeMillis()}.$extension")
        context.contentResolver.openInputStream(uri).use { input ->
            requireNotNull(input) { "Android could not open the selected media" }
            target.outputStream().use { output -> input.copyTo(output) }
        }
        diagnostics.log("INFO", "Copied selected media to private processing cache (${target.length()} bytes)")
        return target
    }

    private fun saveResult(source: File, kind: MediaKind, extension: String): String {
        val timestamp = System.currentTimeMillis()
        val name = "ITthute_${if (kind == MediaKind.AUDIO) "audio" else "silent_video"}_$timestamp.$extension"
        val collection = if (kind == MediaKind.AUDIO) {
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        } else {
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        }
        val relativePath = if (kind == MediaKind.AUDIO) {
            SplitMediaRepository.AUDIO_FOLDER
        } else {
            SplitMediaRepository.VIDEO_FOLDER
        }
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.TITLE, name.substringBeforeLast('.'))
            put(MediaStore.MediaColumns.MIME_TYPE, mimeType(extension))
            put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val outputUri = requireNotNull(context.contentResolver.insert(collection, values)) {
            "Android could not create the output media item"
        }
        try {
            context.contentResolver.openOutputStream(outputUri, "w").use { output ->
                requireNotNull(output) { "Android could not open the output media item" }
                source.inputStream().use { input -> input.copyTo(output) }
            }
            values.clear()
            values.put(MediaStore.MediaColumns.IS_PENDING, 0)
            context.contentResolver.update(outputUri, values, null, null)
        } catch (throwable: Throwable) {
            context.contentResolver.delete(outputUri, null, null)
            throw throwable
        }
        diagnostics.setLastDestination(relativePath)
        return "$relativePath$name"
    }

    private fun queryDisplayName(uri: Uri): String {
        context.contentResolver.query(
            uri,
            arrayOf(MediaStore.MediaColumns.DISPLAY_NAME),
            null,
            null,
            null
        )?.use { cursor ->
            if (cursor.moveToFirst()) return cursor.getString(0) ?: "media"
        }
        return uri.lastPathSegment ?: "media"
    }

    private fun quoteArgument(value: String): String {
        if (value.isEmpty()) return "\"\""
        val escaped = value.replace("\\", "\\\\").replace("\"", "\\\"")
        return "\"$escaped\""
    }

    private fun redactCachePath(value: String): String =
        value.replace(context.cacheDir.absolutePath, "<app-cache>")

    private fun formatNumber(number: Double): String = java.lang.String.format(java.util.Locale.US, "%.3f", number)

    private fun mimeType(extension: String): String = when (extension.lowercase()) {
        "mp3" -> "audio/mpeg"
        "m4a" -> "audio/mp4"
        "aac" -> "audio/aac"
        "wav" -> "audio/wav"
        "flac" -> "audio/flac"
        "ogg" -> "audio/ogg"
        "mp4" -> "video/mp4"
        "mkv" -> "video/x-matroska"
        "webm" -> "video/webm"
        "mov" -> "video/quicktime"
        "avi" -> "video/x-msvideo"
        else -> "application/octet-stream"
    }

    enum class MediaKind { AUDIO, VIDEO }

    class MediaProcessingException(message: String) : Exception(message)
}
