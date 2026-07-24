package africa.itthute.mediasplitter

import android.content.ContentValues
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import africa.itthute.mediasplitter.databinding.ActivityMainBinding
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.FFmpegSession
import com.arthenica.ffmpegkit.ReturnCode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale
import kotlin.coroutines.resume

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private var selectedUri: Uri? = null
    private var durationSeconds: Double = 0.0
    private var activeSessionId: Long? = null

    private val picker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let {
            contentResolver.takePersistableUriPermission(it, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
            selectedUri = it
            loadMetadata(it)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.audioFormat.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            listOf("MP3", "M4A", "AAC", "WAV", "FLAC", "OGG")
        )
        binding.videoFormat.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            listOf("MP4", "MKV", "WEBM", "MOV", "AVI")
        )

        binding.selectButton.setOnClickListener { picker.launch(arrayOf("video/*", "audio/*")) }
        binding.exportAudioButton.setOnClickListener { exportAudio() }
        binding.exportVideoButton.setOnClickListener { exportSilentVideo() }
        binding.cancelButton.setOnClickListener {
            activeSessionId?.let { FFmpegKit.cancel(it) }
            setBusy(false, "Cancellation requested…")
        }
    }

    private fun loadMetadata(uri: Uri) {
        lifecycleScope.launch {
            val info = withContext(Dispatchers.IO) {
                val name = queryDisplayName(uri)
                val retriever = MediaMetadataRetriever()
                try {
                    retriever.setDataSource(this@MainActivity, uri)
                    val ms = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                        ?.toLongOrNull() ?: 0L
                    name to (ms / 1000.0)
                } finally {
                    retriever.release()
                }
            }
            durationSeconds = info.second
            binding.fileLabel.text = info.first
            binding.durationLabel.text = "Duration: ${formatTime(durationSeconds)}"
            binding.endTime.setText(String.format(Locale.US, "%.3f", durationSeconds))
        }
    }

    private fun exportAudio() {
        val format = binding.audioFormat.selectedItem.toString().lowercase(Locale.US)
        val codecs = mapOf(
            "mp3" to listOf("-vn", "-c:a", "libmp3lame", "-b:a", "192k"),
            "m4a" to listOf("-vn", "-c:a", "aac", "-b:a", "192k"),
            "aac" to listOf("-vn", "-c:a", "aac", "-b:a", "192k"),
            "wav" to listOf("-vn", "-c:a", "pcm_s16le"),
            "flac" to listOf("-vn", "-c:a", "flac"),
            "ogg" to listOf("-vn", "-c:a", "libvorbis", "-q:a", "5")
        )
        process(MediaKind.AUDIO, format, codecs.getValue(format))
    }

    private fun exportSilentVideo() {
        val format = binding.videoFormat.selectedItem.toString().lowercase(Locale.US)
        val codecs = when (format) {
            "webm" -> listOf("-an", "-c:v", "libvpx-vp9", "-crf", "32", "-b:v", "0")
            "avi" -> listOf("-an", "-c:v", "mpeg4", "-q:v", "3")
            else -> listOf(
                "-an", "-c:v", "libx264", "-preset", "veryfast", "-crf", "23", "-pix_fmt", "yuv420p"
            )
        }
        process(MediaKind.VIDEO, format, codecs)
    }

    private fun process(kind: MediaKind, extension: String, codecArgs: List<String>) {
        val uri = selectedUri ?: return toast("Choose a media file first")
        val start = parseTime(binding.startTime.text?.toString()) ?: return toast("Invalid start time")
        val end = parseTime(binding.endTime.text?.toString()) ?: return toast("Invalid end time")
        if (start < 0 || end <= start || (durationSeconds > 0 && end > durationSeconds + 0.1)) {
            return toast("Use a valid range within the media duration")
        }

        lifecycleScope.launch {
            setBusy(true, "Preparing media…")
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val input = copyToCache(uri)
                    val tempOutput = File(cacheDir, "output_${System.currentTimeMillis()}.$extension")
                    val args = mutableListOf(
                        "-y",
                        "-ss", start.toString(),
                        "-to", end.toString(),
                        "-i", input.absolutePath
                    )
                    args += codecArgs
                    args += tempOutput.absolutePath
                    val session = executeFfmpeg(args)
                    if (!ReturnCode.isSuccess(session.returnCode)) {
                        error(session.failStackTrace ?: session.allLogsAsString ?: "FFmpeg export failed")
                    }
                    val saved = saveResult(tempOutput, kind, extension)
                    input.delete()
                    tempOutput.delete()
                    saved
                }
            }
            activeSessionId = null
            result.onSuccess {
                setBusy(false, "Saved: $it")
                toast("Saved to $it")
            }.onFailure {
                setBusy(false, "Failed: ${it.message}")
                toast(it.message ?: "Export failed")
            }
        }
    }

    private suspend fun executeFfmpeg(args: List<String>): FFmpegSession =
        suspendCancellableCoroutine { cont ->
            val command = args.joinToString(" ") { arg ->
                if (arg.startsWith("-")) arg else quoteForFfmpeg(arg)
            }
            val session = FFmpegKit.executeAsync(command) { completed ->
                if (cont.isActive) {
                    cont.resume(completed)
                }
            }
            activeSessionId = session.sessionId
            cont.invokeOnCancellation {
                FFmpegKit.cancel(session.sessionId)
            }
        }

    private fun quoteForFfmpeg(value: String): String {
        val escaped = value.replace("\\", "\\\\").replace("\"", "\\\"")
        return "\"$escaped\""
    }

    private fun copyToCache(uri: Uri): File {
        val suffix = queryDisplayName(uri).substringAfterLast('.', "media")
        val file = File(cacheDir, "input_${System.currentTimeMillis()}.$suffix")
        contentResolver.openInputStream(uri).use { input ->
            requireNotNull(input) { "Cannot open selected file" }
            file.outputStream().use { output -> input.copyTo(output) }
        }
        return file
    }

    private fun saveResult(source: File, kind: MediaKind, ext: String): String {
        val timestamp = System.currentTimeMillis()
        val name = "ITthute_${if (kind == MediaKind.AUDIO) "audio" else "silent_video"}_$timestamp.$ext"
        val collection = if (kind == MediaKind.AUDIO) {
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        } else {
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        }
        val relative = if (kind == MediaKind.AUDIO) {
            "Music/ITthute Media Splitter"
        } else {
            "Movies/ITthute Media Splitter"
        }
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, mimeType(ext))
            put(MediaStore.MediaColumns.RELATIVE_PATH, relative)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val outputUri = requireNotNull(contentResolver.insert(collection, values)) {
            "Could not create output file"
        }
        contentResolver.openOutputStream(outputUri).use { out ->
            requireNotNull(out) { "Could not open output file" }
            source.inputStream().use { it.copyTo(out) }
        }
        values.clear()
        values.put(MediaStore.MediaColumns.IS_PENDING, 0)
        contentResolver.update(outputUri, values, null, null)
        return "$relative/$name"
    }

    private fun queryDisplayName(uri: Uri): String {
        contentResolver.query(
            uri,
            arrayOf(MediaStore.MediaColumns.DISPLAY_NAME),
            null,
            null,
            null
        )?.use {
            if (it.moveToFirst()) return it.getString(0)
        }
        return uri.lastPathSegment ?: "media"
    }

    private fun parseTime(raw: String?): Double? {
        val value = raw?.trim().orEmpty()
        if (value.isEmpty()) return null
        value.toDoubleOrNull()?.let { return it }
        val parts = value.split(":").map { it.toDoubleOrNull() ?: return null }
        return when (parts.size) {
            2 -> parts[0] * 60 + parts[1]
            3 -> parts[0] * 3600 + parts[1] * 60 + parts[2]
            else -> null
        }
    }

    private fun formatTime(seconds: Double): String {
        val total = seconds.toLong()
        val h = total / 3600
        val m = (total % 3600) / 60
        val s = total % 60
        return "%02d:%02d:%02d".format(h, m, s)
    }

    private fun mimeType(ext: String) = when (ext) {
        "mp3" -> "audio/mpeg"
        "m4a" -> "audio/mp4"
        "aac" -> "audio/aac"
        "wav" -> "audio/wav"
        "flac" -> "audio/flac"
        "ogg" -> "audio/ogg"
        "mp4" -> "video/mp4"
        "webm" -> "video/webm"
        "mkv" -> "video/x-matroska"
        "mov" -> "video/quicktime"
        "avi" -> "video/x-msvideo"
        else -> "application/octet-stream"
    }

    private fun setBusy(busy: Boolean, message: String) {
        binding.progress.visibility = if (busy) View.VISIBLE else View.GONE
        binding.cancelButton.visibility = if (busy) View.VISIBLE else View.GONE
        binding.selectButton.isEnabled = !busy
        binding.exportAudioButton.isEnabled = !busy
        binding.exportVideoButton.isEnabled = !busy
        binding.statusLabel.text = message
    }

    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_LONG).show()

    private enum class MediaKind { AUDIO, VIDEO }
}
