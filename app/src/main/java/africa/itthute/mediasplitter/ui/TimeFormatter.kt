package africa.itthute.mediasplitter.ui

import java.util.Locale

object TimeFormatter {
    fun format(seconds: Double): String {
        val safeSeconds = seconds.coerceAtLeast(0.0)
        val hours = (safeSeconds / 3600).toInt()
        val minutes = ((safeSeconds % 3600) / 60).toInt()
        val secs = safeSeconds % 60
        return if (hours > 0) {
            String.format(Locale.US, "%d:%02d:%04.1f", hours, minutes, secs)
        } else {
            String.format(Locale.US, "%02d:%04.1f", minutes, secs)
        }
    }
}
