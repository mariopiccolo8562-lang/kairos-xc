package com.kairos.xc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Sequenze dello zapping: canale successivo più probabile, con il tetto */
class ZapBookTest {
    @Test
    fun canaleSuccessivoImparato() {
        val z = ZapBook()
        z.learn("a", "b"); z.learn("a", "b")
        assertNull(z.predict("a")) // due volte non bastano
        z.learn("a", "b")
        assertEquals("b", z.predict("a"))
        repeat(6) { z.learn("a", "c") } // ora prevale «c»
        assertEquals("c", z.predict("a"))
        assertEquals("c", ZapBook(z.save()).predict("a"))
        z.learn("x", "x"); z.learn("", "y")
        assertNull(z.predict("x"))
    }

    @Test
    fun tetto() {
        val z = ZapBook()
        for (i in 0 until 500) for (j in 0 until 10) z.learn("c$i", "d$j")
        assertEquals(ZapBook.MAX, z.size())
        assertTrue(z.save().lines().all { it.split('\t').size <= 5 })
        assertEquals(ZapBook.MAX, ZapBook(z.save()).size())
    }
}
