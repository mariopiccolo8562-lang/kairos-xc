package com.kairos.xc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Budget di leggerezza: niente nei primi 60 s, niente durante la visione, un lavoro alla volta, chiavette deboli più rade */
class BudgetTest {
    private val boot = 1_000_000L

    private fun s(
        sinceBoot: Long = 120_000L, menu: Boolean = true, playing: Boolean = false, smart: Boolean = true, dev: String = "N",
        loaded: Boolean = true, caps: Boolean = true, dirty: Boolean = false, deadDirty: Boolean = false, check: Long = 1L, guide: Long = 1L, remind: Long = 1L,
        remindOn: Boolean = true, chans: Boolean = true, follows: Boolean = true, now: Long = boot + sinceBoot,
    ) = Budget.S(now, boot, menu, playing, smart, dev, loaded, caps, dirty, deadDirty, check, guide, remind, remindOn, chans, follows)

    @Test
    fun nienteNeiPrimiSessantaSecondi() {
        assertNull(Budget.next(s(sinceBoot = 59_999L, loaded = false)))
        assertEquals(Budget.Job.LOAD, Budget.next(s(sinceBoot = 60_000L, loaded = false)))
    }

    @Test
    fun soloNeiMenuMaiDuranteLaVisione() {
        assertNull(Budget.next(s(menu = false, loaded = false)))
        assertNull(Budget.next(s(playing = true, loaded = false)))
        assertNull(Budget.next(s(menu = true, playing = true, check = 0L)))
    }

    @Test
    fun spenteSoloFileEDecoder() {
        // funzioni intelligenti spente: si legge il file (canali non funzionanti) e i decoder una volta, poi più niente
        assertEquals(Budget.Job.LOAD, Budget.next(s(smart = false, loaded = false)))
        assertEquals(Budget.Job.CAPS, Budget.next(s(smart = false, caps = false)))
        assertNull(Budget.next(s(smart = false, check = 0L, guide = 0L, remind = 0L, dirty = true)))
        // l'elenco dei canali non funzionanti (sempre attivo) si scrive sul disco nei menu anche con le funzioni spente
        assertEquals(Budget.Job.FLUSH, Budget.next(s(smart = false, deadDirty = true)))
        assertNull(Budget.next(s(smart = false, deadDirty = true, playing = true)))
    }

    @Test
    fun unLavoroAllaVoltaInOrdine() {
        val now = boot + 10 * 3_600_000L
        assertEquals(Budget.Job.FLUSH, Budget.next(s(dirty = true, check = 0L, now = now)))
        assertEquals(Budget.Job.CHECK, Budget.next(s(check = 0L, guide = 0L, now = now)))
        assertEquals(Budget.Job.GUIDE, Budget.next(s(check = now, guide = 0L, remind = 0L, now = now)))
        assertEquals(Budget.Job.REMIND, Budget.next(s(check = now, guide = now, remind = 0L, now = now)))
        assertNull(Budget.next(s(check = now, guide = now, remind = now, now = now)))
    }

    @Test
    fun autoControlliOgni30MinutiOgni60SulleChiavette() {
        val now = boot + 10 * 3_600_000L
        val last = now - 31 * 60_000L
        assertEquals(Budget.Job.CHECK, Budget.next(s(check = last, guide = now, remind = now, now = now)))
        assertNull(Budget.next(s(dev = "D", check = last, guide = now, remind = now, now = now)))
        assertEquals(Budget.Job.CHECK, Budget.next(s(dev = "D", check = now - 61 * 60_000L, guide = now, remind = now, now = now)))
        // nessuna funzione più frequente sulle chiavette: guida e promemoria al massimo ogni 15 minuti per tutti
        assertNull(Budget.next(s(dev = "D", check = now, guide = now - 14 * 60_000L, remind = now - 14 * 60_000L, now = now)))
        assertEquals(Budget.Job.GUIDE, Budget.next(s(dev = "D", check = now, guide = now - 15 * 60_000L, remind = now, now = now)))
        assertEquals(4, Budget.guideChannels("D"))
        assertEquals(8, Budget.guideChannels("O"))
        assertEquals(2, Budget.guidePerRun("D"))
    }

    @Test
    fun promemoriaSpentiOSenzaProgrammiSeguiti() {
        val now = boot + 10 * 3_600_000L
        assertNull(Budget.next(s(check = now, guide = now, remind = 0L, remindOn = false, now = now)))
        assertNull(Budget.next(s(check = now, guide = now, remind = 0L, follows = false, now = now)))
        // senza canali abituali la guida piccola non serve
        assertNull(Budget.next(s(check = now, guide = 0L, remind = now, chans = false, now = now)))
    }
}

