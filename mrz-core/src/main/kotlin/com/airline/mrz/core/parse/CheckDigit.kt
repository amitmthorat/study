package com.airline.mrz.core.parse

import com.airline.mrz.core.model.MrzAlphabet

/**
 * ICAO Doc 9303 Part 3 check digit: weights 7, 3, 1 repeating, values `0-9` for digits,
 * `10-35` for `A-Z`, `0` for the filler, sum modulo 10.
 */
public object CheckDigit {
    private val WEIGHTS = intArrayOf(7, 3, 1)

    public fun compute(data: CharSequence): Int {
        var sum = 0
        for (i in data.indices) sum += MrzAlphabet.checkValue(data[i]) * WEIGHTS[i % 3]
        return sum % 10
    }

    /**
     * True when [check] matches [data]. When [allowFillerForEmpty] is set and [data] is
     * entirely fillers, a `<` check character is accepted as well as `0`: issuers differ
     * on how they mark an empty optional-data field.
     */
    public fun verify(data: CharSequence, check: Char, allowFillerForEmpty: Boolean = false): Boolean {
        if (allowFillerForEmpty && check == MrzAlphabet.FILLER) {
            return data.all { it == MrzAlphabet.FILLER }
        }
        if (check !in '0'..'9') return false
        return compute(data) == check - '0'
    }
}
