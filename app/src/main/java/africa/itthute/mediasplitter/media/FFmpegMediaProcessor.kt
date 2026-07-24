package africa.itthute.mediasplitter.media

import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.ReturnCode
import java.util.concurrent.atomic.AtomicLong

class FFmpegMediaProcessor {
    private val activeSessionId = AtomicLong(NO_SESSION)

    fun export(
        request: ExportRequest,
        onProgress: (Int) -> Unit,
        onComplete: (Result<Unit>) -> Unit
    ) {
        val arguments = FFmpegCommandBuilder.build(request)
        val expectedDurationMs = (request.durationSeconds * 1_000.0).toLong().coerceAtLeast(1L)

        val session = FFmpegKit.executeWithArgumentsAsync(
            arguments,
            { completedSession ->
                activeSessionId.compareAndSet(completedSession.sessionId, NO_SESSION)
                when {
                    ReturnCode.isSuccess(completedSession.returnCode) -> onComplete(Result.success(Unit))
                    ReturnCode.isCancel(completedSession.returnCode) -> onComplete(
                        Result.failure(ExportCancelledException())
                    )
                    else -> onComplete(
                        Result.failure(
                            IllegalStateException(
                                completedSession.output.ifBlank {
                                    completedSession.failStackTrace ?: "FFmpeg export failed."
                                }
                            )
                        )
                    )
                }
            },
            { /* Detailed logs are intentionally not rendered in the main UI. */ },
            { statistics ->
                val progress = ((statistics.time.toDouble() / expectedDurationMs) * 100.0)
                    .toInt()
                    .coerceIn(0, 99)
                onProgress(progress)
            }
        )
        activeSessionId.set(session.sessionId)
    }

    fun cancel() {
        val sessionId = activeSessionId.getAndSet(NO_SESSION)
        if (sessionId != NO_SESSION) {
            FFmpegKit.cancel(sessionId)
        }
    }

    class ExportCancelledException : RuntimeException("Export cancelled")

    private companion object {
        const val NO_SESSION = -1L
    }
}
