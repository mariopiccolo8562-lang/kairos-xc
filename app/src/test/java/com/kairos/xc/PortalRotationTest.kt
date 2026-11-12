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
}
