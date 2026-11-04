package com.airline.mrz.core.parse

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class NameParserTest {
    private fun field(s: String) = s.padEnd(39, '<')

    @Test
    fun `specimen splits into primary and secondary`() {
        val n = NameParser.parse(field("ERIKSSON<<ANNA<MARIA"))
        assertEquals("ERIKSSON", n.primary)
        assertEquals("ANNA MARIA", n.secondary)
        assertFalse(n.truncated)
    }

    @Test
    fun `multi word primary identifier`() {
        val n = NameParser.parse(field("VAN<DER<BERG<<JAN"))
        assertEquals("VAN DER BERG", n.primary)
        assertEquals("JAN", n.secondary)
    }

    @Test
    fun `no secondary identifier`() {
        val n = NameParser.parse(field("MADONNA"))
        assertEquals("MADONNA", n.primary)
        assertEquals("", n.secondary)
        val n2 = NameParser.parse(field("MADONNA<<"))
        assertEquals("MADONNA", n2.primary)
        assertEquals("", n2.secondary)
    }

    @Test
    fun `letter in last cell flags truncation`() {
        val long = "PAPADOPOULOS<KONSTANTINOPOULOS<<ALEXANDE" // 39 chars exactly? pad/truncate to 39
        val f = long.take(39).padEnd(39, '<')
        val n = NameParser.parse(f)
        assertTrue(n.truncated)
        assertEquals("PAPADOPOULOS KONSTANTINOPOULOS", n.primary)
    }
}
