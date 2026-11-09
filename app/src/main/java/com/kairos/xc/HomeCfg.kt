package com.kairos.xc

import java.text.Normalizer
import java.util.concurrent.ConcurrentHashMap

/**
 * Home decisa dal fornitore (controllo remoto, configurazione rc): rc["home.top"] = categorie in primo piano,
 * rc["home.hide"] = categorie nascoste dalla Home. Nomi o parti di nome separati da virgola, «;» o a capo, in ordine di priorità.
 * Maiuscole, accenti e spazi doppi non contano: una categoria corrisponde se il suo nome CONTIENE lo schema (come la Kairos Pro).
 * Kairos XC non ha righe di categoria nella Home (solo i cerchi): qui conta l'ordine degli elenchi (Film, Serie TV, TV in diretta).
 * rc["adult"] (contratto comune di tutte le app Kairos): se è diverso da "on", anche assente o vuoto, le categorie per adulti
 * ([AdultFilter.cat]) contano come nascoste, quindi negli elenchi vanno in fondo e restano raggiungibili. Nella Home di XC
 * non ci sono titoli né canali da togliere. Con "on" vale solo home.hide, come prima.
 * Voci assenti o vuote: come prima, ma con le categorie per adulti in fondo.
 */
object HomeCfg {
    private val SEP = Regex("[,;\\r\\n]+")
    private val MARKS = Regex("[\\u0300-\\u036F]+")
    private val SPACES = Regex("[\\s\\u00A0]+")

    /** minuscolo, senza accenti (NFD senza segni diacritici), spazi multipli ridotti a uno */
    fun norm(s: String): String =
        MARKS.replace(Normalizer.normalize(s, Normalizer.Form.NFD), "").lowercase().replace(SPACES, " ").trim()

    private fun split(raw: String): List<String> = raw.split(SEP).map { norm(it) }.filter { it.isNotEmpty() }

    /**
     * Regole di una configurazione: le tre voci del pannello come arrivano. Pure (niente Android), provate in HomeCfgTest.
     * Ogni categoria si riconosce una volta sola per nome (cache), finché la configurazione non cambia.
     */
    class Rules(val topRaw: String, val hideRaw: String, val adult: String) {
        /** impronta della configurazione: cambia con una qualunque delle tre voci */
        val key: String = topRaw + "\n" + hideRaw + "\n" + adult
        /** adulti fuori dalla Home: rc["adult"] diverso da "on" (anche assente o vuoto) */
        val noAdult: Boolean = adult != "on"
        private val top = split(topRaw)
        private val hide = split(hideRaw)
        private val memo = ConcurrentHashMap<String, Boolean>()

        /** categoria nascosta dalla Home (negli elenchi va in fondo): da home.hide oppure, con [noAdult], perché è per adulti */
        fun hidden(name: String): Boolean {
            if (hide.isEmpty() && !noAdult) return false
            memo[name]?.let { return it }
            val v = (hide.isNotEmpty() && norm(name).let { n -> hide.any { n.contains(it) } }) || (noAdult && AdultFilter.cat(name))
            if (memo.size >= 5000) memo.clear()
            memo[name] = v
            return v
        }

        /** posizione del primo schema in primo piano che prende la categoria, -1 = nessuno; nascosta vince su primo piano */
        fun topRank(name: String): Int {
            if (top.isEmpty()) return -1
            if (hidden(name)) return -1
            val n = norm(name)
            return top.indexOfFirst { n.contains(it) }
        }

        /**
         * Elenchi delle categorie: prima quelle in primo piano (nell'ordine degli schemi, a parità nell'ordine di prima),
         * poi le altre nell'ordine solito, in fondo le nascoste (restano raggiungibili; il PIN del controllo genitori resta com'è).
         */
        fun <T> order(list: List<T>, name: (T) -> String): List<T> {
            if (top.isEmpty() && hide.isEmpty() && !noAdult) return list
            val first = ArrayList<Pair<Int, T>>()
            val mid = ArrayList<T>()
            val low = ArrayList<T>()
            for (c in list) {
                val nm = name(c)
                if (hidden(nm)) { low.add(c); continue }
                val i = if (top.isEmpty()) -1 else norm(nm).let { n -> top.indexOfFirst { n.contains(it) } }
                if (i >= 0) first.add(i to c) else mid.add(c)
            }
            // sortedBy è stabile: a parità di schema resta l'ordine di prima
            return first.sortedBy { it.first }.map { it.second } + mid + low
        }
    }

    @Volatile private var memo = Rules("", "", "")

    /** regole della configurazione attuale: si ricalcolano (e la cache riparte) solo quando cambia */
    fun rules(): Rules {
        val rc = Remote.rc()
        val t = if (rc.isNull("home.top")) "" else rc.optString("home.top", "")
        val h = if (rc.isNull("home.hide")) "" else rc.optString("home.hide", "")
        val a = if (rc.isNull("adult")) "" else rc.optString("adult", "")
        val m = memo
        if (m.topRaw == t && m.hideRaw == h && m.adult == a) return m
        return Rules(t, h, a).also { memo = it }
    }

    /** impronta della configurazione: se cambia, gli elenchi aperti si riordinano (cambio di stato o ritorno alla schermata) */
    fun key(): String = rules().key

    /** categoria nascosta dalla Home (negli elenchi va in fondo) */
    fun hidden(name: String): Boolean = rules().hidden(name)

    /** posizione del primo schema in primo piano che prende la categoria, -1 = nessuno; nascosta vince su primo piano */
    fun topRank(name: String): Int = rules().topRank(name)

    /** elenchi delle categorie: prima quelle in primo piano, poi le altre, in fondo le nascoste e quelle per adulti */
    fun <T> order(list: List<T>, name: (T) -> String): List<T> = rules().order(list, name)
}
