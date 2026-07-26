package africa.itthute.mediasplitter

import android.graphics.Typeface
import android.text.InputType
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.widget.doAfterTextChanged
import africa.itthute.mediasplitter.databinding.ActivityMainBinding
import com.google.android.material.card.MaterialCardView
import com.google.android.material.slider.RangeSlider
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

class ClipRangeController(
    private val activity: MainActivity,
    private val binding: ActivityMainBinding,
    private val settings: AppSettings
) {
    private val holders = mutableListOf<RangeHolder>()
    private var mediaDurationSeconds = 0.0
    private var enabled = true

    fun configure() {
        binding.clipCountSlider.valueFrom = 1f
        binding.clipCountSlider.stepSize = 1f
        binding.clipCountSlider.addOnChangeListener { _, value, fromUser ->
            updateCountLabel(value.toInt())
            if (fromUser && mediaDurationSeconds > 0.0) {
                renderRanges(ClipRangeRules.evenlySpaced(mediaDurationSeconds, value.toInt()))
            }
        }
        applySettings()
        updateCountLabel(1)
    }

    fun onMediaLoaded(durationSeconds: Double) {
        mediaDurationSeconds = durationSeconds
        applySettings()
        binding.clipCountSlider.value = 1f
        renderRanges(listOf(ClipRange(0.0, durationSeconds)))
    }

    fun clear() {
        mediaDurationSeconds = 0.0
        holders.clear()
        binding.clipRangesContainer.removeAllViews()
        binding.clipCountSlider.value = 1f
        binding.clipCountSlider.isEnabled = false
        binding.clipRangesHint.text = activity.getString(R.string.choose_media_before_ranges)
    }

    fun applySettings() {
        val effectiveMax = effectiveMaximumRanges()
        binding.clipCountSlider.valueTo = max(1, effectiveMax).toFloat()
        binding.clipCountSlider.isEnabled = enabled && mediaDurationSeconds > 0.0 && effectiveMax > 1
        val currentCount = holders.size.coerceAtLeast(1)
        if (currentCount > effectiveMax && mediaDurationSeconds > 0.0) {
            binding.clipCountSlider.value = effectiveMax.toFloat()
            renderRanges(ClipRangeRules.evenlySpaced(mediaDurationSeconds, effectiveMax))
        }
        updateCountLabel(min(currentCount, effectiveMax))
    }

    fun setEnabled(value: Boolean) {
        enabled = value
        binding.clipCountSlider.isEnabled = value && mediaDurationSeconds > 0.0 && effectiveMaximumRanges() > 1
        holders.forEach { holder ->
            holder.slider.isEnabled = value
            holder.startInput.isEnabled = value
            holder.endInput.isEnabled = value
        }
    }

    fun ranges(): Result<List<ClipRange>> = runCatching {
        check(mediaDurationSeconds > 0.0) { "Choose a video or audio file first." }
        val parsed = holders.mapIndexed { index, holder ->
            val start = ClipRangeRules.parseTime(holder.startInput.text?.toString())
                ?: error("Clip ${index + 1} has an invalid start time.")
            val end = ClipRangeRules.parseTime(holder.endInput.text?.toString())
                ?: error("Clip ${index + 1} has an invalid end time.")
            ClipRange(start, end)
        }
        ClipRangeRules.validate(
            ranges = parsed,
            mediaDurationSeconds = mediaDurationSeconds,
            maximumRanges = settings.maxClipRanges,
            minimumClipLengthSeconds = settings.minimumClipLengthSeconds.toDouble()
        )?.let { error(it) }
        parsed
    }

    private fun effectiveMaximumRanges(): Int {
        if (mediaDurationSeconds <= 0.0) return settings.maxClipRanges
        val byMinimumLength = floor(mediaDurationSeconds / settings.minimumClipLengthSeconds).toInt().coerceAtLeast(1)
        return min(settings.maxClipRanges, byMinimumLength)
    }

    private fun updateCountLabel(count: Int) {
        binding.clipCountLabel.text = activity.resources.getQuantityString(
            R.plurals.clip_range_count,
            count,
            count,
            effectiveMaximumRanges()
        )
    }

    private fun renderRanges(ranges: List<ClipRange>) {
        holders.clear()
        binding.clipRangesContainer.removeAllViews()
        binding.clipRangesHint.text = activity.getString(
            R.string.clip_ranges_validation_hint,
            settings.minimumClipLengthSeconds
        )
        ranges.forEachIndexed { index, range -> addRangeCard(index, range) }
        setEnabled(enabled)
    }

    private fun addRangeCard(index: Int, initial: ClipRange) {
        val card = MaterialCardView(activity).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(10) }
            radius = dp(12).toFloat()
            strokeWidth = dp(1)
            setContentPadding(dp(14), dp(12), dp(14), dp(14))
        }
        val content = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
        }
        val title = TextView(activity).apply {
            text = activity.getString(R.string.clip_number, index + 1)
            textSize = 18f
            setTypeface(typeface, Typeface.BOLD)
        }
        val summary = TextView(activity).apply {
            text = durationSummary(initial)
        }
        val slider = RangeSlider(activity).apply {
            valueFrom = 0f
            valueTo = max(mediaDurationSeconds, 0.001).toFloat()
            stepSize = 0f
            values = listOf(initial.startSeconds.toFloat(), initial.endSeconds.toFloat())
        }
        val inputRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        val startLayout = TextInputLayout(activity).apply {
            hint = activity.getString(R.string.start_time_hint)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginEnd = dp(5)
            }
        }
        val startInput = TextInputEditText(activity).apply {
            inputType = InputType.TYPE_CLASS_TEXT
            setText(ClipRangeRules.formatSeconds(initial.startSeconds))
        }
        val endLayout = TextInputLayout(activity).apply {
            hint = activity.getString(R.string.end_time_hint)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = dp(5)
            }
        }
        val endInput = TextInputEditText(activity).apply {
            inputType = InputType.TYPE_CLASS_TEXT
            setText(ClipRangeRules.formatSeconds(initial.endSeconds))
        }
        startLayout.addView(startInput)
        endLayout.addView(endInput)
        inputRow.addView(startLayout)
        inputRow.addView(endLayout)
        content.addView(title)
        content.addView(summary)
        content.addView(slider)
        content.addView(inputRow)
        card.addView(content)
        binding.clipRangesContainer.addView(card)

        val holder = RangeHolder(slider, startInput, endInput, summary)
        holders += holder
        var synchronising = false

        slider.addOnChangeListener { changed, _, _ ->
            if (synchronising) return@addOnChangeListener
            synchronising = true
            val values = changed.values
            startInput.setText(ClipRangeRules.formatSeconds(values[0].toDouble()))
            startInput.setSelection(startInput.text?.length ?: 0)
            endInput.setText(ClipRangeRules.formatSeconds(values[1].toDouble()))
            endInput.setSelection(endInput.text?.length ?: 0)
            summary.text = durationSummary(ClipRange(values[0].toDouble(), values[1].toDouble()))
            synchronising = false
        }

        fun syncSliderFromText() {
            if (synchronising) return
            val start = ClipRangeRules.parseTime(startInput.text?.toString()) ?: return
            val end = ClipRangeRules.parseTime(endInput.text?.toString()) ?: return
            if (start < 0.0 || end <= start || end > mediaDurationSeconds) return
            synchronising = true
            slider.values = listOf(start.toFloat(), end.toFloat())
            summary.text = durationSummary(ClipRange(start, end))
            synchronising = false
        }
        startInput.doAfterTextChanged { syncSliderFromText() }
        endInput.doAfterTextChanged { syncSliderFromText() }
    }

    private fun durationSummary(range: ClipRange): String = activity.getString(
        R.string.clip_range_summary,
        ClipRangeRules.formatClock(range.startSeconds),
        ClipRangeRules.formatClock(range.endSeconds),
        ClipRangeRules.formatSeconds(range.durationSeconds)
    )

    private fun dp(value: Int): Int = (value * activity.resources.displayMetrics.density).toInt()

    private data class RangeHolder(
        val slider: RangeSlider,
        val startInput: TextInputEditText,
        val endInput: TextInputEditText,
        val summary: TextView
    )
}
