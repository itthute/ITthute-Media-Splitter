package africa.itthute.mediasplitter

import kotlin.math.ceil

object DivisionRules {
    const val MIN_SOURCE_SECONDS = 30.0
    const val MAX_SOURCE_SECONDS = 3600.0
    const val LONG_SOURCE_THRESHOLD_SECONDS = 600.0
    const val MIN_SEGMENT_SECONDS = 30
    const val MAX_SEGMENT_SECONDS = 300

    fun isSourceEligible(durationSeconds: Double): Boolean =
        durationSeconds > MIN_SOURCE_SECONDS && durationSeconds < MAX_SOURCE_SECONDS

    fun minimumSegmentSeconds(durationSeconds: Double): Int =
        if (durationSeconds > LONG_SOURCE_THRESHOLD_SECONDS) MAX_SEGMENT_SECONDS else MIN_SEGMENT_SECONDS

    fun validationMessage(durationSeconds: Double, segmentSeconds: Int): String? {
        if (!isSourceEligible(durationSeconds)) {
            return "Media duration must be greater than 30 seconds and less than 3600 seconds."
        }
        if (segmentSeconds !in MIN_SEGMENT_SECONDS..MAX_SEGMENT_SECONDS) {
            return "Division length must be between 30 and 300 seconds."
        }
        val minimum = minimumSegmentSeconds(durationSeconds)
        if (segmentSeconds < minimum) {
            return "Media longer than 600 seconds must use 300-second divisions."
        }
        if (segmentSeconds >= durationSeconds) {
            return "Division length must be shorter than the loaded media."
        }
        return null
    }

    fun segmentCount(durationSeconds: Double, segmentSeconds: Int): Int {
        require(validationMessage(durationSeconds, segmentSeconds) == null)
        return ceil(durationSeconds / segmentSeconds).toInt()
    }
}
