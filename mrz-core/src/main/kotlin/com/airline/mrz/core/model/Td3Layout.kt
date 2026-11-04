package com.airline.mrz.core.model

/** Logical fields of a TD3 MRZ. */
public enum class Field {
    DOCUMENT_CODE,
    ISSUING_STATE,
    NAMES,
    DOCUMENT_NUMBER,
    NATIONALITY,
    DATE_OF_BIRTH,
    SEX,
    DATE_OF_EXPIRY,
    OPTIONAL_DATA,
}

/** The five check digits of a TD3 MRZ. */
public enum class CheckDigitKind {
    DOCUMENT_NUMBER,
    DATE_OF_BIRTH,
    DATE_OF_EXPIRY,
    OPTIONAL_DATA,
    COMPOSITE,
}

public enum class CheckStatus { PASS, FAIL, NOT_PRESENT }

public enum class Sex { MALE, FEMALE, UNSPECIFIED }

/**
 * A contiguous span on one MRZ line. [line] is 0 or 1, [start] inclusive, [end] exclusive,
 * both 0-based. [alphabet] is the set of characters that may legally appear in the span.
 */
public data class Span(
    val line: Int,
    val start: Int,
    val end: Int,
    val alphabet: Set<Char>,
) {
    init {
        require(line == 0 || line == 1)
        require(start in 0 until end && end <= Td3Layout.LINE_LENGTH)
    }

    val length: Int get() = end - start

    public fun extract(lines: List<String>): String = lines[line].substring(start, end)

    /** All (line, position) pairs covered by the span, in order. */
    public fun positions(): List<Position> = (start until end).map { Position(line, it) }
}

/** A single character cell on the MRZ. */
public data class Position(val line: Int, val index: Int) {
    /** Flat index over both lines, `0..87`. */
    val flat: Int get() = line * Td3Layout.LINE_LENGTH + index
}

/**
 * Field positions of a TD3-size MRZ (ICAO Doc 9303 Part 4), 2 lines x 44 characters.
 *
 * ```
 * Line 1: P<ISSNAMES.......................................
 *         0 1 2-4 5-43
 * Line 2: DOCNUMBERcNATDOBDOBcSEXPDATEcOPTIONALDATA....oC
 *         0-8 9 10-12 13-18 19 20 21-26 27 28-41 42 43
 * ```
 */
public object Td3Layout {
    public const val LINE_LENGTH: Int = 44
    public const val LINE_COUNT: Int = 2
    public const val TOTAL_POSITIONS: Int = LINE_LENGTH * LINE_COUNT

    public val DOCUMENT_TYPE: Span = Span(0, 0, 1, setOf('P'))
    public val DOCUMENT_SUBTYPE: Span = Span(0, 1, 2, MrzAlphabet.LETTERS_OR_FILLER)
    public val ISSUING_STATE: Span = Span(0, 2, 5, MrzAlphabet.LETTERS_OR_FILLER)
    public val NAMES: Span = Span(0, 5, 44, MrzAlphabet.LETTERS_OR_FILLER)

    public val DOCUMENT_NUMBER: Span = Span(1, 0, 9, MrzAlphabet.ALL)
    public val DOCUMENT_NUMBER_CHECK: Span = Span(1, 9, 10, MrzAlphabet.DIGITS)
    public val NATIONALITY: Span = Span(1, 10, 13, MrzAlphabet.LETTERS_OR_FILLER)
    public val DATE_OF_BIRTH: Span = Span(1, 13, 19, MrzAlphabet.DIGITS_OR_FILLER)
    public val DATE_OF_BIRTH_CHECK: Span = Span(1, 19, 20, MrzAlphabet.DIGITS)
    public val SEX: Span = Span(1, 20, 21, setOf('M', 'F', MrzAlphabet.FILLER))
    public val DATE_OF_EXPIRY: Span = Span(1, 21, 27, MrzAlphabet.DIGITS)
    public val DATE_OF_EXPIRY_CHECK: Span = Span(1, 27, 28, MrzAlphabet.DIGITS)
    public val OPTIONAL_DATA: Span = Span(1, 28, 42, MrzAlphabet.ALL)
    public val OPTIONAL_DATA_CHECK: Span = Span(1, 42, 43, MrzAlphabet.DIGITS_OR_FILLER)
    public val COMPOSITE_CHECK: Span = Span(1, 43, 44, MrzAlphabet.DIGITS)

    /** Spans whose concatenation the composite check digit covers, in order. */
    public val COMPOSITE_COVERAGE: List<Span> = listOf(
        Span(1, 0, 10, MrzAlphabet.ALL),
        Span(1, 13, 20, MrzAlphabet.ALL),
        Span(1, 21, 43, MrzAlphabet.ALL),
    )

    /** Every span, in reading order. Used to derive per-position alphabets. */
    public val ALL_SPANS: List<Span> = listOf(
        DOCUMENT_TYPE, DOCUMENT_SUBTYPE, ISSUING_STATE, NAMES,
        DOCUMENT_NUMBER, DOCUMENT_NUMBER_CHECK, NATIONALITY, DATE_OF_BIRTH, DATE_OF_BIRTH_CHECK,
        SEX, DATE_OF_EXPIRY, DATE_OF_EXPIRY_CHECK, OPTIONAL_DATA, OPTIONAL_DATA_CHECK, COMPOSITE_CHECK,
    )

    public val FIELD_SPANS: Map<Field, Span> = mapOf(
        Field.DOCUMENT_CODE to Span(0, 0, 2, MrzAlphabet.LETTERS_OR_FILLER),
        Field.ISSUING_STATE to ISSUING_STATE,
        Field.NAMES to NAMES,
        Field.DOCUMENT_NUMBER to DOCUMENT_NUMBER,
        Field.NATIONALITY to NATIONALITY,
        Field.DATE_OF_BIRTH to DATE_OF_BIRTH,
        Field.SEX to SEX,
        Field.DATE_OF_EXPIRY to DATE_OF_EXPIRY,
        Field.OPTIONAL_DATA to OPTIONAL_DATA,
    )

    /** Data span and check-digit span for each check digit. */
    public data class CheckSpec(val kind: CheckDigitKind, val data: List<Span>, val check: Span)

    public val CHECKS: List<CheckSpec> = listOf(
        CheckSpec(CheckDigitKind.DOCUMENT_NUMBER, listOf(DOCUMENT_NUMBER), DOCUMENT_NUMBER_CHECK),
        CheckSpec(CheckDigitKind.DATE_OF_BIRTH, listOf(DATE_OF_BIRTH), DATE_OF_BIRTH_CHECK),
        CheckSpec(CheckDigitKind.DATE_OF_EXPIRY, listOf(DATE_OF_EXPIRY), DATE_OF_EXPIRY_CHECK),
        CheckSpec(CheckDigitKind.OPTIONAL_DATA, listOf(OPTIONAL_DATA), OPTIONAL_DATA_CHECK),
        CheckSpec(CheckDigitKind.COMPOSITE, COMPOSITE_COVERAGE, COMPOSITE_CHECK),
    )

    private val alphabetByFlat: Array<Set<Char>> = Array(TOTAL_POSITIONS) { MrzAlphabet.ALL }.also { arr ->
        for (span in ALL_SPANS) for (p in span.positions()) arr[p.flat] = span.alphabet
    }

    /** Legal alphabet at a position, derived from the span that contains it. */
    public fun alphabetAt(position: Position): Set<Char> = alphabetByFlat[position.flat]

    public fun alphabetAt(line: Int, index: Int): Set<Char> = alphabetByFlat[line * LINE_LENGTH + index]
}
