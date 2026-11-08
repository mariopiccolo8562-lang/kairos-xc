package com.kairos.xc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

/** Abitudini (per fascia), programmi e squadre seguiti, promemoria e tetti */
class HabitsTest {
    private fun cal(y: Int, m: Int, d: Int, h: Int, min: Int = 0): Calendar =
        Calendar.getInstance(TimeZone.getTimeZone("Europe/Rome")).apply { clear(); set(y, m - 1, d, h, min) }

    @Test
    fun fasceEGiorni() {
        // sabato 7 novembre 2026, ore 21; domenica 8, ore 15; lunedì 9, ore 8
        assertEquals(24 + 21, HabitBook.slot(cal(2026, 11, 7, 21)))
        assertEquals(48 + 15, HabitBook.slot(cal(2026, 11, 8, 15)))
        assertEquals(8, HabitBook.slot(cal(2026, 11, 9, 8)))
        assertEquals(20261108, HabitBook.day(cal(2026, 11, 8, 15)))
    }

    @Test
    fun canaleAbitualeAQuestOra() {
        val h = HabitBook()
        val sun = cal(2026, 11, 8, 15)
        val t = sun.timeInMillis
        val sl = HabitBook.slot(sun)
        h.watchedChannel("pl:1", "Sky Sport 1", true, "", t, sl, HabitBook.day(sun))
        assertTrue(h.nowChannels(t, sl).isEmpty()) // una volta sola non è un'abitudine
        h.watchedChannel("pl:1", "Sky Sport 1", true, "", t + 1000, sl, HabitBook.day(sun))
        assertEquals(listOf("pl:1"), h.nowChannels(t + 1000, sl))
        // un'ora dopo vale la metà, un altro tipo di giorno meno ancora
        assertTrue(h.score(h.c["pl:1"]!!, t, sl + 1) < h.score(h.c["pl:1"]!!, t, sl))
        assertTrue(h.nowChannels(t, HabitBook.slot(cal(2026, 11, 9, 15))).isEmpty())
        // dimezza ogni 3 settimane
        val s0 = h.score(h.c["pl:1"]!!, t, sl)
        val s3 = h.score(h.c["pl:1"]!!, t + 21 * 86_400_000L, sl)
        assertEquals(s0 / 2, s3, 0.01)
    }

    @Test
    fun programmaSeguitoInDueGiorniDiversi() {
        val h = HabitBook()
        val d1 = cal(2026, 11, 2, 20, 30)
        val d2 = cal(2026, 11, 3, 20, 30)
        h.watchedChannel("pl:1", "Rai 1", false, "Affari tuoi - Puntata 12", d1.timeInMillis, HabitBook.slot(d1), HabitBook.day(d1))
        h.watchedChannel("pl:1", "Rai 1", false, "Affari tuoi - Puntata 12", d1.timeInMillis + 60_000, HabitBook.slot(d1), HabitBook.day(d1))
        assertFalse(h.follows()) // lo stesso giorno conta una volta
        h.watchedChannel("pl:1", "Rai 1", false, "Affari Tuoi (puntata 13)", d2.timeInMillis, HabitBook.slot(d2), HabitBook.day(d2))
        assertEquals(setOf("affari tuoi"), h.followedProgs().keys)
        h.mute("affari tuoi")
        assertFalse(h.follows())
    }

    @Test
    fun squadreDalTitolo() {
        assertEquals(listOf("inter", "juventus"), HabitBook.teamsOf("Serie A: Inter - Juventus"))
        assertEquals(listOf("milan", "roma"), HabitBook.teamsOf("Calcio: AC Milan vs Roma (diretta)"))
        assertEquals(listOf("juventus", "napoli"), HabitBook.teamsOf("Juventus-Napoli"))
        assertTrue(HabitBook.teamsOf("Tg1").isEmpty())
        assertTrue(HabitBook.teamsOf("Il commissario Montalbano - Ep. 3").isEmpty())
        assertEquals("", HabitBook.normProg("Tg1"))
        assertEquals("", HabitBook.normProg("In onda"))
        assertEquals("che tempo che fa", HabitBook.normProg("Che tempo che fa - Replica"))
    }

    @Test
    fun promemoriaDieciMinutiPrimaQuindiciPerLePartite() {
        val h = HabitBook()
        for (d in listOf(cal(2026, 11, 1, 20, 30), cal(2026, 11, 2, 20, 30))) {
            h.watchedChannel("pl:1", "Rai 1", false, "Affari tuoi", d.timeInMillis, HabitBook.slot(d), HabitBook.day(d))
            h.watchedChannel("pl:7", "Sky Sport 1", true, "Serie A: Inter - Milan", d.timeInMillis, HabitBook.slot(d), HabitBook.day(d))
        }
        assertTrue(h.followedTeams().containsKey("inter"))
        val now = cal(2026, 11, 8, 20, 0).timeInMillis
        val progs = listOf(
            Remind.Prog("pl:1", "Affari tuoi", now + 25 * 60_000L, now + 85 * 60_000L),
            Remind.Prog("pl:2", "Affari tuoi", now + 25 * 60_000L, now + 85 * 60_000L), // altro canale: non è il suo
            Remind.Prog("pl:7", "Serie A: Juventus - Inter", now + 20 * 60_000L, now + 140 * 60_000L),
            Remind.Prog("pl:9", "Serie A: Juventus - Inter", now + 20 * 60_000L, now + 140 * 60_000L), // stessa partita: una volta
            Remind.Prog("pl:1", "Affari tuoi", now + 3 * 3_600_000L, now + 4 * 3_600_000L), // troppo avanti
        )
        val c = Remind.candidates(h, progs, now)
        assertEquals(2, c.size)
        val match = c.first { it.key.startsWith("e:") }
        val show = c.first { it.key.startsWith("p:") }
        assertEquals("pl:7", match.ch)
        assertFalse(match.due(now)) // 20 minuti prima: non ancora
        assertTrue(match.due(now + 5 * 60_000L)) // 15 minuti prima
        assertFalse(show.due(now + 5 * 60_000L))
        assertTrue(show.due(now + 15 * 60_000L)) // 10 minuti prima
        assertTrue(show.due(now + 29 * 60_000L)) // iniziato da 4 minuti
        assertFalse(show.due(now + 31 * 60_000L)) // iniziato da oltre 5 minuti: via
        // già proposto: non torna; «non avvisarmi più» per la squadra
        h.markShown(show.key, now)
        h.mute(match.off)
        assertTrue(Remind.candidates(h, progs, now).isEmpty())
    }

    @Test
    fun tettiESalvataggio() {
        val h = HabitBook()
        val base = cal(2026, 11, 1, 10).timeInMillis
        for (i in 0 until 400) {
            val t = base + i * 3_600_000L
            val c = Calendar.getInstance(TimeZone.getTimeZone("Europe/Rome")).apply { timeInMillis = t }
            h.watchedChannel("pl:$i", "Canale $i", i % 2 == 0, "Programma numero uno $i", t, HabitBook.slot(c), HabitBook.day(c))
            h.watchedVod("m|cat$i", "Categoria $i", t, HabitBook.slot(c))
            h.markShown("p:x$i", t)
            h.mute("k$i")
        }
        assertTrue(h.count() <= HabitBook.MAX)
        assertTrue(HabitBook.MAX <= 200)
        assertEquals(HabitBook.MAX_C, h.c.size)
        // si tengono le più recenti
        assertTrue(h.c.containsKey("pl:399") && !h.c.containsKey("pl:0"))
        val r = HabitBook.load(h.save())
        assertEquals(h.count(), r.count())
        assertEquals(h.c["pl:399"]!!.s, r.c["pl:399"]!!.s)
        assertEquals(h.p.keys, r.p.keys)
        // file rovinato o di un'altra versione: si legge quello che si può, sempre con i tetti
        val bad = HabitBook.load("c\tpl:1\tX\tnon-numero\t1\t3:2,999:1,a:b\nzzz\tq\n" + (0 until 300).joinToString("\n") { "o\tk$it" })
        assertEquals(mapOf(3 to 2), bad.c["pl:1"]!!.s)
        assertEquals(HabitBook.MAX_OFF, bad.off.size)
    }

    @Test
    fun staseraSenzaAssistente() {
        val all = listOf(
            TonightPick.C("f1", "film", "Film A", "Aggiunto da poco", 3.0),
            TonightPick.C("f2", "film", "Film B", "Dalla categoria «Azione»", 5.0),
            TonightPick.C("s1", "serie", "Serie A", "Riprendi da dove eri rimasto", 9.0, resume = true),
            TonightPick.C("t1", "tv", "Il film delle 21", "Alle 21:15 su Rai 1", 6.0),
            TonightPick.C("s2", "serie", "Serie B", "Aggiunta da poco", 2.0),
            TonightPick.C("f3", "film", "Film C", "Da scoprire", 1.0),
        )
        val p = TonightPick.local(all)
        assertEquals(listOf("s1", "t1", "f2", "s2", "f1"), p.map { it.key })
        // l'assistente sceglie per indice; scelte non valide o meno di 3: si usano quelle dell'app
        val ai = TonightPick.fromAi(all, listOf(3 to "Stasera in TV", 3 to "doppio", 99 to "fuori", 1 to "", 0 to "Novità"))!!
        assertEquals(listOf("t1", "f2", "f1"), ai.map { it.first.key })
        assertEquals("Dalla categoria «Azione»", ai[1].second)
        assertEquals(null, TonightPick.fromAi(all, listOf(0 to "x", 1 to "y")))
    }
}
