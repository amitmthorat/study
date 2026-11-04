package com.airline.mrz.core.recognize

import com.airline.mrz.core.model.MrzAlphabet
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CharDistributionTest {

    @Test
    fun `of normalises and clamps negatives`() {
        val raw = FloatArray(MrzAlphabet.SIZE) { -1f }
        raw[MrzAlphabet.indexOf('A')] = 3f
        raw[MrzAlphabet.indexOf('B')] = 1f
        val d = CharDistribution.of(raw)
        assertEquals(0.75f, d.probabilityOf('A'), 1e-6f)
        assertEquals(0.25f, d.probabilityOf('B'), 1e-6f)
        assertEquals('A', d.argmaxChar)
        assertEquals(0.5f, d.margin, 1e-6f)
    }

    @Test
    fun `restriction removes out of alphabet mass`() {
        val d = CharDistribution.oneHot('O', 0.6f) // O vs 0 confusion in a numeric field
        val r = d.restrictedTo(MrzAlphabet.DIGITS)
        assertEquals(0f, r.probabilityOf('O'))
        assertTrue(r.argmaxChar in '0'..'9')
        assertEquals(1f, (0..9).sumOf { r.probabilityOf('0' + it).toDouble() }.toFloat(), 1e-5f)
    }

    @Test
    fun `restriction to an alphabet with no mass is uniform over the alphabet`() {
        val d = CharDistribution.oneHot('A', 1f)
        val r = d.restrictedTo(setOf('M', 'F', '<'))
        assertEquals(1f / 3, r.probabilityOf('M'), 1e-6f)
        assertEquals(0f, r.margin, 1e-6f)
    }

    @Test
    fun `combine is a weighted average`() {
        val a = CharDistribution.oneHot('A', 1f)
        val b = CharDistribution.oneHot('B', 1f)
        val c = CharDistribution.combine(listOf(a to 3f, b to 1f))
        assertEquals(0.75f, c.probabilityOf('A'), 1e-6f)
        assertEquals(0.25f, c.probabilityOf('B'), 1e-6f)
        val zeroWeight = CharDistribution.combine(listOf(a to 1f, b to 0f))
        assertEquals(1f, zeroWeight.probabilityOf('A'), 1e-6f)
    }

    @Test
    fun `topTwo returns distinct best indices`() {
        val d = CharDistribution.oneHot('5', 0.7f)
        val (first, second) = d.topTwo()
        assertEquals(MrzAlphabet.indexOf('5'), first)
        assertTrue(second != first)
    }
}
