package com.kairos.xc

import java.util.Calendar

/*
 * Ricerca con le frasi (senza Android: la provano i test JVM). L'assistente del pannello trasforma la frase in criteri
 * (POST /api/player/ai, modalità «search» o domanda v2, gli stessi della Kairos Pro); qui i criteri diventano filtri SQLite
 * sull'archivio delle liste, sempre con LIMIT: nessuna lista caricata in memoria.
 */

/** criteri ripuliti dal pannello: type movie|series|live|any, generi del vocabolario fisso, anni, persone, titoli, parole, sport, quando, bambini */
class Find(
    val type: String = "any",
    val genres: List<String> = emptyList(),
    val years: IntArray? = null,
    val people: List<String> = emptyList(),
    val titles: List<String> = emptyList(),
    val words: List<String> = emptyList(),
    val sport: String = "",
    val whenW: String = "",
    val kids: Boolean = false,
) {
    /** tipo effettivo: con un orario o uno sport si parla di TV in diretta */
    val kind: String get() = if (type == "movie" || type == "series" || type == "live") type else if (whenW.isNotEmpty() || sport.isNotEmpty()) "live" else "any"
    val forKids: Boolean get() = kids || genres.contains("bambini")
}

/** un titolo della filmografia di un attore (GET /api/player/person): titolo, titolo originale, anno, tipo, generi TMDB */
class Credit(val t: String, val o: String, val y: Int, val k: String, val g: List<Int>)

/** filtro SQL: condizione (con «?») e valori */
class Sql(val where: String, val args: List<String>)

object AiFind {
    /** generi del vocabolario del pannello → parole da cercare nei nomi delle categorie («*» = inizio di parola), come la Kairos Pro */
    val GEN: Map<String, List<String>> = mapOf(
        "azione" to listOf("azione", "action"), "avventura" to listOf("avventur*", "adventure"),
        "animazione" to listOf("animazione", "animati", "animation", "cartoni", "cartoon*", "anime"),
        "commedia" to listOf("commedi*", "comedy", "comici", "comico"), "crimine" to listOf("crime", "crimin*", "polizies*", "gangster", "noir"),
        "documentario" to listOf("document*", "docu*"), "drammatico" to listOf("dramma*", "drama"), "famiglia" to listOf("famiglia", "family", "famil*"),
        "fantascienza" to listOf("fantascienza", "sci fi", "scifi", "science fiction"), "fantasy" to listOf("fantasy", "fantastic*"),
        "guerra" to listOf("guerra", "war", "bellic*"), "horror" to listOf("horror", "orrore", "paura"), "musica" to listOf("music*", "concert*"),
        "mistero" to listOf("mister*", "mystery", "giallo", "gialli"), "romantico" to listOf("romantic*", "romance", "sentimental*"),
        "storico" to listOf("storic*", "history", "biografic*", "biopic"), "thriller" to listOf("thriller", "suspense"), "western" to listOf("western"),
        "sport" to listOf("sport*", "calcio", "football", "soccer", "f1", "motogp", "tennis", "basket", "nba"),
        "reality" to listOf("reality", "talent"), "notizie" to listOf("news", "notizie", "tg", "telegiornale"),
    )
    private val KIDS = listOf("kids", "bambin*", "cartoon*", "cartoni", "animaz*", "animation", "famil*", "ragazz*", "junior", "disney", "nick*", "boing", "cartoonito", "frisbee", "baby", "yoyo", "k2")
    /** parole della guida TV per sport */
    val SPORT_W: Map<String, List<String>> = mapOf(
        "f1" to listOf("formula 1", "f1", "gran premio"), "motogp" to listOf("motogp", "moto gp"), "tennis" to listOf("tennis", "atp", "wta"),
        "basket" to listOf("basket", "nba", "eurolega"), "nfl" to listOf("nfl", "football americano"), "ufc" to listOf("ufc", "mma"), "rugby" to listOf("rugby"),
        "calcio" to listOf("serie a", "champions league", "europa league", "conference league", "coppa italia", "calcio"),
    )
    /** emittenti per sport: si cercano nel nome del canale e della categoria */
    val SPORT_CH: Map<String, List<String>> = mapOf(
        "f1" to listOf("sky sport f1", "tv8", "formula 1"), "motogp" to listOf("sky sport motogp", "tv8", "motogp"), "tennis" to listOf("sky sport tennis", "supertennis", "tennis"),
        "basket" to listOf("sky sport nba", "nba"), "nfl" to listOf("dazn", "nfl"), "ufc" to listOf("dazn", "ufc"), "rugby" to listOf("sky sport", "rugby"),
        "calcio" to listOf("dazn", "sky sport", "calcio", "serie a"),
    )
    private val ALIAS = mapOf(
        "inter" to listOf("internazionale"), "internazionale" to listOf("inter"), "juve" to listOf("juventus"), "juventus" to listOf("juve"),
        "f1" to listOf("formula 1", "gran premio"), "formula 1" to listOf("f1", "gran premio"), "psg" to listOf("paris saint germain", "paris sg"),
    )
    /** generi TMDB della filmografia → vocabolario */
    private val TMDB_G = mapOf(
        28 to "azione", 12 to "avventura", 16 to "animazione", 35 to "commedia", 80 to "crimine", 99 to "documentario", 18 to "drammatico",
        10751 to "famiglia", 14 to "fantasy", 36 to "storico", 27 to "horror", 10402 to "musica", 9648 to "mistero", 10749 to "romantico",
        878 to "fantascienza", 53 to "thriller", 10752 to "guerra", 37 to "western", 10759 to "azione", 10762 to "animazione", 10765 to "fantascienza",
        10768 to "guerra", 10764 to "reality", 10763 to "notizie",
    )
    private val CUE = Regex(" (film|serie|partita|partite|stasera|oggi|domani|adesso|anni|con|per|bambini|cartoni|qualcosa|simili|simile|tipo|genere|horror|comici|commedia|commedie|azione|thriller|documentari|f1|formula|motogp|gara|calcio|tennis|dove|quando|chi|cosa) ")
    private val ASK = Regex("^ (chi|cosa|che cosa|cos e|quando|quanto|quanti|perche|come|quale|quali|dove|a che ora|che ora|mi consigli|consigliami|dimmi|spiegami|parlami) ")

    /** una frase (non un nome): almeno 3 parole, oppure 2 con una parola tipica, oppure una domanda */
    fun phrase(q: String): Boolean {
        val n = Txt.padded(q)
        val w = n.trim().split(' ').count { it.isNotEmpty() }
        return q.trimEnd().endsWith("?") || w >= 3 || (w >= 2 && CUE.containsMatchIn(n))
    }

    /** una domanda libera («chi è l'attore di…», «a che ora c'è…»): risponde l'assistente, poi si cerca nella libreria */
    fun question(q: String): Boolean = q.trimEnd().endsWith("?") || ASK.containsMatchIn(Txt.padded(q))

    /** parole da cercare (con i soprannomi delle squadre e della F1), normalizzate, senza doppioni */
    fun words(list: List<String>): List<String> {
        val out = ArrayList<String>()
        for (w0 in list) {
            val w = Txt.norm(w0)
            if (w.length < 2) continue
            for (x in listOf(w) + (ALIAS[w] ?: emptyList())) if (!out.contains(x)) out.add(x)
        }
        return out.take(10)
    }

    /** categorie ([id] → nome) dei generi chiesti; con [kids] solo quelle per bambini. Al massimo 60 */
    fun cats(cats: List<Pair<String, String>>, genres: List<String>, kids: Boolean): List<String> {
        val terms = genres.flatMap { GEN[it] ?: emptyList() }
        if (terms.isEmpty() && !kids) return emptyList()
        val out = ArrayList<String>()
        for ((id, name) in cats) {
            val n = Txt.padded(name)
            if (terms.isNotEmpty() && terms.none { Txt.has(n, it) }) continue
            if (kids && KIDS.none { Txt.has(n, it) }) continue
            out.add(id)
            if (out.size >= 60) break
        }
        return out
    }

    /** categorie dei canali di uno sport (nome della categoria con lo sport o le sue emittenti) */
    fun sportCats(cats: List<Pair<String, String>>, sport: String): List<String> {
        val terms = (SPORT_CH[sport] ?: emptyList()) + (SPORT_W[sport] ?: emptyList())
        return cats.filter { (_, name) -> val n = Txt.padded(name); terms.any { n.contains(" $it ") || (it.length >= 4 && n.contains(it)) } }.map { it.first }.take(40)
    }

    /** criteri in chiaro, sotto la frase: «Film · Azione · 1990–1999 · Sylvester Stallone» */
    fun crit(f: Find): List<String> {
        val t = mapOf("movie" to "Film", "series" to "Serie TV", "live" to "TV")[f.type]
        val w = mapOf("now" to "Adesso", "tonight" to "Stasera", "today" to "Oggi", "tomorrow" to "Domani")[f.whenW]
        val y = f.years?.let { if (it[0] == it[1]) it[0].toString() else it[0].toString() + "–" + it[1] }
        return (listOfNotNull(t) + f.genres.map { it.replaceFirstChar { c -> c.uppercase() } } + listOfNotNull(y) + f.people + f.words +
            listOfNotNull(w, if (f.kids) "Per bambini" else null)).filter { it.isNotBlank() }.take(8)
    }

    /** finestra di tempo della guida: adesso (1 ora), stasera (18–1, o fino alle 4 se è notte), oggi, domani; senza: 3 giorni */
    fun window(w: String, cal: Calendar): LongArray {
        val now = cal.timeInMillis
        fun at(h: Int, m: Int, days: Int = 0): Long {
            val x = cal.clone() as Calendar
            x.set(Calendar.HOUR_OF_DAY, 0); x.set(Calendar.MINUTE, 0); x.set(Calendar.SECOND, 0); x.set(Calendar.MILLISECOND, 0)
            x.add(Calendar.DAY_OF_MONTH, days)
            x.add(Calendar.MINUTE, h * 60 + m)
            return x.timeInMillis
        }
        return when (w) {
            "now" -> longArrayOf(now, now + 3_600_000L)
            "tonight" -> if (cal.get(Calendar.HOUR_OF_DAY) < 4) longArrayOf(now, at(4, 0)) else longArrayOf(maxOf(now, at(18, 0)), at(25, 0))
            "today" -> longArrayOf(now, at(24, 0))
            "tomorrow" -> longArrayOf(at(24, 0), at(48, 0))
            else -> longArrayOf(now, now + 3 * 86_400_000L)
        }
    }

    /** testo per LIKE: % e _ sono lettere normali */
    fun like(t: String): String = "%" + t.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%"

    /** «nome contiene uno dei [names]» (al massimo 12) */
    fun nameLike(names: List<String>, col: String = "name"): Sql? {
        val l = names.map { it.trim() }.filter { it.length >= 2 }.distinct().take(12)
        if (l.isEmpty()) return null
        return Sql("(" + l.joinToString(" OR ") { "$col LIKE ? ESCAPE '\\'" } + ")", l.map { like(it) })
    }

    /**
     * Filtro di film e serie per i criteri (oltre al portale e al profilo bambini, che mette l'archivio): [gCats] = categorie dei
     * generi chiesti, [kCats] = categorie per bambini (null = non chiesti). Generi o bambini chiesti senza categorie che
     * corrispondono: niente (come la Kairos Pro, un titolo fuori genere non si propone). null = nessun criterio da cercare.
     */
    fun vodWhere(f: Find, gCats: List<String>?, kCats: List<String>?): Sql? {
        val parts = ArrayList<String>()
        val args = ArrayList<String>()
        for (cs in listOf(gCats, kCats)) {
            if (cs == null) continue
            if (cs.isEmpty()) return null
            parts.add("cat IN (" + cs.joinToString(",") { "?" } + ")")
            args.addAll(cs)
        }
        f.years?.let { parts.add("year >= ? AND year <= ?"); args.add(it[0].toString()); args.add(it[1].toString()) }
        nameLike(words(f.words))?.let { parts.add(it.where); args.addAll(it.args) }
        return if (parts.isEmpty()) null else Sql(parts.joinToString(" AND "), args)
    }

    /** titoli della filmografia da cercare nella libreria: filtrati per tipo, anni e generi, i più noti prima (al massimo 12) */
    fun creditTitles(credits: List<Credit>, f: Find): List<String> {
        val out = ArrayList<String>()
        for (c in credits) {
            if (f.type == "movie" && c.k != "movie") continue
            if (f.type == "series" && c.k != "series") continue
            val y = f.years
            if (y != null && c.y > 0 && (c.y < y[0] || c.y > y[1])) continue
            if (f.genres.isNotEmpty() && c.g.isNotEmpty() && c.g.none { f.genres.contains(TMDB_G[it]) }) continue
            for (t in listOf(c.t, c.o)) { val x = t.trim(); if (x.length >= 2 && out.none { it.equals(x, true) }) out.add(x) }
            if (out.size >= 12) break
        }
        return out.take(12)
    }
}
