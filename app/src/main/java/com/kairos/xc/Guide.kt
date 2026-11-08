package com.kairos.xc

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/**
 * Guida piccola su SQLite (smart.db, separata dall'archivio delle liste; esiste solo con le funzioni intelligenti accese):
 * i canali che si guardano di più (8, sulle chiavette deboli 4) e quelli dei programmi seguiti, una dozzina di programmi
 * ciascuno dalla guida breve del server (get_short_epg, qualche KB). Serve ai promemoria, a «Stasera» e alla ricerca con le
 * frasi («stasera in TV»). Si aggiorna nei menu, al massimo [Budget.guidePerRun] canali per giro; si apre solo dai thread di lavoro.
 */
object Guide {
    private class H(c: Context) : SQLiteOpenHelper(c, "smart.db", null, 1) {
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL("CREATE TABLE guide (ch TEXT, start INTEGER, stop INTEGER, title TEXT)")
            db.execSQL("CREATE INDEX guide_cs ON guide(ch, start)")
            db.execSQL("CREATE INDEX guide_s ON guide(start)")
            // per canale: ultimo aggiornamento e fine dell'ultimo programma (-1 = il server non ha la guida di quel canale)
            db.execSQL("CREATE TABLE gch (ch TEXT PRIMARY KEY, at INTEGER, last INTEGER)")
        }

        override fun onUpgrade(db: SQLiteDatabase, oldV: Int, newV: Int) {
            db.execSQL("DROP TABLE IF EXISTS guide")
            db.execSQL("DROP TABLE IF EXISTS gch")
            onCreate(db)
        }

        override fun onDowngrade(db: SQLiteDatabase, oldV: Int, newV: Int) = onUpgrade(db, oldV, newV)
    }

    private val h by lazy { H(App.I) }
    private val db: SQLiteDatabase get() = h.writableDatabase

    /** esito dell'ultimo aggiornamento, per l'auto-controllo (chk.epg): ok | fix | err; "" = nessun canale da coprire */
    @Volatile var state = ""
        private set

    /** canali da coprire (chiavi stabili): i più guardati e quelli dei programmi seguiti */
    fun wanted(b: HabitBook, now: Long): List<String> {
        val n = Budget.guideChannels(Line.device())
        val out = LinkedHashSet(b.usualChannels(now, n))
        for (d in b.followedProgs().values) for (ch in d.ch) if (out.size < n + 4) out.add(ch)
        return out.toList()
    }

    /** lavoro GUIDE (thread di lavoro): i canali con la guida vecchia o che finisce entro 3 ore */
    fun refresh() {
        if (!Smart.on()) return
        val now = System.currentTimeMillis()
        val want = wanted(HabitStore.book(), now)
        if (want.isEmpty()) { state = ""; return }
        val d = db
        val ph = want.joinToString(",") { "?" }
        d.delete("guide", "stop < ? OR ch NOT IN ($ph)", (listOf((now - 3_600_000L).toString()) + want).toTypedArray())
        d.delete("gch", "ch NOT IN ($ph)", want.toTypedArray())
        val info = HashMap<String, LongArray>()
        d.rawQuery("SELECT ch, at, last FROM gch", null).use { c -> while (c.moveToNext()) info[c.getString(0)] = longArrayOf(c.getLong(1), c.getLong(2)) }
        val stale = want.filter { k ->
            val x = info[k]
            when {
                x == null -> true
                x[1] < 0L -> now - x[0] > 24 * 3_600_000L // nessuna guida sul server: si riprova una volta al giorno
                else -> now - x[0] > 6 * 3_600_000L || (x[1] < now + 3 * 3_600_000L && now - x[0] > 15 * 60_000L)
            }
        }
        if (stale.isEmpty()) { state = "ok"; return }
        var got = 0
        var failed = 0
        for (k in stale.take(Budget.guidePerRun(Line.device()))) {
            if (Smart.playing()) break // visione appena iniziata: si riprende al prossimo giro
            val r = try { Db.oneByK("live", k) } catch (_: Exception) { null }
            val p = r?.let { K.pl(it.s("pl")) }
            val sid = r?.s("sid") ?: ""
            if (p?.xt == null || sid.isEmpty()) { mark(k, now, -1L); continue }
            val list = Epg.get(p, sid, 12)
            val had = (info[k]?.get(1) ?: 0L) > 0L
            d.beginTransaction()
            try {
                if (list.isNotEmpty()) {
                    d.delete("guide", "ch = ?", arrayOf(k))
                    for (x in list.take(16)) d.insert("guide", null, ContentValues().apply { put("ch", k); put("start", x.start); put("stop", x.end); put("title", x.title.take(120)) })
                    mark(k, now, list.last().end)
                } else mark(k, now, if (had) info[k]!![1] else -1L)
                d.setTransactionSuccessful()
            } finally { d.endTransaction() }
            if (list.isNotEmpty()) got++ else if (had) failed++
        }
        state = if (got > 0) "fix" else if (failed > 0) "err" else "ok"
    }

    private fun mark(k: String, at: Long, last: Long) {
        db.insertWithOnConflict("gch", null, ContentValues().apply { put("ch", k); put("at", at); put("last", last) }, SQLiteDatabase.CONFLICT_REPLACE)
    }

    /** programmi che iniziano fra [from] e [to] (promemoria) */
    fun upcoming(from: Long, to: Long): List<Remind.Prog> =
        query("SELECT ch, title, start, stop FROM guide WHERE start >= ? AND start <= ? ORDER BY start LIMIT 60", listOf(from.toString(), to.toString()))

    /** programmi in onda fra [from] e [to] con una delle [words] nel titolo; senza parole tutti (ricerca con le frasi) */
    fun find(words: List<String>, from: Long, to: Long, limit: Int = 12): List<Remind.Prog> {
        val l = AiFind.nameLike(words, "title")
        return query("SELECT ch, title, start, stop FROM guide WHERE stop > ? AND start < ?" + (l?.let { " AND " + it.where } ?: "") + " ORDER BY start LIMIT $limit",
            listOf(from.toString(), to.toString()) + (l?.args ?: emptyList()))
    }

    /** prima serata di un canale: il programma in onda a [target] (o il primo dopo), di almeno 25 minuti, niente notiziari */
    fun prime(ch: String, from: Long, to: Long, target: Long): Remind.Prog? {
        val l = query("SELECT ch, title, start, stop FROM guide WHERE ch = ? AND stop > ? AND start < ? ORDER BY start LIMIT 20", listOf(ch, from.toString(), to.toString()))
            .filter { it.stop - it.start >= 25 * 60_000L && !RX_NEWS.containsMatchIn(it.title) }
        return l.firstOrNull { it.start <= target && it.stop > target } ?: l.firstOrNull { it.start >= target } ?: l.firstOrNull()
    }

    private val RX_NEWS = Regex("(?i)\\b(tg\\d?|telegiornale|meteo|news|televendit)")

    private fun query(sql: String, args: List<String>): List<Remind.Prog> = try {
        db.rawQuery(sql, args.toTypedArray()).use { c ->
            val out = ArrayList<Remind.Prog>()
            while (c.moveToNext()) out.add(Remind.Prog(c.getString(0) ?: "", c.getString(1) ?: "", c.getLong(2), c.getLong(3)))
            out
        }
    } catch (_: Exception) { emptyList() }
}
