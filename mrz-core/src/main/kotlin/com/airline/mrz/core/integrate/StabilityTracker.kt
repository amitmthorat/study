package com.airline.mrz.core.integrate

/** Counts how many consecutive observations have produced the same value. */
public class StabilityTracker<T> {
    private var last: T? = null
    public var consecutive: Int = 0
        private set

    public fun observe(value: T): Int {
        if (value == last) consecutive++ else {
            last = value; consecutive = 1
        }
        return consecutive
    }

    public fun reset() {
        last = null; consecutive = 0
    }
}
