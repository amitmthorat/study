package com.airline.mrz.core.recognize

import com.airline.mrz.core.Fixtures
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class StructuralFilterTest {

    @Test
    fun `clean specimen passes`() {
        assertTrue(StructuralFilter.check(Fixtures.frame()).accepted)
    }

    @Test
    fun `a few confusable swaps still pass`() {
        val f = Fixtures.frame(swaps2 = mapOf(15 to 'O', 17 to 'I'))
        assertTrue(StructuralFilter.check(f).accepted)
    }

    @Test
    fun `wrong document type fails`() {
        assertFalse(StructuralFilter.check(Fixtures.frame(swaps1 = mapOf(0 to 'V'))).accepted)
    }

    @Test
    fun `garbage line fails`() {
        val garbage = "P<" + "1234567890123456789012345678901234567890AB"
        val f = Fixtures.frame(l1 = garbage)
        assertFalse(StructuralFilter.check(f).accepted)
    }

    @Test
    fun `misaligned read with fillers in expiry fails`() {
        val l2 = Fixtures.SPECIMEN_L2.replaceRange(21, 27, "<<<<<<")
        assertFalse(StructuralFilter.check(Fixtures.frame(l2 = l2)).accepted)
    }
}
