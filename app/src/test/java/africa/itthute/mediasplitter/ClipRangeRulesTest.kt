package africa.itthute.mediasplitter

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ClipRangeRulesTest {
    @Test
    fun acceptsSequentialNonOverlappingRanges() {
        val error = ClipRangeRules.validate(
            ranges = listOf(ClipRange(0.0, 10.0), ClipRange(15.0, 25.0)),
            mediaDurationSeconds = 30.0,
            maximumRanges = 20,
            minimumClipLengthSeconds = 5.0
        )
        assertNull(error)
    }

    @Test
    fun rejectsOverlap() {
        val error = ClipRangeRules.validate(
            ranges = listOf(ClipRange(0.0, 10.0), ClipRange(9.0, 20.0)),
            mediaDurationSeconds = 30.0,
            maximumRanges = 20,
            minimumClipLengthSeconds = 5.0
        )
        assertTrue(error!!.contains("overlaps"))
    }

    @Test
    fun rejectsEndBeforeStart() {
        val error = ClipRangeRules.validate(
            ranges = listOf(ClipRange(10.0, 5.0)),
            mediaDurationSeconds = 30.0,
            maximumRanges = 20,
            minimumClipLengthSeconds = 5.0
        )
        assertTrue(error!!.contains("end after"))
    }

    @Test
    fun rejectsClipShorterThanConfiguredMinimum() {
        val error = ClipRangeRules.validate(
            ranges = listOf(ClipRange(0.0, 4.9)),
            mediaDurationSeconds = 30.0,
            maximumRanges = 20,
            minimumClipLengthSeconds = 5.0
        )
        assertTrue(error!!.contains("too short"))
    }

    @Test
    fun parsesClockAndSecondsFormats() {
        assertEquals(90.5, ClipRangeRules.parseTime("00:01:30.5")!!, 0.0001)
        assertEquals(90.5, ClipRangeRules.parseTime("90.5")!!, 0.0001)
    }

    @Test
    fun createsRequestedNumberOfSequentialRanges() {
        val ranges = ClipRangeRules.evenlySpaced(100.0, 4)
        assertEquals(4, ranges.size)
        assertEquals(0.0, ranges.first().startSeconds, 0.0001)
        assertEquals(100.0, ranges.last().endSeconds, 0.0001)
        assertEquals(ranges[0].endSeconds, ranges[1].startSeconds, 0.0001)
    }
}
