package africa.itthute.mediasplitter

import android.graphics.Typeface
import android.net.Uri
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import africa.itthute.mediasplitter.databinding.ActivityMainBinding
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.google.android.material.radiobutton.MaterialRadioButton
import com.google.android.material.slider.Slider
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
    val pageView: ScrollView = ScrollView(activity).apply {
        layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        )
        isFillViewport = true
        visibility = View.GONE
    }

    private val selectMediaButton = MaterialButton(activity)
    private val fileLabel = TextView(activity)
    private val durationLabel = TextView(activity)
    private val eligibilityLabel = TextView(activity)
    private val optionGroup = RadioGroup(activity)
    private val division30 = MaterialRadioButton(activity)
    private val division60 = MaterialRadioButton(activity)
    private val division90 = MaterialRadioButton(activity)
    private val divisionCustom = MaterialRadioButton(activity)
    private val customSlider = Slider(activity)
    private val customValueLabel = TextView(activity)
    private val planLabel = TextView(activity)
    private val divideButton = MaterialButton(activity)
    private val progress = LinearProgressIndicator(activity)
    private val progressText = TextView(activity)
    private val cancelButton = MaterialButton(
        activity,
        null,
        com.google.android.material.R.attr.materialButtonOutlinedStyle
    )

    private var selectedUri: Uri? = null
    private var selectedName = ""
    private var selectedDurationSeconds = 0.0
    private var selectedIsAudio = false
    private var processing = false

    fun configure() {
        buildPage()
        val pageHost = binding.splitterPage.parent as ViewGroup
        if (pageView.parent == null) {
            pageHost.addView(pageView)
        }

        selectMediaButton.setOnClickListener { chooseMedia() }
        optionGroup.setOnCheckedChangeListener { _, _ ->
            customSlider.isEnabled =
                !processing && divisionCustom.isChecked &&
                    selectedDurationSeconds <= DivisionRules.LONG_SOURCE_THRESHOLD_SECONDS
            updatePlan()
        }
        customSlider.addOnChangeListener { _, value, _ ->
            customValueLabel.text = activity.getString(
                R.string.custom_division_value,
                value.roundToInt()
            )
            if (divisionCustom.isChecked) updatePlan()
        }
        divideButton.setOnClickListener { startDivision() }
        cancelButton.setOnClickListener {
            processor.cancel()
            progressText.text = activity.getString(R.string.division_cancellation_requested)
        }
        resetForNoMedia()
    }

    fun onMediaLoaded(uri: Uri, displayName: String, durationSeconds: Double, isAudio: Boolean) {
        selectedUri = uri
        selectedName = displayName
        selectedDurationSeconds = durationSeconds
        selectedIsAudio = isAudio
        fileLabel.text = displayName
        durationLabel.text = activity.getString(
            R.string.duration_value,
            formatDuration(durationSeconds)
        )
        configureOptionsForDuration()
        updatePlan()
    }

    private fun buildPage() {
        val content = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), dp(24))
        }
        pageView.addView(content)

        content.addView(TextView(activity).apply {
            text = activity.getString(R.string.file_divider)
            textSize = 30f
            setTypeface(typeface, Typeface.BOLD)
        })
        content.addView(TextView(activity).apply {
            text = activity.getString(R.string.file_divider_description)
            setPadding(0, dp(4), 0, dp(12))
        })

        selectMediaButton.apply {
            text = activity.getString(R.string.choose_media_for_division)
        }
        content.addView(selectMediaButton, matchWrap())

        fileLabel.apply {
            setTypeface(typeface, Typeface.BOLD)
            setPadding(0, dp(12), 0, 0)
        }
        content.addView(fileLabel, matchWrap())
        content.addView(durationLabel, matchWrap())
        eligibilityLabel.setPadding(0, dp(8), 0, dp(8))
        content.addView(eligibilityLabel, matchWrap())

        val optionsCard = MaterialCardView(activity).apply {
            useCompatPadding = true
        }
        val options = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(16))
        }
        optionsCard.addView(options)
        content.addView(optionsCard, matchWrap(top = 8))

        options.addView(TextView(activity).apply {
            text = activity.getString(R.string.division_length)
            textSize = 20f
            setTypeface(typeface, Typeface.BOLD)
        })

        optionGroup.orientation = RadioGroup.VERTICAL
        division30.id = View.generateViewId()
        division60.id = View.generateViewId()
        division90.id = View.generateViewId()
        divisionCustom.id = View.generateViewId()
        division30.text = activity.getString(R.string.divide_every_30_seconds)
        division60.text = activity.getString(R.string.divide_every_60_seconds)
        division90.text = activity.getString(R.string.divide_every_90_seconds)
        divisionCustom.text = activity.getString(R.string.custom_division_length)
        optionGroup.addView(division30, matchWrap())
        optionGroup.addView(division60, matchWrap())
        optionGroup.addView(division90, matchWrap())
        optionGroup.addView(divisionCustom, matchWrap())
        options.addView(optionGroup, matchWrap())

        customSlider.apply {
            valueFrom = DivisionRules.MIN_SEGMENT_SECONDS.toFloat()
            valueTo = DivisionRules.MAX_SEGMENT_SECONDS.toFloat()
            stepSize = 1f
            value = DivisionRules.MIN_SEGMENT_SECONDS.toFloat()
            isEnabled = false
        }
        options.addView(customSlider, matchWrap(top = 8))
        customValueLabel.apply {
            gravity = Gravity.CENTER_HORIZONTAL
            text = activity.getString(
                R.string.custom_division_value,
                DivisionRules.MIN_SEGMENT_SECONDS
            )
        }
        options.addView(customValueLabel, matchWrap())

        planLabel.setPadding(0, dp(12), 0, dp(4))
        content.addView(planLabel, matchWrap())

        divideButton.apply {
            text = activity.getString(R.string.divide_loaded_media)
            isEnabled = false
        }
        content.addView(divideButton, matchWrap(top = 8))

        progress.apply {
            max = 100
            progress = 0
            visibility = View.GONE
        }
        content.addView(progress, matchWrap(top = 16))
        progressText.setPadding(0, dp(8), 0, 0)
        content.addView(progressText, matchWrap())

        cancelButton.apply {
            text = activity.getString(R.string.cancel_file_division)
            visibility = View.GONE
        }
        content.addView(cancelButton, matchWrap(top = 8))

        content.addView(TextView(activity).apply {
            text = activity.getString(R.string.divider_output_note)
            textSize = 12f
            setPadding(0, dp(16), 0, 0)
        }, matchWrap())
    }

    private fun configureOptionsForDuration() {
        val eligible = DivisionRules.isSourceEligible(selectedDurationSeconds)
        val isLong = selectedDurationSeconds > DivisionRules.LONG_SOURCE_THRESHOLD_SECONDS
        division30.isEnabled = eligible && !isLong && selectedDurationSeconds > 30
        division60.isEnabled = eligible && !isLong && selectedDurationSeconds > 60
        division90.isEnabled = eligible && !isLong && selectedDurationSeconds > 90
        divisionCustom.isEnabled = eligible

        if (!eligible) {
            eligibilityLabel.text = when {
                selectedDurationSeconds <= DivisionRules.MIN_SOURCE_SECONDS ->
                    activity.getString(R.string.divider_too_short)
                else -> activity.getString(R.string.divider_too_long)
            }
            optionGroup.clearCheck()
            customSlider.isEnabled = false
            divideButton.isEnabled = false
            return
        }

        if (isLong) {
            eligibilityLabel.text = activity.getString(R.string.long_media_division_rule)
            divisionCustom.isChecked = true
            customSlider.value = DivisionRules.MAX_SEGMENT_SECONDS.toFloat()
            customSlider.isEnabled = false
        } else {
            eligibilityLabel.text = activity.getString(R.string.divider_ready)
            val maxValid = floor(selectedDurationSeconds - 0.001)
                .toInt()
                .coerceIn(DivisionRules.MIN_SEGMENT_SECONDS, DivisionRules.MAX_SEGMENT_SECONDS)
            if (customSlider.value.roundToInt() > maxValid) {
                customSlider.value = maxValid.toFloat()
            }
            if (optionGroup.checkedRadioButtonId == View.NO_ID ||
                selectedSegmentSecondsOrNull()?.let { it >= selectedDurationSeconds } == true
            ) {
                division30.isChecked = true
            }
            customSlider.isEnabled = divisionCustom.isChecked
        }
    }

    private fun updatePlan() {
        val segmentSeconds = selectedSegmentSecondsOrNull()
        if (segmentSeconds == null) {
            planLabel.text = activity.getString(R.string.choose_division_length)
            divideButton.isEnabled = false
            return
        }
        val validation = DivisionRules.validationMessage(selectedDurationSeconds, segmentSeconds)
        if (validation != null) {
            planLabel.text = validation
            divideButton.isEnabled = false
            return
        }
        val count = DivisionRules.segmentCount(selectedDurationSeconds, segmentSeconds)
        planLabel.text = activity.resources.getQuantityString(
            R.plurals.division_plan_count,
            count,
            count,
            segmentSeconds,
            if (selectedIsAudio) "M4A" else "MP4"
        )
        divideButton.isEnabled = !processing
    }

    private fun selectedSegmentSecondsOrNull(): Int? = when (optionGroup.checkedRadioButtonId) {
        division30.id -> 30
        division60.id -> 60
        division90.id -> 90
        divisionCustom.id -> customSlider.value.roundToInt()
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
            ) { divisionUpdate ->
                activity.runOnUiThread {
                    progress.progress = divisionUpdate.overallPercent
                    progressText.text = activity.getString(
                        R.string.division_progress_value,
                        divisionUpdate.currentPart,
                        divisionUpdate.totalParts,
                        divisionUpdate.overallPercent
                    )
                }
            }.onSuccess { result ->
                progress.progress = 100
                progressText.text = activity.resources.getQuantityString(
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
                progressText.text = message
                toast(message ?: activity.getString(R.string.division_failed))
            }
            setProcessing(false)
        }
    }

    private fun setProcessing(value: Boolean) {
        processing = value
        selectMediaButton.isEnabled = !value
        division30.isEnabled = !value && selectedDurationSeconds > 30 && selectedDurationSeconds <= 600
        division60.isEnabled = !value && selectedDurationSeconds > 60 && selectedDurationSeconds <= 600
        division90.isEnabled = !value && selectedDurationSeconds > 90 && selectedDurationSeconds <= 600
        divisionCustom.isEnabled = !value && DivisionRules.isSourceEligible(selectedDurationSeconds)
        customSlider.isEnabled = !value && divisionCustom.isChecked && selectedDurationSeconds <= 600
        divideButton.isEnabled = !value &&
            selectedSegmentSecondsOrNull()?.let {
                DivisionRules.validationMessage(selectedDurationSeconds, it) == null
            } == true
        cancelButton.visibility = if (value) View.VISIBLE else View.GONE
        progress.visibility = if (value) View.VISIBLE else View.GONE
        binding.bottomNavigation.isEnabled = !value
        if (value) {
            progress.progress = 0
            progressText.text = activity.getString(R.string.preparing_division)
        } else {
            configureOptionsForDuration()
            updatePlan()
        }
    }

    private fun resetForNoMedia() {
        fileLabel.text = activity.getString(R.string.no_file_selected)
        durationLabel.text = activity.getString(R.string.duration_unknown)
        eligibilityLabel.text = activity.getString(R.string.choose_media_for_division)
        optionGroup.clearCheck()
        division30.isEnabled = false
        division60.isEnabled = false
        division90.isEnabled = false
        divisionCustom.isEnabled = false
        customSlider.isEnabled = false
        divideButton.isEnabled = false
        cancelButton.visibility = View.GONE
        progress.visibility = View.GONE
        progressText.text = activity.getString(R.string.no_division_in_progress)
        customValueLabel.text = activity.getString(
            R.string.custom_division_value,
            DivisionRules.MIN_SEGMENT_SECONDS
        )
    }

    private fun matchWrap(top: Int = 0): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(top) }

    private fun dp(value: Int): Int =
        (value * activity.resources.displayMetrics.density).toInt()

    private fun formatDuration(seconds: Double): String {
        val total = seconds.toLong()
        return "%02d:%02d:%02d".format(total / 3600, (total % 3600) / 60, total % 60)
    }

    private fun toast(message: String) = Toast.makeText(activity, message, Toast.LENGTH_LONG).show()
}
