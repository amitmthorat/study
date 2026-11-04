package com.airline.mrz.core.parse

import com.airline.mrz.core.Fixtures
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CheckDigitTest {

    @Test
    fun `ICAO 9303 specimen check digits`() {
        assertEquals(6, CheckDigit.compute("L898902C3"))
        assertEquals(2, CheckDigit.compute("740812"))
        assertEquals(9, CheckDigit.compute("120415"))
        assertEquals(1, CheckDigit.compute("ZE184226B<<<<<"))
        val l2 = Fixtures.SPECIMEN_L2
        val composite = l2.substring(0, 10) + l2.substring(13, 20) + l2.substring(21, 43)
        assertEquals(0, CheckDigit.compute(composite))
    }

    @Test
    fun `verify accepts matching digit and rejects others`() {
        assertTrue(CheckDigit.verify("L898902C3", '6'))
        assertFalse(CheckDigit.verify("L898902C3", '5'))
        assertFalse(CheckDigit.verify("L898902C3", '<'))
        assertFalse(CheckDigit.verify("L898902C3", 'A'))
    }

    @Test
    fun `filler is 0 and letters are 10 to 35`() {
        assertEquals(CheckDigit.compute("<<<<<<"), 0)
        assertEquals(CheckDigit.compute("A"), (10 * 7) % 10)
        assertEquals(CheckDigit.compute("Z"), (35 * 7) % 10)
    }

    @Test
    fun `empty optional data accepts filler or zero as check`() {
        val empty = "<<<<<<<<<<<<<<"
        assertTrue(CheckDigit.verify(empty, '<', allowFillerForEmpty = true))
        assertTrue(CheckDigit.verify(empty, '0', allowFillerForEmpty = true))
        assertFalse(CheckDigit.verify(empty, '<'))
        assertFalse(CheckDigit.verify("ZE184226B<<<<<", '<', allowFillerForEmpty = true))
    }

    @Test
    fun `single substitution is always detected`() {
        val data = "L898902C3"
        val check = '6'
        for (i in data.indices) {
            for (c in "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ<") {
                if (c == data[i]) continue
                val mutated = data.substring(0, i) + c + data.substring(i + 1)
                // A single substitution changes the weighted sum by (v' - v) * w where w in {7,3,1}
                // is coprime with 10, so it can only pass when the value difference is 0 mod 10.
                val diff = (valueOf(c) - valueOf(data[i])).mod(10)
                if (diff != 0) assertFalse(CheckDigit.verify(mutated, check), "undetected: $mutated")
            }
        }
    }

    private fun valueOf(c: Char) = when (c) {
        in '0'..'9' -> c - '0'
        '<' -> 0
        else -> 10 + (c - 'A')
    }
}
