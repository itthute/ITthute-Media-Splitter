package africa.itthute.mediasplitter

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DivisionRulesTest {
    @Test
    fun sourceMustBeLongerThanThirtyAndShorterThanOneHour() {
        assertFalse(DivisionRules.isSourceEligible(30.0))
        assertTrue(DivisionRules.isSourceEligible(30.001))
        assertTrue(DivisionRules.isSourceEligible(3599.999))
        assertFalse(DivisionRules.isSourceEligible(3600.0))
    }

    @Test
    fun mediaLongerThanTenMinutesRequiresThreeHundredSecondParts() {
        assertEquals(30, DivisionRules.minimumSegmentSeconds(600.0))
        assertEquals(300, DivisionRules.minimumSegmentSeconds(600.001))
        assertTrue(DivisionRules.validationMessage(601.0, 90)?.contains("300-second") == true)
        assertNull(DivisionRules.validationMessage(601.0, 300))
    }

    @Test
    fun calculatesLastShortPartInSegmentCount() {
        assertEquals(4, DivisionRules.segmentCount(100.0, 30))
        assertEquals(3, DivisionRules.segmentCount(601.0, 300))
    }

    @Test
    fun divisionLengthMustBeShorterThanSource() {
        assertTrue(DivisionRules.validationMessage(45.0, 45)?.contains("shorter") == true)
        assertNull(DivisionRules.validationMessage(45.0, 30))
    }
}
