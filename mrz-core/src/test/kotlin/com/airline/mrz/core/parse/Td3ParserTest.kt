package com.airline.mrz.core.parse

import com.airline.mrz.core.Fixtures
import com.airline.mrz.core.model.CheckDigitKind
import com.airline.mrz.core.model.CheckStatus
import com.airline.mrz.core.model.Sex
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class Td3ParserTest {
    private val parser = Td3Parser(Fixtures.dates)

    private fun parsed(l1: String, l2: String) = parser.parse(l1, l2) as Td3Parser.Result.Parsed

    @Test
    fun `ICAO specimen parses with all checks passing`() {
        val r = parsed(Fixtures.SPECIMEN_L1, Fixtures.SPECIMEN_L2)
        assertTrue(r.allChecksPass)
        val d = r.data
        assertEquals("P<", d.documentCode)
        assertEquals("UTO", d.issuingState)
        assertEquals("ERIKSSON", d.primaryIdentifier)
        assertEquals("ANNA MARIA", d.secondaryIdentifier)
        assertEquals("L898902C3", d.documentNumber)
        assertEquals("UTO", d.nationality)
        assertEquals(LocalDate.of(1974, 8, 12), d.dateOfBirth)
        assertEquals(Sex.FEMALE, d.sex)
        assertEquals(LocalDate.of(2012, 4, 15), d.dateOfExpiry)
        assertEquals("ZE184226B", d.optionalData)
        assertFalse(d.nameTruncated)
    }

    @Test
    fun `a wrong check digit is reported, not thrown`() {
        val l2 = Fixtures.SPECIMEN_L2.replaceRange(9, 10, "5")
        val r = parsed(Fixtures.SPECIMEN_L1, l2)
        assertEquals(CheckStatus.FAIL, r.checks[CheckDigitKind.DOCUMENT_NUMBER])
        assertEquals(CheckStatus.FAIL, r.checks[CheckDigitKind.COMPOSITE])
        assertEquals(CheckStatus.PASS, r.checks[CheckDigitKind.DATE_OF_BIRTH])
        assertFalse(r.allChecksPass)
    }

    @Test
    fun `wrong length is structural`() {
        assertIs<Td3Parser.Result.Structural>(parser.parse(Fixtures.SPECIMEN_L1.dropLast(1), Fixtures.SPECIMEN_L2))
        assertIs<Td3Parser.Result.Structural>(parser.parse(Fixtures.SPECIMEN_L1, Fixtures.SPECIMEN_L2 + "<"))
    }

    @Test
    fun `letter in a date is structural`() {
        val l2 = Fixtures.SPECIMEN_L2.replaceRange(13, 14, "O")
        assertIs<Td3Parser.Result.Structural>(parser.parse(Fixtures.SPECIMEN_L1, l2))
    }

    @Test
    fun `document type must be P`() {
        assertIs<Td3Parser.Result.Structural>(parser.parse("I" + Fixtures.SPECIMEN_L1.drop(1), Fixtures.SPECIMEN_L2))
    }

    @Test
    fun `German D with fillers and British variants are valid codes`() {
        val l1 = Fixtures.line("P<D<<MUSTERMANN<<ERIKA")
        val l2 = withChecks("C01X00T47", "D<<", "640812", "F", "271031", "<<<<<<<<<<<<<<")
        val r = parsed(l1, l2)
        assertTrue(r.allChecksPass, r.checks.toString())
        assertEquals("D", r.data.issuingStateCanonical)
        assertEquals("D", r.data.nationalityCanonical)

        val gb = withChecks("123456789", "GBD", "900101", "M", "300101", "<<<<<<<<<<<<<<")
        assertTrue(parsed(Fixtures.line("P<GBRSMITH<<JOHN"), gb).allChecksPass)
    }

    @Test
    fun `unknown country code is structural`() {
        val l2 = withChecks("123456789", "ZZZ", "900101", "M", "300101", "<<<<<<<<<<<<<<")
        assertIs<Td3Parser.Result.Structural>(parser.parse(Fixtures.line("P<GBRSMITH<<JOHN"), l2))
    }

    @Test
    fun `sex filler is unspecified`() {
        val l2 = withChecks("123456789", "GBR", "900101", "<", "300101", "<<<<<<<<<<<<<<")
        assertEquals(Sex.UNSPECIFIED, parsed(Fixtures.line("P<GBRSMITH<<JOHN"), l2).data.sex)
    }

    @Test
    fun `unknown date of birth digits parse to null and still verify`() {
        val l2 = withChecks("123456789", "XXA", "90<<<<", "M", "300101", "<<<<<<<<<<<<<<")
        val r = parsed(Fixtures.line("P<GBRDOE<<JOHN"), l2)
        assertNull(r.data.dateOfBirth)
        assertTrue(r.allChecksPass)
    }

    @Test
    fun `empty optional data accepts filler check digit`() {
        val base = withChecks("123456789", "GBR", "900101", "M", "300101", "<<<<<<<<<<<<<<")
        // Replace the optional check digit with '<' and recompute the composite.
        val withFiller = base.replaceRange(42, 43, "<")
        val composite = CheckDigit.compute(withFiller.substring(0, 10) + withFiller.substring(13, 20) + withFiller.substring(21, 43))
        val l2 = withFiller.replaceRange(43, 44, composite.toString())
        val r = parsed(Fixtures.line("P<GBRSMITH<<JOHN"), l2)
        assertEquals(CheckStatus.PASS, r.checks[CheckDigitKind.OPTIONAL_DATA])
        assertEquals(CheckStatus.PASS, r.checks[CheckDigitKind.COMPOSITE])
    }

    @Test
    fun `truncated name is flagged`() {
        val l1 = "P<GBR" + "ABCDEFGHIJKLMNOPQRSTUVWXYZAB<<ABCDEFGHI" // 39 name chars, last is a letter
        assertEquals(44, l1.length)
        val l2 = withChecks("123456789", "GBR", "900101", "M", "300101", "<<<<<<<<<<<<<<")
        assertTrue(parsed(l1, l2).data.nameTruncated)
    }

    /** Builds a valid line 2 by computing every check digit. */
    private fun withChecks(doc: String, nat: String, dob: String, sex: String, exp: String, opt: String): String {
        val docC = CheckDigit.compute(doc)
        val dobC = CheckDigit.compute(dob)
        val expC = CheckDigit.compute(exp)
        val optC = CheckDigit.compute(opt)
        val body = "$doc$docC$nat$dob$dobC$sex$exp$expC$opt$optC"
        val composite = CheckDigit.compute(body.substring(0, 10) + body.substring(13, 20) + body.substring(21, 43))
        return body + composite
    }
}
