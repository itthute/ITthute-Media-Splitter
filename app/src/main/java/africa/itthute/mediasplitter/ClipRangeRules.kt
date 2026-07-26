package africa.itthute.mediasplitter

import kotlin.math.min

data class ClipRange(val startSeconds: Double, val endSeconds: Double) {
    val durationSeconds: Double get() = endSeconds - startSeconds
}

object ClipRangeRules {
    fun validate(
        ranges: List<ClipRange>,
        mediaDurationSeconds: Double,
        maximumRanges: Int,
        minimumClipLengthSeconds: Double
    ): String? {
        if (ranges.isEmpty()) return "Add at least one clip range."
        if (ranges.size > maximumRanges) {
            return "The current settings allow at most $maximumRanges clip ranges."
        }
        ranges.forEachIndexed { index, range ->
            val number = index + 1
            if (!range.startSeconds.isFinite() || !range.endSeconds.isFinite()) {
                return "Clip $number contains an invalid time value."
            }
            if (range.startSeconds < 0.0) return "Clip $number starts before 0 seconds."
            if (range.endSeconds <= range.startSeconds) {
                return "Clip $number must end after it starts."
            }
            if (range.endSeconds > mediaDurationSeconds + 0.001) {
                return "Clip $number ends after the loaded media."
            }
            if (range.durationSeconds + 0.001 < minimumClipLengthSeconds) {
                return "Clip $number is too short (${formatSeconds(range.durationSeconds)} seconds). " +
                    "The configured minimum is ${formatSeconds(minimumClipLengthSeconds)} seconds."
            }
            if (index > 0) {
                val previous = ranges[index - 1]
                if (range.startSeconds + 0.001 < previous.endSeconds) {
                    return "Clip $number overlaps clip $index. Clip ranges must be sequential and non-overlapping."
                }
            }
        }
        return null
    }

    fun evenlySpaced(mediaDurationSeconds: Double, count: Int): List<ClipRange> {
        require(mediaDurationSeconds > 0.0)
        require(count > 0)
        val width = mediaDurationSeconds / count
        return List(count) { index ->
            val start = width * index
            val end = if (index == count - 1) mediaDurationSeconds else min(mediaDurationSeconds, width * (index + 1))
            ClipRange(start, end)
        }
    }

    fun parseTime(raw: String?): Double? {
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

    fun formatClock(seconds: Double): String {
        val safe = seconds.coerceAtLeast(0.0)
        val hours = (safe / 3600).toInt()
        val minutes = ((safe % 3600) / 60).toInt()
        val secs = safe % 60
        return java.lang.String.format(java.util.Locale.US, "%02d:%02d:%06.3f", hours, minutes, secs)
    }

    fun formatSeconds(seconds: Double): String =
        java.lang.String.format(java.util.Locale.US, "%.3f", seconds)
}
