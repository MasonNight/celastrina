package dev.celastrina.core.testing

import dev.celastrina.core.port.Clock
import kotlin.time.Duration

/** The clock fake: time stands still until a test moves it on. */
class VirtualClock(private var nowMillis: Long = 1_795_000_000_000) : Clock {
    override fun nowMillis() = nowMillis

    fun advanceBy(duration: Duration) {
        require(!duration.isNegative()) { "Time only moves forward: $duration" }
        nowMillis += duration.inWholeMilliseconds
    }
}
