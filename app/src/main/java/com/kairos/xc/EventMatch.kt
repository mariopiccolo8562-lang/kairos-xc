package com.kairos.xc

import java.text.Normalizer
import java.util.Locale

object EventMatch {
    private data class LeagueRule(val pattern: Regex, val terms: List<String>)
    private data class SportRule(val sport: String, val terms: List<String>)

    private val marks = Regex("\\p{M}+")
    private val prefix = Regex("^\\W*(?:it|ita|en|eng|uk|us|fr|de|es|4k|vip|multi)\\s*[|:\\-–]\\s*")
    private val brackets = Regex("\\[[^\\]]*\\]|\\([^)]*\\)")
    private val qualityTags = Regex("(?<![a-z0-9_])(fhd|uhd|hd|sd|4k|8k|hevc|h ?26[45]|1080[pi]?|720p?|576[pi]?|480p?|2160p?|[0-9]{2}fps|fps|backup|bkp|raw|multi|vip|hq|lq|low|high|test|new|nuovo|orig|original|feed|direct|dash)(?![a-z0-9_])")
    private val punctuation = Regex("[^a-z0-9]+")
    private val trailingYear = Regex("\\s(19|20)\\d\\d$")
    private val leadingArticle = Regex("^(il|lo|la|i|gli|le|l|un|una|uno|the|a)\\s+")
    private val initials = Regex("^(?:\\p{Lu}\\.\\s*)+")
    private val quality4k = Regex("\\b(4k|uhd)\\b", RegexOption.IGNORE_CASE)
    private val qualityFhd = Regex("\\bfhd\\b", RegexOption.IGNORE_CASE)
    private val qualityHd = Regex("\\bhd\\b", RegexOption.IGNORE_CASE)
    private val shortWordExclusion = Regex("^(calcio|club|city|united|real|sport|team|rugby|basket)$")
    private val soccerWordExclusion = Regex("^(calcio|club|city|united|real|sport|team|fc)$")
    private val f1Noise = Regex("^(gran|premio|grand|prix)$")

    private val soccerRules = listOf(
        LeagueRule(Regex("serie a", RegexOption.IGNORE_CASE), listOf("dazn", "zona dazn", "sky sport calcio", "sky calcio", "sky sport uno", "serie a")),
        LeagueRule(Regex("champions", RegexOption.IGNORE_CASE), listOf("sky sport uno", "sky sport calcio", "sky calcio", "prime video", "amazon", "canale 5", "champions")),
        LeagueRule(Regex("europa|conference", RegexOption.IGNORE_CASE), listOf("sky sport", "dazn", "tv8", "europa league", "conference")),
        LeagueRule(Regex("premier", RegexOption.IGNORE_CASE), listOf("sky sport", "premier")),
        LeagueRule(Regex("laliga|la liga|liga", RegexOption.IGNORE_CASE), listOf("dazn", "laliga", "la liga")),
        LeagueRule(Regex("bundesliga", RegexOption.IGNORE_CASE), listOf("sky sport", "bundesliga")),
        LeagueRule(Regex("ligue", RegexOption.IGNORE_CASE), listOf("dazn", "ligue")),
        LeagueRule(Regex("serie b", RegexOption.IGNORE_CASE), listOf("dazn", "sky sport", "serie b")),
        LeagueRule(Regex("coppa italia", RegexOption.IGNORE_CASE), listOf("canale 5", "italia 1", "mediaset", "coppa italia")),
    )

    val BROAD: List<Pair<String, List<String>>> = listOf(
        "serie a" to listOf("dazn", "zona dazn", "sky sport calcio", "sky calcio", "sky sport uno", "serie a"),
        "champions" to listOf("sky sport uno", "sky sport calcio", "sky calcio", "prime video", "amazon", "canale 5", "champions"),
        "europa|conference" to listOf("sky sport", "dazn", "tv8", "europa league", "conference"),
        "premier" to listOf("sky sport", "premier"),
        "laliga|la liga|liga" to listOf("dazn", "laliga", "la liga"),
        "bundesliga" to listOf("sky sport", "bundesliga"),
        "ligue" to listOf("dazn", "ligue"),
        "serie b" to listOf("dazn", "sky sport", "serie b"),
        "coppa italia" to listOf("canale 5", "italia 1", "mediaset", "coppa italia"),
        "f1" to listOf("sky sport f1", "tv8", "formula 1"),
        "motogp" to listOf("sky sport motogp", "tv8", "motogp"),
        "tennis" to listOf("sky sport tennis", "supertennis", "tennis"),
        "basket" to listOf("sky sport nba", "nba"),
        "nfl" to listOf("dazn", "nfl"),
        "ufc" to listOf("dazn", "ufc"),
        "rugby" to listOf("sky sport", "rugby"),
    )

    private val sportsRules = listOf(
        SportRule("f1", BROAD[9].second),
        SportRule("motogp", BROAD[10].second),
        SportRule("tennis", BROAD[11].second),
        SportRule("basket", BROAD[12].second),
        SportRule("nfl", BROAD[13].second),
        SportRule("ufc", BROAD[14].second),
        SportRule("rugby", BROAD[15].second),
    )

    fun normT(value: String?): String {
        var text = Normalizer.normalize(value.orEmpty().lowercase(Locale.ROOT), Normalizer.Form.NFD).replace(marks, "")
        text = prefix.replaceFirst(text, "")
            .replace(brackets, " ")
            .replace(qualityTags, " ")
            .replace(punctuation, " ")
            .trim()
        text = leadingArticle.replaceFirst(text, "")
        return trailingYear.replaceFirst(text, "").trim()
    }

    fun kwHit(keyword: String, text: String): Boolean {
        val key = keyword.lowercase(Locale.ROOT).filter { it in 'a'..'z' || it in '0'..'9' || it == ' ' }
        if (key.isEmpty()) return false
        return hitNormalized(key, text.lowercase(Locale.ROOT))
    }

    private fun hitNormalized(key: String, text: String): Boolean {
        if (key.length < 4) {
            var at = text.indexOf(key)
            while (at >= 0) {
                val before = at == 0 || !asciiAlphaNum(text[at - 1])
                val afterAt = at + key.length
                val after = afterAt == text.length || !asciiAlphaNum(text[afterAt])
                if (before && after) return true
                at = text.indexOf(key, at + 1)
            }
            return false
        }
        return text.contains(key)
    }

    private fun asciiAlphaNum(value: Char): Boolean = value in 'a'..'z' || value in '0'..'9'

    fun evWords(event: SportEvent): List<String> {
        fun tokens(value: String?): List<String> = normT(value).split(' ')
            .filter { it.length >= 4 && !shortWordExclusion.matches(it) }
        fun surname(value: String?): String = initials.replaceFirst(value.orEmpty(), "")

        if (event.sport == "f1") {
            return (listOf("formula 1", "f1") + tokens(event.gp.ifBlank { event.title }).filterNot { f1Noise.matches(it) }).distinct()
        }
        val home = event.home
        if (home == null) return tokens(event.title).distinct()
        if (event.sport == "tennis" || event.sport == "ufc") {
            val words = tokens(surname(home.name)) + tokens(surname(event.away?.name))
            return (words + if (event.sport == "ufc") listOf("ufc") else emptyList()).distinct()
        }
        return listOf(home.name, event.away?.name.orEmpty())
            .flatMap { normT(it).split(' ') }
            .filter { it.length >= 4 && !soccerWordExclusion.matches(it) }
            .distinct()
    }

    fun matchesTitle(event: SportEvent, title: String): Boolean = titleHit(event, title)

    fun matchesTitle(words: List<String>, title: String): Boolean {
        val normalized = normT(title)
        return words.any { hitNormalized(it, normalized) }
    }

    fun broadOf(event: SportEvent): List<String>? {
        if (event.sport.isNotEmpty() && event.sport != "calcio") {
            return sportsRules.firstOrNull { it.sport == event.sport }?.terms
        }
        return soccerRules.firstOrNull { it.pattern.containsMatchIn(event.league) }?.terms
    }

    fun broadcasterTerms(event: SportEvent): List<String> {
        val base = broadOf(event) ?: listOf("sport")
        return (base + normT(event.league)).map(::normT).filter { it.length > 2 }.distinct()
    }

    fun matchesBroadcaster(event: SportEvent, channelName: String, category: String): Boolean {
        return matchesBroadcaster(broadcasterTerms(event), channelName, category)
    }

    fun matchesBroadcaster(terms: List<String>, channelName: String, category: String): Boolean {
        val search = "${normT(channelName)} ${category.lowercase(Locale.ROOT)}"
        return terms.any { hitNormalized(it, search) }
    }

    /* «Dove vederla» (22 novembre 2026, il titolare: «il canale evento non corrisponde», in tutte le app). Stessa regola
       della Pro e del pannello: confermato = programma della guida a cavallo del calcio d'inizio con TUTTE E DUE le squadre
       (parole intere, Inter = Internazionale) oppure canale-evento con le due squadre nel nome; se ce ne sono, solo quelli;
       altrimenti al massimo 6 dell'emittente giusta, «da verificare», mai notizie né canali di un altro sport. */
    private val evStop = Regex("^(ac|fc|as|ss|us|ssc|afc|cf|sc|bc|calcio|club|city|united|real|sport|team|de|del|la|le|il|1907|1909|1913)$")
    private val digits = Regex("^\\d+$")
    private val alias = mapOf(
        "juventus" to listOf("juventus", "juve"),
        "hellas verona" to listOf("verona"),
        "manchester city" to listOf("man city", "manchester city"),
        "manchester united" to listOf("man utd", "manchester united", "man united"),
    )
    private val interRx = Regex("\\binter\\b|internazionale")
    private val milanRx = Regex("\\bmilan\\b")
    private val newsRx = Regex("\\b24\\b|\\bnews\\b|\\btg\\b|\\bnotizie\\b")
    private val eventLikeRx = Regex("\\bvs\\.?(\\s|$)|\\b\\d{1,2}[:.]\\d{2}\\b", RegexOption.IGNORE_CASE)
    private val onlySport = mapOf(
        "motogp" to Regex("\\bmotogp\\b|\\bmoto gp\\b"), "f1" to Regex("\\bf1\\b|formula 1"), "tennis" to Regex("\\btennis\\b"),
        "basket" to Regex("\\bnba\\b|\\bbasket\\b"), "golf" to Regex("\\bgolf\\b"), "nfl" to Regex("\\bnfl\\b"), "ufc" to Regex("\\bufc\\b"),
        "rugby" to Regex("\\brugby\\b"), "calcio" to Regex("\\bcalcio\\b|\\bfootball\\b"),
    )

    /** parola (o parole) intera dentro un testo già normalizzato con [normT] */
    fun has(text: String, key: String): Boolean {
        if (key.isEmpty()) return false
        var at = text.indexOf(key)
        while (at >= 0) {
            val end = at + key.length
            if ((at == 0 || text[at - 1] == ' ') && (end == text.length || text[end] == ' ')) return true
            at = text.indexOf(key, at + 1)
        }
        return false
    }

    /** parole di una squadra più i nomi come li scrivono le guide italiane, mandati dal pannello («Bayern Monaco», «Rennes») */
    fun teamWords(name: String?, person: Boolean, aka: List<String>): List<String> {
        val base = teamWords(name, person)
        if (aka.isEmpty()) return base
        return (base + aka.map(::normT).filter { it.length >= 2 }).distinct()
    }

    /** parole di una squadra (o il cognome di un giocatore) da cercare come parole intere */
    fun teamWords(name: String?, person: Boolean): List<String> {
        val n = normT(if (person) initials.replaceFirst(name.orEmpty(), "") else name)
        if (interRx.containsMatchIn(n)) return listOf("inter", "internazionale")
        alias[n]?.let { return it }
        if (milanRx.containsMatchIn(n)) return listOf("milan")
        val w = n.split(' ').filter { it.length >= 3 && !evStop.matches(it) && !digits.matches(it) }
        return when { w.isNotEmpty() -> if (person) listOf(w.last()) else w; n.isNotEmpty() -> listOf(n); else -> emptyList() }
    }

    /** il titolo di un programma (o il nome di un canale) parla di QUESTO evento */
    fun titleHit(event: SportEvent, title: String): Boolean {
        val t = normT(title)
        if (t.isEmpty()) return false
        if (event.sport == "f1") return listOf("f1", "formula 1", "formula uno").any { has(t, it) }
        if (event.sport == "motogp") return listOf("motogp", "moto gp").any { has(t, it) }
        val home = event.home
        val away = event.away
        if (home == null || away == null) return normT(event.title).split(' ').filter { it.length >= 4 }.any { has(t, it) }
        val person = event.sport == "tennis" || event.sport == "ufc"
        val a = teamWords(home.name, person, home.aka)
        val b = teamWords(away.name, person, away.aka)
        if (a.any { has(t, it) } && b.any { has(t, it) }) return true
        val sportWords = if (event.sport == "tennis") listOf("tennis", "atp", "wta") else listOf("ufc")
        return person && (a.any { has(t, it) } || b.any { has(t, it) }) && sportWords.any { has(t, it) }
    }

    /* «Dove vederla» con le guide TV pubbliche (23 novembre 2026, il titolare: «perché da verificare? … prendi anche altre
       fonti, importante che funzioni bene»). Il pannello legge ogni 3 ore le guide TV pubbliche italiane e manda con ogni
       evento i canali confermati (tvch), i probabili (tvp) e i nomi italiani delle squadre (aka). Qui: il nome del canale
       «pulito» per trovarli nella lista (uguale al pannello e a Kairos Pro), e il livello «probabile» dalla guida della lista. */
    private val chShift = Regex("\\+\\s*\\d|\\bpiu ?\\d\\b", RegexOption.IGNORE_CASE)
    private val chPrefix = Regex("^\\W*(it|ita|italy|italia)\\s*[:|\\-–]\\s*", RegexOption.IGNORE_CASE)
    private val chQual = Regex("\\b(full ?hd|fhd|uhd|ultra ?hd|super ?hd|hdr ?plus|plus hd|hdr|hd|sd|raw|h ?265|hevc|1080[ip]?|720p|streaming|in streaming|fastweb|dsl lente|adsl lenta|backup|dtt|4k|8k|50fps|60fps)\\b")
    private val chSports = Regex("\\bsports\\b")
    private val chGlue = Regex("\\b(sport|eurosport|dazn|rai|italia|canale|sky)(\\d)")
    private val chGlueQ = Regex("(\\d)(hd|fhd|uhd|sd|abr)\\b")
    private val chUno = Regex("\\buno\\b")
    private val chDue = Regex("\\bdue\\b")
    private val chTre = Regex("\\btre\\b")
    private val chSpaces = Regex("\\s+")
    private val chTail = Regex(" (abr|italia)$")
    private val chEuro = Regex("^(premium|sky) eurosport")
    private val chSkyNum = Regex("^sky sport \\d{3}$")
    private val chNum = Regex("^(.+) \\d{3}$")

    /** «IT: Sky Sports Uno FHD» → «sky sport 1», «Rai Sport 227» → «rai sport»; le differite (+1, +24) → "" */
    fun chKey(name: String?): String {
        var n = name.orEmpty()
        if (chShift.containsMatchIn(n)) return ""
        n = chPrefix.replaceFirst(n, "").replace(brackets, " ").lowercase(Locale.ROOT)
        n = Normalizer.normalize(n, Normalizer.Form.NFD).replace(marks, "")
        n = n.replace(punctuation, " ").replace(chQual, " ").replace(chSports, "sport").replace(chGlue, "$1 $2").replace(chGlueQ, "$1")
            .replace(chUno, "1").replace(chDue, "2").replace(chTre, "3").replace(chSpaces, " ").trim()
            .replace(chTail, "").replace(chEuro, "eurosport")
        if (!chSkyNum.matches(n)) n = chNum.replaceFirst(n, "$1")
        return n
    }

    private val halfNoise = Regex("\\b(sunday|monday|prepartita|postpartita|highlights|studio|magazine|rubrica|replica)\\b")
    private val vsTitle = Regex(" - | vs\\.? ", RegexOption.IGNORE_CASE)
    private val clubRx = Regex("\\b(inter|milan|juventus|juve|roma|lazio|napoli|barca|real madrid|chelsea|liverpool|man utd|bayern) ?(tv|channel|style)\\b")
    private val leagueWords = listOf(
        Regex("serie a", RegexOption.IGNORE_CASE) to "serie a", Regex("serie b", RegexOption.IGNORE_CASE) to "serie b",
        Regex("ligue", RegexOption.IGNORE_CASE) to "ligue 1", Regex("laliga|la liga|spanish", RegexOption.IGNORE_CASE) to "liga",
        Regex("premier", RegexOption.IGNORE_CASE) to "premier league", Regex("bundesliga", RegexOption.IGNORE_CASE) to "bundesliga",
        Regex("champions", RegexOption.IGNORE_CASE) to "champions league", Regex("europa league", RegexOption.IGNORE_CASE) to "europa league",
        Regex("conference", RegexOption.IGNORE_CASE) to "conference league", Regex("coppa", RegexOption.IGNORE_CASE) to "coppa italia",
        Regex("eredivisie", RegexOption.IGNORE_CASE) to "eredivisie",
    )

    /** canale di un club (Inter TV, Milan TV…): non trasmette le partite in diretta */
    fun clubChannel(name: String): Boolean = clubRx.containsMatchIn(normT(name))

    /** «probabile»: il titolo ha UNA delle due squadre, o il campionato se non è un'altra partita («Lazio - Lecce»); mai notizie e rubriche */
    fun halfHit(event: SportEvent): ((String) -> Boolean)? {
        val home = event.home ?: return null
        val away = event.away ?: return null
        val person = event.sport == "tennis" || event.sport == "ufc"
        val a = teamWords(home.name, person, home.aka)
        val b = teamWords(away.name, person, away.aka)
        val lw = leagueWords.firstOrNull { it.first.containsMatchIn(event.league) }?.second.orEmpty()
        return { title ->
            val t = normT(title)
            t.isNotEmpty() && !newsRx.containsMatchIn(t) && !halfNoise.containsMatchIn(t) &&
                (a.any { has(t, it) } || b.any { has(t, it) } || (lw.isNotEmpty() && has(t, lw) && !vsTitle.containsMatchIn(title)))
        }
    }

    /** programma in onda al calcio d'inizio (non le notizie di prima, non il dopo partita); tutto in millisecondi */
    fun atKick(kickMs: Long, startMs: Long, endMs: Long): Boolean = startMs <= kickMs + 20 * 60_000L && endMs >= kickMs + 10 * 60_000L

    /** un canale dell'emittente può stare tra i «da verificare»: niente notizie, canali-evento di altre partite o di un altro sport */
    fun fallbackOk(event: SportEvent, channelName: String): Boolean {
        val nm = normT(channelName)
        if (newsRx.containsMatchIn(nm) || eventLikeRx.containsMatchIn(channelName)) return false
        val sport = event.sport.ifEmpty { "calcio" }
        return onlySport.none { (k, re) -> k != sport && re.containsMatchIn(nm) }
    }

    fun quality(name: String): Int = when {
        quality4k.containsMatchIn(name) -> 0
        qualityFhd.containsMatchIn(name) -> 1
        qualityHd.containsMatchIn(name) -> 2
        else -> 3
    }
}
