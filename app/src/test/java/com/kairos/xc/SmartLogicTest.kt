package com.kairos.xc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Decoder del dispositivo e versione di partenza, lettore imparato e scelta del lettore, canali «server|flusso» */
class SmartLogicTest {
    private val noHevc1080 = Caps(hevc = false, b10 = false, uhd = false, fps60 = true, maxH = 1080, disp = 1080)
    private val full4k = Caps(hevc = true, b10 = true, uhd = true, fps60 = true, maxH = 2160, disp = 2160)

    @Test
    fun capsSalvateERilette() {
        val c = Caps.load(full4k.save())!!
        assertTrue(c.hevc && c.b10 && c.uhd && c.fps60)
        assertEquals(2160, c.maxH)
        assertEquals("hevc,uhd,b10,fps60", c.text())
        assertEquals("nohevc,nouhd,fps60", noHevc1080.text())
        assertNull(Caps.load(""))
        assertNull(Caps.load("12;x"))
    }

    @Test
    fun versioneDiPartenzaSecondoIlDispositivo() {
        val uhd = VerPick.V(1, "Sport 1 4K", 4, false)
        val fhd = VerPick.V(2, "Sport 1 FHD", 3, false)
        val hd = VerPick.V(3, "Sport 1 HD", 2, false)
        val all = listOf(uhd, fhd, hd)
        // schermo e decoder 1080p: dal 4K si parte dalla FHD
        assertEquals(2L, VerPick.pick(noHevc1080, uhd, all)?.id)
        // il dispositivo regge il 4K: si resta lì
        assertNull(VerPick.pick(full4k, uhd, all))
        // decoder non ancora letti: niente cambia (come oggi)
        assertNull(VerPick.pick(null, uhd, all))
        // HEVC senza decoder HEVC: la versione H.264 (a parità di qualità prima quella non HEVC, mai più pesante)
        val hevc = VerPick.V(10, "Cinema FHD HEVC", 3, true)
        val avc = VerPick.V(11, "Cinema FHD", 3, false)
        val avcHd = VerPick.V(12, "Cinema HD", 2, false)
        assertEquals(11L, VerPick.pick(noHevc1080, hevc, listOf(hevc, avc, avcHd))?.id)
        assertEquals(12L, VerPick.pick(noHevc1080, hevc, listOf(hevc, avcHd))?.id)
        // nessuna sorella che vada: si resta (il recupero automatico fa il resto)
        assertNull(VerPick.pick(noHevc1080, hevc, listOf(hevc)))
        // 10 bit senza Main10 e 50 fps senza decoder adatto
        assertFalse(VerPick.canPlay(noHevc1080, "Film HDR10", 3, false))
        assertFalse(VerPick.canPlay(noHevc1080.let { Caps(it.hevc, it.b10, it.uhd, false, 1080, 1080) }, "Sport FHD 50fps", 3, false))
        assertTrue(VerPick.canPlay(noHevc1080, "Sport HD 50fps", 2, false))
        // schermo che non si conosce (Android 5): il 4K dipende solo dal decoder
        assertTrue(VerPick.canPlay(Caps(true, true, true, true, 2160, 0), "X 4K", 4, false))
    }

    @Test
    fun lettoreImparatoPerFormato() {
        val b = EngineBook(ver = 7)
        val k = EngineBook.key("http://s.tv/live/u/p/1.ts", "Canale")
        assertEquals("ts", k)
        assertEquals("tshevc", EngineBook.key("http://s.tv/live/u/p/1.ts", "Canale HEVC"))
        assertEquals("mkvhevc", EngineBook.key("http://s.tv/movie/u/p/9.mkv", "Film", "HEVC"))
        assertEquals("", EngineBook.key("http://s.tv/live/u/p/1", "Canale"))
        // una prova sola non basta
        b.fail("exo", k); b.ok("vlc", k)
        assertNull(b.best(k))
        // ExoPlayer fallisce di nuovo e VLC riesce: si parte con VLC
        b.fail("exo", k); b.ok("vlc", k)
        assertEquals("vlc", b.best(k))
        assertEquals("ts:vlc", b.summary())
        // VLC comincia a fallire: si torna da soli a ExoPlayer
        repeat(4) { b.fail("vlc", k) }
        repeat(3) { b.ok("exo", k) }
        assertEquals("exo", b.best(k))
        assertEquals("", b.summary())
        // salvato e riletto con la stessa versione; con una versione nuova si ricomincia
        val saved = b.save()
        assertEquals("exo", EngineBook(saved, 7).best(k))
        assertNull(EngineBook(saved, 8).best(k))
        assertEquals(0, EngineBook(saved, 8).size())
    }

    @Test
    fun lettoreImparatoConITetti() {
        val b = EngineBook(ver = 1)
        for (i in 0 until 30) b.ok("exo", "f$i")
        assertEquals(EngineBook.MAX_KEYS, b.size())
        // tanti conteggi: si dimezzano (le prove recenti contano di più)
        repeat(250) { b.ok("exo", "x") }
        assertTrue(b.save().lines().first { it.startsWith("x\t") }.split('\t')[1].toInt() <= 200)
        // motori o chiavi sconosciuti: ignorati
        b.ok("mpv", "x"); b.ok("exo", "")
        assertTrue(b.save().lines().none { it.startsWith("\t") })
    }

    @Test
    fun sceltaDelLettore() {
        // il lettore scelto dal cliente o imposto dal pannello vince sempre
        assertEquals("exo", EngineChoice.pick("exo", true, "vlc"))
        assertEquals("vlc", EngineChoice.pick("vlc", true, null))
        assertEquals("vlc", EngineChoice.pick("vlc", false, "exo"))
        // «Automatico»: l'imparato solo con le funzioni intelligenti accese
        assertEquals("vlc", EngineChoice.pick("auto", true, "vlc"))
        assertEquals("exo", EngineChoice.pick("auto", false, "vlc"))
        assertEquals("exo", EngineChoice.pick("auto", true, null))
        assertEquals("exo", EngineChoice.pick("auto", true, "exo"))
    }

    @Test
    fun canaleComeLoConosceIlPannello() {
        assertEquals("prov.tv:8080|123", Ck.of("http://PROV.tv:8080/live/u/p/123.m3u8", "123"))
        // porta predefinita: come «new URL().host» della Kairos Pro e del pannello
        assertEquals("prov.tv|55", Ck.of("http://prov.tv:80/live/u/p/55.ts", "55"))
        assertEquals("prov.tv|55", Ck.of("https://prov.tv:443/live/u/p/55.ts", ""))
        // lista M3U di tipo Xtream: il numero dall'indirizzo
        assertEquals("m3u.tv:25461|777", Ck.of("http://m3u.tv:25461/live/user/pass/777.ts", ""))
        assertEquals("", Ck.of("http://m3u.tv/canale-senza-numero.m3u8", ""))
        assertEquals("", Ck.of("rtmp://x.tv/live/1", "1"))
        assertEquals("prov.tv:8080", Ck.hostKey("http://prov.tv:8080"))
        assertEquals("prov.tv", Ck.hostKey("prov.tv"))
    }

    @Test
    fun canaliNonFunzionanti() {
        val d = DeadBook("abc", listOf("prov.tv:8080|1", "prov.tv:8080|2", "riserva.tv|3", "altro.tv|9", "<script>|1", "prov.tv:8080|1"), 1_000L)
        assertEquals(4, d.all.size)
        assertEquals(setOf("1", "2", "3"), d.sids(listOf("prov.tv:8080", "riserva.tv")))
        assertTrue(d.sids(listOf("nessuno.tv")).isEmpty())
        val r = DeadBook.load(d.save())
        assertEquals("abc", r.v)
        assertEquals(d.all, r.all)
        assertEquals(1_000L, r.at)
        // tetto
        assertEquals(DeadBook.MAX, DeadBook("v", (1..500).map { "s.tv|$it" }).all.size)
        // senza conferme del pannello da oltre un giorno non vale più
        assertTrue(r.fresh(1_000L + 3_600_000L))
        assertFalse(r.fresh(1_000L + DeadBook.STALE_MS + 1))
        assertFalse(DeadBook.load("").fresh(0L))
    }
}
