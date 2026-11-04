package com.airline.mrz.core.model

/**
 * The 37-symbol MRZ alphabet: digits, upper-case Latin letters and the filler `<`.
 *
 * Index layout is fixed and is the class order of every recognizer model:
 * `0..9` are digits, `10..35` are `A..Z`, `36` is `<`.
 */
public object MrzAlphabet {
    public const val SIZE: Int = 37
    public const val FILLER: Char = '<'
    public const val FILLER_INDEX: Int = 36

    public const val SYMBOLS: String = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ<"

    /** Index of [c] in the alphabet, or -1 when [c] is not an MRZ symbol. */
    public fun indexOf(c: Char): Int = when (c) {
        in '0'..'9' -> c - '0'
        in 'A'..'Z' -> 10 + (c - 'A')
        FILLER -> FILLER_INDEX
        else -> -1
    }

    public fun charAt(index: Int): Char {
        require(index in 0 until SIZE) { "index $index out of alphabet range" }
        return SYMBOLS[index]
    }

    public fun isValid(c: Char): Boolean = indexOf(c) >= 0

    /** Numeric value used by the ICAO 9303 check digit algorithm. */
    public fun checkValue(c: Char): Int = when (c) {
        in '0'..'9' -> c - '0'
        in 'A'..'Z' -> 10 + (c - 'A')
        FILLER -> 0
        else -> throw IllegalArgumentException("'$c' is not an MRZ character")
    }

    public val DIGITS: Set<Char> = ('0'..'9').toSet()
    public val LETTERS: Set<Char> = ('A'..'Z').toSet()
    public val DIGITS_OR_FILLER: Set<Char> = DIGITS + FILLER
    public val LETTERS_OR_FILLER: Set<Char> = LETTERS + FILLER
    public val ALL: Set<Char> = SYMBOLS.toSet()
}
