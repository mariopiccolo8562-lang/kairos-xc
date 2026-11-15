package com.kairos.xc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PortalRotationTest {
    @Test
    fun rotationIsStableAndDifferentCodesSpreadAcrossCandidates() {
        val candidates = listOf("main", "alt1", "alt2")
        assertEquals(PortalRotation.order(candidates, "DEVICE-123"), PortalRotation.order(candidates, "DEVICE-123"))
        val starts = (0..30).map { PortalRotation.order(candidates, "DEVICE-$it").first() }.toSet()
        assertEquals(candidates.toSet(), starts)
    }

    @Test
    fun firstHealthySkipsBadCandidates() {
        val candidates = listOf("main", "alt1", "alt2")
        val code = "DEVICE-123"
        val order = PortalRotation.order(candidates, code)
        val selected = PortalRotation.firstHealthy(candidates, code) { it == order.first() }
        assertEquals(order[1], selected)
        assertTrue(PortalRotation.firstHealthy(candidates, code) { true } == null)
    }

    @Test
    fun rotProbeKeepsDeviceOrderWhileAutoProbeKeepsFastestHost() {
        val candidates = listOf("main", "alt1", "alt2")
        val order = PortalRotation.order(candidates, "DEVICE-123")
        val elapsed = mapOf(order[1] to 10L, order[0] to 20L)

        assertEquals(order[0], PortalRotation.pickProbed("rot", order, elapsed))
        assertEquals(order[1], PortalRotation.pickProbed("auto", order, elapsed))
    }

    @Test
    fun playingOriginFindsTheCandidateInTheCurrentStreamUrl() {
        val candidates = listOf("https://main.example", "https://backup.example")
        assertEquals(candidates[0], Portals.playingOrigin(candidates, "https://main.example/live/channel.ts"))
    }

    @Test
    fun nextCandidateUsesPlayingHostWhenItIsAlreadyBad() {
        val candidates = listOf("https://main.example", "https://backup.example")
        assertEquals(candidates[1], Portals.nextCandidate(candidates, candidates[0]) { it == candidates[0] })
    }
}
