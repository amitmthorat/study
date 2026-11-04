package com.airline.mrz.core.model

import java.time.LocalDate

/**
 * Parsed passport data in the shape airline APIS submissions need.
 *
 * The SDK returns dates, never validity verdicts. Expiry-versus-arrival rules belong
 * to the airline's own business logic.
 */
public data class PassportData(
    /** Two characters: `P` plus the issuer's subtype character (`<` when none). */
    val documentCode: String,
    /** Three-letter code as printed (for example `D<<`). See [issuingStateCanonical]. */
    val issuingState: String,
    /** Surname(s), fillers converted to spaces. */
    val primaryIdentifier: String,
    /** Given names, fillers converted to spaces. */
    val secondaryIdentifier: String,
    /** True when the name field ran out of room and the issuer truncated it. */
    val nameTruncated: Boolean,
    /** Document number without trailing fillers. */
    val documentNumber: String,
    /** Three-letter code as printed. See [nationalityCanonical]. */
    val nationality: String,
    /** Null when the issuer used filler digits for an unknown date. */
    val dateOfBirth: LocalDate?,
    val sex: Sex,
    val dateOfExpiry: LocalDate,
    /** Personal number or other optional data, trailing fillers removed. May be empty. */
    val optionalData: String,
    /** Per-field confidence in `0..1`, the minimum top-two margin over the field's cells. */
    val fieldConfidence: Map<Field, Float>,
) {
    val issuingStateCanonical: String get() = CountryCodes.canonical(issuingState)
    val nationalityCanonical: String get() = CountryCodes.canonical(nationality)
    val documentSubtype: Char get() = documentCode[1]
}
