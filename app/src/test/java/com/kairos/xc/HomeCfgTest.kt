package com.kairos.xc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** Elenchi dal pannello (home.top, home.hide e adult): logica pura di [HomeCfg.Rules], con e senza la voce «adult» */
class HomeCfgTest {
    private val cats = listOf("Prima Visione", "Sport", "XXX | 4K", "Kids", "Film +18", "Adult Swim", "Documentari", "Per adulti")

    private fun rules(top: String = "", hide: String = "", adult: String = "") = HomeCfg.Rules(top, hide, adult)

    @Test
    fun senzaLaVoceAdultiGliAdultiSonoNascosti() {
        for (r in listOf(rules(), rules(adult = "off"), rules(adult = "qualsiasi"))) {
            assertTrue(r.noAdult)
            assertTrue(r.hidden("XXX | 4K"))
            assertTrue(r.hidden("Film +18"))
            assertTrue(r.hidden("Per adulti"))
            assertFalse(r.hidden("Adult Swim"))
            assertFalse(r.hidden("Sport"))
        }
    }

    @Test
    fun conOnTuttoComePrima() {
        val r = rules(adult = "on")
        assertFalse(r.noAdult)
        assertFalse(r.hidden("XXX | 4K"))
        // senza home.top e home.hide l'elenco resta quello di prima, lo stesso oggetto
        assertSame(cats, r.order(cats) { it })
        assertEquals(0, rules(top = "xxx, sport", adult = "on").topRank("XXX | 4K"))
    }

    @Test
    fun laListaHomeHideValeSempre() {
        for (a in listOf("", "on", "off")) {
            val r = rules(hide = "sport; documentari", adult = a)
            assertTrue(r.hidden("Sport"))
            assertTrue(r.hidden("DOCUMENTARI HD"))
            assertFalse(r.hidden("Kids"))
        }
    }

    @Test
    fun negliElenchiGliAdultiVannoInFondo() {
        assertEquals(
            listOf("Prima Visione", "Sport", "Kids", "Adult Swim", "Documentari", "XXX | 4K", "Film +18", "Per adulti"),
            rules().order(cats) { it },
        )
        // primo piano prima, poi le altre, in fondo le nascoste da home.hide e quelle per adulti (ognuna nel suo ordine)
        assertEquals(
            listOf("Documentari", "Prima Visione", "Kids", "Adult Swim", "Sport", "XXX | 4K", "Film +18", "Per adulti"),
            rules(top = "documentari, prima", hide = "sport").order(cats) { it },
        )
        // con «on» gli adulti restano al loro posto, come prima
        assertEquals(
            listOf("Documentari", "Prima Visione", "XXX | 4K", "Kids", "Film +18", "Adult Swim", "Per adulti", "Sport"),
            rules(top = "documentari, prima", hide = "sport", adult = "on").order(cats) { it },
        )
        // una categoria per adulti in primo piano va comunque in fondo
        assertEquals("XXX | 4K", rules(top = "xxx").order(cats) { it }[5])
    }

    @Test
    fun maiCategorieInPrimoPianoPerAdulti() {
        val r = rules(top = "xxx, sport")
        assertEquals(-1, r.topRank("XXX | 4K"))
        assertEquals(1, r.topRank("Sport"))
        assertEquals(-1, rules().topRank("Sport"))
    }

    @Test
    fun limprontaCambiaConLaVoceAdulti() {
        assertNotEquals(rules().key, rules(adult = "on").key)
        assertNotEquals(rules(adult = "on").key, rules(adult = "off").key)
        assertNotEquals(rules(top = "a").key, rules(hide = "a").key)
        assertEquals(rules(top = "a", adult = "off").key, rules(top = "a", adult = "off").key)
    }

    @Test
    fun laCacheDaSempreLoStessoEsito() {
        val r = rules(hide = "kids")
        repeat(3) {
            assertTrue(r.hidden("XXX | 4K"))
            assertTrue(r.hidden("Kids"))
            assertFalse(r.hidden("Sport"))
        }
    }
}
