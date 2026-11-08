package com.kairos.xc

import org.json.JSONArray
import org.json.JSONObject
import java.util.Calendar
import java.util.Locale

/** risposta dell'assistente del pannello: codice HTTP (0 = pannello non raggiungibile) e corpo */
class AiResp(val code: Int, val j: JSONObject?) {
    val ok: Boolean get() = code == 200 && j != null && !j.has("error") && !j.optBoolean("off")
    val limit: Boolean get() = j?.optBoolean("limit") == true
    val left: Int get() = j?.optInt("left", -1) ?: -1

    /** messaggio semplice, senza dettagli tecnici e senza mai citare il fornitore (come la Kairos Pro) */
    val msg: String get() = when {
        limit -> "Per oggi le domande all'assistente sono finite: riprendono domani."
        j?.optBoolean("off") == true || code == 403 -> "L'assistente non è disponibile adesso."
        code == 429 -> "Troppe richieste insieme: riprova tra poco."
        else -> "L'assistente non risponde adesso: riprova tra poco."
    }
}

/** riga dei risultati dell'assistente e delle pagine «Per te» e «Stasera» (immagini piccole come nel resto dell'app) */
data class Pick(
    val title: String, val sub: String, val tag: String, val img: String = "", val imgKind: Int = Img.LOGO,
    /** live | vod | series; "" = riga di solo testo, con [action] */
    val table: String = "", val id: Long = 0L, val k: String = "", val cat: String = "",
    /** programma della guida: inizio */
    val start: Long = 0L,
    /** serie da riprendere («Continua a guardare») */
    val resume: JSONObject? = null,
    /** il perché della proposta */
    val why: String = "",
    val action: String = "",
)

/**
 * Assistente IA, solo a richiesta (funzioni intelligenti accese e assistente non spento dal pannello, permesso «ai»): usa gli
 * endpoint del pannello già fatti per la Kairos Pro (POST /api/player/ai: «search», «tonight», domande v2). Il motore lo
 * sceglie il pannello (Gemini di serie, Claude per cliente) con i suoi limiti al giorno. All'IA va solo la frase o il minimo
 * dei titoli proposti, mai dati del cliente. I risultati si cercano nell'archivio con LIMIT.
 */
object Ai {
    fun available(): Boolean = Smart.on() && Panel.state?.perm("ai") != false

    fun leftText(n: Int): String = when {
        n < 0 || n > 5 -> ""
        n == 0 -> "Era l'ultima domanda di oggi"
        n == 1 -> "Ancora 1 domanda oggi"
        else -> "Ancora $n domande oggi"
    }

    fun post(body: JSONObject, readMs: Int): AiResp { val (c, j) = Panel.ai(body, readMs); return AiResp(c, j) }

    private fun strs(a: JSONArray?, n: Int): List<String> {
        if (a == null) return emptyList()
        val out = ArrayList<String>()
        for (i in 0 until minOf(a.length(), n)) a.optString(i).trim().takeIf { it.isNotEmpty() && it != "null" }?.let { out.add(it) }
        return out
    }

    fun find(o: JSONObject?): Find? {
        if (o == null) return null
        val y = o.optJSONArray("years")?.let { a -> if (a.length() >= 2) intArrayOf(a.optInt(0), a.optInt(1)) else null }?.takeIf { it[0] in 1900..2100 && it[1] in it[0]..2100 }
        return Find(o.optString("type", "any"), strs(o.optJSONArray("genres"), 4), y, strs(o.optJSONArray("people"), 3), strs(o.optJSONArray("titles"), 6),
            strs(o.optJSONArray("words"), 5), o.optString("sport"), o.optString("when"), o.optBoolean("kids"))
    }

    /* ---------------- ricerca con le frasi e domande libere ---------------- */
    class Result(val resp: AiResp, val label: String, val crit: List<String>, val answer: String, val picks: List<Pick>)

    /** thread di rete: criteri dall'assistente, risultati dall'archivio, dalla guida piccola e dalla guida breve dello sport chiesto */
    fun search(q: String): Result {
        val ask = AiFind.question(q)
        val resp = post(if (ask) JSONObject().put("v", 2).put("q", q) else JSONObject().put("mode", "search").put("q", q), if (ask) 20_000 else 16_000)
        if (!resp.ok) return Result(resp, "", emptyList(), "", emptyList())
        val j = resp.j!!
        val f = find(j.optJSONObject("find"))
        val named = ArrayList(strs(j.optJSONArray("titles"), 8))
        if (f != null && f.people.isNotEmpty()) named.addAll(people(f))
        val picks = if (f != null || named.isNotEmpty()) results(f ?: Find(), named) else emptyList()
        return Result(resp, j.optString("label"), f?.let { AiFind.crit(it) } ?: emptyList(), if (ask) j.optString("answer") else "", picks)
    }

    /** filmografia (dal pannello, senza IA): titoli da cercare nella libreria */
    private fun people(f: Find): List<String> {
        val out = ArrayList<String>()
        for (nm in f.people.take(2)) {
            val j = Panel.person(nm) ?: continue
            if (j.optBoolean("off")) break
            val cr = j.optJSONArray("people")?.optJSONObject(0)?.optJSONArray("credits") ?: continue
            val list = ArrayList<Credit>()
            for (i in 0 until minOf(cr.length(), 120)) {
                val c = cr.optJSONObject(i) ?: continue
                val g = c.optJSONArray("g")
                list.add(Credit(c.optString("t"), c.optString("o"), c.optInt("y"), c.optString("k"), (0 until (g?.length() ?: 0)).map { g!!.optInt(it) }))
            }
            out.addAll(AiFind.creditTitles(list, f))
        }
        return out.distinct().take(12)
    }

    private fun locked(adult: Boolean) = adult && Parental.locked(true)

    private fun stars(r: String): String = r.toDoubleOrNull()?.takeIf { it > 0 }?.let { "★ " + String.format(Locale.ITALY, "%.1f", it) } ?: ""

    private fun vodPick(it: Db.Item, why: String = ""): Pick = Pick(it.name, listOf(if (it.table == "series") "Serie TV" else "Film", it.year, stars(it.rating)).filter { s -> s.isNotEmpty() }.joinToString(" · "),
        if (it.table == "series") "SERIE" else "FILM", it.img, Img.POSTER, it.table, it.id, it.k, it.cat, why = why)

    private fun chPick(it: Db.Item, sub: String, why: String = ""): Pick = Pick(it.name, sub, "CANALE", it.img, Img.LOGO, "live", it.id, it.k, it.cat, why = why)

    /** «In onda ora», «oggi alle 21:15», «domani alle 20:45» */
    fun whenText(t: Long, now: Long = System.currentTimeMillis()): String {
        if (t <= now) return "In onda ora"
        val a = Calendar.getInstance().apply { timeInMillis = now }
        val b = Calendar.getInstance().apply { timeInMillis = t }
        val days = (b.get(Calendar.YEAR) - a.get(Calendar.YEAR)) * 366 + b.get(Calendar.DAY_OF_YEAR) - a.get(Calendar.DAY_OF_YEAR)
        return (when (days) { 0 -> "oggi"; 1 -> "domani"; else -> java.text.SimpleDateFormat("EEE d", Locale.ITALY).format(b.time) }) + " alle " + Ui.hm(t)
    }

    private fun sportWhere(cats: List<Pair<String, String>>, sport: String): Sql? {
        val ids = AiFind.sportCats(cats, sport)
        val names = AiFind.nameLike(AiFind.SPORT_CH[sport] ?: emptyList())
        val parts = ArrayList<String>()
        val args = ArrayList<String>()
        if (ids.isNotEmpty()) { parts.add("cat IN (" + ids.joinToString(",") { "?" } + ")"); args.addAll(ids) }
        if (names != null) { parts.add(names.where); args.addAll(names.args) }
        return if (parts.isEmpty()) null else Sql(parts.joinToString(" OR "), args)
    }

    private fun results(f: Find, named: List<String>): List<Pick> {
        val pls = K.activeIds()
        val kids = K.kids
        val out = ArrayList<Pick>()
        val kind = f.kind
        val tables = when (kind) { "movie" -> listOf("vod"); "series" -> listOf("series"); "live" -> emptyList(); else -> listOf("vod", "series") }
        // 1. titoli citati dall'assistente o della filmografia dell'attore
        AiFind.nameLike(named)?.let { w -> for (t in tables) out += Db.items(t, pls, kids, w.where, w.args, "name", 12).filter { !locked(it.adult) }.map { vodPick(it) } }
        // 2. criteri: generi (dai nomi delle categorie), bambini, anni, parole nel titolo
        for (t in tables) {
            val cats = Db.catList(t, pls)
            val gen = f.genres.filter { it != "bambini" }
            val g = if (gen.isNotEmpty()) AiFind.cats(cats, gen, false) else null
            val kc = if (f.forKids) AiFind.cats(cats, emptyList(), true) else null
            val w = AiFind.vodWhere(f, g, kc) ?: continue
            out += Db.items(t, pls, kids, w.where, w.args, "added DESC, _id", 20, byKey = false).filter { !locked(it.adult) }.map { vodPick(it) }
        }
        // 3. TV: programmi (guida piccola, guida breve dei canali dello sport chiesto), poi i canali
        if (kind == "live" || kind == "any") {
            val win = AiFind.window(f.whenW, Calendar.getInstance())
            val words = AiFind.words(f.words.ifEmpty { AiFind.SPORT_W[f.sport] ?: if (f.genres.contains("sport")) AiFind.SPORT_W["calcio"] ?: emptyList() else emptyList() })
            if (kind == "live" || f.whenW.isNotEmpty() || words.isNotEmpty()) out += tv(f, words, win, pls, kids)
            val cats = Db.catList("live", pls)
            val ch: Sql? = when {
                f.forKids -> AiFind.cats(cats, emptyList(), true).takeIf { it.isNotEmpty() }?.let { ids -> Sql("cat IN (" + ids.joinToString(",") { "?" } + ")", ids) }
                f.words.isNotEmpty() -> AiFind.nameLike(AiFind.words(f.words))
                f.sport.isNotEmpty() -> sportWhere(cats, f.sport)
                else -> null
            }
            // in ordine di numero con l'indice del portale: la lettura si ferma ai primi 12 trovati
            if (ch != null) out += Db.items("live", pls, kids, ch.where, ch.args, "num, _id", 12, byKey = false).filter { !locked(it.adult) }.map { chPick(it, if (it.num > 0) "Canale ${it.num}" else "Canale") }
        }
        return out.distinctBy { it.table + ":" + it.id + ":" + it.start }.take(40)
    }

    /** programmi in TV per le parole chieste (squadre, sport, programmi), nella finestra di tempo */
    private fun tv(f: Find, words: List<String>, win: LongArray, pls: List<String>, kids: Boolean): List<Pick> {
        val out = ArrayList<Pick>()
        val seen = HashSet<String>()
        fun add(r: Db.Row?, title: String, start: Long) {
            if (r == null || !seen.add(r.s("k") + "@" + start) || locked(r.l("adult") == 1L)) return
            out.add(Pick(title, whenText(start) + " · " + r.s("name"), "IN TV", r.s("logo"), Img.LOGO, "live", r.l("_id"), r.s("k"), r.s("cat"), start))
        }
        // la guida piccola: i canali che si guardano di solito («cosa c'è stasera?» senza parole: tutti i loro programmi)
        if (words.isNotEmpty() || f.kind == "live") for (pr in Guide.find(words, win[0], win[1])) add(try { Db.oneByK("live", pr.ch) } catch (_: Exception) { null }, pr.title, pr.start)
        // squadra o sport: la guida breve dei canali di quello sport (pochi, solo adesso che lo si chiede)
        if (words.isNotEmpty() && (f.sport.isNotEmpty() || f.genres.contains("sport") || f.kind == "live")) {
            val sw = sportWhere(Db.catList("live", pls), f.sport.ifEmpty { "calcio" }) ?: return out
            val pads = words.map { " $it " }
            for (ch in Db.items("live", pls, kids, sw.where, sw.args, "num, _id", if (Line.device() == "D") 4 else 8, byKey = false)) {
                if (out.size >= 12) break
                val p = K.pl(ch.pl) ?: continue
                if (p.xt == null || ch.sid.isEmpty() || locked(ch.adult)) continue
                for (pr in Epg.get(p, ch.sid, 8)) {
                    if (pr.end <= win[0] || pr.start >= win[1]) continue
                    val n = Txt.padded(pr.title)
                    if (pads.none { n.contains(it) }) continue
                    if (seen.add(ch.k + "@" + pr.start)) out.add(Pick(pr.title, whenText(pr.start) + " · " + ch.name, "IN TV", ch.img, Img.LOGO, "live", ch.id, ch.k, ch.cat, pr.start))
                }
            }
        }
        return out.sortedBy { it.start }.take(12)
    }

    /* ---------------- «Per te»: quello che guardi di solito a quest'ora ---------------- */
    private val FASCIA = { h: Int -> if (h < 5) "di notte" else if (h < 12) "di mattina" else if (h < 14) "all'ora di pranzo" else if (h < 19) "di pomeriggio" else if (h < 23) "di sera" else "di notte" }

    /** thread di lavoro: sottotitolo e righe (vuote se ci sono meno di 3 contenuti) */
    fun forYou(): Pair<String, List<Pick>> {
        val b = HabitStore.book()
        val cal = Calendar.getInstance()
        val now = cal.timeInMillis
        val slot = HabitBook.slot(cal)
        val pls = K.activeIds()
        val kids = K.kids
        val out = ArrayList<Pick>()
        for (k in b.nowChannels(now, slot)) {
            val r = try { Db.oneByK("live", k) } catch (_: Exception) { null } ?: continue
            if (locked(r.l("adult") == 1L) || (kids && r.l("adult") == 1L)) continue
            out.add(Pick(r.s("name"), if (r.l("num") > 0) "Canale ${r.l("num")}" else "Canale", "CANALE", r.s("logo"), Img.LOGO, "live", r.l("_id"), k, r.s("cat"), why = "Lo guardi spesso a quest'ora"))
        }
        val recent = Store.recent()
        for (o in recent.filter { it.optString("group").isNotEmpty() }.take(3)) {
            out.add(Pick(o.optString("title"), "Serie TV", "SERIE", o.optString("img"), Img.POSTER, "series", 0L, o.optString("group"), resume = o, why = "Riprendi da dove eri rimasto"))
        }
        val seen = recent.map { it.optString("group").ifEmpty { it.optString("id") } }.toHashSet()
        for (key in b.nowCats(now, slot)) {
            val table = if (key.startsWith("s|")) "series" else "vod"
            val name = b.g[key]?.n ?: ""
            if (HomeCfg.hidden(name)) continue
            for (it in Db.items(table, pls, kids, "cat = ?", listOf(key.substring(2)), "added DESC, _id", 6, byKey = false)) {
                if (!seen.contains(it.k) && !locked(it.adult)) out.add(vodPick(it, "Nuovo in «$name»"))
            }
        }
        val day = slot / 24
        val sub = "Quello che guardi di solito " + listOf("nei giorni feriali", "il sabato", "la domenica")[day] + " " + FASCIA(slot % 24)
        val list = out.distinctBy { it.table + ":" + it.k }.take(24)
        return sub to (if (list.size >= 3) list else emptyList())
    }

    /* ---------------- «Stasera»: da 3 a 5 proposte, con il perché ---------------- */
    class Tonight(val picks: List<Pick>, val note: String)

    /** thread di rete; [skip] = proposte già viste («Altre idee») */
    fun tonight(skip: Set<String>): Tonight {
        val all = tonightCands(skip)
        if (all.isEmpty()) return Tonight(emptyList(), "")
        var note = ""
        if (available() && all.size >= 3) {
            val list = all.take(20)
            val body = JSONObject().put("mode", "tonight").put("cands", JSONArray().apply { for ((p, c) in list) put(wire(p, c)) })
                .put("taste", JSONArray(taste())).put("hm", Ui.hm(System.currentTimeMillis())).put("kids", K.kids)
            val r = post(body, 20_000)
            if (r.ok) {
                val a = r.j?.optJSONArray("picks")
                val idx = ArrayList<Pair<Int, String>>()
                if (a != null) for (i in 0 until a.length()) { val o = a.optJSONObject(i) ?: continue; idx.add(o.optInt("i", -1) to o.optString("why")) }
                val chosen = TonightPick.fromAi(list.map { it.second }, idx)
                if (chosen != null) return Tonight(chosen.map { (c, why) -> list.first { it.second === c }.first.copy(why = why) }, leftText(r.left))
            } else if (r.limit) note = "Le domande all'assistente di oggi sono finite: proposte scelte in base ai tuoi gusti."
        }
        return Tonight(TonightPick.local(all.map { it.second }).map { c -> all.first { it.second === c }.first }, note)
    }

    /** per l'IA solo titolo, tipo, anno, categoria, canale, orario e il perché: niente dati del cliente */
    private fun wire(p: Pick, c: TonightPick.C): JSONObject {
        val o = JSONObject().put("k", c.k).put("t", c.t.take(80)).put("w", c.w.take(70))
        if (p.table != "live") p.sub.split(" · ").firstOrNull { it.length == 4 && it.all { ch -> ch.isDigit() } }?.let { o.put("y", it.toInt()) }
        if (p.table == "live") { o.put("c", p.sub.substringAfter(" · ", "").take(40)); if (p.start > System.currentTimeMillis()) o.put("h", Ui.hm(p.start)) }
        else if (p.cat.isNotEmpty()) catNames[p.cat]?.let { o.put("g", it.take(50)) }
        return o
    }

    /** nomi delle categorie delle proposte di questo giro (per l'IA, come genere) */
    private val catNames = HashMap<String, String>()

    /** gusti: le categorie che si guardano di più (nomi brevi) */
    private fun taste(): List<String> {
        val b = HabitStore.book()
        return b.usualCats(System.currentTimeMillis(), 5).mapNotNull { b.g[it]?.n?.take(24)?.ifEmpty { null } }
    }

    private fun tonightCands(skip: Set<String>): List<Pair<Pick, TonightPick.C>> {
        val out = ArrayList<Pair<Pick, TonightPick.C>>()
        val used = HashSet(skip)
        fun add(p: Pick, c: TonightPick.C) { if (used.add(c.key)) out.add(p to c) }
        val pls = K.activeIds()
        val kids = K.kids
        val b = HabitStore.book()
        val now = System.currentTimeMillis()
        catNames.clear()
        // 1. serie lasciate a metà
        val recent = Store.recent()
        for (o in recent.filter { it.optString("group").isNotEmpty() }.take(2)) {
            val g = o.optString("group")
            val t = o.optString("title").substringBefore(" · S")
            val w = "Riprendi da dove eri rimasto"
            add(Pick(t, "Serie TV", "SERIE", o.optString("img"), Img.POSTER, "series", 0L, g, resume = o, why = w), TonightPick.C("s:$g", "serie", t, w, 9.0, resume = true))
        }
        val seen = recent.map { it.optString("group").ifEmpty { it.optString("id") } }.toHashSet()
        // 2. film e serie non visti delle categorie che guarda, poi le novità (archivio, LIMIT)
        for (key in b.usualCats(now, 4)) {
            val table = if (key.startsWith("s|")) "series" else "vod"
            val name = b.g[key]?.n ?: ""
            if (HomeCfg.hidden(name)) continue
            var n = 0
            for (it in Db.items(table, pls, kids, "cat = ?", listOf(key.substring(2)), "added DESC, _id", 6, byKey = false)) {
                if (n >= 2) break
                if (seen.contains(it.k) || locked(it.adult)) continue
                val w = "Dalla categoria «$name», che guardi spesso"
                catNames[it.cat] = name
                add(vodPick(it, w), TonightPick.C("v:" + it.k, if (table == "series") "serie" else "film", it.name, w, 3.0 + (it.rating.toDoubleOrNull() ?: 0.0).coerceIn(0.0, 10.0) / 3))
                n++
            }
        }
        for (table in listOf("vod", "series")) for (it in Db.items(table, pls, kids, null, emptyList(), "added DESC, _id", if (table == "vod") 6 else 3, byKey = false)) {
            if (seen.contains(it.k) || locked(it.adult)) continue
            val r = (it.rating.toDoubleOrNull() ?: 0.0).coerceIn(0.0, 10.0)
            val w = if (r >= 7) "Voto " + String.format(Locale.ITALY, "%.1f", r) else if (table == "series") "Aggiunta da poco" else "Aggiunto da poco"
            add(vodPick(it, w), TonightPick.C("v:" + it.k, if (table == "series") "serie" else "film", it.name, w, 1.0 + r / 3))
        }
        // 3. stasera in TV: la prima serata dei canali che guarda (guida piccola); le partite contano come sport
        val cal = Calendar.getInstance()
        fun at(h: Int, m: Int): Long = (cal.clone() as Calendar).apply { set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0); add(Calendar.MINUTE, h * 60 + m) }.timeInMillis
        val night = cal.get(Calendar.HOUR_OF_DAY) < 4
        val from = if (night) now else maxOf(now, at(19, 30))
        val to = if (night) at(4, 0) else at(25, 0)
        val target = maxOf(now, at(21, 15))
        var ntv = 0
        for (k in b.usualChannels(now, Budget.guideChannels(Line.device()))) {
            if (ntv >= 6) break
            val pr = Guide.prime(k, from, to, target) ?: continue
            val r = try { Db.oneByK("live", k) } catch (_: Exception) { null } ?: continue
            if (locked(r.l("adult") == 1L) || (kids && r.l("adult") == 1L)) continue
            val on = pr.start <= now
            val w = (if (on) "In onda ora su " else "Alle ${Ui.hm(pr.start)} su ") + r.s("name") + ", uno dei tuoi canali"
            add(Pick(pr.title, whenText(pr.start) + " · " + r.s("name"), "IN TV", r.s("logo"), Img.LOGO, "live", r.l("_id"), k, r.s("cat"), pr.start, why = w),
                TonightPick.C("tv:$k", if (HabitBook.teamsOf(pr.title).isNotEmpty()) "sport" else "tv", pr.title, w, 6.0))
            ntv++
        }
        return out.take(22)
    }
}
