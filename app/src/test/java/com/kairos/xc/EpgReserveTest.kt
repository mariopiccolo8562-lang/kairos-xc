package com.kairos.xc

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** «Guida TV di riserva» (21 novembre 2026): i canali che hanno già i programmi del fornitore non ricevono quelli della riserva */
class EpgReserveTest {
    @Test fun sealedChannelsKeepTheProviderGuide() {
        val limiter = XmltvLimiter(perChannel = 60, total = 100)
        limiter.seal(listOf("id:rai1.it"))
        assertFalse(limiter.accept("id:rai1.it", 1_000L))
        assertTrue(limiter.accept("n:canaleesempio", 1_000L))
    }

    @Test fun sealAfterFirstSourceLocksOnlyChannelsAlreadyFilled() {
        val limiter = XmltvLimiter(perChannel = 60, total = 100)
        assertTrue(limiter.accept("id:a", 1_000L))
        limiter.seal()
        assertFalse(limiter.accept("id:a", 2_000L))
        assertTrue(limiter.accept("id:b", 2_000L))
    }
}
