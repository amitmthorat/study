package com.airline.mrz.core.parse

import com.airline.mrz.core.model.MrzAlphabet

/**
 * Splits the 39-character TD3 name field into primary and secondary identifiers.
 *
 * `<<` separates primary from secondary; a single `<` is a space inside either. A
 * letter in the very last cell means the issuer ran out of room and truncated.
 */
public object NameParser {
    public data class Names(
        val primary: String,
        val secondary: String,
        val truncated: Boolean,
    )

    public fun parse(field: String): Names {
        val truncated = field.isNotEmpty() && field.last() != MrzAlphabet.FILLER
        val sepIndex = field.indexOf("<<")
        val primaryRaw: String
        val secondaryRaw: String
        if (sepIndex < 0) {
            primaryRaw = field
            secondaryRaw = ""
        } else {
            primaryRaw = field.substring(0, sepIndex)
            secondaryRaw = field.substring(sepIndex + 2)
        }
        return Names(
            primary = clean(primaryRaw),
            secondary = clean(secondaryRaw),
            truncated = truncated,
        )
    }

    private fun clean(raw: String): String =
        raw.trimEnd(MrzAlphabet.FILLER).replace(MrzAlphabet.FILLER, ' ').trim()
}
