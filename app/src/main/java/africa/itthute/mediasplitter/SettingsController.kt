package africa.itthute.mediasplitter

import android.graphics.Typeface
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.slider.Slider

class SettingsController(
    private val activity: MainActivity,
    private val settings: AppSettings,
    private val onSettingsChanged: () -> Unit
) {
    fun show() {
        val container = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(8), dp(22), dp(4))
        }

        val maxRanges = addSliderSetting(
            container = container,
            title = activity.getString(R.string.setting_max_clip_ranges),
            description = activity.getString(R.string.setting_max_clip_ranges_description),
            valueFrom = AppSettings.MIN_MAX_CLIP_RANGES.toFloat(),
            valueTo = AppSettings.MAX_MAX_CLIP_RANGES.toFloat(),
            stepSize = 1f,
            initialValue = settings.maxClipRanges.toFloat(),
            formatter = { value -> value.toInt().toString() }
        )
        val maxDuration = addSliderSetting(
            container = container,
            title = activity.getString(R.string.setting_max_media_duration),
            description = activity.getString(R.string.setting_max_media_duration_description),
            valueFrom = AppSettings.MIN_MAX_MEDIA_DURATION_SECONDS.toFloat(),
            valueTo = AppSettings.ABSOLUTE_MAX_MEDIA_DURATION_SECONDS.toFloat(),
            stepSize = 60f,
            initialValue = settings.maxMediaDurationSeconds.toFloat(),
            formatter = { value ->
                val seconds = value.toInt()
                "${ClipRangeRules.formatClock(seconds.toDouble())} ($seconds seconds)"
            }
        )
        val minimumLength = addSliderSetting(
            container = container,
            title = activity.getString(R.string.setting_minimum_clip_length),
            description = activity.getString(R.string.setting_minimum_clip_length_description),
            valueFrom = AppSettings.MIN_MINIMUM_CLIP_LENGTH_SECONDS.toFloat(),
            valueTo = AppSettings.MAX_MINIMUM_CLIP_LENGTH_SECONDS.toFloat(),
            stepSize = 1f,
            initialValue = settings.minimumClipLengthSeconds.toFloat(),
            formatter = { value -> "${value.toInt()} seconds" }
        )

        MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.settings)
            .setView(container)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.save) { _, _ ->
                settings.maxClipRanges = maxRanges.value.toInt()
                settings.maxMediaDurationSeconds = maxDuration.value.toInt()
                settings.minimumClipLengthSeconds = minimumLength.value.toInt()
                onSettingsChanged()
                Toast.makeText(activity, R.string.settings_saved, Toast.LENGTH_LONG).show()
            }
            .show()
    }

    private fun addSliderSetting(
        container: LinearLayout,
        title: String,
        description: String,
        valueFrom: Float,
        valueTo: Float,
        stepSize: Float,
        initialValue: Float,
        formatter: (Float) -> String
    ): Slider {
        val heading = TextView(activity).apply {
            text = title
            textSize = 17f
            setTypeface(typeface, Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(14) }
        }
        val explanation = TextView(activity).apply { text = description }
        val valueLabel = TextView(activity).apply {
            text = formatter(initialValue)
            setTypeface(typeface, Typeface.BOLD)
        }
        val slider = Slider(activity).apply {
            this.valueFrom = valueFrom
            this.valueTo = valueTo
            this.stepSize = stepSize
            value = initialValue.coerceIn(valueFrom, valueTo)
            addOnChangeListener { _, newValue, _ -> valueLabel.text = formatter(newValue) }
        }
        container.addView(heading)
        container.addView(explanation)
        container.addView(valueLabel)
        container.addView(slider)
        return slider
    }

    private fun dp(value: Int): Int = (value * activity.resources.displayMetrics.density).toInt()
}
