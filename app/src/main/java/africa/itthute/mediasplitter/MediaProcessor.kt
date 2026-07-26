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
import kotlin.math.min

class MediaProcessor(
    private val context: Context,
    private val diagnostics: DiagnosticsStore
) {
    @Volatile
    private var activeSessionId: Long? = null

    @Volatile
    private var cancellationRequested = false

    suspend fun export(
        sourceUri: Uri,
        startSeconds: Double,
        endSeconds: Double,
        kind: MediaKind,
        extension: String,
        codecArguments: List<String>
    ): Result<String> = exportBatch(
        sourceUri = sourceUri,
        ranges = listOf(ClipRange(startSeconds, endSeconds)),
        kind = kind,
        extension = extension,
        codecArguments = codecArguments
    ) {}.map { it.savedPaths.single() }

    suspend fun exportBatch(
        sourceUri: Uri,
        ranges: List<ClipRange>,
        kind: MediaKind,
        extension: String,
        codecArguments: List<String>,
        onProgress: (BatchProgress) -> Unit
    ): Result<BatchResult> = withContext(Dispatchers.IO) {
        cancellationRequested = false
        runCatching {
            require(ranges.isNotEmpty()) { "At least one clip range is required" }
            ranges.forEachIndexed { index, range ->
                require(range.endSeconds > range.startSeconds) { "Clip ${index + 1} must end after it starts" }
            }
            val input = copyToCache(sourceUri)
            val savedPaths = mutableListOf<String>()
            val batchTimestamp = System.currentTimeMillis()
            val outputPrefix = if (kind == MediaKind.AUDIO) "audio" else "silent_video"

            diagnostics.log(
                "INFO",
                "Starting ${kind.name.lowercase()} batch export: clips=${ranges.size}, extension=$extension"
            )
            try {
                ranges.forEachIndexed { zeroBasedIndex, range ->
                    if (cancellationRequested) throw BatchCancelledException(savedPaths.size)
                    val clipNumber = zeroBasedIndex + 1
                    val duration = range.durationSeconds
                    val output = File(
                        context.cacheDir,
                        "batch_${batchTimestamp}_${clipNumber.toString().padStart(3, '0')}.$extension"
                    )
                    try {
                        val arguments = mutableListOf(
                            "-hide_banner",
                            "-y",
                            "-ss", formatNumber(range.startSeconds),
                            "-i", input.absolutePath,
                            "-t", formatNumber(duration)
                        )
                        arguments += codecArguments
                        arguments += output.absolutePath

                        diagnostics.log(
                            "INFO",
                            "Starting clip $clipNumber of ${ranges.size}: " +
                                arguments.joinToString(" ") { redactCachePath(it) }
                        )
                        onProgress(
                            BatchProgress(
                                currentClip = clipNumber,
                                totalClips = ranges.size,
                                overallPercent = ((zeroBasedIndex.toDouble() / ranges.size) * 100).toInt(),
                                message = "Processing clip $clipNumber of ${ranges.size}"
                            )
                        )
                        val session = execute(
                            arguments = arguments,
                            expectedDurationMs = (duration * 1000).toLong()
                        ) { currentClipPercent ->
                            val overall = (((zeroBasedIndex + currentClipPercent / 100.0) / ranges.size) * 100)
                                .toInt()
                                .coerceIn(0, 99)
                            onProgress(
                                BatchProgress(
                                    currentClip = clipNumber,
                                    totalClips = ranges.size,
                                    overallPercent = overall,
                                    message = "Processing clip $clipNumber of ${ranges.size}"
                                )
                            )
                        }
                        if (ReturnCode.isCancel(session.returnCode) || cancellationRequested) {
                            throw BatchCancelledException(savedPaths.size)
                        }
                        ensureSessionSucceeded(session) { BatchCancelledException(savedPaths.size) }
                        check(output.exists() && output.length() > 0L) {
                            "The media engine did not create clip $clipNumber"
                        }
                        val requestedName = "ITthute_${outputPrefix}_${batchTimestamp}_clip_${clipNumber.toString().padStart(3, '0')}.$extension"
                        val savedPath = saveResult(
                            source = output,
                            kind = kind,
                            extension = extension,
                            requestedName = requestedName
                        )
                        savedPaths += savedPath
                        diagnostics.log("INFO", "Clip $clipNumber saved: $savedPath")
                    } finally {
                        output.delete()
                        activeSessionId = null
                    }
                }
                onProgress(
                    BatchProgress(
                        currentClip = ranges.size,
                        totalClips = ranges.size,
                        overallPercent = 100,
                        message = "Completed ${ranges.size} clip${if (ranges.size == 1) "" else "s"}"
                    )
                )
                diagnostics.log("INFO", "Batch export completed: ${savedPaths.size} files saved")
                BatchResult(savedPaths)
            } catch (throwable: Throwable) {
                diagnostics.log(
                    "ERROR",
                    "Batch export failed after ${savedPaths.size} of ${ranges.size} clips",
                    throwable
                )
                throw throwable
            } finally {
                activeSessionId = null
                input.delete()
            }
        }
    }

    suspend fun divide(
        sourceUri: Uri,
        totalDurationSeconds: Double,
        segmentSeconds: Int,
        sourceIsAudio: Boolean,
        onProgress: (DivisionProgress) -> Unit
    ): Result<DivisionResult> = withContext(Dispatchers.IO) {
        cancellationRequested = false
        runCatching {
            DivisionRules.validationMessage(totalDurationSeconds, segmentSeconds)?.let { error(it) }
            val totalParts = DivisionRules.segmentCount(totalDurationSeconds, segmentSeconds)
            val input = copyToCache(sourceUri)
            val savedPaths = mutableListOf<String>()
            val batchTimestamp = System.currentTimeMillis()
            val extension = if (sourceIsAudio) "m4a" else "mp4"
            val kind = if (sourceIsAudio) MediaKind.AUDIO else MediaKind.VIDEO
            val folder = if (sourceIsAudio) {
                SplitMediaRepository.DIVIDED_AUDIO_FOLDER
            } else {
                SplitMediaRepository.DIVIDED_VIDEO_FOLDER
            }

            diagnostics.log(
                "INFO",
                "Starting file division: duration=${formatNumber(totalDurationSeconds)}s, " +
                    "segment=${segmentSeconds}s, parts=$totalParts, audio=$sourceIsAudio"
            )

            try {
                repeat(totalParts) { zeroBasedIndex ->
                    if (cancellationRequested) throw DivisionCancelledException(savedPaths.size)
                    val partNumber = zeroBasedIndex + 1
                    val start = zeroBasedIndex * segmentSeconds.toDouble()
                    val partDuration = min(segmentSeconds.toDouble(), totalDurationSeconds - start)
                    val output = File(
                        context.cacheDir,
                        "division_${batchTimestamp}_${partNumber.toString().padStart(3, '0')}.$extension"
                    )
                    try {
                        val arguments = buildDivisionArguments(
                            input = input,
                            output = output,
                            startSeconds = start,
                            durationSeconds = partDuration,
                            sourceIsAudio = sourceIsAudio
                        )
                        onProgress(
                            DivisionProgress(
                                currentPart = partNumber,
                                totalParts = totalParts,
                                overallPercent = ((zeroBasedIndex.toDouble() / totalParts) * 100).toInt(),
                                message = "Creating part $partNumber of $totalParts"
                            )
                        )
                        val session = execute(
                            arguments = arguments,
                            expectedDurationMs = (partDuration * 1000).toLong()
                        ) { currentPartPercent ->
                            val overall = (((zeroBasedIndex + currentPartPercent / 100.0) / totalParts) * 100)
                                .toInt()
                                .coerceIn(0, 99)
                            onProgress(
                                DivisionProgress(
                                    currentPart = partNumber,
                                    totalParts = totalParts,
                                    overallPercent = overall,
                                    message = "Creating part $partNumber of $totalParts"
                                )
                            )
                        }
                        if (ReturnCode.isCancel(session.returnCode) || cancellationRequested) {
                            throw DivisionCancelledException(savedPaths.size)
                        }
                        ensureSessionSucceeded(session) { DivisionCancelledException(savedPaths.size) }
                        check(output.exists() && output.length() > 0L) {
                            "The media engine did not create division part $partNumber"
                        }
                        val requestedName = "ITthute_divided_${batchTimestamp}_part_${partNumber.toString().padStart(3, '0')}.$extension"
                        val savedPath = saveResult(
                            source = output,
                            kind = kind,
                            extension = extension,
                            requestedName = requestedName,
                            relativePathOverride = folder
                        )
                        savedPaths += savedPath
                        diagnostics.log("INFO", "Division part $partNumber saved: $savedPath")
                    } finally {
                        output.delete()
                        activeSessionId = null
                    }
                }
                onProgress(
                    DivisionProgress(
                        currentPart = totalParts,
                        totalParts = totalParts,
                        overallPercent = 100,
                        message = "Division complete: $totalParts files saved"
                    )
                )
                diagnostics.log("INFO", "File division completed: ${savedPaths.size} files saved")
                DivisionResult(savedPaths)
            } catch (throwable: Throwable) {
                diagnostics.log(
                    "ERROR",
                    "File division failed after ${savedPaths.size} of $totalParts parts",
                    throwable
                )
                throw throwable
            } finally {
                activeSessionId = null
                input.delete()
            }
        }
    }

    fun cancel() {
        cancellationRequested = true
        val id = activeSessionId
        if (id == null) {
            diagnostics.log("INFO", "Cancellation requested between media operations")
            return
        }
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
            Class.forName("com.arthenica.smartexception.java.Exceptions", false, loader)
        }.onSuccess {
            lines += "FFmpegKit Java API: present"
            lines += "Smart Exception runtime: present"
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

    private fun buildDivisionArguments(
        input: File,
        output: File,
        startSeconds: Double,
        durationSeconds: Double,
        sourceIsAudio: Boolean
    ): List<String> {
        val arguments = mutableListOf(
            "-hide_banner",
            "-y",
            "-ss", formatNumber(startSeconds),
            "-i", input.absolutePath,
            "-t", formatNumber(durationSeconds)
        )
        if (sourceIsAudio) {
            arguments += listOf("-vn", "-c:a", "aac", "-b:a", "192k", "-movflags", "+faststart")
        } else {
            arguments += listOf(
                "-map", "0:v:0",
                "-map", "0:a?",
                "-c:v", "mpeg4",
                "-q:v", "3",
                "-pix_fmt", "yuv420p",
                "-c:a", "aac",
                "-b:a", "160k",
                "-movflags", "+faststart"
            )
        }
        arguments += output.absolutePath
        return arguments
    }

    private fun ensureSessionSucceeded(
        session: FFmpegSession,
        cancellationException: () -> Throwable
    ) {
        if (ReturnCode.isSuccess(session.returnCode)) return
        if (ReturnCode.isCancel(session.returnCode) || cancellationRequested) throw cancellationException()
        val logs = session.allLogsAsString.orEmpty()
        val details = session.failStackTrace?.takeIf { it.isNotBlank() }
            ?: logs.takeLast(12_000).takeIf { it.isNotBlank() }
            ?: "FFmpeg returned ${session.returnCode}"
        throw MediaProcessingException(details)
    }

    private suspend fun execute(
        arguments: List<String>,
        expectedDurationMs: Long,
        onProgress: (Int) -> Unit = {}
    ): FFmpegSession = suspendCancellableCoroutine { continuation ->
        try {
            val command = arguments.joinToString(" ") { quoteArgument(it) }
            val session = FFmpegKit.executeAsync(
                command,
                { completed ->
                    if (continuation.isActive) continuation.resume(completed)
                },
                { /* Detailed FFmpeg output remains available from the completed session. */ },
                { statistics ->
                    val percentage = ((statistics.time.toDouble() / expectedDurationMs.coerceAtLeast(1L)) * 100)
                        .toInt()
                        .coerceIn(0, 99)
                    onProgress(percentage)
                }
            )
            activeSessionId = session.sessionId
            continuation.invokeOnCancellation {
                cancellationRequested = true
                runCatching { FFmpegKit.cancel(session.sessionId) }
            }
        } catch (throwable: Throwable) {
            diagnostics.log("ERROR", "FFmpegKit could not start", throwable)
            if (continuation.isActive) continuation.resumeWith(Result.failure(throwable))
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

    private fun saveResult(
        source: File,
        kind: MediaKind,
        extension: String,
        requestedName: String? = null,
        relativePathOverride: String? = null
    ): String {
        val timestamp = System.currentTimeMillis()
        val name = requestedName
            ?: "ITthute_${if (kind == MediaKind.AUDIO) "audio" else "silent_video"}_$timestamp.$extension"
        val collection = if (kind == MediaKind.AUDIO) {
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        } else {
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        }
        val relativePath = relativePathOverride ?: if (kind == MediaKind.AUDIO) {
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

    private fun formatNumber(number: Double): String =
        java.lang.String.format(java.util.Locale.US, "%.3f", number)

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

    data class BatchProgress(
        val currentClip: Int,
        val totalClips: Int,
        val overallPercent: Int,
        val message: String
    )

    data class BatchResult(val savedPaths: List<String>)

    data class DivisionProgress(
        val currentPart: Int,
        val totalParts: Int,
        val overallPercent: Int,
        val message: String
    )

    data class DivisionResult(val savedPaths: List<String>)

    class MediaProcessingException(message: String) : Exception(message)

    class BatchCancelledException(val completedClips: Int) : Exception(
        if (completedClips > 0) {
            "Operation cancelled after $completedClips clip${if (completedClips == 1) " was" else "s were"} saved"
        } else {
            "Operation cancelled"
        }
    )

    class DivisionCancelledException(val completedParts: Int) : Exception(
        if (completedParts > 0) {
            "Division cancelled after $completedParts files were saved"
        } else {
            "Division cancelled"
        }
    )
}
