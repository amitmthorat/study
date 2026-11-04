package com.airline.mrz.core.integrate

import com.airline.mrz.core.model.Field
import com.airline.mrz.core.parse.Td3Parser
import com.airline.mrz.core.recognize.FieldAwareCorrector
import com.airline.mrz.core.recognize.FrameHypothesis
import com.airline.mrz.core.result.FailureReason
import com.airline.mrz.core.result.MrzRead
import com.airline.mrz.core.result.MrzReadOutcome
import com.airline.mrz.core.result.VizCrossCheck

/**
 * Decides, after every voted frame, whether the session has a trustworthy result.
 *
 * `Verified` requires all of:
 *  1. the integrated, corrected MRZ parses and every check digit passes;
 *  2. the corrected string has been identical for [Config.stableFrames] consecutive votes;
 *  3. the minimum top-two margin over all 88 cells is at least [Config.minMargin];
 *  4. at least [Config.minFrames] frames have voted;
 *  5. if a visual-zone read exists, it agrees on document number, date of birth and expiry.
 *
 * A visual-zone disagreement that persists ends the session as `Unverified(VIZ_MISMATCH)`
 * regardless of configuration. Timeout returns the best partial read as `Unverified`
 * only when [Config.allowUnverifiedResult] is set, otherwise `Failed(TIMEOUT)`.
 *
 * An early stop is allowed one frame sooner than [Config.stableFrames] when the
 * integrator's expected-change bound is below [Config.earlyStopChangeBound] and every
 * other condition holds: more frames cannot move the answer.
 */
public class StoppingRule(
    private val config: Config = Config(),
    private val parser: Td3Parser = Td3Parser(),
    private val corrector: FieldAwareCorrector = FieldAwareCorrector(),
) {
    public data class Config(
        val stableFrames: Int = 3,
        val minMargin: Float = 0.25f,
        val minFrames: Int = 5,
        val timeoutMs: Long = 15_000,
        val allowUnverifiedResult: Boolean = false,
        val earlyStopChangeBound: Float = 0.005f,
        /** Agreeing MRZ frames to wait for a VIZ disagreement to clear before giving up. */
        val vizMismatchPatience: Int = 3,
    )

    public sealed interface Decision {
        public data class Continue(val progress: Progress) : Decision
        public data class Stop(val outcome: MrzReadOutcome) : Decision
    }

    /** What the UI can show while reading continues. */
    public data class Progress(
        val framesVoted: Int,
        val stableFrames: Int,
        val checksPassing: Boolean,
        val minMargin: Float,
        val vizState: VizCrossCheck,
    )

    /** Snapshot fed in by the pipeline on every voted frame. */
    public data class Observation(
        val integrator: FrameIntegrator,
        val viz: VizCrossCheck,
        val elapsedMs: Long,
    )

    private val stability = StabilityTracker<String>()
    private var vizMismatchStreak = 0
    private var lastCandidate: Candidate? = null

    private data class Candidate(val outcomeIfStopped: MrzReadOutcome.Verified, val checksPass: Boolean)

    public fun reset() {
        stability.reset()
        vizMismatchStreak = 0
        lastCandidate = null
    }

    public fun evaluate(obs: Observation): Decision {
        val integrated = obs.integrator.integrated()
        if (integrated == null) {
            return if (obs.elapsedMs >= config.timeoutMs) timeout(null, null, obs) else Decision.Continue(Progress(0, 0, false, 0f, obs.viz))
        }

        val corrected = corrector.correct(integrated.line1, integrated.line2)
        val stable = stability.observe(corrected.correctedLines[0] + corrected.correctedLines[1])
        val parse = parser.parse(corrected.correctedLines[0], corrected.correctedLines[1], corrected.fieldConfidence())
        val checksPass = corrected.checks.values.none { it == com.airline.mrz.core.model.CheckStatus.FAIL }

        val read = MrzRead(
            rawLine1 = corrected.rawLines[0],
            rawLine2 = corrected.rawLines[1],
            correctedLine1 = corrected.correctedLines[0],
            correctedLine2 = corrected.correctedLines[1],
            corrections = corrected.corrections,
            checks = corrected.checks,
            vizCrossCheck = obs.viz,
            framesVoted = integrated.framesVoted,
            durationMs = obs.elapsedMs,
            minMargin = integrated.minMargin,
        )
        val passport = (parse as? Td3Parser.Result.Parsed)?.data
        lastCandidate = if (passport != null) Candidate(MrzReadOutcome.Verified(passport, read), checksPass) else null

        if (obs.viz is VizCrossCheck.Disagrees) vizMismatchStreak++ else vizMismatchStreak = 0
        if (vizMismatchStreak >= config.vizMismatchPatience && checksPass && stable >= config.stableFrames) {
            return Decision.Stop(MrzReadOutcome.Unverified(passport, read, FailureReason.VIZ_MISMATCH))
        }

        if (obs.elapsedMs >= config.timeoutMs) return timeout(passport, read, obs)

        val progress = Progress(integrated.framesVoted, stable, checksPass, integrated.minMargin, obs.viz)
        if (passport == null || !checksPass) return Decision.Continue(progress)
        if (integrated.minMargin < config.minMargin) return Decision.Continue(progress)
        if (integrated.framesVoted < config.minFrames) return Decision.Continue(progress)
        if (obs.viz is VizCrossCheck.Disagrees) return Decision.Continue(progress)

        val stableEnough = stable >= config.stableFrames ||
            (stable >= config.stableFrames - 1 && obs.integrator.expectedChangeBound() < config.earlyStopChangeBound)
        if (!stableEnough) return Decision.Continue(progress)

        return Decision.Stop(MrzReadOutcome.Verified(passport, read))
    }

    private fun timeout(passport: com.airline.mrz.core.model.PassportData?, read: MrzRead?, obs: Observation): Decision =
        if (config.allowUnverifiedResult) {
            Decision.Stop(MrzReadOutcome.Unverified(passport, read, FailureReason.TIMEOUT))
        } else {
            Decision.Stop(MrzReadOutcome.Failed(FailureReason.TIMEOUT))
        }
}

/** Fields compared by the visual-zone cross-check. */
public val VIZ_CROSS_CHECK_FIELDS: Set<Field> = setOf(Field.DOCUMENT_NUMBER, Field.DATE_OF_BIRTH, Field.DATE_OF_EXPIRY)

/** Convenience used by the pipeline: integrate and evaluate one frame in a single call. */
public class ReadSession(
    private val integrator: FrameIntegrator = FrameIntegrator(),
    private val rule: StoppingRule = StoppingRule(),
) {
    public fun reset() {
        integrator.reset(); rule.reset()
    }

    public fun onFrame(frame: FrameHypothesis, viz: VizCrossCheck, elapsedMs: Long): Pair<FrameIntegrator.AddResult, StoppingRule.Decision> {
        val add = integrator.add(frame)
        if (add is FrameIntegrator.AddResult.ResetAndVoted) rule.reset()
        val decision = rule.evaluate(StoppingRule.Observation(integrator, viz, elapsedMs))
        return add to decision
    }

    public fun onTick(viz: VizCrossCheck, elapsedMs: Long): StoppingRule.Decision =
        rule.evaluate(StoppingRule.Observation(integrator, viz, elapsedMs))

    public fun integrated(): FrameIntegrator.Integrated? = integrator.integrated()
}
