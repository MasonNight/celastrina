package dev.celastrina.core.port

/**
 * The Core's only source of the current time, so every timing rule can run on a virtual clock in
 * tests. Epoch milliseconds, because `java.time` needs API 26 and Celastrina supports API 25.
 */
fun interface Clock {
    fun nowMillis(): Long

    companion object {
        val System = Clock { java.lang.System.currentTimeMillis() }
    }
}
