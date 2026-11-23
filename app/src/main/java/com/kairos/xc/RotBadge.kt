package com.kairos.xc

/**
 * «ROTAX DNS» (23 novembre 2026, il titolare: «in modo da vedere se i due DNS ci sono»): c'è quando il pannello ha dato alla
 * lista dei server di riserva. Il numero = quanti indirizzi (2 = principale + 1 di riserva).
 * Verde = rotativo (a turno); azzurro = automatico, riserva pronta; giallo = automatico, in uso la riserva perché il principale
 * non andava; bianco tenue = fissato dal pannello su un solo indirizzo. Al tocco: «I DNS sono gestiti dal fornitore» (mai
 * indirizzi, nomi dei server o quale si sta usando). Logica pura: la provano i test JVM.
 */
object RotBadge {
    /** "x" = niente pulsante; "on", "res", "warn", "" (fisso) */
    /** 23 novembre 2026, il titolare: «le due icone verde»: verde col principale (o a turno), giallo quando si usa la riserva */
    fun state(mode: String, n: Int, usingMain: Boolean): String = when {
        n < 2 -> "x"
        mode == "rot" -> "on"
        mode == "alt" -> "warn"
        mode == "main" -> "on"
        else -> if (usingMain) "on" else "warn"
    }

    fun color(st: String): Int = when (st) {
        "on" -> 0xFF2EE59D.toInt(); "res" -> 0xFF6CB8FF.toInt(); "warn" -> 0xFFF5B041.toInt(); else -> 0xCCFFFFFF.toInt()
    }

    /** al tocco e per chi legge lo schermo (23 novembre 2026, il titolare: «non voglio che esca che dns sta usando, mi basta
     *  capirlo dai colori; se il cliente clicca sopra scrivi i dns sono gestiti dal fornitore»): niente indirizzi, modalità o
     *  «in uso la riserva». Stato e numero restano solo nel colore e nella cifra del pulsante. */
    const val MSG = "I DNS sono gestiti dal fornitore"
    @Suppress("UNUSED_PARAMETER")
    fun text(st: String, mode: String, n: Int): String = MSG

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
