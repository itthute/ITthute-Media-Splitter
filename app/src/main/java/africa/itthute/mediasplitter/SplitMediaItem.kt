package africa.itthute.mediasplitter

import android.net.Uri

data class SplitMediaItem(
    val uri: Uri,
    val displayName: String,
    val relativePath: String,
    val mimeType: String,
    val sizeBytes: Long,
    val durationMs: Long,
    val dateAddedSeconds: Long,
    val isAudio: Boolean
) {
    val userVisiblePath: String
        get() = "$relativePath$displayName"
}
