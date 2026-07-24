package africa.itthute.mediasplitter.media

enum class ExportKind {
    AUDIO,
    SILENT_VIDEO
}

data class OutputFormat(
    val label: String,
    val extension: String,
    val mimeType: String,
    val ffmpegArguments: List<String>
)

object OutputFormats {
    val audio = listOf(
        OutputFormat("MP3 — 192 kbps", "mp3", "audio/mpeg", listOf("-vn", "-c:a", "libmp3lame", "-b:a", "192k")),
        OutputFormat("M4A / AAC — 192 kbps", "m4a", "audio/mp4", listOf("-vn", "-c:a", "aac", "-b:a", "192k")),
        OutputFormat("AAC", "aac", "audio/aac", listOf("-vn", "-c:a", "aac", "-b:a", "192k", "-f", "adts")),
        OutputFormat("WAV — PCM 16-bit", "wav", "audio/wav", listOf("-vn", "-c:a", "pcm_s16le")),
        OutputFormat("FLAC", "flac", "audio/flac", listOf("-vn", "-c:a", "flac")),
        OutputFormat("OGG Vorbis", "ogg", "audio/ogg", listOf("-vn", "-c:a", "libvorbis", "-q:a", "5")),
        OutputFormat("Opus", "opus", "audio/opus", listOf("-vn", "-c:a", "libopus", "-b:a", "160k"))
    )

    val silentVideo = listOf(
        OutputFormat("MP4 — H.264", "mp4", "video/mp4", listOf("-an", "-c:v", "libx264", "-preset", "veryfast", "-crf", "23", "-movflags", "+faststart")),
        OutputFormat("MKV — H.264", "mkv", "video/x-matroska", listOf("-an", "-c:v", "libx264", "-preset", "veryfast", "-crf", "23")),
        OutputFormat("WebM — VP9", "webm", "video/webm", listOf("-an", "-c:v", "libvpx-vp9", "-crf", "32", "-b:v", "0")),
        OutputFormat("MOV — H.264", "mov", "video/quicktime", listOf("-an", "-c:v", "libx264", "-preset", "veryfast", "-crf", "23")),
        OutputFormat("AVI — MPEG-4", "avi", "video/x-msvideo", listOf("-an", "-c:v", "mpeg4", "-q:v", "4"))
    )
}

data class ExportRequest(
    val inputPath: String,
    val outputPath: String,
    val startSeconds: Double,
    val endSeconds: Double,
    val format: OutputFormat
) {
    val durationSeconds: Double
        get() = (endSeconds - startSeconds).coerceAtLeast(0.0)
}
