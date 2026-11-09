package com.kairos.xc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException

/** Aggiornamenti: store-k.download, poi il pannello, poi GitHub; si passa alla base dopo solo se quella prima fallisce */
class UpdFeedTest {
    private val dl = "https://store-k.download"
    private val gh = "https://github.com/mariopiccolo8562-lang/kairos-xc/releases/latest/download/kairos-xc.json"
    private val ghApk = "https://github.com/mariopiccolo8562-lang/kairos-xc/releases/latest/download/kairos-xc.apk"
    private val dlFeed = "https://store-k.download/get/xc.json"
    private val panel = "https://panel-kairos.com/get/xc.json"
    private val dlApk = "https://store-k.download/get/xc.apk"
    private val panelApk = "https://panel-kairos.com/get/xc.apk"

    @Test
    fun ordineDominioDeiDownloadPannelloGitHub() {
        assertEquals(listOf(dlFeed, panel, gh), UpdFeed.urls(dl, "xc", gh))
        assertEquals(listOf(dlFeed, panel, gh), UpdFeed.urls("$dl/", "xc", gh))
    }

    @Test
    fun conIlPannelloComeIndirizzoDeiDownloadIlDoppioneSiSalta() {
        assertEquals(listOf(panel, gh), UpdFeed.urls("https://panel-kairos.com", "xc", gh))
    }

    @Test
    fun indirizziNonValidiSiSaltano() {
        assertEquals(listOf(panel), UpdFeed.urls("", "xc", ""))
        assertEquals(listOf(panel, gh), UpdFeed.urls("http://store-k.download", "xc", gh))
    }

    @Test
    fun conLaPrimaBaseCheRispondeLeAltreNonSiChiedono() {
        val asked = ArrayList<String>()
        val r = UpdFeed.first(UpdFeed.urls(dl, "xc", gh)) { asked.add(it); "file" }
        assertEquals("file", r)
        assertEquals(listOf(dlFeed), asked)
    }

    @Test
    fun dominioNonAttivoOParcheggiatoSiPassaAlPannello() {
        // null = 200 con HTML (dominio parcheggiato), JSON rotto o senza versione/link, errore HTTP;
        // eccezione = nessuna risposta (nome sconosciuto, connessione scaduta dopo 5 s)
        val fails = listOf<(String) -> String?>({ null }, { throw IOException("store-k.download: nome sconosciuto") })
        for (fail in fails) {
            val asked = ArrayList<String>()
            val r = UpdFeed.first(UpdFeed.urls(dl, "xc", gh)) { u -> asked.add(u); if (u == panel) "pannello" else fail(u) }
            assertEquals("pannello", r)
            assertEquals(listOf(dlFeed, panel), asked) // GitHub non si chiede
        }
    }

    @Test
    fun pannelloGiuSiUsaGitHub() {
        val asked = ArrayList<String>()
        val r = UpdFeed.first(UpdFeed.urls(dl, "xc", gh)) { u -> asked.add(u); if (u == gh) "github" else null }
        assertEquals("github", r)
        assertEquals(listOf(dlFeed, panel, gh), asked)
    }

    @Test
    fun tuttiGiuNessunaVersione() {
        // nessuno risponde (o repository privato): come una rete giù, si riprova più tardi
        assertNull(UpdFeed.first(UpdFeed.urls(dl, "xc", gh)) { null })
    }

    @Test
    fun apkDalLinkDelFilePoiDalleBasiSuccessivePoiGitHub() {
        // file dal dominio dei download: poi il pannello, poi GitHub
        assertEquals(listOf(dlApk, panelApk, ghApk), UpdFeed.apkUrls(dlApk, dl, "xc", ghApk))
        // file dal pannello (dominio dei download giù): il dominio dei download non si riprova
        assertEquals(listOf(panelApk, ghApk), UpdFeed.apkUrls(panelApk, dl, "xc", ghApk))
        // file di riserva di GitHub: il suo link, poi l'ultima release
        val rel = "https://github.com/mariopiccolo8562-lang/kairos-xc/releases/download/v2.4.3/kairos-xc.apk"
        assertEquals(listOf(rel, ghApk), UpdFeed.apkUrls(rel, dl, "xc", ghApk))
        assertEquals(listOf(ghApk), UpdFeed.apkUrls(ghApk, dl, "xc", ghApk))
        // link del file con un nome diverso sulla stessa base: lo stesso APK con il nome solito sulle basi dopo
        assertEquals(listOf("$dl/get/xc.apk?v=20403", panelApk, ghApk), UpdFeed.apkUrls("$dl/get/xc.apk?v=20403", dl, "xc", ghApk))
    }

    @Test
    fun downloadRiuscitoAlPrimoColpo() {
        val tried = ArrayList<String>()
        val r = UpdFeed.firstOk(listOf(dlApk, panelApk, ghApk)) { tried.add(it); "ok:$it" }
        assertEquals("ok:$dlApk", r)
        assertEquals(listOf(dlApk), tried)
    }

    @Test
    fun downloadFallitoSiProvaLaBaseDopoELErroreRestaQuelloDelPrimo() {
        val tried = ArrayList<String>()
        val r = UpdFeed.firstOk(listOf(dlApk, panelApk, ghApk)) { u -> tried.add(u); if (u == ghApk) "ok" else throw IOException("HTTP 502 da $u") }
        assertEquals("ok", r)
        assertEquals(listOf(dlApk, panelApk, ghApk), tried)
        val first = IOException("HTTP 502")
        try {
            UpdFeed.firstOk<String>(listOf(dlApk, panelApk)) { u -> throw if (u == dlApk) first else IOException("file non trovato (HTTP 404)") }
            fail("doveva fallire")
        } catch (e: IOException) {
            assertSame(first, e)
        }
    }

    private class Visione : Exception()

    @Test
    fun visioneIniziataFermaTuttoSenzaProvareLeAltreBasi() {
        val tried = ArrayList<String>()
        try {
            UpdFeed.firstOk<String>(listOf(dlApk, panelApk, ghApk), { it is Visione }) { u -> tried.add(u); throw Visione() }
            fail("doveva fermarsi")
        } catch (_: Visione) {
        }
        assertEquals(listOf(dlApk), tried)
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
        assertTrue(UpdFeed.valid(30401, panelApk))
        assertFalse(UpdFeed.valid(0, panelApk))
        assertFalse(UpdFeed.valid(30401, "http://panel-kairos.com/get/xc.apk"))
        assertFalse(UpdFeed.valid(30401, ""))
    }
}
