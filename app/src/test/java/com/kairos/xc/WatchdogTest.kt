package com.kairos.xc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** testo dell'app bloccata per il pannello (Watchdog.describe) */
class WatchdogTest {
    private fun f(cls: String, file: String?, line: Int) = StackTraceElement(cls, "m", file, line)

    @Test fun primeRigheConFileENumero() {
        val st = arrayOf(f("java.lang.Object", null, -2), f("a.b", "LiveActivity.kt", 514), f("a.c", "Lists.kt", 60), f("android.os.Looper", "Looper.java", 135))
        val (a, b) = Watchdog.describe(st, 9_400, "BLOCKED", "LiveActivity")
        assertEquals("Bloccata 9s (BLOCKED): LiveActivity:514 < Lists:60 < Looper:135", a)
        assertEquals("LiveActivity", b)
    }

    @Test fun massimo120CaratteriERestoNelSecondo() {
        val st = Array(30) { f("x.y", "File$it.kt", 1000 + it) }
        val (a, b) = Watchdog.describe(st, 12_000, "RUNNABLE", "VodActivity")
        assertTrue(a.length <= 120); assertTrue(b.length <= 120)
        assertTrue(a.startsWith("Bloccata 12s (RUNNABLE): File0:1000 < File1:1001"))
        assertTrue(b.startsWith("VodActivity < File"))
    }

    @Test fun righeUgualiDiFilaUnaVolta() {
        val st = arrayOf(f("a", "Db.kt", 10), f("a", "Db.kt", 10), f("b", "Sync.kt", 5))
        assertEquals("Bloccata 8s (WAITING): Db:10 < Sync:5", Watchdog.describe(st, 8_000, "WAITING", "?").first)
    }
}
