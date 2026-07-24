package africa.itthute.mediasplitter

import android.net.Uri
import android.view.View
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import africa.itthute.mediasplitter.databinding.ActivityMainBinding
import kotlinx.coroutines.launch
import kotlin.math.floor
import kotlin.math.roundToInt

class FileDividerController(
    private val activity: MainActivity,
    private val binding: ActivityMainBinding,
    private val processor: MediaProcessor,
    private val diagnostics: DiagnosticsStore,
    private val chooseMedia: () -> Unit,
    private val onFilesCreated: () -> Unit
) {
    private var selectedUri: Uri? = null
    private var selectedName = ""
    private var selectedDurationSeconds = 0.0
    private var selectedIsAudio = false
    private var processing = false

    fun configure() {
        binding.selectDividerMediaButton.setOnClickListener { chooseMedia() }
        binding.divisionOptionGroup.setOnCheckedChangeListener { _, _ ->
            binding.customDivisionSlider.isEnabled =
                !processing && binding.divisionCustom.isChecked && selectedDurationSeconds <= DivisionRules.LONG_SOURCE_THRESHOLD_SECONDS
            updatePlan()
        }
        binding.customDivisionSlider.addOnChangeListener { _, value, _ ->
            binding.customDivisionValue.text = activity.getString(
                R.string.custom_division_value,
                value.roundToInt()
            )
            if (binding.divisionCustom.isChecked) updatePlan()
        }
        binding.divideFileButton.setOnClickListener { startDivision() }
        binding.cancelDivisionButton.setOnClickListener {
            processor.cancel()
            binding.divisionProgressText.text = activity.getString(R.string.division_cancellation_requested)
        }
        resetForNoMedia()
    }

    fun onMediaLoaded(uri: Uri, displayName: String, durationSeconds: Double, isAudio: Boolean) {
        selectedUri = uri
        selectedName = displayName
        selectedDurationSeconds = durationSeconds
        selectedIsAudio = isAudio
        binding.dividerFileLabel.text = displayName
        binding.dividerDurationLabel.text = activity.getString(
            R.string.duration_value,
            formatDuration(durationSeconds)
        )
        configureOptionsForDuration()
        updatePlan()
    }

    private fun configureOptionsForDuration() {
        val eligible = DivisionRules.isSourceEligible(selectedDurationSeconds)
        val isLong = selectedDurationSeconds > DivisionRules.LONG_SOURCE_THRESHOLD_SECONDS
        binding.division30.isEnabled = eligible && !isLong && selectedDurationSeconds > 30
        binding.division60.isEnabled = eligible && !isLong && selectedDurationSeconds > 60
        binding.division90.isEnabled = eligible && !isLong && selectedDurationSeconds > 90
        binding.divisionCustom.isEnabled = eligible

        if (!eligible) {
            binding.dividerEligibilityLabel.text = when {
                selectedDurationSeconds <= DivisionRules.MIN_SOURCE_SECONDS ->
                    activity.getString(R.string.divider_too_short)
                else -> activity.getString(R.string.divider_too_long)
            }
            binding.divisionOptionGroup.clearCheck()
            binding.customDivisionSlider.isEnabled = false
            binding.divideFileButton.isEnabled = false
            return
        }

        if (isLong) {
            binding.dividerEligibilityLabel.text = activity.getString(R.string.long_media_division_rule)
            binding.divisionCustom.isChecked = true
            binding.customDivisionSlider.value = DivisionRules.MAX_SEGMENT_SECONDS.toFloat()
            binding.customDivisionSlider.isEnabled = false
        } else {
            binding.dividerEligibilityLabel.text = activity.getString(R.string.divider_ready)
            val maxValid = floor(selectedDurationSeconds - 0.001)
                .toInt()
                .coerceIn(DivisionRules.MIN_SEGMENT_SECONDS, DivisionRules.MAX_SEGMENT_SECONDS)
            if (binding.customDivisionSlider.value.roundToInt() > maxValid) {
                binding.customDivisionSlider.value = maxValid.toFloat()
            }
            if (binding.divisionOptionGroup.checkedRadioButtonId == View.NO_ID ||
                selectedSegmentSecondsOrNull()?.let { it >= selectedDurationSeconds } == true
            ) {
                binding.division30.isChecked = true
            }
            binding.customDivisionSlider.isEnabled = binding.divisionCustom.isChecked
        }
    }

    private fun updatePlan() {
        val segmentSeconds = selectedSegmentSecondsOrNull()
        if (segmentSeconds == null) {
            binding.divisionPlanLabel.text = activity.getString(R.string.choose_division_length)
            binding.divideFileButton.isEnabled = false
            return
        }
        val validation = DivisionRules.validationMessage(selectedDurationSeconds, segmentSeconds)
        if (validation != null) {
            binding.divisionPlanLabel.text = validation
            binding.divideFileButton.isEnabled = false
            return
        }
        val count = DivisionRules.segmentCount(selectedDurationSeconds, segmentSeconds)
        binding.divisionPlanLabel.text = activity.resources.getQuantityString(
            R.plurals.division_plan_count,
            count,
            count,
            segmentSeconds,
            if (selectedIsAudio) "M4A" else "MP4"
        )
        binding.divideFileButton.isEnabled = !processing
    }

    private fun selectedSegmentSecondsOrNull(): Int? = when (binding.divisionOptionGroup.checkedRadioButtonId) {
        R.id.division30 -> 30
        R.id.division60 -> 60
        R.id.division90 -> 90
        R.id.divisionCustom -> binding.customDivisionSlider.value.roundToInt()
        else -> null
    }

    private fun startDivision() {
        val uri = selectedUri ?: return toast(activity.getString(R.string.choose_media_first))
        val segmentSeconds = selectedSegmentSecondsOrNull()
            ?: return toast(activity.getString(R.string.choose_division_length))
        DivisionRules.validationMessage(selectedDurationSeconds, segmentSeconds)?.let {
            return toast(it)
        }

        setProcessing(true)
        diagnostics.log(
            "INFO",
            "User started file division for $selectedName using ${segmentSeconds}s parts"
        )
        activity.lifecycleScope.launch {
            processor.divide(
                sourceUri = uri,
                totalDurationSeconds = selectedDurationSeconds,
                segmentSeconds = segmentSeconds,
                sourceIsAudio = selectedIsAudio
            ) { progress ->
                activity.runOnUiThread {
                    binding.divisionProgress.progress = progress.overallPercent
                    binding.divisionProgressText.text = activity.getString(
                        R.string.division_progress_value,
                        progress.currentPart,
                        progress.totalParts,
                        progress.overallPercent
                    )
                }
            }.onSuccess { result ->
                binding.divisionProgress.progress = 100
                binding.divisionProgressText.text = activity.resources.getQuantityString(
                    R.plurals.division_complete_count,
                    result.savedPaths.size,
                    result.savedPaths.size
                )
                toast(
                    activity.resources.getQuantityString(
                        R.plurals.division_complete_count,
                        result.savedPaths.size,
                        result.savedPaths.size
                    )
                )
                onFilesCreated()
            }.onFailure { throwable ->
                val message = when (throwable) {
                    is MediaProcessor.DivisionCancelledException -> throwable.message
                    else -> throwable.message?.lineSequence()?.firstOrNull()?.take(180)
                        ?: activity.getString(R.string.division_failed)
                }
                binding.divisionProgressText.text = message
                toast(message ?: activity.getString(R.string.division_failed))
            }
            setProcessing(false)
        }
    }

    private fun setProcessing(value: Boolean) {
        processing = value
        binding.selectDividerMediaButton.isEnabled = !value
        binding.division30.isEnabled = !value && selectedDurationSeconds > 30 && selectedDurationSeconds <= 600
        binding.division60.isEnabled = !value && selectedDurationSeconds > 60 && selectedDurationSeconds <= 600
        binding.division90.isEnabled = !value && selectedDurationSeconds > 90 && selectedDurationSeconds <= 600
        binding.divisionCustom.isEnabled = !value && DivisionRules.isSourceEligible(selectedDurationSeconds)
        binding.customDivisionSlider.isEnabled = !value && binding.divisionCustom.isChecked && selectedDurationSeconds <= 600
        binding.divideFileButton.isEnabled = !value &&
            selectedSegmentSecondsOrNull()?.let {
                DivisionRules.validationMessage(selectedDurationSeconds, it) == null
            } == true
        binding.cancelDivisionButton.visibility = if (value) View.VISIBLE else View.GONE
        binding.divisionProgress.visibility = if (value) View.VISIBLE else View.GONE
        binding.bottomNavigation.isEnabled = !value
        if (value) {
            binding.divisionProgress.progress = 0
            binding.divisionProgressText.text = activity.getString(R.string.preparing_division)
        } else {
            configureOptionsForDuration()
            updatePlan()
        }
    }

    private fun resetForNoMedia() {
        binding.dividerFileLabel.text = activity.getString(R.string.no_file_selected)
        binding.dividerDurationLabel.text = activity.getString(R.string.duration_unknown)
        binding.dividerEligibilityLabel.text = activity.getString(R.string.choose_media_for_division)
        binding.divisionOptionGroup.clearCheck()
        binding.division30.isEnabled = false
        binding.division60.isEnabled = false
        binding.division90.isEnabled = false
        binding.divisionCustom.isEnabled = false
        binding.customDivisionSlider.isEnabled = false
        binding.divideFileButton.isEnabled = false
        binding.cancelDivisionButton.visibility = View.GONE
        binding.divisionProgress.visibility = View.GONE
        binding.divisionProgressText.text = activity.getString(R.string.no_division_in_progress)
        binding.customDivisionValue.text = activity.getString(R.string.custom_division_value, 30)
    }

    private fun formatDuration(seconds: Double): String {
        val total = seconds.toLong()
        return "%02d:%02d:%02d".format(total / 3600, (total % 3600) / 60, total % 60)
    }

    private fun toast(message: String) = Toast.makeText(activity, message, Toast.LENGTH_LONG).show()
}
