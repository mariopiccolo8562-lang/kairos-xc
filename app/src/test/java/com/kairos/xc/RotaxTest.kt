package com.kairos.xc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** «ROTAX DNS» e lista dalla riserva (23 novembre 2026). Indirizzi inventati. */
class RotaxTest {
    @Test fun statoDelPulsante() {
        assertEquals("x", RotBadge.state("auto", 1, true)) // senza riserva: niente pulsante
        assertEquals("on", RotBadge.state("rot", 2, true))
        assertEquals("on", RotBadge.state("auto", 3, true)) // principale in uso: verde
        assertEquals("warn", RotBadge.state("auto", 2, false)) // principale giù: in uso la riserva
        assertEquals("on", RotBadge.state("main", 2, true))
        assertEquals("warn", RotBadge.state("alt", 2, false)) // fissato sulla riserva: giallo
        assertEquals("I DNS sono gestiti dal fornitore", RotBadge.text("on", "rot", 2))
        assertFalse(RotBadge.text("warn", "auto", 3).contains("riserva"))
        assertFalse(RotBadge.text("warn", "auto", 2).contains("server.esempio"))
        assertEquals(0xFF2EE59D.toInt(), RotBadge.color("on"))
    }

    @Test fun rispostaVeraDelServer() {
        assertTrue(PortalReach.answers(200, " {\"user_info\":{\"auth\":1}}"))
        assertTrue(PortalReach.answers(200, "{\"user_info\":{\"auth\":0}}")) // linea scaduta: risponde, il server non si cambia
        assertFalse(PortalReach.answers(200, "<html>pagina del gestore</html>"))
        assertFalse(PortalReach.answers(503, "{}"))
    }

    @Test fun ordineDelleRiserve() {
        val c = listOf("http://server.esempio:8080", "http://nuovo.server.tv:8080", "http://altro.server.test:8080")
        assertEquals(listOf("http://nuovo.server.tv:8080", "http://altro.server.test:8080"), PortalReach.others("auto", c, c[0], "KXRT-0001"))
        val rot = PortalReach.others("rot", c, c[1], "KXRT-0001")
        assertEquals(2, rot.size)
        assertFalse(rot.contains(c[1]))
    }
}
