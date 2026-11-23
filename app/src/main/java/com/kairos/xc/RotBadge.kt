package com.kairos.xc

/**
 * «ROTAX DNS» (23 novembre 2026, il titolare: «in modo da vedere se i due DNS ci sono»): c'è quando il pannello ha dato alla
 * lista dei server di riserva. Il numero = quanti indirizzi (2 = principale + 1 di riserva).
 * Verde = rotativo (a turno); azzurro = automatico, riserva pronta; giallo = automatico, in uso la riserva perché il principale
 * non andava; bianco tenue = fissato dal pannello su un solo indirizzo. Al tocco: quanti indirizzi e come si usano (mai gli
 * indirizzi o i nomi dei server sullo schermo). Logica pura: la provano i test JVM.
 */
object RotBadge {
    /** "x" = niente pulsante; "on", "res", "warn", "" (fisso) */
    fun state(mode: String, n: Int, usingMain: Boolean): String = when {
        n < 2 -> "x"
        mode == "rot" -> "on"
        mode == "auto" || mode.isEmpty() -> if (usingMain) "res" else "warn"
        else -> ""
    }

    fun color(st: String): Int = when (st) {
        "on" -> 0xFF2EE59D.toInt(); "res" -> 0xFF6CB8FF.toInt(); "warn" -> 0xFFF5B041.toInt(); else -> 0xCCFFFFFF.toInt()
    }

    fun text(st: String, mode: String, n: Int): String {
        val how = when (mode) { "rot" -> "a turno"; "main" -> "solo il principale"; "alt" -> "solo quello di riserva"; else -> "il principale, la riserva se non va" }
        val head = when (st) { "on" -> "ROTAX DNS attivo"; "res" -> "DNS di riserva pronto"; "warn" -> "Il server principale non risponde: si usa quello di riserva"; else -> "DNS fissato dal fornitore" }
        return "$head\n$n indirizzi (1 principale + ${n - 1} di riserva) · $how"
    }

    /** stato della lista in uso: (stato, numero di indirizzi, modo) o null */
    fun of(p: Playlist?): Triple<String, Int, String>? {
        p ?: return null
        val c = Portals.cands(p)
        if (c.size < 2) return null
        val usingMain = Portals.host(p).equals(c.first(), true)
        val st = state(p.mode, c.size, usingMain)
        return if (st == "x") null else Triple(st, c.size, p.mode)
    }
}
