package africa.itthute.mediasplitter.media

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class FFmpegCommandBuilderTest {
    @Test
    fun buildsPreciseMp3TrimCommand() {
        val request = ExportRequest(
            inputPath = "/tmp/input video.mp4",
            outputPath = "/tmp/output.mp3",
            startSeconds = 12.345,
            endSeconds = 42.345,
            format = OutputFormats.audio.first()
        )

        assertArrayEquals(
            arrayOf(
                "-hide_banner", "-y", "-ss", "12.345", "-i", "/tmp/input video.mp4",
                "-t", "30.000", "-vn", "-c:a", "libmp3lame", "-b:a", "192k",
                "/tmp/output.mp3"
            ),
            FFmpegCommandBuilder.build(request)
        )
    }

    @Test
    fun rejectsEmptyRange() {
        val request = ExportRequest("in.mp4", "out.mp3", 10.0, 10.0, OutputFormats.audio.first())
        assertThrows(IllegalArgumentException::class.java) {
            FFmpegCommandBuilder.build(request)
        }
    }

    @Test
    fun formatsUsingDotDecimalSeparator() {
        assertEquals("1.250", FFmpegCommandBuilder.formatSeconds(1.25))
    }
}
