package com.wentuyi.protocol

import java.security.GeneralSecurityException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class Argon2ResourceBudgetTest {
    private val mib = 1024L * 1024

    @Test
    fun tinyUnauthenticatedEnvelopesCannotRequestExcessiveMemoryOrWork() {
        for ((memoryKb, iterations) in listOf(256 * 1024 to 1, 64 * 1024 to 10)) {
            val packed = ByteArray(54).apply {
                this[0] = 4
                this[1] = 1
                this[3] = (memoryKb ushr 24).toByte()
                this[4] = (memoryKb ushr 16).toByte()
                this[5] = (memoryKb ushr 8).toByte()
                this[6] = memoryKb.toByte()
                this[7] = iterations.toByte()
                this[8] = 1
            }
            val forged = SecurePayloadCodec.PREFIX_V4 + Encoding.b64(packed)
            assertEquals(77, forged.length)
            val error = assertFailsWith<GeneralSecurityException> { SecurePayloadCodec.decryptPayload(forged, "pw") }
            assertTrue(error.message!!.contains("argon2"))
        }
    }

    @Test
    fun smallOrBusyHeapsRejectBeforeTheAllocatorIsCalled() {
        var calls = 0
        val small = Argon2ResourceBudget({ Argon2ResourceBudget.Heap(128 * mib, 128 * mib) })
        assertFailsWith<GeneralSecurityException> { small.run(64 * 1024, 4, 1) { calls++ } }
        var collections = 0
        val busy = Argon2ResourceBudget(
            { Argon2ResourceBudget.Heap(256 * mib, 100 * mib) },
            { collections++ },
        )
        val failure = assertFailsWith<GeneralSecurityException> { busy.run(64 * 1024, 4, 1) { calls++ } }
        assertEquals(0, calls)
        assertEquals(1, collections)
        assertTrue(failure.message!!.contains("max=${256 * mib}"))
        assertTrue(failure.message!!.contains("available=${100 * mib}"))
        assertTrue(failure.message!!.contains("availableBefore=${100 * mib}"))
        assertTrue(failure.message!!.contains("required=${88 * mib}"))
        assertTrue(failure.message!!.contains("reserve=${64 * mib}"))
    }

    @Test
    fun unreachablePreviousKdfBlocksMayBeReclaimedWithoutReducingStrength() {
        var available = 100 * mib
        var collections = 0
        val budget = Argon2ResourceBudget(
            { Argon2ResourceBudget.Heap(256 * mib, available) },
            { collections++; available = 200 * mib },
        )
        assertEquals("derived", budget.run(64 * 1024, 4, 1) { "derived" })
        assertEquals(1, collections)
    }

    @Test
    fun normalV3AndV4ParametersFitA256MiBHeap() {
        val budget = Argon2ResourceBudget({ Argon2ResourceBudget.Heap(256 * mib, 200 * mib) })
        assertEquals("v3", budget.run(CryptoUtils.ARGON2_MEMORY_KB, CryptoUtils.ARGON2_ITERATIONS, 1) { "v3" })
        assertEquals("v4", budget.run(SecurePayloadCodec.ARGON_MEM_KB_DEFAULT, SecurePayloadCodec.ARGON_ITER_DEFAULT, 1) { "v4" })
        assertFailsWith<IllegalStateException> { budget.run(64 * 1024, 4, 1) { error("derivation failed") } }
        assertEquals("retry", budget.run(64 * 1024, 4, 1) { "retry" })
    }

    @Test
    fun concurrentKdfsShareTheSameMemoryBudget() {
        val budget = Argon2ResourceBudget({ Argon2ResourceBudget.Heap(256 * mib, 200 * mib) })
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val secondAttempt = CountDownLatch(1)
        val allocations = AtomicInteger()
        val workers = Executors.newFixedThreadPool(2)
        try {
            val first = workers.submit<Int> {
                budget.run(64 * 1024, 4, 1) {
                    allocations.incrementAndGet()
                    entered.countDown()
                    check(release.await(5, TimeUnit.SECONDS))
                    allocations.decrementAndGet()
                }
            }
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            val second = workers.submit<Int> {
                secondAttempt.countDown()
                budget.run(64 * 1024, 4, 1) { allocations.incrementAndGet() }
            }
            assertTrue(secondAttempt.await(5, TimeUnit.SECONDS))
            assertFailsWith<TimeoutException> { second.get(100, TimeUnit.MILLISECONDS) }
            assertEquals(1, allocations.get())
            release.countDown()
            assertEquals(0, first.get(5, TimeUnit.SECONDS))
            assertEquals(1, second.get(5, TimeUnit.SECONDS))
        } finally {
            release.countDown()
            workers.shutdownNow()
        }
    }
}
