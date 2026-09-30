package com.wentuyi.protocol

import java.security.GeneralSecurityException

/**
 * Argon2 parameters arrive before header authentication. Bound both memory and work before
 * Bouncy Castle allocates its blocks. The single process-wide instance also serializes KDFs,
 * so individually acceptable requests cannot exhaust the heap by running concurrently.
 */
internal class Argon2ResourceBudget(
    private val heap: () -> Heap = {
        val runtime = Runtime.getRuntime()
        Heap(runtime.maxMemory(), runtime.maxMemory() - runtime.totalMemory() + runtime.freeMemory())
    },
    // Older Android System.gc() can defer the request until runFinalization() rather
    // than collect anything. Runtime.gc() directly requests collection on ART too.
    private val reclaim: () -> Unit = { Runtime.getRuntime().gc() },
) {
    internal data class Heap(val maximumBytes: Long, val availableBytes: Long)

    companion object {
        const val MAX_MEMORY_KB = 64 * 1024
        private const val MAX_WORK_KB_ROUNDS = MAX_MEMORY_KB * 4L
        private const val MIB = 1024L * 1024
    }

    @Synchronized
    fun <T> run(memoryKb: Int, iterations: Int, parallelism: Int, derive: () -> T): T {
        if (parallelism !in 1..4 || memoryKb !in (8 * parallelism)..MAX_MEMORY_KB ||
            iterations !in 1..10 || memoryKb.toLong() * iterations > MAX_WORK_KB_ROUNDS) {
            throw GeneralSecurityException("argon2 parameters exceed resource budget")
        }
        // BC stores each 1 KiB block in a separate object/LongArray. Account for those
        // allocations, scratch buffers and the generator, rather than budgeting only m.
        val requiredBytes = memoryKb.toLong() * 1280 + 8 * MIB
        var current = heap()
        if (requiredBytes > current.maximumBytes / 2) {
            throw GeneralSecurityException("insufficient heap for argon2 parameters: " +
                "max=${current.maximumBytes}, available=${current.availableBytes}, required=$requiredBytes")
        }
        fun hasRoom(snapshot: Heap): Boolean {
            val reserve = (snapshot.maximumBytes / 4).coerceIn(16 * MIB, 64 * MIB)
            return snapshot.availableBytes >= requiredBytes + reserve
        }
        if (!hasRoom(current)) {
            // A previous serialized KDF's now-unreachable blocks may still await GC.
            // Recheck actual headroom once; never lower the KDF strength or catch OOM.
            val availableBefore = current.availableBytes
            reclaim()
            current = heap()
            if (!hasRoom(current)) throw GeneralSecurityException("insufficient free heap for argon2: " +
                "max=${current.maximumBytes}, available=${current.availableBytes}, " +
                "availableBefore=$availableBefore, required=$requiredBytes, " +
                "reserve=${(current.maximumBytes / 4).coerceIn(16 * MIB, 64 * MIB)}")
        }
        return derive()
    }
}
