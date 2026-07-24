package africa.itthute.mediasplitter

import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Bundle
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import africa.itthute.mediasplitter.databinding.ActivityMainBinding
import africa.itthute.mediasplitter.media.ExportKind
import africa.itthute.mediasplitter.media.ExportRequest
import africa.itthute.mediasplitter.media.FFmpegMediaProcessor
import africa.itthute.mediasplitter.media.MediaFileManager
import africa.itthute.mediasplitter.media.OutputFormat
import africa.itthute.mediasplitter.media.OutputFormats
import africa.itthute.mediasplitter.ui.TimeFormatter
import com.google.android.material.slider.RangeSlider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.max

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private lateinit var fileManager: MediaFileManager
    private val processor = FFmpegMediaProcessor()

    private var inputUri: Uri? = null
    private var inputDurationSeconds = 0.0
    private var exportKind = ExportKind.AUDIO
    private var selectedFormat: OutputFormat = OutputFormats.audio.first()
    private var cachedInput: File? = null
    private var cachedOutput: File? = null

    private val pickVideo = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) loadVideo(uri)
    }

    private val createOutput = registerForActivityResult(ActivityResultContracts.CreateDocument("*/*")) { uri ->
        if (uri != null) beginExport(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        fileManager = MediaFileManager(this)

        configureFormatDropdown(OutputFormats.audio)
        configureListeners()
        updateRangeLabels(0.0, 0.0)
    }

    override fun onDestroy() {
        processor.cancel()
        fileManager.cleanup(cachedInput, cachedOutput)
        super.onDestroy()
    }

    private fun configureListeners() = with(binding) {
        selectVideoButton.setOnClickListener {
            pickVideo.launch(arrayOf("video/*", "application/octet-stream"))
        }

        outputTypeGroup.setOnCheckedChangeListener { _, checkedId ->
            exportKind = if (checkedId == R.id.videoRadio) ExportKind.SILENT_VIDEO else ExportKind.AUDIO
            configureFormatDropdown(
                if (exportKind == ExportKind.AUDIO) OutputFormats.audio else OutputFormats.silentVideo
            )
        }

        rangeSlider.addOnChangeListener { slider: RangeSlider, _: Float, _: Boolean ->
            val values = slider.values
            updateRangeLabels(values[0].toDouble(), values[1].toDouble())
        }

        exportButton.setOnClickListener {
            if (inputUri == null) {
                toast(getString(R.string.select_input_first))
                return@setOnClickListener
            }
            val values = rangeSlider.values
            if (values[1] <= values[0]) {
                toast(getString(R.string.select_valid_range))
                return@setOnClickListener
            }
            createOutput.launch(defaultOutputName(selectedFormat.extension))
        }

        cancelButton.setOnClickListener {
            processor.cancel()
            setProcessing(false)
            statusText.text = getString(R.string.export_cancelled)
        }
    }

    private fun configureFormatDropdown(formats: List<OutputFormat>) {
        selectedFormat = formats.first()
        val labels = formats.map { it.label }
        binding.formatDropdown.setAdapter(
            ArrayAdapter(this, android.R.layout.simple_dropdown_item_1line, labels)
        )
        binding.formatDropdown.setText(labels.first(), false)
        binding.formatDropdown.setOnItemClickListener { _, _, position, _ ->
            selectedFormat = formats[position]
        }
    }

    private fun loadVideo(uri: Uri) {
        runCatching {
            contentResolver.takePersistableUriPermission(
                uri,
                android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        }
        val duration = readDuration(uri)
        if (duration <= 0.0) {
            toast(getString(R.string.cannot_read_video))
            return
        }

        inputUri = uri
        inputDurationSeconds = duration
        binding.selectedFileText.text = fileManager.displayName(uri)
        binding.durationText.text = "Duration: ${TimeFormatter.format(duration)}"
        binding.rangeSlider.apply {
            isEnabled = true
            valueFrom = 0f
            valueTo = max(0.1f, duration.toFloat())
            stepSize = 0f
            values = listOf(0f, duration.toFloat())
        }
        binding.exportButton.isEnabled = true
        binding.statusText.text = "Ready to export ${fileManager.displayName(uri)}."
        updateRangeLabels(0.0, duration)
    }

    private fun readDuration(uri: Uri): Double {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(this, uri)
            val durationMs = retriever
                .extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull() ?: 0L
            durationMs / 1_000.0
        } catch (_: Exception) {
            0.0
        } finally {
            retriever.release()
        }
    }

    private fun beginExport(destinationUri: Uri) {
        val sourceUri = inputUri ?: return
        val values = binding.rangeSlider.values
        val start = values[0].toDouble()
        val end = values[1].toDouble()

        setProcessing(true)
        binding.statusText.text = getString(R.string.processing)
        lifecycleScope.launch {
            try {
                fileManager.cleanup(cachedInput, cachedOutput)
                cachedInput = withContext(Dispatchers.IO) {
                    fileManager.copyInputToCache(sourceUri)
                }
                cachedOutput = fileManager.createOutputCacheFile(selectedFormat.extension)

                val request = ExportRequest(
                    inputPath = requireNotNull(cachedInput).absolutePath,
                    outputPath = requireNotNull(cachedOutput).absolutePath,
                    startSeconds = start,
                    endSeconds = end,
                    format = selectedFormat
                )

                processor.export(
                    request = request,
                    onProgress = { progress ->
                        runOnUiThread { binding.progressIndicator.progress = progress }
                    },
                    onComplete = { result ->
                        lifecycleScope.launch {
                            result.fold(
                                onSuccess = {
                                    withContext(Dispatchers.IO) {
                                        fileManager.copyOutputToUri(
                                            requireNotNull(cachedOutput),
                                            destinationUri
                                        )
                                    }
                                    binding.progressIndicator.progress = 100
                                    binding.statusText.text = getString(R.string.export_complete)
                                    toast(getString(R.string.export_complete))
                                },
                                onFailure = { error ->
                                    binding.statusText.text = when (error) {
                                        is FFmpegMediaProcessor.ExportCancelledException ->
                                            getString(R.string.export_cancelled)
                                        else -> "Export failed: ${error.message ?: "Unknown error"}"
                                    }
                                }
                            )
                            setProcessing(false)
                            fileManager.cleanup(cachedInput, cachedOutput)
                            cachedInput = null
                            cachedOutput = null
                        }
                    }
                )
            } catch (error: Exception) {
                setProcessing(false)
                binding.statusText.text =
                    "Export failed: ${error.message ?: getString(R.string.cannot_write_output)}"
                fileManager.cleanup(cachedInput, cachedOutput)
                cachedInput = null
                cachedOutput = null
            }
        }
    }

    private fun setProcessing(processing: Boolean) = with(binding) {
        selectVideoButton.isEnabled = !processing
        outputTypeGroup.isEnabled = !processing
        audioRadio.isEnabled = !processing
        videoRadio.isEnabled = !processing
        formatDropdown.isEnabled = !processing
        rangeSlider.isEnabled = !processing && inputUri != null
        exportButton.isEnabled = !processing && inputUri != null
        cancelButton.isEnabled = processing
        progressIndicator.isVisible = processing
        if (processing) progressIndicator.progress = 0
    }

    private fun updateRangeLabels(start: Double, end: Double) = with(binding) {
        startTimeText.text = getString(R.string.start_time, TimeFormatter.format(start))
        endTimeText.text = getString(R.string.end_time, TimeFormatter.format(end))
        clipLengthText.text = getString(
            R.string.clip_length,
            TimeFormatter.format((end - start).coerceAtLeast(0.0))
        )
    }

    private fun defaultOutputName(extension: String): String {
        val base = inputUri
            ?.let(fileManager::displayName)
            ?.substringBeforeLast('.')
            ?.replace(Regex("[^A-Za-z0-9._-]+"), "_")
            ?.take(60)
            .orEmpty()
            .ifBlank { "ITthute_clip" }
        val suffix = if (exportKind == ExportKind.AUDIO) "audio" else "silent_video"
        return "${base}_${suffix}.${extension}"
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }
}
