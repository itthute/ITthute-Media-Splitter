package africa.itthute.mediasplitter

import android.content.Context
import android.os.Build
import android.os.StatFs
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class DiagnosticsStore(private val context: Context) {
    private val directory = File(context.filesDir, "diagnostics").apply { mkdirs() }
    private val logFile = File(directory, "diagnostics.log")
    private val preferences = context.getSharedPreferences("diagnostics", Context.MODE_PRIVATE)
    private val timestampFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS Z", Locale.US)

    @Synchronized
    fun log(level: String, message: String, throwable: Throwable? = null) {
        val line = buildString {
            append(timestampFormat.format(Date()))
            append(" [")
            append(level.uppercase(Locale.US))
            append("] ")
            append(message)
            if (throwable != null) {
                append('\n')
                append(stackTraceOf(throwable))
                preferences.edit()
                    .putString("last_error", throwable.toString())
                    .putLong("last_error_time", System.currentTimeMillis())
                    .apply()
            }
            append('\n')
        }
        logFile.appendText(line)
        trimIfNeeded()
    }

    fun setLastDestination(relativePath: String) {
        preferences.edit().putString("last_destination", relativePath).apply()
    }

    fun lastDestination(): String =
        preferences.getString("last_destination", SplitMediaRepository.VIDEO_FOLDER)
            ?: SplitMediaRepository.VIDEO_FOLDER

    fun readLogs(): String = if (logFile.exists()) logFile.readText() else "No diagnostics logs have been recorded."

    @Synchronized
    fun clear() {
        if (logFile.exists()) logFile.delete()
        preferences.edit().clear().commit()
    }

    fun buildReport(ffmpegStatus: String): String {
        val packageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
        val storage = StatFs(context.filesDir.absolutePath)
        val lastError = preferences.getString("last_error", "None") ?: "None"
        val lastErrorTime = preferences.getLong("last_error_time", 0L)
        val nativeDirectory = context.applicationInfo.nativeLibraryDir
        val nativeFiles = File(nativeDirectory).listFiles()
            ?.sortedBy { it.name }
            ?.joinToString(", ") { it.name }
            ?: "Unavailable"

        return buildString {
            appendLine("ITthute Media Splitter diagnostics report")
            appendLine("Generated: ${timestampFormat.format(Date())}")
            appendLine()
            appendLine("APP")
            appendLine("Package: ${context.packageName}")
            appendLine("Version: ${packageInfo.versionName} (${packageInfo.longVersionCode})")
            appendLine("Data directory: ${context.filesDir.absolutePath}")
            appendLine("Native library directory: $nativeDirectory")
            appendLine("Native libraries: $nativeFiles")
            appendLine()
            appendLine("DEVICE")
            appendLine("Manufacturer: ${Build.MANUFACTURER}")
            appendLine("Model: ${Build.MODEL}")
            appendLine("Device: ${Build.DEVICE}")
            appendLine("Android: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
            appendLine("Build fingerprint: ${Build.FINGERPRINT}")
            appendLine("Supported ABIs: ${Build.SUPPORTED_ABIS.joinToString()}")
            appendLine("Available app storage: ${storage.availableBytes} bytes")
            appendLine()
            appendLine("MEDIA ENGINE")
            appendLine(ffmpegStatus)
            appendLine()
            appendLine("STATE")
            appendLine("Last saved destination: ${lastDestination()}")
            appendLine("Last error: $lastError")
            appendLine("Last error time: ${if (lastErrorTime == 0L) "None" else timestampFormat.format(Date(lastErrorTime))}")
            appendLine()
            appendLine("DETAILED LOGS")
            append(readLogs())
        }
    }

    private fun stackTraceOf(throwable: Throwable): String {
        val writer = StringWriter()
        throwable.printStackTrace(PrintWriter(writer))
        return writer.toString()
    }

    private fun trimIfNeeded() {
        val maxBytes = 512 * 1024
        if (!logFile.exists() || logFile.length() <= maxBytes) return
        val text = logFile.readText()
        val keepFrom = text.length / 2
        logFile.writeText("[Older diagnostics truncated]\n${text.substring(keepFrom)}")
    }
}
