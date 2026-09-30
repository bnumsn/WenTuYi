package com.wentuyi.protocol

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class RatchetEpochTest {
    @Test
    fun noArgumentEpochUsesTheCurrentClock() {
        val before = System.currentTimeMillis()
        val epoch = DoubleRatchet.newEpoch()
        val after = System.currentTimeMillis()
        assertTrue(epoch in before..after)
    }

    @Test
    fun rapidResetsAlwaysProduceStrictlyNewerEpochs() {
        var epoch = DoubleRatchet.newEpoch()
        repeat(100) {
            val next = DoubleRatchet.newEpoch(epoch)
            assertTrue(next > epoch, "reset $it reused a previous epoch")
            epoch = next
        }
    }

    @Test
    fun knownFutureEpochSurvivesClockRollback() {
        val remembered = Long.MAX_VALUE - 2
        assertEquals(remembered + 1, DoubleRatchet.newEpoch(remembered))
        assertEquals(Long.MAX_VALUE, DoubleRatchet.newEpoch(Long.MAX_VALUE - 1))
    }

    @Test
    fun exhaustedEpochSpaceIsRejectedWithoutOverflow() {
        assertFailsWith<IllegalArgumentException> { DoubleRatchet.newEpoch(Long.MAX_VALUE) }
    }
}
