package africa.itthute.mediasplitter

import android.content.Intent
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.view.View
import android.widget.ArrayAdapter
import android.widget.PopupMenu
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import africa.itthute.mediasplitter.databinding.ActivityMainBinding
import com.google.android.material.dialog.MaterialAlertDialogBuilder
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
    private lateinit var clipRangeController: ClipRangeController
    private lateinit var appSettings: AppSettings
    private lateinit var settingsController: SettingsController

    private var selectedUri: Uri? = null
    private var durationSeconds = 0.0
    private var selectedIsAudio = false
    private var splitterMediaEligible = false
    private var currentPage = Page.SPLITTER
    private var pickerPurpose = Page.SPLITTER

    private val mediaPicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@registerForActivityResult
        runCatching {
            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }.onFailure {
            diagnostics.log("WARN", "The selected provider did not grant persistent access", it)
        }
        diagnostics.log("INFO", "Media selected: ${queryDisplayName(uri)}")
        loadMetadata(uri, pickerPurpose)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        configureSystemInsets()

        diagnostics = DiagnosticsStore(this)
        appSettings = AppSettings(this)
        processor = MediaProcessor(this, diagnostics)
        val repository = SplitMediaRepository(this, diagnostics)
        splitMediaController = SplitMediaController(this, binding, repository, diagnostics)
        diagnosticsController = DiagnosticsController(this, binding, diagnostics, processor)
        clipRangeController = ClipRangeController(this, binding, appSettings)
        settingsController = SettingsController(this, appSettings, ::onSettingsChanged)
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
        configureOverflowMenu()
        configureSplitterActions()
        splitMediaController.configure()
        diagnosticsController.configure()
        fileDividerController.configure()
        clipRangeController.configure()
        clipRangeController.clear()
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

    private fun configureSystemInsets() {
        val headerStart = binding.headerContainer.paddingStart
        val headerTop = binding.headerContainer.paddingTop
        val headerEnd = binding.headerContainer.paddingEnd
        val headerBottom = binding.headerContainer.paddingBottom
        ViewCompat.setOnApplyWindowInsetsListener(binding.headerContainer) { view, insets ->
            val status = insets.getInsets(WindowInsetsCompat.Type.statusBars())
            view.setPaddingRelative(headerStart, headerTop + status.top, headerEnd, headerBottom)
            insets
        }
        val navigationBottom = binding.bottomNavigation.paddingBottom
        ViewCompat.setOnApplyWindowInsetsListener(binding.bottomNavigation) { view, insets ->
            val navigation = insets.getInsets(WindowInsetsCompat.Type.navigationBars())
            view.setPadding(view.paddingLeft, view.paddingTop, view.paddingRight, navigationBottom + navigation.bottom)
            insets
        }
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

    private fun configureOverflowMenu() {
        binding.overflowMenuButton.setOnClickListener { anchor ->
            PopupMenu(this, anchor).apply {
                menu.add(getString(R.string.splitter)).setOnMenuItemClickListener {
                    binding.bottomNavigation.selectedItemId = R.id.navigation_splitter
                    true
                }
                menu.add(getString(R.string.file_divider)).setOnMenuItemClickListener {
                    binding.bottomNavigation.selectedItemId = R.id.navigation_file_divider
                    true
                }
                menu.add(getString(R.string.split_media)).setOnMenuItemClickListener {
                    binding.bottomNavigation.selectedItemId = R.id.navigation_split_media
                    true
                }
                menu.add(getString(R.string.diagnostics)).setOnMenuItemClickListener {
                    binding.bottomNavigation.selectedItemId = R.id.navigation_diagnostics
                    true
                }
                menu.add(getString(R.string.settings)).setOnMenuItemClickListener {
                    settingsController.show()
                    true
                }
                menu.add(getString(R.string.help)).setOnMenuItemClickListener {
                    showHelp()
                    true
                }
                menu.add(getString(R.string.about)).setOnMenuItemClickListener {
                    showAbout()
                    true
                }
                show()
            }
        }
    }

    private fun configureSplitterActions() {
        binding.selectButton.setOnClickListener { launchMediaPicker() }
        binding.exportAudioButton.setOnClickListener { exportAudio() }
        binding.exportVideoButton.setOnClickListener { exportSilentVideo() }
        binding.cancelButton.setOnClickListener {
            processor.cancel()
            binding.statusLabel.text = getString(R.string.cancellation_requested)
        }
    }

    private fun launchMediaPicker() {
        pickerPurpose = currentPage
        mediaPicker.launch(arrayOf("video/*", "audio/*"))
    }

    private fun showPage(page: Page): Boolean {
        currentPage = page
        binding.splitterPage.visibility = if (page == Page.SPLITTER) View.VISIBLE else View.GONE
        fileDividerController.pageView.visibility = if (page == Page.FILE_DIVIDER) View.VISIBLE else View.GONE
        binding.splitMediaPage.visibility = if (page == Page.SPLIT_MEDIA) View.VISIBLE else View.GONE
        binding.diagnosticsPage.visibility = if (page == Page.DIAGNOSTICS) View.VISIBLE else View.GONE
        when (page) {
            Page.SPLITTER -> updateSplitterEligibilityMessage()
            Page.FILE_DIVIDER -> Unit
            Page.SPLIT_MEDIA -> splitMediaController.refresh()
            Page.DIAGNOSTICS -> diagnosticsController.updateSummary()
        }
        return true
    }

    private fun loadMetadata(uri: Uri, purpose: Page) {
        lifecycleScope.launch {
            setBusy(true, getString(R.string.reading_media_information))
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
                selectedUri = uri
                durationSeconds = info.durationSeconds
                selectedIsAudio = info.isAudio
                splitterMediaEligible = info.durationSeconds > 0.0 &&
                    info.durationSeconds <= appSettings.maxMediaDurationSeconds

                binding.fileLabel.text = info.displayName
                binding.durationLabel.text = getString(
                    R.string.duration_value_with_seconds,
                    formatTime(info.durationSeconds),
                    ClipRangeRules.formatSeconds(info.durationSeconds)
                )
                updateSourceSpecificLabels()

                if (splitterMediaEligible) {
                    clipRangeController.onMediaLoaded(info.durationSeconds)
                } else {
                    clipRangeController.clear()
                }
                fileDividerController.onMediaLoaded(
                    uri = uri,
                    displayName = info.displayName,
                    durationSeconds = info.durationSeconds,
                    isAudio = info.isAudio
                )
                setBusy(false, getString(R.string.ready))
                if (purpose == Page.SPLITTER && !splitterMediaEligible) {
                    updateSplitterEligibilityMessage()
                    toast(
                        getString(
                            R.string.media_exceeds_configured_limit,
                            formatTime(appSettings.maxMediaDurationSeconds.toDouble()),
                            appSettings.maxMediaDurationSeconds
                        )
                    )
                }
            }.onFailure {
                diagnostics.log("ERROR", "Could not read selected media metadata", it)
                setBusy(false, getString(R.string.could_not_read_media_information))
                toast(getString(R.string.could_not_read_media_diagnostics))
            }
        }
    }

    private fun updateSourceSpecificLabels() {
        if (selectedIsAudio) {
            binding.audioActionTitle.text = getString(R.string.trim_audio)
            binding.exportAudioButton.text = getString(R.string.save_audio_clips)
            binding.exportVideoButton.isEnabled = false
            binding.videoSourceNote.visibility = View.VISIBLE
        } else {
            binding.audioActionTitle.text = getString(R.string.extract_and_trim_audio)
            binding.exportAudioButton.text = getString(R.string.save_audio_clips)
            binding.videoSourceNote.visibility = View.GONE
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
        process(
            kind = MediaProcessor.MediaKind.AUDIO,
            extension = extension,
            arguments = arguments,
            operationLabel = if (selectedIsAudio) getString(R.string.trimming_audio) else getString(R.string.extracting_and_trimming_audio)
        )
    }

    private fun exportSilentVideo() {
        if (selectedIsAudio) return toast(getString(R.string.silent_video_requires_video))
        val extension = binding.videoFormat.selectedItem.toString().lowercase(Locale.US)
        val arguments = when (extension) {
            "webm" -> listOf("-an", "-c:v", "libvpx-vp9", "-crf", "32", "-b:v", "0")
            "avi" -> listOf("-an", "-c:v", "mpeg4", "-q:v", "3")
            "mp4", "mov", "mkv" -> listOf("-an", "-c:v", "mpeg4", "-q:v", "3", "-pix_fmt", "yuv420p")
            else -> return toast("Unsupported video format")
        }
        process(
            kind = MediaProcessor.MediaKind.VIDEO,
            extension = extension,
            arguments = arguments,
            operationLabel = getString(R.string.creating_silent_video_clips)
        )
    }

    private fun process(
        kind: MediaProcessor.MediaKind,
        extension: String,
        arguments: List<String>,
        operationLabel: String
    ) {
        val uri = selectedUri ?: return toast(getString(R.string.choose_media_first))
        if (!splitterMediaEligible) {
            updateSplitterEligibilityMessage()
            return
        }
        val ranges = clipRangeController.ranges().getOrElse { throwable ->
            toast(throwable.message ?: getString(R.string.invalid_clip_ranges))
            return
        }

        lifecycleScope.launch {
            setBusy(true, operationLabel)
            processor.exportBatch(
                sourceUri = uri,
                ranges = ranges,
                kind = kind,
                extension = extension,
                codecArguments = arguments
            ) { progress ->
                runOnUiThread {
                    binding.batchProgress.progress = progress.overallPercent
                    binding.progressText.text = getString(
                        R.string.batch_progress_value,
                        progress.currentClip,
                        progress.totalClips,
                        progress.overallPercent
                    )
                    binding.statusLabel.text = progress.message
                }
            }.onSuccess { result ->
                setBusy(false, resources.getQuantityString(
                    R.plurals.media_files_saved,
                    result.savedPaths.size,
                    result.savedPaths.size
                ))
                toast(resources.getQuantityString(
                    R.plurals.media_files_saved,
                    result.savedPaths.size,
                    result.savedPaths.size
                ))
                splitMediaController.refresh()
            }.onFailure { throwable ->
                val message = when (throwable) {
                    is MediaProcessor.BatchCancelledException -> throwable.message ?: getString(R.string.operation_cancelled)
                    is NoClassDefFoundError, is UnsatisfiedLinkError, is ExceptionInInitializerError ->
                        getString(R.string.media_engine_start_failure)
                    else -> throwable.message?.lineSequence()?.firstOrNull()?.take(220)
                        ?: getString(R.string.media_operation_failed)
                }
                setBusy(false, getString(R.string.failed_value, message))
                toast(message)
                diagnosticsController.updateSummary()
            }
        }
    }

    private fun setBusy(busy: Boolean, message: String) {
        binding.batchProgress.visibility = if (busy) View.VISIBLE else View.GONE
        binding.progressText.visibility = if (busy) View.VISIBLE else View.GONE
        binding.cancelButton.visibility = if (busy) View.VISIBLE else View.GONE
        if (busy) {
            binding.batchProgress.progress = 0
            binding.progressText.text = getString(R.string.preparing_media)
        }
        binding.selectButton.isEnabled = !busy
        binding.audioFormat.isEnabled = !busy
        binding.videoFormat.isEnabled = !busy
        binding.exportAudioButton.isEnabled = !busy && selectedUri != null && splitterMediaEligible
        binding.exportVideoButton.isEnabled = !busy && selectedUri != null && splitterMediaEligible && !selectedIsAudio
        binding.bottomNavigation.isEnabled = !busy
        binding.overflowMenuButton.isEnabled = !busy
        clipRangeController.setEnabled(!busy)
        binding.statusLabel.text = message
    }

    private fun onSettingsChanged() {
        clipRangeController.applySettings()
        val uri = selectedUri
        if (uri != null && durationSeconds > 0.0) {
            val wasEligible = splitterMediaEligible
            splitterMediaEligible = durationSeconds <= appSettings.maxMediaDurationSeconds
            if (splitterMediaEligible && !wasEligible) clipRangeController.onMediaLoaded(durationSeconds)
            if (!splitterMediaEligible) clipRangeController.clear()
        }
        updateSplitterEligibilityMessage()
    }

    private fun updateSplitterEligibilityMessage(): Boolean {
        if (selectedUri == null) {
            binding.exportAudioButton.isEnabled = false
            binding.exportVideoButton.isEnabled = false
            return true
        }
        if (!splitterMediaEligible) {
            val message = getString(
                R.string.media_exceeds_configured_limit,
                formatTime(appSettings.maxMediaDurationSeconds.toDouble()),
                appSettings.maxMediaDurationSeconds
            )
            binding.statusLabel.text = message
            binding.exportAudioButton.isEnabled = false
            binding.exportVideoButton.isEnabled = false
            return false
        }
        binding.exportAudioButton.isEnabled = true
        binding.exportVideoButton.isEnabled = !selectedIsAudio
        return true
    }

    private fun showHelp() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.help)
            .setMessage(R.string.help_text)
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    private fun showAbout() {
        val packageInfo = packageManager.getPackageInfo(packageName, 0)
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.about)
            .setMessage(getString(R.string.about_text, packageInfo.versionName ?: "Unknown"))
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    private fun queryDisplayName(uri: Uri): String {
        contentResolver.query(uri, arrayOf(MediaStore.MediaColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) return cursor.getString(0) ?: "media"
        }
        return uri.lastPathSegment ?: "media"
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
