package africa.itthute.mediasplitter

import android.app.Activity
import android.app.RecoverableSecurityException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.text.format.DateFormat
import android.text.format.Formatter
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.Toast
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.setPadding
import androidx.lifecycle.lifecycleScope
import africa.itthute.mediasplitter.databinding.ActivityMainBinding
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.launch
import java.util.Date

class SplitMediaController(
    private val activity: MainActivity,
    private val binding: ActivityMainBinding,
    private val repository: SplitMediaRepository,
    private val diagnostics: DiagnosticsStore
) {
    private var pendingMoveItem: SplitMediaItem? = null
    private var pendingDeleteItem: SplitMediaItem? = null

    private val moveDestinationPicker = activity.registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { destinationUri ->
        val item = pendingMoveItem.also { pendingMoveItem = null } ?: return@registerForActivityResult
        if (destinationUri == null) {
            toast(activity.getString(R.string.move_cancelled))
            return@registerForActivityResult
        }
        runCatching {
            activity.contentResolver.takePersistableUriPermission(
                destinationUri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
        }.onFailure {
            diagnostics.log("WARN", "Destination provider did not grant persistent write access", it)
        }
        activity.lifecycleScope.launch {
            binding.mediaListStatus.text = activity.getString(R.string.moving_media_file, item.displayName)
            repository.moveToTree(item, destinationUri)
                .onSuccess {
                    toast(activity.getString(R.string.media_moved))
                    refresh()
                }
                .onFailure {
                    toast(it.message ?: activity.getString(R.string.move_failed))
                    refresh()
                }
        }
    }

    private val deleteConsentLauncher = activity.registerForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        val item = pendingDeleteItem.also { pendingDeleteItem = null }
        if (result.resultCode == Activity.RESULT_OK) {
            toast(activity.getString(R.string.media_deleted))
            refresh()
        } else if (item != null) {
            toast(activity.getString(R.string.delete_cancelled))
        }
    }

    fun configure() {
        binding.refreshMediaButton.setOnClickListener { refresh() }
        binding.openDestinationButton.setOnClickListener { openSavedDestination() }
    }

    fun refresh() {
        activity.lifecycleScope.launch {
            binding.mediaListStatus.text = activity.getString(R.string.loading_saved_media)
            val items = repository.recent(10)
            binding.mediaListContainer.removeAllViews()
            if (items.isEmpty()) {
                binding.mediaListStatus.text = activity.getString(R.string.no_split_media)
            } else {
                binding.mediaListStatus.text = activity.resources.getQuantityString(
                    R.plurals.saved_media_count,
                    items.size,
                    items.size
                )
                items.forEach(::addMediaRow)
            }
        }
    }

    private fun addMediaRow(item: SplitMediaItem) {
        val row = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12))
        }
        val details = android.widget.TextView(activity).apply {
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            text = buildString {
                append(item.displayName)
                append('\n')
                append(formatDuration(item.durationMs))
                append(" • ")
                append(Formatter.formatShortFileSize(activity, item.sizeBytes))
                append(" • ")
                append(DateFormat.getMediumDateFormat(activity).format(Date(item.dateAddedSeconds * 1000)))
            }
            setOnClickListener { openMedia(item) }
        }
        val menuButton = MaterialButton(
            activity,
            null,
            com.google.android.material.R.attr.materialButtonOutlinedStyle
        ).apply {
            text = "⋮"
            contentDescription = activity.getString(R.string.media_options, item.displayName)
            minWidth = dp(48)
            minimumWidth = dp(48)
            setOnClickListener { showMediaMenu(this, item) }
        }
        row.addView(details)
        row.addView(menuButton, LinearLayout.LayoutParams(dp(56), LinearLayout.LayoutParams.WRAP_CONTENT))
        binding.mediaListContainer.addView(row)
        binding.mediaListContainer.addView(View(activity).apply {
            setBackgroundColor(activity.getColor(R.color.itthute_divider))
        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(1)))
    }

    private fun showMediaMenu(anchor: View, item: SplitMediaItem) {
        PopupMenu(activity, anchor).apply {
            menu.add(activity.getString(R.string.open_media))
            menu.add(activity.getString(R.string.edit_media_metadata))
            menu.add(activity.getString(R.string.copy_file_path))
            menu.add(activity.getString(R.string.share))
            menu.add(activity.getString(R.string.move_file))
            menu.add(activity.getString(R.string.delete_file))
            setOnMenuItemClickListener { selected ->
                when (selected.title.toString()) {
                    activity.getString(R.string.open_media) -> openMedia(item)
                    activity.getString(R.string.edit_media_metadata) -> editMediaMetadata(item)
                    activity.getString(R.string.copy_file_path) -> copyMediaPath(item)
                    activity.getString(R.string.share) -> shareMedia(item)
                    activity.getString(R.string.move_file) -> moveMedia(item)
                    activity.getString(R.string.delete_file) -> confirmDelete(item)
                }
                true
            }
            show()
        }
    }

    private fun openMedia(item: SplitMediaItem) {
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(item.uri, item.mimeType)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivitySafely(intent, "No installed app can open this media format")
    }

    private fun shareMedia(item: SplitMediaItem) {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = item.mimeType
            putExtra(Intent.EXTRA_STREAM, item.uri)
            clipData = ClipData.newUri(activity.contentResolver, item.displayName, item.uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivitySafely(
            Intent.createChooser(intent, activity.getString(R.string.share_media)),
            "No sharing app is available"
        )
    }

    private fun copyMediaPath(item: SplitMediaItem) {
        val clipboard = activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(
            ClipData.newPlainText("Split media path", "${item.userVisiblePath}\n${item.uri}")
        )
        toast("File path copied")
    }

    private fun editMediaMetadata(item: SplitMediaItem) {
        val container = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20))
        }
        val nameInput = EditText(activity).apply {
            hint = activity.getString(R.string.file_name)
            setText(item.displayName)
        }
        val titleInput = EditText(activity).apply {
            hint = activity.getString(R.string.media_title)
            setText(item.displayName.substringBeforeLast('.'))
        }
        container.addView(nameInput)
        container.addView(titleInput)
        val dialog = MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.edit_media_metadata)
            .setView(container)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.save, null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                activity.lifecycleScope.launch {
                    repository.updateMetadata(item, nameInput.text.toString(), titleInput.text.toString())
                        .onSuccess {
                            dialog.dismiss()
                            toast("Media metadata updated")
                            refresh()
                        }
                        .onFailure { toast(it.message ?: "Metadata update failed") }
                }
            }
        }
        dialog.show()
    }

    private fun moveMedia(item: SplitMediaItem) {
        pendingMoveItem = item
        moveDestinationPicker.launch(null)
    }

    private fun confirmDelete(item: SplitMediaItem) {
        MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.delete_file)
            .setMessage(activity.getString(R.string.delete_file_confirmation, item.displayName))
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.delete_file) { _, _ -> deleteMedia(item) }
            .show()
    }

    private fun deleteMedia(item: SplitMediaItem) {
        activity.lifecycleScope.launch {
            repository.delete(item)
                .onSuccess {
                    toast(activity.getString(R.string.media_deleted))
                    refresh()
                }
                .onFailure { error ->
                    if (!launchDeleteConsent(item, error)) {
                        toast(error.message ?: activity.getString(R.string.delete_failed))
                    }
                }
        }
    }

    private fun launchDeleteConsent(item: SplitMediaItem, error: Throwable): Boolean {
        val intentSender = when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.R -> {
                MediaStore.createDeleteRequest(activity.contentResolver, listOf(item.uri)).intentSender
            }
            error is RecoverableSecurityException -> error.userAction.actionIntent.intentSender
            else -> null
        } ?: return false

        pendingDeleteItem = item
        deleteConsentLauncher.launch(IntentSenderRequest.Builder(intentSender).build())
        return true
    }

    private fun openSavedDestination() {
        val relative = diagnostics.lastDestination().trimEnd('/')
        val uri = DocumentsContract.buildDocumentUri(
            "com.android.externalstorage.documents",
            "primary:$relative"
        )
        val fileManagerIntent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
            putExtra(DocumentsContract.EXTRA_INITIAL_URI, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivitySafely(fileManagerIntent, "No compatible file manager is installed")
    }

    private fun startActivitySafely(intent: Intent, failureMessage: String) {
        runCatching { activity.startActivity(intent) }
            .onFailure {
                diagnostics.log("WARN", failureMessage, it)
                toast(failureMessage)
            }
    }

    private fun formatDuration(milliseconds: Long): String {
        val total = milliseconds / 1000
        return "%02d:%02d:%02d".format(total / 3600, (total % 3600) / 60, total % 60)
    }

    private fun dp(value: Int): Int = (value * activity.resources.displayMetrics.density).toInt()

    private fun toast(message: String) = Toast.makeText(activity, message, Toast.LENGTH_LONG).show()
}
