package com.airline.mrz.eval

import com.airline.mrz.core.model.Field
import kotlin.test.Test
import kotlin.test.assertEquals

class MetricsTest {
    private val l1 = "P<UTOERIKSSON<<ANNA<MARIA<<<<<<<<<<<<<<<<<<<"
    private val l2 = "L898902C36UTO7408122F1204159ZE184226B<<<<<10"

    private fun rec(id: String, p1: String?, p2: String?, verified: Boolean, issuer: String = "UTO", tier: String = "t1", corrections: Int = 0) =
        EvalRecord(id, issuer, tier, l1, l2, p1, p2, verified, 1000, corrections)

    @Test
    fun `false accepts are verified sessions with a wrong field`() {
        val wrongDoc = l2.replaceRange(8, 9, "8")
        val r = Metrics.compute(
            listOf(
                rec("a", l1, l2, true),
                rec("b", l1, l2, true, corrections = 1),
                rec("c", l1, wrongDoc, true),
                rec("d", null, null, false),
            ),
        )
        assertEquals(4, r.sessions)
        assertEquals(0.75, r.verifiedRate)
        assertEquals(0.5, r.exactMrzAccuracy)
        assertEquals(1.0 / 3, r.falseAcceptRate, 1e-9)
        assertEquals(listOf("c"), r.falseAccepts)
        assertEquals(2.0 / 3, r.perFieldAccuracy.getValue(Field.DOCUMENT_NUMBER), 1e-9)
        assertEquals(1.0, r.perFieldAccuracy.getValue(Field.NAMES))
        assertEquals(1.0 / 3, r.correctionRate, 1e-9)
    }

    @Test
    fun `per issuer and per tier breakdowns`() {
        val r = Metrics.compute(
            listOf(
                rec("a", l1, l2, true, issuer = "UTO", tier = "t1"),
                rec("b", null, null, false, issuer = "UTO", tier = "t3"),
                rec("c", l1, l2, true, issuer = "GBR", tier = "t3"),
            ),
        )
        assertEquals(0.5, r.perIssuerExactAccuracy.getValue("UTO"))
        assertEquals(1.0, r.perIssuerExactAccuracy.getValue("GBR"))
        assertEquals(1.0, r.perTierVerifiedRate.getValue("t1"))
        assertEquals(0.5, r.perTierVerifiedRate.getValue("t3"))
    }
}
