package com.kairos.xc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/** Aggiornamenti: prima l'indirizzo dei download (oggi il pannello), GitHub solo come riserva se il pannello fallisce */
class UpdFeedTest {
    private val gh = "https://github.com/mariopiccolo8562-lang/kairos-xc/releases/latest/download/kairos-xc.json"
    private val panel = "https://panel-kairos.com/get/xc.json"

    @Test
    fun oggiPannelloPoiGitHub() {
        // l'indirizzo dei download oggi è il pannello: il secondo tentativo, uguale al primo, si salta
        assertEquals(listOf(panel, gh), UpdFeed.urls("https://panel-kairos.com", "xc", gh))
        assertEquals(listOf(panel, gh), UpdFeed.urls("https://panel-kairos.com/", "xc", gh))
    }

    @Test
    fun dominioDedicatoPrimaDelPannello() {
        assertEquals(listOf("https://dl.example.com/get/xc.json", panel, gh), UpdFeed.urls("https://dl.example.com/", "xc", gh))
    }

    @Test
    fun indirizziNonValidiSiSaltano() {
        assertEquals(listOf(panel), UpdFeed.urls("", "xc", ""))
        assertEquals(listOf(panel, gh), UpdFeed.urls("http://dl.example.com", "xc", gh))
    }

    @Test
    fun pannelloCheRispondeGitHubNonSiChiede() {
        val asked = ArrayList<String>()
        val r = UpdFeed.first(UpdFeed.urls("https://panel-kairos.com", "xc", gh)) { asked.add(it); "pannello" }
        assertEquals("pannello", r)
        assertEquals(listOf(panel), asked) // nessun controllo in più del solito
    }

    @Test
    fun pannelloConErroreRispostaNonValidaOMutoSiUsaGitHub() {
        // null = errore HTTP (es. 502) o file non valido; eccezione = il pannello non risponde (rete, tempo scaduto)
        val fails = listOf<(String) -> String?>({ null }, { throw IOException("tempo scaduto") })
        for (fail in fails) {
            val asked = ArrayList<String>()
            val r = UpdFeed.first(UpdFeed.urls("https://panel-kairos.com", "xc", gh)) { u -> asked.add(u); if (u == gh) "github" else fail(u) }
            assertEquals("github", r)
            assertEquals(listOf(panel, gh), asked)
        }
    }

    @Test
    fun dominioDedicatoGiuRispondeIlPannello() {
        val asked = ArrayList<String>()
        val r = UpdFeed.first(UpdFeed.urls("https://dl.example.com", "xc", gh)) { u -> asked.add(u); if (u == panel) "pannello" else null }
        assertEquals("pannello", r)
        assertEquals(listOf("https://dl.example.com/get/xc.json", panel), asked)
    }

    @Test
    fun tuttiGiuNessunaVersione() {
        // pannello e GitHub non rispondono (o repository privato): come una rete giù, si riprova più tardi
        assertNull(UpdFeed.first(UpdFeed.urls("https://panel-kairos.com", "xc", gh)) { null })
    }

    @Test
    fun numeroDellaVersione() {
        assertEquals(30401, UpdFeed.code("3.4.1", 0))
        assertEquals(30401, UpdFeed.code("3.4.1", 99))
        assertEquals(30401, UpdFeed.code("", 30401))
        assertEquals(7, UpdFeed.code("3.4", 7))
    }

    @Test
    fun fileValido() {
        assertTrue(UpdFeed.valid(30401, "https://panel-kairos.com/get/xc.apk"))
        assertFalse(UpdFeed.valid(0, "https://panel-kairos.com/get/xc.apk"))
        assertFalse(UpdFeed.valid(30401, "http://panel-kairos.com/get/xc.apk"))
        assertFalse(UpdFeed.valid(30401, ""))
    }
}
