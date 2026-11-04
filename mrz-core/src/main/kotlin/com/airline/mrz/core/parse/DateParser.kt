package com.airline.mrz.core.parse

import com.airline.mrz.core.model.MrzAlphabet
import java.time.DateTimeException
import java.time.LocalDate

/**
 * Parses `YYMMDD` MRZ dates with a century pivot chosen per field.
 *
 * ICAO permits filler characters in a date of birth when the issuer does not know a
 * digit; such dates parse to `null` rather than failing.
 */
public class DateParser(private val today: () -> LocalDate = { LocalDate.now() }) {

    public sealed interface Result {
        public data class Date(val value: LocalDate) : Result
        /** Issuer used fillers; the date is legitimately unknown. */
        public object Unknown : Result
        public data class Invalid(val reason: String) : Result
    }

    /** Date of birth: always in the past, so `yy` later than this year maps to 19yy. */
    public fun parseDateOfBirth(yymmdd: String): Result = parse(yymmdd) { yy ->
        val currentYy = today().year % 100
        if (yy <= currentYy) 2000 + yy else 1900 + yy
    }

    /**
     * Date of expiry: passports are valid at most ten years, so anything more than
     * [maxYearsAhead] years in the future is read as the previous century.
     */
    public fun parseDateOfExpiry(yymmdd: String, maxYearsAhead: Int = 20): Result = parse(yymmdd) { yy ->
        val now = today().year
        val candidate = 2000 + yy
        if (candidate > now + maxYearsAhead) 1900 + yy else candidate
    }

    private inline fun parse(yymmdd: String, century: (Int) -> Int): Result {
        if (yymmdd.length != 6) return Result.Invalid("expected 6 characters, got ${yymmdd.length}")
        if (yymmdd.any { it == MrzAlphabet.FILLER }) {
            return if (yymmdd.all { it == MrzAlphabet.FILLER || it in '0'..'9' }) Result.Unknown
            else Result.Invalid("non-digit in date")
        }
        if (yymmdd.any { it !in '0'..'9' }) return Result.Invalid("non-digit in date")
        val yy = yymmdd.substring(0, 2).toInt()
        val mm = yymmdd.substring(2, 4).toInt()
        val dd = yymmdd.substring(4, 6).toInt()
        return try {
            Result.Date(LocalDate.of(century(yy), mm, dd))
        } catch (e: DateTimeException) {
            Result.Invalid("not a calendar date: $yymmdd")
        }
    }
}
