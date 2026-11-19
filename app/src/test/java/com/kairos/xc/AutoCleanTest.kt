package com.kairos.xc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AutoCleanTest {
    private val D = AutoClean.DAY_MS

    @Test fun giorni() {
        assertEquals(1, AutoClean.days("1")); assertEquals(7, AutoClean.days("7")); assertEquals(30, AutoClean.days(" 30 "))
        assertEquals(0, AutoClean.days(null)); assertEquals(0, AutoClean.days("")); assertEquals(0, AutoClean.days("off")); assertEquals(0, AutoClean.days("2"))
    }

    @Test fun spentaMai() { assertFalse(AutoClean.due(10 * D, 0L, 0)) }

    @Test fun primaVoltaSubito() { assertTrue(AutoClean.due(10 * D, 0L, 7)) }

    @Test fun ogniTotGiorni() {
        val last = 100 * D
        assertFalse(AutoClean.due(last + 6 * D, last, 7))
        assertTrue(AutoClean.due(last + 7 * D, last, 7))
        assertFalse(AutoClean.due(last + 23 * 3600_000L, last, 1))
        assertTrue(AutoClean.due(last + D, last, 1))
    }

    @Test fun orologioIndietro() { assertTrue(AutoClean.due(50 * D, 100 * D, 7)) }
}
