package com.airline.mrz.core.parse

import com.airline.mrz.core.model.CheckDigitKind
import com.airline.mrz.core.model.CheckStatus
import com.airline.mrz.core.model.CountryCodes
import com.airline.mrz.core.model.Field
import com.airline.mrz.core.model.MrzAlphabet
import com.airline.mrz.core.model.PassportData
import com.airline.mrz.core.model.Sex
import com.airline.mrz.core.model.Td3Layout

/**
 * Parses two 44-character lines into [PassportData] and reports every check digit.
 *
 * Parsing is deliberately tolerant of check-digit failures: a failing check is
 * reported in [Parsed.checks], not thrown, because the integrator and stopping rule
 * decide what to do with a partially verified read. Structural problems (wrong length,
 * illegal characters in fixed-alphabet fields, invalid dates) are hard failures.
 */
public class Td3Parser(private val dates: DateParser = DateParser()) {

    public sealed interface Result {
        public data class Parsed(
            val data: PassportData,
            val checks: Map<CheckDigitKind, CheckStatus>,
        ) : Result {
            val allChecksPass: Boolean get() = checks.values.all { it == CheckStatus.PASS || it == CheckStatus.NOT_PRESENT }
        }

        public data class Structural(val reason: String) : Result
    }

    public fun parse(
        line1: String,
        line2: String,
        fieldConfidence: Map<Field, Float> = emptyMap(),
    ): Result {
        if (line1.length != Td3Layout.LINE_LENGTH) return Result.Structural("line 1 has ${line1.length} characters")
        if (line2.length != Td3Layout.LINE_LENGTH) return Result.Structural("line 2 has ${line2.length} characters")
        val lines = listOf(line1, line2)

        for (span in Td3Layout.ALL_SPANS) {
            val text = span.extract(lines)
            val bad = text.indexOfFirst { it !in span.alphabet }
            if (bad >= 0) {
                return Result.Structural("illegal '${text[bad]}' at line ${span.line + 1} position ${span.start + bad + 1}")
            }
        }

        val issuingState = Td3Layout.ISSUING_STATE.extract(lines)
        if (!CountryCodes.isValid(issuingState)) return Result.Structural("unknown issuing state '$issuingState'")
        val nationality = Td3Layout.NATIONALITY.extract(lines)
        if (!CountryCodes.isValid(nationality)) return Result.Structural("unknown nationality '$nationality'")

        val dob = when (val r = dates.parseDateOfBirth(Td3Layout.DATE_OF_BIRTH.extract(lines))) {
            is DateParser.Result.Date -> r.value
            DateParser.Result.Unknown -> null
            is DateParser.Result.Invalid -> return Result.Structural("date of birth: ${r.reason}")
        }
        val expiry = when (val r = dates.parseDateOfExpiry(Td3Layout.DATE_OF_EXPIRY.extract(lines))) {
            is DateParser.Result.Date -> r.value
            DateParser.Result.Unknown -> return Result.Structural("date of expiry contains fillers")
            is DateParser.Result.Invalid -> return Result.Structural("date of expiry: ${r.reason}")
        }

        val names = NameParser.parse(Td3Layout.NAMES.extract(lines))
        val sex = when (Td3Layout.SEX.extract(lines)[0]) {
            'M' -> Sex.MALE
            'F' -> Sex.FEMALE
            else -> Sex.UNSPECIFIED
        }

        val checks = verifyChecks(lines)

        val data = PassportData(
            documentCode = line1.substring(0, 2),
            issuingState = issuingState,
            primaryIdentifier = names.primary,
            secondaryIdentifier = names.secondary,
            nameTruncated = names.truncated,
            documentNumber = Td3Layout.DOCUMENT_NUMBER.extract(lines).trimEnd(MrzAlphabet.FILLER),
            nationality = nationality,
            dateOfBirth = dob,
            sex = sex,
            dateOfExpiry = expiry,
            optionalData = Td3Layout.OPTIONAL_DATA.extract(lines).trimEnd(MrzAlphabet.FILLER),
            fieldConfidence = fieldConfidence,
        )
        return Result.Parsed(data, checks)
    }

    /** Verifies every check digit on structurally valid lines. */
    public fun verifyChecks(lines: List<String>): Map<CheckDigitKind, CheckStatus> {
        val out = LinkedHashMap<CheckDigitKind, CheckStatus>()
        for (spec in Td3Layout.CHECKS) {
            val data = spec.data.joinToString("") { it.extract(lines) }
            val check = spec.check.extract(lines)[0]
            val ok = when (spec.kind) {
                CheckDigitKind.OPTIONAL_DATA -> CheckDigit.verify(data, check, allowFillerForEmpty = true)
                else -> CheckDigit.verify(data, check)
            }
            out[spec.kind] = if (ok) CheckStatus.PASS else CheckStatus.FAIL
        }
        return out
    }
}
