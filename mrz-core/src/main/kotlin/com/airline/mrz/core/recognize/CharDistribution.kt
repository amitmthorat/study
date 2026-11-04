package com.airline.mrz.core.recognize

import com.airline.mrz.core.model.MrzAlphabet

/**
 * A probability distribution over the 37 MRZ symbols for one character cell.
 *
 * Values are expected to be calibrated (temperature-scaled) so that the top-two margin
 * is meaningful across models and devices. The class is immutable; arithmetic returns
 * new instances.
 */
public class CharDistribution private constructor(private val p: FloatArray) {

    init {
        require(p.size == MrzAlphabet.SIZE) { "expected ${MrzAlphabet.SIZE} probabilities, got ${p.size}" }
    }

    public operator fun get(index: Int): Float = p[index]

    public fun probabilityOf(c: Char): Float {
        val i = MrzAlphabet.indexOf(c)
        return if (i < 0) 0f else p[i]
    }

    /** Index of the most probable symbol. */
    public val argmax: Int
        get() {
            var best = 0
            for (i in 1 until p.size) if (p[i] > p[best]) best = i
            return best
        }

    public val argmaxChar: Char get() = MrzAlphabet.charAt(argmax)

    /** Top-1 probability minus top-2 probability. */
    public val margin: Float
        get() {
            var first = -1f
            var second = -1f
            for (v in p) {
                if (v > first) {
                    second = first; first = v
                } else if (v > second) {
                    second = v
                }
            }
            return first - second
        }

    /** The best and second-best symbol indices. */
    public fun topTwo(): Pair<Int, Int> {
        var first = -1
        var second = -1
        for (i in p.indices) {
            if (first < 0 || p[i] > p[first]) {
                second = first; first = i
            } else if (second < 0 || p[i] > p[second]) {
                second = i
            }
        }
        return first to second
    }

    /**
     * Zeroes every symbol outside [alphabet] and renormalises. If nothing in the
     * alphabet has mass, returns a uniform distribution over the alphabet.
     */
    public fun restrictedTo(alphabet: Set<Char>): CharDistribution {
        val out = FloatArray(MrzAlphabet.SIZE)
        var sum = 0f
        for (c in alphabet) {
            val i = MrzAlphabet.indexOf(c)
            if (i >= 0) {
                out[i] = p[i]; sum += p[i]
            }
        }
        if (sum <= 0f) {
            val u = 1f / alphabet.size
            for (c in alphabet) out[MrzAlphabet.indexOf(c)] = u
        } else {
            for (i in out.indices) out[i] /= sum
        }
        return CharDistribution(out)
    }

    public fun toFloatArray(): FloatArray = p.copyOf()

    override fun toString(): String = "CharDistribution(${argmaxChar} p=${"%.2f".format(p[argmax])} margin=${"%.2f".format(margin)})"

    public companion object {
        /** Builds from raw scores; normalises so they sum to one. Negative entries are clamped to zero. */
        public fun of(values: FloatArray): CharDistribution {
            require(values.size == MrzAlphabet.SIZE)
            val out = FloatArray(MrzAlphabet.SIZE)
            var sum = 0f
            for (i in values.indices) {
                val v = if (values[i] < 0f) 0f else values[i]
                out[i] = v; sum += v
            }
            if (sum <= 0f) {
                out.fill(1f / MrzAlphabet.SIZE)
            } else {
                for (i in out.indices) out[i] /= sum
            }
            return CharDistribution(out)
        }

        /** A distribution that puts [confidence] on [c] and spreads the remainder evenly. */
        public fun oneHot(c: Char, confidence: Float = 1f): CharDistribution {
            val i = MrzAlphabet.indexOf(c)
            require(i >= 0) { "'$c' is not an MRZ symbol" }
            val rest = (1f - confidence) / (MrzAlphabet.SIZE - 1)
            val out = FloatArray(MrzAlphabet.SIZE) { rest }
            out[i] = confidence
            return CharDistribution(out)
        }

        public fun uniform(): CharDistribution = CharDistribution(FloatArray(MrzAlphabet.SIZE) { 1f / MrzAlphabet.SIZE })

        /** Weighted sum of distributions; weights need not be normalised. */
        public fun combine(items: List<Pair<CharDistribution, Float>>): CharDistribution {
            val out = FloatArray(MrzAlphabet.SIZE)
            for ((d, w) in items) {
                if (w <= 0f) continue
                for (i in out.indices) out[i] += d.p[i] * w
            }
            return of(out)
        }
    }
}
