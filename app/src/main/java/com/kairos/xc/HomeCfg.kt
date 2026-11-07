package com.kairos.xc

import java.text.Normalizer

/**
 * Home decisa dal fornitore (controllo remoto, configurazione rc): rc["home.top"] = categorie in primo piano,
 * rc["home.hide"] = categorie nascoste dalla Home. Nomi o parti di nome separati da virgola, «;» o a capo, in ordine di priorità.
 * Maiuscole, accenti e spazi doppi non contano: una categoria corrisponde se il suo nome CONTIENE lo schema (come la Kairos Pro).
 * Kairos XC non ha righe di categoria nella Home (solo i cerchi): qui conta l'ordine degli elenchi (Film, Serie TV, TV in diretta).
 * Voci assenti o vuote: tutto come prima.
 */
object HomeCfg {
    private val SEP = Regex("[,;\\r\\n]+")
    private val MARKS = Regex("[\\u0300-\\u036F]+")
    private val SPACES = Regex("[\\s\\u00A0]+")

    private class Pats(val key: String, val top: List<String>, val hide: List<String>)
    @Volatile private var memo: Pats? = null

    /** minuscolo, senza accenti (NFD senza segni diacritici), spazi multipli ridotti a uno */
    fun norm(s: String): String =
        MARKS.replace(Normalizer.normalize(s, Normalizer.Form.NFD), "").lowercase().replace(SPACES, " ").trim()

    private fun split(raw: String): List<String> = raw.split(SEP).map { norm(it) }.filter { it.isNotEmpty() }

    private fun pats(): Pats {
        val rc = Remote.rc()
        val t = if (rc.isNull("home.top")) "" else rc.optString("home.top", "")
        val h = if (rc.isNull("home.hide")) "" else rc.optString("home.hide", "")
        val key = t + "\n" + h
        memo?.let { if (it.key == key) return it }
        return Pats(key, split(t), split(h)).also { memo = it }
    }

    /** impronta della configurazione: se cambia, gli elenchi aperti si riordinano (cambio di stato o ritorno alla schermata) */
    fun key(): String = pats().key

    /** categoria nascosta dalla Home (negli elenchi va in fondo) */
    fun hidden(name: String): Boolean {
        val p = pats()
        if (p.hide.isEmpty()) return false
        val n = norm(name)
        return p.hide.any { n.contains(it) }
    }

    /** posizione del primo schema in primo piano che prende la categoria, -1 = nessuno; nascosta vince su primo piano */
    fun topRank(name: String): Int {
        val p = pats()
        if (p.top.isEmpty()) return -1
        val n = norm(name)
        if (p.hide.any { n.contains(it) }) return -1
        return p.top.indexOfFirst { n.contains(it) }
    }

    /**
     * Elenchi delle categorie: prima quelle in primo piano (nell'ordine degli schemi, a parità nell'ordine di prima),
     * poi le altre nell'ordine solito, in fondo le nascoste (restano raggiungibili; il PIN del controllo genitori resta com'è).
     */
    fun <T> order(list: List<T>, name: (T) -> String): List<T> {
        val p = pats()
        if (p.top.isEmpty() && p.hide.isEmpty()) return list
        val top = ArrayList<Pair<Int, T>>()
        val mid = ArrayList<T>()
        val low = ArrayList<T>()
        for (c in list) {
            val n = norm(name(c))
            if (p.hide.any { n.contains(it) }) { low.add(c); continue }
            val i = p.top.indexOfFirst { n.contains(it) }
            if (i >= 0) top.add(i to c) else mid.add(c)
        }
        // sortedBy è stabile: a parità di schema resta l'ordine di prima
        return top.sortedBy { it.first }.map { it.second } + mid + low
    }
}
