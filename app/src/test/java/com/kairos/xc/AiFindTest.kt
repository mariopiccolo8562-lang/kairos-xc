package com.kairos.xc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

/** Ricerca con le frasi: frase o nome, criteri dell'assistente → filtri SQLite con LIMIT, finestra della guida */
class AiFindTest {
    private val rome = TimeZone.getTimeZone("Europe/Rome")

    @Test
    fun fraseNomeODomanda() {
        assertFalse(AiFind.phrase("Rai 1"))
        assertFalse(AiFind.phrase("Gladiatore"))
        assertTrue(AiFind.phrase("film d'azione anni 90"))
        assertTrue(AiFind.phrase("partita stasera"))
        assertTrue(AiFind.phrase("Inter?"))
        assertTrue(AiFind.question("chi è l'attore di Rambo"))
        assertTrue(AiFind.question("A che ora c'è la F1"))
        assertFalse(AiFind.question("film d'azione anni 90"))
    }

    @Test
    fun generiNelleCategorie() {
        val cats = listOf("1" to "FILM | AZIONE", "2" to "Commedie italiane", "3" to "Cartoni animati", "4" to "Warner Bros", "5" to "Documentari", "6" to "Kids & Family")
        assertEquals(listOf("1"), AiFind.cats(cats, listOf("azione"), false))
        // «war» è una parola intera: «Warner» non è un film di guerra
        assertTrue(AiFind.cats(cats, listOf("guerra"), false).isEmpty())
        assertEquals(listOf("2"), AiFind.cats(cats, listOf("commedia"), false))
        assertEquals(listOf("3", "6"), AiFind.cats(cats, emptyList(), true))
        assertEquals(listOf("5"), AiFind.cats(cats, listOf("documentario"), false))
        assertEquals(listOf("9"), AiFind.sportCats(listOf("8" to "Cinema", "9" to "SKY SPORT"), "calcio"))
    }

    @Test
    fun criteriInSql() {
        val f = Find(type = "movie", genres = listOf("azione"), years = intArrayOf(1990, 1999), words = listOf("Inter"))
        val q = AiFind.vodWhere(f, listOf("1", "7"), null)!!
        assertEquals("cat IN (?,?) AND year >= ? AND year <= ? AND (name LIKE ? ESCAPE '\\' OR name LIKE ? ESCAPE '\\')", q.where)
        assertEquals(listOf("1", "7", "1990", "1999", "%inter%", "%internazionale%"), q.args)
        // genere chiesto ma nessuna categoria di quel genere: niente
        assertNull(AiFind.vodWhere(f, emptyList(), null))
        // nessun criterio da cercare (solo «quando», per la TV)
        assertNull(AiFind.vodWhere(Find(whenW = "tonight"), null, null))
        // % e _ restano lettere
        assertEquals("%100\\%\\_x%", AiFind.like("100%_x"))
        val n = AiFind.nameLike(listOf("Rambo", "Rambo", "x", "Cliffhanger"))!!
        assertEquals(2, n.args.size)
        assertEquals(12, AiFind.nameLike((1..30).map { "Titolo $it" })!!.args.size) // al massimo 12
    }

    @Test
    fun tipoEChip() {
        assertEquals("live", Find(whenW = "tonight").kind)
        assertEquals("live", Find(sport = "f1").kind)
        assertEquals("movie", Find(type = "movie", whenW = "today").kind)
        assertEquals("any", Find().kind)
        assertTrue(Find(genres = listOf("bambini")).forKids)
        assertEquals(listOf("Film", "Azione", "1990–1999", "Sylvester Stallone", "Stasera"), AiFind.crit(Find(type = "movie", genres = listOf("azione"), years = intArrayOf(1990, 1999), people = listOf("Sylvester Stallone"), whenW = "tonight")))
        assertEquals(listOf("inter", "internazionale", "f1", "formula 1", "gran premio"), AiFind.words(listOf("Inter", "F1", "x")))
    }

    @Test
    fun finestraDellaGuida() {
        val c = Calendar.getInstance(rome).apply { clear(); set(2026, 10, 8, 15, 0) }
        val tonight = AiFind.window("tonight", c)
        val c18 = Calendar.getInstance(rome).apply { clear(); set(2026, 10, 8, 18, 0) }
        val c01 = Calendar.getInstance(rome).apply { clear(); set(2026, 10, 9, 1, 0) }
        assertEquals(c18.timeInMillis, tonight[0])
        assertEquals(c01.timeInMillis, tonight[1])
        // di notte «stasera» arriva fino alle 4
        val late = Calendar.getInstance(rome).apply { clear(); set(2026, 10, 9, 2, 0) }
        val w = AiFind.window("tonight", late)
        assertEquals(late.timeInMillis, w[0])
        assertEquals(Calendar.getInstance(rome).apply { clear(); set(2026, 10, 9, 4, 0) }.timeInMillis, w[1])
        val tom = AiFind.window("tomorrow", c)
        assertEquals(Calendar.getInstance(rome).apply { clear(); set(2026, 10, 9, 0, 0) }.timeInMillis, tom[0])
        assertEquals(3_600_000L, AiFind.window("now", c).let { it[1] - it[0] })
    }

    @Test
    fun filmografia() {
        val credits = listOf(
            Credit("Rocky", "Rocky", 1976, "movie", listOf(18)),
            Credit("Cliffhanger - L'ultima sfida", "Cliffhanger", 1993, "movie", listOf(28, 12, 53)),
            Credit("Tulsa King", "Tulsa King", 2022, "series", listOf(80)),
        )
        assertEquals(listOf("Cliffhanger - L'ultima sfida", "Cliffhanger"), AiFind.creditTitles(credits, Find(type = "movie", genres = listOf("azione"), years = intArrayOf(1990, 1999))))
        assertEquals(listOf("Tulsa King"), AiFind.creditTitles(credits, Find(type = "series")))
    }
}
