package com.kairos.xc

import org.junit.Assert.assertEquals
import org.junit.Test

/** Conti dello speed test: latenza, velocità con l'avvio escluso, testo della conferma al pannello */
class SpeedCalcTest {
    @Test
    fun mediana() {
        assertEquals(20.0, SpeedCalc.median(listOf(30.0, 10.0, 20.0)), 1e-9)
        assertEquals(15.0, SpeedCalc.median(listOf(30.0, 10.0, 20.0, 10.0)), 1e-9)
        assertEquals(0.0, SpeedCalc.median(emptyList()), 1e-9)
    }

    @Test
    fun jitter() {
        assertEquals(7.5, SpeedCalc.jitter(listOf(10.0, 20.0, 15.0)), 1e-9)
        assertEquals(0.0, SpeedCalc.jitter(listOf(10.0)), 1e-9)
    }

    @Test
    fun velocitaSenzaLAvvio() {
        // 1 MB nei primi 1,5 s (non conta), poi 10 MB in 6,5 s
        val mbps = SpeedCalc.rate(0L, 1_000_000L, 1_500_000_000L, 11_000_000L, 8_000_000_000L)
        assertEquals(10_000_000 * 8.0 / 6.5 / 1e6, mbps, 1e-9)
    }

    @Test
    fun velocitaSuTuttaLaDurataSeLAvvioNonFinisce() {
        assertEquals(8.0, SpeedCalc.rate(0L, -1L, 0L, 1_000_000L, 1_000_000_000L), 1e-9)
        // avvio finito ma meno di mezzo secondo dopo: tutta la durata
        assertEquals(8.0, SpeedCalc.rate(0L, 900_000L, 800_000_000L, 1_000_000L, 1_000_000_000L), 1e-9)
        assertEquals(0.0, SpeedCalc.rate(5L, -1L, 0L, 0L, 5L), 1e-9)
    }

    @Test
    fun riassuntoPerIlPannello() {
        assertEquals("↓ 42 Mbps · ↑ 9,1 Mbps · ping 18 ms · jitter 3 ms · Cloudflare MXP", SpeedCalc.summary(42.3, 9.14, 18.4, 2.6, "Cloudflare MXP"))
        assertEquals("↓ 0,85 Mbps · ↑ n.d. · ping 120 ms · jitter 40 ms", SpeedCalc.summary(0.853, -1.0, 120.2, 39.6, ""))
    }

    @Test
    fun numeriNonValidi() {
        assertEquals(0.0, SpeedCalc.r1(Double.NaN), 0.0)
        assertEquals(0.0, SpeedCalc.r1(Double.POSITIVE_INFINITY), 0.0)
        assertEquals(42.3, SpeedCalc.r1(42.26), 1e-9)
        assertEquals(0L, SpeedCalc.ms(Double.NaN))
        assertEquals(18L, SpeedCalc.ms(18.4))
        assertEquals("0,00 Mbps", SpeedCalc.mbps(Double.NaN))
        assertEquals("0,00 Mbps", SpeedCalc.mbps(-3.0))
    }
}
