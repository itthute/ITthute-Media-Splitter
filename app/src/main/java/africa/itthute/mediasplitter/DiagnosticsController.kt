package africa.itthute.mediasplitter

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import africa.itthute.mediasplitter.databinding.ActivityMainBinding
import com.google.android.material.dialog.MaterialAlertDialogBuilder

class DiagnosticsController(
    private val activity: MainActivity,
    private val binding: ActivityMainBinding,
    private val diagnostics: DiagnosticsStore,
    private val processor: MediaProcessor
) {
    fun configure() {
        binding.copyDiagnosticsButton.setOnClickListener { copyReport() }
        binding.shareDiagnosticsButton.setOnClickListener { shareReport() }
        binding.viewLogsButton.setOnClickListener { viewLogs() }
        binding.openSettingsButton.setOnClickListener { openSettings() }
        binding.clearDiagnosticsButton.setOnClickListener { confirmClear() }
        updateSummary()
    }

    fun updateSummary(probeNativeCode: Boolean = true) {
        binding.diagnosticsSummary.text = processor.engineStatus(probeNativeCode)
    }

    private fun completeReport(): String =
        diagnostics.buildReport(processor.engineStatus(probeNativeCode = true))

    private fun copyReport() {
        val clipboard = activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(
            ClipData.newPlainText("ITthute Media Splitter diagnostics", completeReport())
        )
        toast("Complete diagnostics report copied")
    }

    private fun shareReport() {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, "ITthute Media Splitter diagnostics")
            putExtra(Intent.EXTRA_TEXT, completeReport())
        }
        runCatching {
            activity.startActivity(Intent.createChooser(intent, "Share diagnostics report"))
        }.onFailure {
            diagnostics.log("WARN", "No sharing app is available", it)
            toast("No sharing app is available")
        }
    }

    private fun viewLogs() {
        val textView = TextView(activity).apply {
            text = diagnostics.readLogs()
            setTextIsSelectable(true)
            setPadding(dp(16), dp(16), dp(16), dp(16))
            typeface = android.graphics.Typeface.MONOSPACE
        }
        MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.detailed_diagnostics_logs)
            .setView(ScrollView(activity).apply { addView(textView) })
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    private fun openSettings() {
        activity.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.fromParts("package", activity.packageName, null)
        })
    }

    private fun confirmClear() {
        MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.clear_all_diagnostics_data)
            .setMessage(R.string.clear_diagnostics_confirmation)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.clear) { _, _ ->
                diagnostics.clear()
                updateSummary(probeNativeCode = false)
                toast("Diagnostics data cleared")
            }
            .show()
    }

    private fun dp(value: Int): Int = (value * activity.resources.displayMetrics.density).toInt()

    private fun toast(message: String) = Toast.makeText(activity, message, Toast.LENGTH_LONG).show()
}
