package com.kairos.xc

import com.kairos.xc.CmdBook.Act
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Registro dei comandi del pannello: doppioni, attesa della fine della visione, comandi già eseguiti */
class CmdBookTest {
    @Test
    fun doppioniIgnorati() {
        val b = CmdBook()
        assertEquals(Act.RUN, b.arrive(1, "sync", false, 0))
        // il pannello lo rimanda al battito dopo, finché non arriva la conferma: già in corso
        assertEquals(Act.SKIP, b.arrive(1, "sync", false, 20_000))
        assertEquals(Act.SKIP, b.arrive(1, "sync", false, 40_000))
        // un comando diverso parte lo stesso
        assertEquals(Act.RUN, b.arrive(2, "sync", false, 40_000))
        assertTrue(b.isRunning(1))
    }

    @Test
    fun inAttesaDuranteLaVisioneSenzaConferma() {
        val b = CmdBook()
        assertEquals(Act.WAIT, b.arrive(5, "speed", true, 0))
        assertTrue(b.isWaiting(5))
        // ogni battito della visione lo riporta: resta in attesa, nessuna conferma
        assertEquals(Act.SKIP, b.arrive(5, "speed", true, 20_000))
        assertNull(b.doneOf(5))
        // lettore ancora aperto: non parte
        assertTrue(b.release(30_000) { true }.isEmpty())
        // lettore chiuso: parte una volta sola
        assertEquals(listOf(5L to "speed"), b.release(40_000) { false })
        assertTrue(b.release(41_000) { false }.isEmpty())
        assertFalse(b.isWaiting(5))
        assertEquals(Act.SKIP, b.arrive(5, "speed", false, 45_000))
        // concluso: da qui si conferma soltanto
        b.finish(5, true, "↓ 42 Mbps")
        assertEquals(Act.ACK, b.arrive(5, "speed", false, 60_000))
    }

    @Test
    fun inAttesaRipartitoDalBattito() {
        val b = CmdBook()
        assertEquals(Act.WAIT, b.arrive(3, "perm", true, 0))
        // il battito dopo la fine della visione lo riporta prima dell'aggancio: parte da lì, una volta sola
        assertEquals(Act.RUN, b.arrive(3, "perm", false, 60_000))
        assertTrue(b.release(61_000) { false }.isEmpty())
        assertEquals(Act.SKIP, b.arrive(3, "perm", false, 80_000))
    }

    @Test
    fun attesaNellOrdineDiArrivo() {
        val b = CmdBook()
        b.arrive(10, "sync", true, 0)
        b.arrive(11, "speed", true, 0)
        b.arrive(12, "perm", true, 0)
        // fine dello schermo intero ma anteprima ancora accesa: lo speed test aspetta ancora
        assertEquals(listOf(10L to "sync", 12L to "perm"), b.release(1_000) { it == "speed" })
        assertEquals(listOf(11L to "speed"), b.release(2_000) { false })
    }

    @Test
    fun giaEseguitoSiConfermaSoltantoAncheDopoIlRiavvio() {
        val b = CmdBook()
        assertEquals(Act.RUN, b.arrive(7, "reload", false, 0))
        b.finish(7, true, "riavvio")
        // processo chiuso prima che la conferma arrivasse: dopo la riapertura il pannello rimanda il comando
        val dopo = CmdBook(b.save())
        assertEquals(Act.ACK, dopo.arrive(7, "reload", false, 0))
        val d = dopo.doneOf(7)
        assertNotNull(d)
        assertTrue(d!!.ok)
        assertEquals("riavvio", d.info)
        // e resta così a ogni battito, finché il pannello non smette di mandarlo
        assertEquals(Act.ACK, dopo.arrive(7, "reload", false, 20_000))
        assertFalse(dopo.isRunning(7))
    }

    @Test
    fun confermaInViaggioNonRaddoppia() {
        val b = CmdBook()
        b.arrive(8, "sync", false, 0)
        b.finish(8, true, "liste in aggiornamento")
        assertTrue(b.sending(8))
        assertFalse(b.sending(8))
        assertEquals(Act.SKIP, b.arrive(8, "sync", false, 20_000))
        b.sent(8)
        assertEquals(Act.ACK, b.arrive(8, "sync", false, 40_000))
    }

    @Test
    fun restanoGliUltimi30() {
        val b = CmdBook()
        for (id in 1L..35L) b.finish(id, true, "ok $id")
        for (id in 1L..5L) assertNull(b.doneOf(id))
        for (id in 6L..35L) assertNotNull(b.doneOf(id))
        val dopo = CmdBook(b.save())
        assertNull(dopo.doneOf(5))
        assertEquals("ok 6", dopo.doneOf(6)!!.info)
        assertEquals("ok 35", dopo.doneOf(35)!!.info)
        // un comando vecchio uscito dal registro che torna si esegue di nuovo
        assertEquals(Act.RUN, dopo.arrive(1, "sync", false, 0))
    }

    @Test
    fun inCorsoDaTroppoSiRifa() {
        val b = CmdBook(staleMs = 60_000)
        assertEquals(Act.RUN, b.arrive(9, "update", false, 0))
        assertEquals(Act.SKIP, b.arrive(9, "update", false, 59_000))
        assertEquals(Act.RUN, b.arrive(9, "update", false, 61_000))
    }

    @Test
    fun visioneIniziataDuranteLaMisura() {
        val b = CmdBook()
        assertEquals(Act.RUN, b.arrive(4, "speed", false, 0))
        b.requeue(4, "speed")
        assertTrue(b.isWaiting(4))
        assertFalse(b.isRunning(4))
        assertEquals(Act.SKIP, b.arrive(4, "speed", true, 20_000))
        assertEquals(listOf(4L to "speed"), b.release(30_000) { false })
        // un comando non in corso non torna in attesa
        b.finish(4, true, "fatto")
        b.requeue(4, "speed")
        assertFalse(b.isWaiting(4))
    }

    @Test
    fun esitoNegativoSalvato() {
        val b = CmdBook()
        b.finish(20, false, "comando non supportato")
        val d = CmdBook(b.save()).doneOf(20)
        assertNotNull(d)
        assertFalse(d!!.ok)
        assertEquals("comando non supportato", d.info)
    }

    @Test
    fun testoSuUnaRigaETagliato() {
        val b = CmdBook()
        b.finish(21, true, "a\tb\nc\rd")
        b.finish(22, true, "x".repeat(300))
        val dopo = CmdBook(b.save())
        assertEquals("a b c d", dopo.doneOf(21)!!.info)
        assertEquals(200, dopo.doneOf(22)!!.info.length)
    }

    @Test
    fun valoriNonValidi() {
        val b = CmdBook("righe\nnon valide\n0\t1\tzero\n-3\t1\tmeno\nabc\t1\tx\n33\t1\tbuona")
        assertEquals(Act.ACK, b.arrive(33, "sync", false, 0))
        assertEquals(Act.SKIP, b.arrive(0, "sync", false, 0))
        assertEquals(Act.SKIP, b.arrive(-1, "sync", false, 0))
        assertEquals(Act.SKIP, b.arrive(34, "", false, 0))
        assertEquals(Act.RUN, b.arrive(34, "sync", false, 0))
    }
}
