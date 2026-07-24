package africa.itthute.mediasplitter.media

object FFmpegCommandBuilder {
    fun build(request: ExportRequest): Array<String> {
        require(request.inputPath.isNotBlank()) { "Input path is required." }
        require(request.outputPath.isNotBlank()) { "Output path is required." }
        require(request.startSeconds >= 0.0) { "Start time cannot be negative." }
        require(request.durationSeconds > 0.0) { "End time must be later than start time." }

        return buildList {
            add("-hide_banner")
            add("-y")
            add("-ss")
            add(formatSeconds(request.startSeconds))
            add("-i")
            add(request.inputPath)
            add("-t")
            add(formatSeconds(request.durationSeconds))
            addAll(request.format.ffmpegArguments)
            add(request.outputPath)
        }.toTypedArray()
    }

    internal fun formatSeconds(value: Double): String =
        "%.3f".format(java.util.Locale.US, value)
}
