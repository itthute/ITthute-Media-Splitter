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
import com.arthenica.ffmpegkit.FFmpegKitConfig
import com.arthenica.ffmpegkit.ReturnCode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private var selectedUri: Uri? = null
    private var durationSeconds = 0.0
    private var sessionId: Long? = null

    private val picker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri ?: return@registerForActivityResult
        contentResolver.takePersistableUriPermission(uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
        selectedUri = uri
        lifecycleScope.launch {
            val data = withContext(Dispatchers.IO) {
                val retriever = MediaMetadataRetriever()
                try {
                    retriever.setDataSource(this@MainActivity, uri)
                    val seconds = (retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L) / 1000.0
                    queryName(uri) to seconds
                } finally { retriever.release() }
            }
            durationSeconds = data.second
            binding.fileLabel.text = "${data.first} (${formatTime(durationSeconds)})"
            binding.endTime.setText(String.format(Locale.US, "%.3f", durationSeconds))
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.audioFormat.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, listOf("MP3", "M4A", "AAC", "WAV", "FLAC", "OGG"))
        binding.videoFormat.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, listOf("MP4", "MKV", "WEBM", "MOV", "AVI"))
        binding.selectButton.setOnClickListener { picker.launch(arrayOf("video/*", "audio/*")) }
        binding.exportAudioButton.setOnClickListener { exportAudio() }
        binding.exportVideoButton.setOnClickListener { exportVideo() }
        binding.cancelButton.setOnClickListener { sessionId?.let(FFmpegKit::cancel) }
    }

    private fun exportAudio() {
        val ext = binding.audioFormat.selectedItem.toString().lowercase(Locale.US)
        val args = when (ext) {
            "mp3" -> listOf("-vn", "-c:a", "libmp3lame", "-b:a", "192k")
            "m4a", "aac" -> listOf("-vn", "-c:a", "aac", "-b:a", "192k")
            "wav" -> listOf("-vn", "-c:a", "pcm_s16le")
            "flac" -> listOf("-vn", "-c:a", "flac")
            else -> listOf("-vn", "-c:a", "libvorbis", "-q:a", "5")
        }
        process(true, ext, args)
    }

    private fun exportVideo() {
        val ext = binding.videoFormat.selectedItem.toString().lowercase(Locale.US)
        val args = if (ext == "webm") listOf("-an", "-c:v", "libvpx-vp9", "-crf", "32", "-b:v", "0") else listOf("-an", "-c:v", "mpeg4", "-q:v", "3")
        process(false, ext, args)
    }

    private fun process(audio: Boolean, ext: String, codec: List<String>) {
        val uri = selectedUri ?: return toast("Choose a media file first")
        val start = parseTime(binding.startTime.text.toString()) ?: return toast("Invalid start time")
        val end = parseTime(binding.endTime.text.toString()) ?: return toast("Invalid end time")
        if (start < 0 || end <= start || (durationSeconds > 0 && end > durationSeconds + .1)) return toast("Choose a valid range")
        lifecycleScope.launch {
            busy(true, "Processing…")
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val input = copyToCache(uri)
                    val output = File(cacheDir, "output_${System.currentTimeMillis()}.$ext")
                    val command = (listOf("-y", "-ss", start.toString(), "-to", end.toString(), "-i", input.absolutePath) + codec + output.absolutePath)
                        .joinToString(" ") { FFmpegKitConfig.escapeFilePath(it) }
                    val session = FFmpegKit.execute(command)
                    sessionId = session.sessionId
                    if (!ReturnCode.isSuccess(session.returnCode)) error(session.output ?: "FFmpeg export failed")
                    val path = save(output, audio, ext)
                    input.delete(); output.delete(); path
                }
            }
            sessionId = null
            result.onSuccess { busy(false, "Saved: $it") }.onFailure { busy(false, "Failed: ${it.message}") }
        }
    }

    private fun copyToCache(uri: Uri): File {
        val ext = queryName(uri).substringAfterLast('.', "media")
        val file = File(cacheDir, "input_${System.currentTimeMillis()}.$ext")
        contentResolver.openInputStream(uri).use { input -> requireNotNull(input); file.outputStream().use { input.copyTo(it) } }
        return file
    }

    private fun save(file: File, audio: Boolean, ext: String): String {
        val name = "ITthute_${if (audio) "audio" else "silent_video"}_${System.currentTimeMillis()}.$ext"
        val collection = if (audio) MediaStore.Audio.Media.EXTERNAL_CONTENT_URI else MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        val folder = if (audio) "Music/ITthute Media Splitter" else "Movies/ITthute Media Splitter"
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, mime(ext))
            put(MediaStore.MediaColumns.RELATIVE_PATH, folder)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val outUri = requireNotNull(contentResolver.insert(collection, values))
        contentResolver.openOutputStream(outUri).use { out -> requireNotNull(out); file.inputStream().use { it.copyTo(out) } }
        values.clear(); values.put(MediaStore.MediaColumns.IS_PENDING, 0); contentResolver.update(outUri, values, null, null)
        return "$folder/$name"
    }

    private fun queryName(uri: Uri): String = contentResolver.query(uri, arrayOf(MediaStore.MediaColumns.DISPLAY_NAME), null, null, null)?.use { if (it.moveToFirst()) it.getString(0) else null } ?: (uri.lastPathSegment ?: "media")
    private fun parseTime(raw: String): Double? { raw.trim().toDoubleOrNull()?.let { return it }; val p = raw.split(":").map { it.toDoubleOrNull() ?: return null }; return when (p.size) { 2 -> p[0] * 60 + p[1]; 3 -> p[0] * 3600 + p[1] * 60 + p[2]; else -> null } }
    private fun formatTime(s: Double): String { val t = s.toLong(); return "%02d:%02d:%02d".format(t / 3600, (t % 3600) / 60, t % 60) }
    private fun mime(ext: String) = mapOf("mp3" to "audio/mpeg", "m4a" to "audio/mp4", "aac" to "audio/aac", "wav" to "audio/wav", "flac" to "audio/flac", "ogg" to "audio/ogg", "mp4" to "video/mp4", "mkv" to "video/x-matroska", "webm" to "video/webm", "mov" to "video/quicktime", "avi" to "video/x-msvideo")[ext] ?: "application/octet-stream"
    private fun busy(on: Boolean, text: String) { binding.progress.visibility = if (on) View.VISIBLE else View.GONE; binding.cancelButton.visibility = if (on) View.VISIBLE else View.GONE; binding.selectButton.isEnabled = !on; binding.exportAudioButton.isEnabled = !on; binding.exportVideoButton.isEnabled = !on; binding.statusLabel.text = text }
    private fun toast(text: String) = Toast.makeText(this, text, Toast.LENGTH_LONG).show()
}
