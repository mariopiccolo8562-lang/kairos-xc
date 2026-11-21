package com.kairos.xc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/** Cassaforte: nel database dei canali niente server, utente e password; letti tornano uguali (Playlist.packUrl) */
class VaultUrlTest {
    private val o = "http://server.esempio:8080"

    @Test fun packTogliePasswordEServer() {
        val u = "$o/live/mario123/segreta99/1001.ts"
        val p = Playlist.packUrl(u, o, "mario123", "segreta99")
        assertEquals("kx:/live/@U@/@P@/1001.ts", p)
        assertFalse(p.contains("server.esempio") || p.contains("mario123") || p.contains("segreta99"))
        assertEquals(u, Playlist.unpackUrl(p, o, "mario123", "segreta99"))
    }

    @Test fun percorsoSenzaLiveEQuery() {
        val u = "$o/mario123/segreta99/55?x=1"
        val p = Playlist.packUrl(u, o, "mario123", "segreta99")
        assertEquals("kx:/@U@/@P@/55?x=1", p)
        assertEquals(u, Playlist.unpackUrl(p, o, "mario123", "segreta99"))
    }

    @Test fun altroServerOVuotiRestanoComeSono() {
        assertEquals("http://altro.esempio/a.ts", Playlist.packUrl("http://altro.esempio/a.ts", o, "u1x", "p1x"))
        assertEquals("$o:9/a", Playlist.packUrl("$o:9/a", o, "u1x", "p1x")) // stesso nome ma porta diversa
        assertEquals("", Playlist.packUrl("", o, "u", "p"))
        assertEquals("$o/a.ts", Playlist.packUrl("$o/a.ts", "", "u", "p"))
    }

    @Test fun utenteCortoNonSiTocca() {
        val p = Playlist.packUrl("$o/live/ab/cd/1.ts", o, "ab", "cd")
        assertEquals("kx:/live/ab/cd/1.ts", p)
        assertEquals("$o/live/ab/cd/1.ts", Playlist.unpackUrl(p, o, "ab", "cd"))
    }

    @Test fun vecchiIndirizziInChiaroPassano() {
        assertEquals("$o/live/a/b/1.ts", Playlist.unpackUrl("$o/live/a/b/1.ts", o, "a", "b"))
    }
}

class VaultSigTest {
    @Test fun firmaSalvataComeImpronta() {
        val sig = "pl1|xtream|mario123|segreta99|"
        val h = Vault.hash(sig)
        assertEquals(32, h.length)
        assertFalse(h.contains("mario123"))
        org.junit.Assert.assertTrue(Vault.sameSig(h, sig))
        org.junit.Assert.assertTrue(Vault.sameSig(sig, sig)) // versioni vecchie: firma in chiaro
        assertFalse(Vault.sameSig("", sig))
        assertFalse(Vault.sameSig(Vault.hash("altro"), sig))
    }
}
