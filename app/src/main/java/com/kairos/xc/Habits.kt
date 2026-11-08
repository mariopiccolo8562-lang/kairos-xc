package com.kairos.xc

import java.text.Normalizer
import java.util.Calendar
import java.util.Locale
import kotlin.math.abs
import kotlin.math.pow

/*
 * Abitudini e promemoria (senza Android: li provano i test JVM). Tutto per profilo e solo sul dispositivo, in un testo piccolo
 * con un tetto di [HabitBook.MAX] voci in tutto: canali e categorie per fascia oraria, programmi e squadre seguiti, promemoria
 * già proposti e «non avvisarmi più».
 */

/** testi normalizzati per i confronti: minuscolo, senza accenti, solo lettere e numeri separati da uno spazio */
object Txt {
    private val MARKS = Regex("[\\u0300-\\u036F]+")
    private val NON = Regex("[^a-z0-9]+")

    fun norm(s: String): String {
        var t = s.lowercase(Locale.ROOT)
        if (t.any { it.code > 127 }) t = MARKS.replace(Normalizer.normalize(t, Normalizer.Form.NFD), "")
        return NON.replace(t, " ").trim()
    }

    /** « parola parola » per i confronti a parola intera */
    fun padded(s: String): String = " " + norm(s) + " "

    /** [n] (da [padded]) contiene la parola [t]; con «*» finale basta l'inizio della parola */
    fun has(n: String, t: String): Boolean = if (t.endsWith("*")) n.contains(" " + t.dropLast(1)) else n.contains(" $t ")

    /** su una riga, senza tabulazioni: per i testi salvati */
    fun line(s: String, max: Int): String = s.replace('\t', ' ').replace('\n', ' ').replace('\r', ' ').trim().take(max)
}

class HabitBook {
    /** canale o categoria: nome, visioni per fascia (tipo di giorno × 24 + ora), ultima volta, canale di sport */
    class Slots(var n: String, var t: Long, var sp: Boolean = false) { val s = HashMap<Int, Int>() }
    /** programma o squadra seguiti: giorni distinti in cui li si è guardati (aaaammgg), canali dove li si è visti */
    class Days(var n: String, var t: Long) { val d = ArrayList<Int>(); val ch = ArrayList<String>() }

    val c = LinkedHashMap<String, Slots>()
    val g = LinkedHashMap<String, Slots>()
    val p = LinkedHashMap<String, Days>()
    val tm = LinkedHashMap<String, Days>()
    val shown = LinkedHashMap<String, Long>()
    val off = LinkedHashSet<String>()

    /** voci in tutto (il tetto è [MAX]) */
    fun count(): Int = c.size + g.size + p.size + tm.size + shown.size + off.size

    /**
     * Un canale guardato per 3 minuti di fila ([k] = chiave stabile del canale): abitudine di quella fascia; il programma in onda
     * diventa «seguito» se lo si guarda in almeno 2 giorni diversi; sui canali di sport anche le due squadre del titolo.
     */
    fun watchedChannel(k: String, name: String, sport: Boolean, prog: String, now: Long, slot: Int, day: Int) {
        if (k.isEmpty()) return
        val e = c.remove(k) ?: Slots(Txt.line(name, 80), now)
        c[k] = e
        e.n = Txt.line(name, 80).ifEmpty { e.n }
        e.t = now
        if (sport) e.sp = true
        bump(e, slot)
        trim(c, MAX_C)
        val pk = normProg(prog)
        if (pk.isNotEmpty()) {
            val d = p.remove(pk) ?: Days(Txt.line(prog, 80), now)
            p[pk] = d
            d.t = now
            addDay(d, day)
            d.ch.remove(k); d.ch.add(0, k)
            while (d.ch.size > 3) d.ch.removeAt(d.ch.size - 1)
            trimDays(p, MAX_P)
        }
        if (sport || RX_MATCH.containsMatchIn(prog)) for (team in teamsOf(prog)) {
            val d = tm.remove(team) ?: Days(team, now)
            tm[team] = d
            d.t = now
            addDay(d, day)
            trimDays(tm, MAX_T)
        }
    }

    /** un film o una serie guardati per 5 minuti: abitudine della loro categoria ([key] = «m|categoria» o «s|categoria») */
    fun watchedVod(key: String, name: String, now: Long, slot: Int) {
        if (key.length < 3) return
        val e = g.remove(key) ?: Slots(Txt.line(name, 60), now)
        g[key] = e
        e.t = now
        if (name.isNotBlank()) e.n = Txt.line(name, 60)
        bump(e, slot)
        trim(g, MAX_G)
    }

    private fun bump(e: Slots, slot: Int) {
        e.s[slot] = (e.s[slot] ?: 0) + 1
        // al massimo 24 fasce per voce: via quelle viste meno
        while (e.s.size > 24) e.s.minByOrNull { it.value }?.let { e.s.remove(it.key) }
    }

    private fun addDay(d: Days, day: Int) {
        if (!d.d.contains(day)) d.d.add(day)
        while (d.d.size > 8) d.d.removeAt(0)
    }

    /** quanto è abituale adesso: stessa ora 1, ora vicina 0,5; stesso tipo di giorno (feriale, sabato, domenica) o un altro 0,35; dimezza ogni 3 settimane */
    fun score(e: Slots, now: Long, slot: Int): Double {
        val day = slot / 24
        val hr = slot % 24
        var s = 0.0
        for ((k, n) in e.s) {
            val dh = abs(k % 24 - hr).let { minOf(it, 24 - it) }
            val w = if (dh == 0) 1.0 else if (dh == 1) 0.5 else 0.0
            if (w > 0) s += n * w * (if (k / 24 == day) 1.0 else 0.35)
        }
        return s * decay(e.t, now)
    }

    private fun decay(t: Long, now: Long): Double = 0.5.pow((now - t).coerceAtLeast(0L) / (21 * 86_400_000.0))

    /** canali abituali a quest'ora (punteggio almeno [min]), dal più abituale */
    fun nowChannels(now: Long, slot: Int, min: Double = 1.5, n: Int = 8): List<String> =
        c.entries.map { it.key to score(it.value, now, slot) }.filter { it.second >= min }.sortedByDescending { it.second }.take(n).map { it.first }

    /** categorie di film e serie abituali a quest'ora */
    fun nowCats(now: Long, slot: Int, min: Double = 1.5, n: Int = 2): List<String> =
        g.entries.map { it.key to score(it.value, now, slot) }.filter { it.second >= min }.sortedByDescending { it.second }.take(n).map { it.first }

    /** i canali che si guardano di più (a qualunque ora): sono quelli della guida piccola */
    fun usualChannels(now: Long, n: Int): List<String> =
        c.entries.map { e -> e.key to e.value.s.values.sum() * decay(e.value.t, now) }.sortedByDescending { it.second }.take(n).map { it.first }

    /** categorie più guardate (per «Stasera»), dalla più guardata */
    fun usualCats(now: Long, n: Int): List<String> =
        g.entries.map { e -> e.key to e.value.s.values.sum() * decay(e.value.t, now) }.sortedByDescending { it.second }.take(n).map { it.first }

    /** programmi seguiti: visti in almeno 2 giorni diversi e non spenti con «non avvisarmi più» */
    fun followedProgs(): Map<String, Days> = p.filter { it.value.d.size >= 2 && !off.contains(it.key) }
    fun followedTeams(): Map<String, Days> = tm.filter { it.value.d.size >= 2 && !off.contains("t:" + it.key) }
    fun follows(): Boolean = followedProgs().isNotEmpty() || followedTeams().isNotEmpty()

    fun markShown(key: String, now: Long) {
        shown.remove(key)
        shown[key] = now
        while (shown.size > MAX_SHOWN) shown.remove(shown.keys.first())
    }

    fun mute(key: String) {
        off.remove(key)
        off.add(key)
        while (off.size > MAX_OFF) off.remove(off.first())
    }

    fun save(): String {
        val sb = StringBuilder()
        fun slots(kind: String, m: Map<String, Slots>) {
            for ((k, e) in m) sb.append(kind).append('\t').append(k).append('\t').append(e.n).append('\t').append(e.t).append('\t')
                .append(if (e.sp) '1' else '0').append('\t').append(e.s.entries.joinToString(",") { it.key.toString() + ":" + it.value }).append('\n')
        }
        slots("c", c)
        slots("g", g)
        for ((k, d) in p) sb.append("p\t").append(k).append('\t').append(d.n).append('\t').append(d.t).append('\t').append(d.d.joinToString(",")).append('\t').append(d.ch.joinToString(",")).append('\n')
        for ((k, d) in tm) sb.append("t\t").append(k).append('\t').append(d.n).append('\t').append(d.t).append('\t').append(d.d.joinToString(",")).append('\n')
        for ((k, t) in shown) sb.append("s\t").append(k).append('\t').append(t).append('\n')
        for (k in off) sb.append("o\t").append(k).append('\n')
        return sb.toString()
    }

    companion object {
        const val MAX_C = 50
        const val MAX_G = 25
        const val MAX_P = 50
        const val MAX_T = 30
        const val MAX_SHOWN = 30
        const val MAX_OFF = 15
        /** tetto di voci in tutto (abitudini e promemoria) */
        const val MAX = MAX_C + MAX_G + MAX_P + MAX_T + MAX_SHOWN + MAX_OFF

        private val RX_GENERIC = Regex("^(nessuna informazione|no information|programmazione|to be announced|tba|fine trasmissioni|in onda|diretta|live|film|telefilm|news)$")
        private val RX_EP = Regex("\\b(ep|episodio|puntata|stagione|st|parte|part)\\s*\\d+\\b")
        private val RX_SE = Regex("\\bs\\d{1,2}\\s?e\\d{1,3}\\b")
        private val RX_WORDS = Regex("\\b(replica|prima tv|in diretta|live|r)\\b")
        private val RX_DIGITS = Regex("\\b\\d+\\b")
        private val RX_SP = Regex("\\s+")
        private val RX_PAREN = Regex("[\\[(][^\\])]*[\\])]")
        private val RX_SEP = Regex("\\s+(?:vs\\.?|v|[-–—])\\s+", RegexOption.IGNORE_CASE)
        private val RX_TEAM_CUT = Regex("\\b(ac|fc|as|ss|us|ssc|afc|cf|sc|calcio|club)\\b")
        private val RX_NOT_TEAM = Regex("^(diretta|live|replica|sintesi|highlights|speciale|studio|pre partita|post partita|prepartita|postpartita|ep|episodio|puntata|parte|news|tg)$")
        /** titoli che sembrano una partita anche fuori dai canali di sport */
        private val RX_MATCH = Regex("(?i)\\b(serie [abc]|champions|europa league|conference|coppa|premier|liga|bundesliga|ligue|calcio|vs\\.?)\\b")
        val RX_SPORT_CH = Regex("(?i)sport|calcio|dazn|eurosport|serie ?a\\b|football|formula ?1|\\bf1\\b|motogp|tennis|\\bnba\\b|\\bufc\\b|golf|basket|rugby")

        /** fascia: tipo di giorno (0 feriale, 1 sabato, 2 domenica) × 24 + ora */
        fun slot(cal: Calendar): Int {
            val w = cal.get(Calendar.DAY_OF_WEEK)
            val d = if (w == Calendar.SUNDAY) 2 else if (w == Calendar.SATURDAY) 1 else 0
            return d * 24 + cal.get(Calendar.HOUR_OF_DAY)
        }

        fun day(cal: Calendar): Int = cal.get(Calendar.YEAR) * 10000 + (cal.get(Calendar.MONTH) + 1) * 100 + cal.get(Calendar.DAY_OF_MONTH)

        /** titolo del programma senza numeri di puntata e parole generiche ("" = non è un programma da seguire) */
        fun normProg(t: String): String {
            var s = Txt.norm(t)
            s = RX_EP.replace(s, " ")
            s = RX_SE.replace(s, " ")
            s = RX_WORDS.replace(s, " ")
            s = RX_DIGITS.replace(s, " ")
            s = RX_SP.replace(s, " ").trim()
            return if (s.length >= 4 && !RX_GENERIC.matches(s)) s.take(60) else ""
        }

        /** nome della squadra senza sigle (AC, FC…) e numeri (anche «Ep. 3» non è una squadra) */
        fun normTeam(n: String): String = RX_SP.replace(RX_DIGITS.replace(RX_TEAM_CUT.replace(Txt.norm(n), " "), " "), " ").trim()

        /** le due squadre di un titolo come «Serie A: Inter - Juventus» o «AC Milan vs Roma (diretta)»; vuota se non è una partita */
        fun teamsOf(title: String): List<String> {
            var t = RX_PAREN.replace(title, " ").trim()
            val colon = t.lastIndexOf(':')
            if (colon >= 0 && RX_SEP.containsMatchIn(t.substring(colon + 1))) t = t.substring(colon + 1)
            var parts = t.split(RX_SEP).map { it.trim() }.filter { it.isNotEmpty() }
            // «Inter-Milan»: un solo trattino attaccato, fra due nomi
            if (parts.size == 1) {
                val h = t.split('-')
                if (h.size == 2) parts = h.map { it.trim() }
            }
            if (parts.size < 2) return emptyList()
            val two = parts.takeLast(2).map { normTeam(it) }
            val ok = two.all { s -> s.length in 3..30 && s.split(' ').size <= 4 && s.any { it.isLetter() } && !RX_NOT_TEAM.matches(s) }
            return if (ok && two[0] != two[1]) two else emptyList()
        }

        fun load(s: String?): HabitBook {
            val h = HabitBook()
            for (line in (s ?: "").split('\n')) {
                val f = line.split('\t')
                try {
                    when (f[0]) {
                        "c", "g" -> if (f.size >= 6) {
                            val e = Slots(f[2], f[3].toLongOrNull() ?: 0L, f[4] == "1")
                            for (x in f[5].split(',')) { val kv = x.split(':'); if (kv.size == 2) { val k = kv[0].toIntOrNull(); val n = kv[1].toIntOrNull(); if (k != null && n != null && k in 0 until 72 && n > 0) e.s[k] = n } }
                            if (f[0] == "c") h.c[f[1]] = e else h.g[f[1]] = e
                        }
                        "p", "t" -> if (f.size >= 5) {
                            val d = Days(f[2], f[3].toLongOrNull() ?: 0L)
                            f[4].split(',').mapNotNullTo(d.d) { it.toIntOrNull() }
                            if (f.size >= 6) f[5].split(',').filterTo(d.ch) { it.isNotEmpty() }
                            if (f[0] == "p") h.p[f[1]] = d else h.tm[f[1]] = d
                        }
                        "s" -> if (f.size >= 3) h.shown[f[1]] = f[2].toLongOrNull() ?: 0L
                        "o" -> if (f.size >= 2) h.off.add(f[1])
                    }
                } catch (_: Exception) {}
            }
            // tetti anche su un file scritto da una versione diversa
            trim(h.c, MAX_C); trim(h.g, MAX_G); trimDays(h.p, MAX_P); trimDays(h.tm, MAX_T)
            while (h.shown.size > MAX_SHOWN) h.shown.remove(h.shown.keys.first())
            while (h.off.size > MAX_OFF) h.off.remove(h.off.first())
            return h
        }

        private fun trim(m: LinkedHashMap<String, Slots>, max: Int) {
            while (m.size > max) m.minByOrNull { it.value.t }?.let { m.remove(it.key) }
        }

        private fun trimDays(m: LinkedHashMap<String, Days>, max: Int) {
            while (m.size > max) m.minByOrNull { it.value.t }?.let { m.remove(it.key) }
        }
    }
}

/** Promemoria automatici: programmi seguiti 10 minuti prima, partite delle squadre seguite 15 minuti prima, fino a 5 minuti dopo l'inizio */
object Remind {
    const val LEAD_PROG = 10 * 60_000L
    const val LEAD_MATCH = 15 * 60_000L
    const val AFTER = 5 * 60_000L
    /** finestra calcolata a ogni giro (i giri sono ogni 15 minuti, nei menu) */
    const val AHEAD = 30 * 60_000L

    /** programma della guida piccola ([ch] = chiave stabile del canale) */
    class Prog(val ch: String, val title: String, val start: Long, val stop: Long)

    /** [key] = una volta sola per programma; [off] = la voce di «non avvisarmi più» */
    class Cand(val key: String, val off: String, val title: String, val ch: String, val start: Long, val lead: Long) {
        fun due(now: Long): Boolean = now >= start - lead && now - start < AFTER
        fun gone(now: Long): Boolean = now - start >= AFTER
    }

    /** promemoria da tenere pronti fra adesso e [AHEAD] (al più uno per programma, i canali nell'ordine di [progs]) */
    fun candidates(h: HabitBook, progs: List<Prog>, now: Long): List<Cand> {
        val fp = h.followedProgs()
        val ft = h.followedTeams()
        if (fp.isEmpty() && ft.isEmpty()) return emptyList()
        val out = LinkedHashMap<String, Cand>()
        for (pr in progs) {
            if (pr.start - now > AHEAD || now - pr.start >= AFTER) continue
            val pk = HabitBook.normProg(pr.title)
            val f = if (pk.isEmpty()) null else fp[pk]
            if (f != null && f.ch.contains(pr.ch)) {
                val k = "p:$pk@${pr.start}"
                if (!h.shown.containsKey(k) && !out.containsKey(k)) out[k] = Cand(k, pk, pr.title, pr.ch, pr.start, LEAD_PROG)
                continue
            }
            val teams = HabitBook.teamsOf(pr.title)
            val t = teams.firstOrNull { ft.containsKey(it) } ?: continue
            val k = "e:" + teams.joinToString("-") + "@" + pr.start
            if (!h.shown.containsKey(k) && !out.containsKey(k)) out[k] = Cand(k, "t:$t", pr.title, pr.ch, pr.start, LEAD_MATCH)
        }
        return out.values.sortedBy { it.start }
    }
}

/** «Stasera» senza IA: da 3 a 5 proposte varie (serie da riprendere, partita, TV, film, serie), ognuna con il suo perché */
object TonightPick {
    /** [k] = film | serie | tv | sport · [w] = perché · [s] = punteggio · [resume] = serie lasciata a metà */
    class C(val key: String, val k: String, val t: String, val w: String, val s: Double, val resume: Boolean = false)

    fun local(all: List<C>): List<C> {
        val pick = ArrayList<C>()
        fun take(c: C?) { if (c != null && !pick.contains(c) && pick.size < 5) pick.add(c) }
        fun by(k: String) = all.filter { it.k == k }.sortedByDescending { it.s }
        take(by("serie").firstOrNull { it.resume })
        take(by("sport").firstOrNull())
        take(by("tv").firstOrNull())
        take(by("film").firstOrNull())
        take(by("serie").firstOrNull { !pick.contains(it) })
        for (c in all.sortedByDescending { it.s }) take(c)
        return pick
    }

    /** scelte dell'assistente ([picks] = indici dell'elenco mandato, con il perché): valide solo se almeno 3 */
    fun fromAi(all: List<C>, picks: List<Pair<Int, String>>): List<Pair<C, String>>? {
        val out = ArrayList<Pair<C, String>>()
        val seen = HashSet<Int>()
        for ((i, why) in picks) {
            if (i !in all.indices || !seen.add(i)) continue
            out.add(all[i] to why.trim().ifEmpty { all[i].w })
            if (out.size >= 5) break
        }
        return if (out.size >= 3) out else null
    }
}
