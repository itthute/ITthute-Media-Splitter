package africa.itthute.mediasplitter

import android.content.Intent
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private lateinit var diagnostics: DiagnosticsStore
    private lateinit var processor: MediaProcessor
    private lateinit var splitMediaController: SplitMediaController
    private lateinit var diagnosticsController: DiagnosticsController
    private lateinit var fileDividerController: FileDividerController

    private var selectedUri: Uri? = null
    private var durationSeconds = 0.0
    private var selectedIsAudio = false

    private val mediaPicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@registerForActivityResult
        runCatching {
            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }.onFailure {
            diagnostics.log("WARN", "The selected provider did not grant persistent access", it)
        }
        selectedUri = uri
        diagnostics.log("INFO", "Media selected: ${queryDisplayName(uri)}")
        loadMetadata(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        diagnostics = DiagnosticsStore(this)
        processor = MediaProcessor(this, diagnostics)
        val repository = SplitMediaRepository(this, diagnostics)
        splitMediaController = SplitMediaController(this, binding, repository, diagnostics)
        diagnosticsController = DiagnosticsController(this, binding, diagnostics, processor)
        fileDividerController = FileDividerController(
            activity = this,
            binding = binding,
            processor = processor,
            diagnostics = diagnostics,
            chooseMedia = ::launchMediaPicker,
            onFilesCreated = splitMediaController::refresh
        )
        diagnostics.log("INFO", "Application started")

        configureFormatSelectors()
        configureNavigation()
        configureSplitterActions()
        splitMediaController.configure()
        diagnosticsController.configure()
        fileDividerController.configure()
        showPage(Page.SPLITTER)
    }

    override fun onResume() {
        super.onResume()
        if (::splitMediaController.isInitialized && binding.splitMediaPage.visibility == View.VISIBLE) {
            splitMediaController.refresh()
        }
    }

    override fun onDestroy() {
        if (::processor.isInitialized) processor.cancel()
        super.onDestroy()
    }

    private fun configureFormatSelectors() {
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
    }

    private fun configureNavigation() {
        binding.bottomNavigation.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.navigation_splitter -> showPage(Page.SPLITTER)
                R.id.navigation_file_divider -> showPage(Page.FILE_DIVIDER)
                R.id.navigation_split_media -> showPage(Page.SPLIT_MEDIA)
                R.id.navigation_diagnostics -> showPage(Page.DIAGNOSTICS)
                else -> false
            }
        }
    }

    private fun configureSplitterActions() {
        binding.selectButton.setOnClickListener { launchMediaPicker() }
        binding.exportAudioButton.setOnClickListener { exportAudio() }
        binding.exportVideoButton.setOnClickListener { exportSilentVideo() }
        binding.cancelButton.setOnClickListener {
            processor.cancel()
            setBusy(false, "Cancellation requested")
        }
    }

    private fun launchMediaPicker() {
        mediaPicker.launch(arrayOf("video/*", "audio/*"))
    }

    private fun showPage(page: Page): Boolean {
        binding.splitterPage.visibility = if (page == Page.SPLITTER) View.VISIBLE else View.GONE
        fileDividerController.pageView.visibility = if (page == Page.FILE_DIVIDER) View.VISIBLE else View.GONE
        binding.splitMediaPage.visibility = if (page == Page.SPLIT_MEDIA) View.VISIBLE else View.GONE
        binding.diagnosticsPage.visibility = if (page == Page.DIAGNOSTICS) View.VISIBLE else View.GONE
        when (page) {
            Page.SPLITTER, Page.FILE_DIVIDER -> Unit
            Page.SPLIT_MEDIA -> splitMediaController.refresh()
            Page.DIAGNOSTICS -> diagnosticsController.updateSummary()
        }
        return true
    }

    private fun loadMetadata(uri: Uri) {
        lifecycleScope.launch {
            setBusy(true, "Reading media information…")
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val retriever = MediaMetadataRetriever()
                    try {
                        retriever.setDataSource(this@MainActivity, uri)
                        val milliseconds = retriever
                            .extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                            ?.toLongOrNull() ?: 0L
                        val mimeType = contentResolver.getType(uri).orEmpty()
                        val hasVideo = retriever
                            .extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_VIDEO)
                            ?.equals("yes", ignoreCase = true) == true
                        MediaInfo(
                            displayName = queryDisplayName(uri),
                            durationSeconds = milliseconds / 1000.0,
                            isAudio = mimeType.startsWith("audio/") || !hasVideo
                        )
                    } finally {
                        retriever.release()
                    }
                }
            }
            result.onSuccess { info ->
                durationSeconds = info.durationSeconds
                selectedIsAudio = info.isAudio
                binding.fileLabel.text = info.displayName
                binding.durationLabel.text = getString(R.string.duration_value, formatTime(durationSeconds))
                binding.endTime.setText(String.format(Locale.US, "%.3f", durationSeconds))
                fileDividerController.onMediaLoaded(
                    uri = uri,
                    displayName = info.displayName,
                    durationSeconds = info.durationSeconds,
                    isAudio = info.isAudio
                )
                setBusy(false, "Ready")
            }.onFailure {
                diagnostics.log("ERROR", "Could not read selected media metadata", it)
                setBusy(false, "Could not read media information")
                toast("Android could not read this media file. See Diagnostics for details.")
            }
        }
    }

    private fun exportAudio() {
        val extension = binding.audioFormat.selectedItem.toString().lowercase(Locale.US)
        val arguments = when (extension) {
            "mp3" -> listOf("-vn", "-c:a", "libmp3lame", "-b:a", "192k")
            "m4a" -> listOf("-vn", "-c:a", "aac", "-b:a", "192k", "-movflags", "+faststart")
            "aac" -> listOf("-vn", "-c:a", "aac", "-b:a", "192k")
            "wav" -> listOf("-vn", "-c:a", "pcm_s16le")
            "flac" -> listOf("-vn", "-c:a", "flac")
            "ogg" -> listOf("-vn", "-c:a", "libvorbis", "-q:a", "5")
            else -> return toast("Unsupported audio format")
        }
        process(MediaProcessor.MediaKind.AUDIO, extension, arguments)
    }

    private fun exportSilentVideo() {
        if (selectedIsAudio) return toast("Silent video export requires a video source")
        val extension = binding.videoFormat.selectedItem.toString().lowercase(Locale.US)
        val arguments = when (extension) {
            "webm" -> listOf("-an", "-c:v", "libvpx-vp9", "-crf", "32", "-b:v", "0")
            "avi" -> listOf("-an", "-c:v", "mpeg4", "-q:v", "3")
            "mp4", "mov", "mkv" -> listOf("-an", "-c:v", "mpeg4", "-q:v", "3", "-pix_fmt", "yuv420p")
            else -> return toast("Unsupported video format")
        }
        process(MediaProcessor.MediaKind.VIDEO, extension, arguments)
    }

    private fun process(kind: MediaProcessor.MediaKind, extension: String, arguments: List<String>) {
        val uri = selectedUri ?: return toast("Choose a video or media file first")
        val start = parseTime(binding.startTime.text?.toString()) ?: return toast("Enter a valid start time")
        val end = parseTime(binding.endTime.text?.toString()) ?: return toast("Enter a valid end time")
        if (start < 0 || end <= start || (durationSeconds > 0 && end > durationSeconds + 0.1)) {
            return toast("Choose a valid clip range within the media duration")
        }

        lifecycleScope.launch {
            setBusy(true, "Processing media on this device…")
            processor.export(uri, start, end, kind, extension, arguments)
                .onSuccess { savedPath ->
                    setBusy(false, "Saved: $savedPath")
                    toast("Media saved successfully")
                    splitMediaController.refresh()
                }
                .onFailure { throwable ->
                    val message = when (throwable) {
                        is NoClassDefFoundError, is UnsatisfiedLinkError, is ExceptionInInitializerError ->
                            "The media engine could not start. Open Diagnostics and copy the complete report."
                        else -> throwable.message?.lineSequence()?.firstOrNull()?.take(180)
                            ?: "The media operation failed. Open Diagnostics for details."
                    }
                    setBusy(false, "Failed: $message")
                    toast(message)
                    diagnosticsController.updateSummary()
                }
        }
    }

    private fun setBusy(busy: Boolean, message: String) {
        binding.progress.visibility = if (busy) View.VISIBLE else View.GONE
        binding.cancelButton.visibility = if (busy) View.VISIBLE else View.GONE
        binding.selectButton.isEnabled = !busy
        binding.exportAudioButton.isEnabled = !busy && selectedUri != null
        binding.exportVideoButton.isEnabled = !busy && selectedUri != null && !selectedIsAudio
        binding.statusLabel.text = message
    }

    private fun queryDisplayName(uri: Uri): String {
        contentResolver.query(uri, arrayOf(MediaStore.MediaColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) return cursor.getString(0) ?: "media"
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
        return "%02d:%02d:%02d".format(total / 3600, (total % 3600) / 60, total % 60)
    }

    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_LONG).show()

    private data class MediaInfo(
        val displayName: String,
        val durationSeconds: Double,
        val isAudio: Boolean
    )

    private enum class Page { SPLITTER, FILE_DIVIDER, SPLIT_MEDIA, DIAGNOSTICS }
}
