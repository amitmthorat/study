package com.airline.mrz.core.parse

import com.airline.mrz.core.Fixtures
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class DateParserTest {
    private val parser = Fixtures.dates // today = 2026-11-04

    private fun dob(s: String) = (parser.parseDateOfBirth(s) as DateParser.Result.Date).value
    private fun exp(s: String) = (parser.parseDateOfExpiry(s) as DateParser.Result.Date).value

    @Test
    fun `date of birth pivots to the past`() {
        assertEquals(LocalDate.of(1974, 8, 12), dob("740812"))
        assertEquals(LocalDate.of(2015, 1, 1), dob("150101"))
        assertEquals(LocalDate.of(2026, 12, 31), dob("261231")) // same two-digit year as today
        assertEquals(LocalDate.of(1927, 1, 1), dob("270101"))
    }

    @Test
    fun `date of expiry pivots to the near future`() {
        assertEquals(LocalDate.of(2012, 4, 15), exp("120415"))
        assertEquals(LocalDate.of(2035, 1, 1), exp("350101"))
        assertEquals(LocalDate.of(2046, 6, 30), exp("460630"))
        assertEquals(LocalDate.of(1999, 1, 1), exp("990101"))
    }

    @Test
    fun `filler digits mean unknown, not invalid`() {
        assertIs<DateParser.Result.Unknown>(parser.parseDateOfBirth("74<<<<"))
        assertIs<DateParser.Result.Unknown>(parser.parseDateOfBirth("<<<<<<"))
    }

    @Test
    fun `non calendar dates and letters are invalid`() {
        assertIs<DateParser.Result.Invalid>(parser.parseDateOfBirth("741332"))
        assertIs<DateParser.Result.Invalid>(parser.parseDateOfBirth("74O812"))
        assertIs<DateParser.Result.Invalid>(parser.parseDateOfBirth("7408"))
        assertIs<DateParser.Result.Invalid>(parser.parseDateOfBirth("230229")) // 2023 is not a leap year
        assertIs<DateParser.Result.Date>(parser.parseDateOfBirth("240229"))
    }
}
