package com.kairos.xc

/**
 * Lista anche dai DNS di riserva (23 novembre 2026, il titolare: «tutte le app devono scaricare e caricare la lista anche con
 * la riserva»). Prima di scaricare le liste Xtream: il server in uso risponde davvero (JSON di player_api)? Se no (rete,
 * tempo scaduto, HTTP d'errore, pagina del gestore di rete) e un altro indirizzo della lista risponde, si segna giù per 30
 * minuti quello di prima e si usa l'altro: le liste (e poi i canali) arrivano da lì. Solo in automatico e rotativo, come il
 * cambio di server del lettore. Una linea scaduta o sbagliata risponde (con auth 0): non fa cambiare server. Se non risponde
 * nessuno (TV senza rete) non si segna niente.
 */
object PortalReach {
    private const val BAD_MS = 30 * 60_000L

    /** risposta vera del server (non una pagina d'errore) */
    fun answers(code: Int, text: String): Boolean = code in 200..299 && text.trimStart().let { it.startsWith("{") || it.startsWith("[") }

    /** ordine in cui provare gli altri indirizzi, senza quello in uso */
    fun others(mode: String, cands: List<String>, current: String, code: String): List<String> {
        val order = if (mode == "rot") PortalRotation.order(cands, code) else cands
        return order.filter { !it.equals(current, true) }
    }

    /** true se ha cambiato server; [probe] = il server risponde? (nei test si finge) */
    fun ensure(p: Playlist, probe: (String) -> Boolean = { h -> ask(p, h) }): Boolean {
        if (p.mode != "auto" && p.mode != "rot") return false
        if (p.xt == null) return false
        val cands = Portals.cands(p)
        if (cands.size < 2) return false
        val cur = Portals.host(p)
        if (probe(cur)) return false
        for (c in others(p.mode, cands, cur, Store.code)) {
            if (Portals.isBad(c)) continue
            if (probe(c)) {
                Portals.markBad(cur, BAD_MS)
                Store.setPortalChoice(p.id, c)
                return true
            }
        }
        return false
    }

    private fun ask(p: Playlist, host: String): Boolean {
        val x = p.xt ?: return true
        return try {
            val r = Http.text("$host/player_api.php?username=${Playlist.enc(x.second)}&password=${Playlist.enc(x.third)}", connectMs = 7_000, readMs = 12_000)
            answers(r.code, r.text)
        } catch (_: Exception) { false }
    }
}
