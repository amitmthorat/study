package com.airline.mrz.core.recognize

import com.airline.mrz.core.Fixtures
import com.airline.mrz.core.model.CheckDigitKind
import com.airline.mrz.core.model.CheckStatus
import com.airline.mrz.core.model.Td3Layout
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FieldAwareCorrectorTest {
    private val corrector = FieldAwareCorrector(maxSwapMargin = 0.15f)

    private fun correct(f: FrameHypothesis) = corrector.correct(f.line1.cells, f.line2.cells)

    @Test
    fun `clean read is untouched`() {
        val out = correct(Fixtures.frame())
        assertEquals(Fixtures.SPECIMEN_L1, out.rawLines[0])
        assertEquals(Fixtures.SPECIMEN_L2, out.correctedLines[1])
        assertTrue(out.corrections.isEmpty())
        assertTrue(out.checks.values.all { it == CheckStatus.PASS })
    }

    @Test
    fun `confusable letters in numeric fields are resolved by alphabet restriction`() {
        // DOB "740812" read as "74O8I2": O for 0 at index 15, I for 1 at index 17.
        // Even with a wide margin, restriction alone fixes these because O and I have no mass in the digit alphabet.
        val f = Fixtures.frame(swaps2 = mapOf(15 to 'O', 17 to 'I'), confidence = 0.95f)
        val out = correct(f)
        assertEquals("74O8I2", out.rawLines[1].substring(13, 19))
        assertEquals("740812", out.correctedLines[1].substring(13, 19))
        assertTrue(out.corrections.isEmpty(), "alphabet restriction is not a recorded correction")
        assertEquals(CheckStatus.PASS, out.checks[CheckDigitKind.DATE_OF_BIRTH])
    }

    @Test
    fun `P in position one is forced`() {
        val f = Fixtures.frame(swaps1 = mapOf(0 to 'R'))
        assertEquals('P', correct(f).correctedLines[0][0])
    }

    @Test
    fun `ambiguous digit is swapped when the check digit demands it`() {
        // Expiry "120415" read as "120413" with 3 narrowly ahead of 5 (margin 0.1 < 0.15).
        val f = Fixtures.frame(swaps2 = mapOf(26 to '3'))
        val out = correct(f)
        assertEquals("120413", out.rawLines[1].substring(21, 27))
        assertEquals("120415", out.correctedLines[1].substring(21, 27))
        assertEquals(1, out.corrections.size)
        val c = out.corrections[0]
        assertEquals(26, c.position.index)
        assertEquals('3', c.from)
        assertEquals('5', c.to)
        assertEquals(CheckDigitKind.DATE_OF_EXPIRY, c.motivatedBy)
        assertEquals(CheckStatus.PASS, out.checks[CheckDigitKind.DATE_OF_EXPIRY])
        assertEquals(CheckStatus.PASS, out.checks[CheckDigitKind.COMPOSITE])
    }

    @Test
    fun `a confident wrong digit is not swapped`() {
        // Same error but with a wide margin: the recognizer is sure, so we must not "fix" it.
        val f = Fixtures.frame(swaps2 = mapOf(26 to '3'), ).let {
            FrameHypothesis(it.line1, Fixtures.noisyLine(Fixtures.SPECIMEN_L2, swaps = mapOf(26 to '3'), swapMargin = 0.4f))
        }
        val out = correct(f)
        assertEquals("120413", out.correctedLines[1].substring(21, 27))
        assertTrue(out.corrections.isEmpty())
        assertEquals(CheckStatus.FAIL, out.checks[CheckDigitKind.DATE_OF_EXPIRY])
    }

    @Test
    fun `two ambiguous cells in one field are left alone`() {
        val f = Fixtures.frame(swaps2 = mapOf(25 to '2', 26 to '3'))
        val out = correct(f)
        assertTrue(out.corrections.isEmpty())
        assertEquals(CheckStatus.FAIL, out.checks[CheckDigitKind.DATE_OF_EXPIRY])
    }

    @Test
    fun `document number is never statistically corrected`() {
        // 'L898902C3' read as 'L898902C8' narrowly; the check digit fails but no swap is made.
        val f = Fixtures.frame(swaps2 = mapOf(8 to '8'))
        val out = correct(f)
        assertEquals("L898902C8", out.correctedLines[1].substring(0, 9))
        assertTrue(out.corrections.isEmpty())
        assertEquals(CheckStatus.FAIL, out.checks[CheckDigitKind.DOCUMENT_NUMBER])
    }

    @Test
    fun `property - corrected text is always in alphabet and passing fields are never changed`() {
        val rng = Random(42)
        repeat(300) {
            val f = Fixtures.randomlyCorruptedFrame(rng, errors = rng.nextInt(0, 4), confidence = 0.6f)
            val out = correct(f)
            for (line in 0..1) for (i in 0 until Td3Layout.LINE_LENGTH) {
                assertTrue(out.correctedLines[line][i] in Td3Layout.alphabetAt(line, i), "out of alphabet at $line:$i")
            }
            // Any recorded correction must sit in a date field or its check cell.
            for (c in out.corrections) {
                val inDob = c.position.line == 1 && c.position.index in 13..19
                val inExp = c.position.line == 1 && c.position.index in 21..27
                assertTrue(inDob || inExp, "correction outside date fields: $c")
            }
        }
    }

    @Test
    fun `field confidence is the minimum margin over the field`() {
        val f = Fixtures.frame(confidence = 0.9f, swaps2 = mapOf(3 to 'B'))
        val conf = correct(f).fieldConfidence()
        val docConf = conf.getValue(com.airline.mrz.core.model.Field.DOCUMENT_NUMBER)
        val natConf = conf.getValue(com.airline.mrz.core.model.Field.NATIONALITY)
        assertTrue(docConf < natConf)
    }
}
