package com.kairos.xc

import android.util.Base64
import android.util.LruCache
import org.json.JSONObject
import java.security.MessageDigest
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit

class Prog(val title: String, val desc: String, val start: Long, val end: Long) {
    fun progress(now: Long = System.currentTimeMillis()): Int =
        if (end <= start) 0 else (((now - start) * 100) / (end - start)).toInt().coerceIn(0, 100)
}

/**
 * Guida TV a tre livelli (da Kairos XC 3.4.11, come in KAIROS Ultimate):
 *  1. la risposta breve del server per il canale che stai guardando o selezionando (get_short_epg), in memoria 10 minuti;
 *  2. la guida XMLTV del server (xmltv.php), letta in sottofondo e tenuta nell'archivio: copre i canali senza risposta breve;
 *  3. una guida XMLTV esterna, se il cliente ne ha messa una in Impostazioni: riempie i buchi rimasti.
 * La lettura XMLTV non parte mai durante la visione, un caricamento delle liste o con poca memoria, e ha un tetto di programmi.
 */
object Epg {
    private class Entry(val t: Long, val list: List<Prog>)
    private val shortCache = LruCache<String, Entry>(if (App.lite) 120 else 400)
    private val storeCache = LruCache<String, Entry>(if (App.lite) 96 else 256)
    private val rowCache = LruCache<String, Entry>(if (App.lite) 1_500 else 4_000)
    private val scheduleLock = Any()
    private val worker = ScheduledThreadPoolExecutor(1) { r ->
        Thread(r, "kx-epg").apply { isDaemon = true; priority = Thread.MIN_PRIORITY }
    }
    @Volatile private var pending: List<Playlist>? = null
    private var queued = false
    private var failedSignature = ""
    private var retryAt = 0L
    @Volatile private var prefetchAt = 0L

    private const val CACHE_MS = 10 * 60_000L
    private const val BUILD_MS = 8 * 60 * 60_000L
    private const val BATCH = 500

    /* ---- Guida TV di riserva (dalla KAIROS HEAVY, 21 novembre 2026: il titolare, «gli epg non vanno a tutti i canali… puoi
       prenderli altrove oltre che alla sorgente del fornitore?») ----
       Due guide pubbliche italiane in formato XMLTV. Servono solo ai canali rimasti SENZA programmi dopo le guide del
       fornitore (si riconoscono dal nome del canale); la seconda si scarica solo se la prima non risponde. Parte anche quando
       la guida del fornitore non si scarica. Dal pannello: voce «Guida TV di riserva» («epg2»), accesa di serie. */
    private val BACKUP_GUIDES = listOf(
        "https://epgshare01.online/epgshare01/epg_ripper_IT1.xml.gz",
        "http://www.epgitalia.tv/gzip",
    )
    private fun backupUrls(): List<String> =
        // saltata solo con «Leggerissima»: la lettura è a flusso, con un tetto di programmi, e si ferma da sola quando Android
        // chiede memoria (con «!App.lite» molte Fire Stick sarebbero rimaste senza)
        if (!App.max && Remote.player("epg2", "on") != "off") BACKUP_GUIDES else emptyList()

    private var backupQueued = false
    private var backupTries = 0

    /** la guida di riserva è un lavoro a parte, DOPO quella del fornitore: se viene interrotta non rovina niente */
    private fun scheduleBackup(pls: List<Playlist>, delayMs: Long) {
        if (pls.isEmpty() || backupUrls().isEmpty()) return
        synchronized(scheduleLock) { if (backupQueued) return; backupQueued = true }
        try { worker.schedule({ runBackup(pls) }, delayMs, TimeUnit.MILLISECONDS) } catch (_: Throwable) { synchronized(scheduleLock) { backupQueued = false } }
    }

    private fun runBackup(pls: List<Playlist>) {
        synchronized(scheduleLock) { backupQueued = false }
        val urls = backupUrls()
        if (urls.isEmpty()) return
        val ids = pls.map { it.id }
        if (K.activeIds().toSet() != ids.toSet()) return
        // una volta per ogni guida del fornitore costruita (o ogni 8 ore se il fornitore non ne ha)
        val key = Store.epgSourceSig + ":" + Store.epgBuiltAt
        val sp = App.I.getSharedPreferences("epg2", android.content.Context.MODE_PRIVATE)
        if (sp.getString("epg2Key", "") == key && System.currentTimeMillis() - sp.getLong("epg2At", 0L) in 0 until BUILD_MS) return
        if (deferMs() > 0L) { scheduleBackup(pls, 120_000L); return }
        when (try { buildBackup(ids, urls) } catch (_: Throwable) { BuildResult.FAILED }) {
            BuildResult.DONE -> {
                backupTries = 0
                sp.edit().putString("epg2Key", key).putLong("epg2At", System.currentTimeMillis()).apply()
                storeCache.evictAll(); rowCache.evictAll()
            }
            // visione iniziata nel frattempo: si riprova più tardi, la guida del fornitore resta com'è
            BuildResult.ABORTED -> scheduleBackup(pls, 120_000L)
            // nessuna delle due guide di riserva ha risposto: tre nuovi tentativi, uno ogni mezz'ora
            BuildResult.FAILED -> if (++backupTries <= 3) scheduleBackup(pls, 30 * 60_000L)
        }
    }

    /**
     * Legge la prima guida di riserva che risponde e aggiunge i programmi SOLO ai canali che non ne hanno (riconosciuti
     * dall'ID o dal nome). Passa da «prog_stage»: nella guida vera entra tutto insieme alla fine, o niente.
     */
    private fun buildBackup(ids: List<String>, urls: List<String>): BuildResult {
        fun busy() = App.memTight || App.isPlayer(App.top) || Players.previewing || Sync.running
        if (busy()) return BuildResult.ABORTED
        if (Net.type() == "none") return BuildResult.FAILED
        val channels = Db.epgChannels(ids)
        if (channels.isEmpty()) return BuildResult.DONE
        val idMap = HashMap<String, MutableSet<String>>()
        val nameMap = HashMap<String, MutableSet<String>>()
        for (channel in channels) {
            val epgId = channel.epg.trim().lowercase(java.util.Locale.ROOT)
            val nameKey = EpgName.key(channel.name)
            val key = when {
                epgId.isNotEmpty() -> "id:$epgId"
                nameKey.isNotEmpty() -> "n:$nameKey"
                else -> ""
            }
            if (epgId.isNotEmpty()) idMap.getOrPut(epgId) { LinkedHashSet() }.add(key)
            if (nameKey.isNotEmpty()) nameMap.getOrPut(nameKey) { LinkedHashSet() }.add(key)
        }
        val index = XmltvIndex(idMap, nameMap)
        val db = Db.w
        // canali che hanno già la guida del fornitore: non si toccano
        val covered = HashSet<String>()
        db.rawQuery("SELECT DISTINCT ch FROM prog", null).use { c -> while (c.moveToNext()) covered.add(c.getString(0) ?: "") }
        val limiter = XmltvLimiter(perChannel = 60, total = 16_000)
        limiter.seal(covered)
        val now = System.currentTimeMillis() / 1000L
        var inserted = 0
        var loaded = false
        fun clearStage() { try { db.delete("prog_stage", null, null) } catch (_: Throwable) {} }
        clearStage()
        val insert = db.compileStatement("INSERT OR IGNORE INTO prog_stage(ch, start, stop, title, descr) VALUES(?,?,?,?,?)")
        try {
            for (url in urls) {
                if (busy() || K.activeIds().toSet() != ids.toSet()) { clearStage(); return BuildResult.ABORTED }
                var inTransaction = false
                var batch = 0
                val connection = try { Http.open(url, connectMs = 12_000, readMs = 20_000) } catch (_: Throwable) { continue }
                try {
                    if (connection.responseCode != 200) continue
                    var checks = 0
                    XmltvParser.parse(
                        Http.stream(connection),
                        android.util.Xml.newPullParser(),
                        index,
                        now - 2 * 3600L,
                        now + 24 * 3600L,
                        stopAtLimit = { limiter.full },
                        shouldAbort = { checks++; busy() || (checks % 128 == 0 && K.activeIds().toSet() != ids.toSet()) },
                    ) { programme ->
                        if (!limiter.accept(programme.channel, programme.start)) return@parse
                        if (!inTransaction) { db.beginTransactionNonExclusive(); inTransaction = true }
                        insert.clearBindings()
                        insert.bindString(1, programme.channel)
                        insert.bindLong(2, programme.start)
                        insert.bindLong(3, programme.stop)
                        insert.bindString(4, programme.title)
                        insert.bindString(5, programme.description)
                        if (insert.executeInsert() >= 0L) { inserted++; batch++ }
                        if (batch >= BATCH) { db.setTransactionSuccessful(); db.endTransaction(); inTransaction = false; batch = 0 }
                    }
                    if (inTransaction) { db.setTransactionSuccessful(); db.endTransaction(); inTransaction = false }
                    loaded = true
                } catch (_: XmltvAborted) {
                    if (inTransaction) { try { db.endTransaction() } catch (_: Throwable) {}; inTransaction = false }
                    clearStage()
                    return BuildResult.ABORTED
                } catch (_: Throwable) {
                    // questa guida si è rotta a metà: si butta quello che aveva dato e si prova l'altra
                    if (inTransaction) { try { db.endTransaction() } catch (_: Throwable) {}; inTransaction = false }
                    clearStage()
                    inserted = 0
                } finally { try { connection.disconnect() } catch (_: Throwable) {} }
                if (loaded) break
            }
        } finally { try { insert.close() } catch (_: Throwable) {} }
        if (!loaded) { clearStage(); return BuildResult.FAILED }
        if (busy()) { clearStage(); return BuildResult.ABORTED }
        if (inserted > 0) {
            db.beginTransactionNonExclusive()
            try {
                db.execSQL("INSERT OR IGNORE INTO prog(ch, start, stop, title, descr) SELECT ch, start, stop, title, descr FROM prog_stage")
                db.delete("prog_stage", null, null)
                db.setTransactionSuccessful()
            } finally { db.endTransaction() }
        }
        return BuildResult.DONE
    }


    fun channelKeys(epg: String, name: String): List<String> = buildList(2) {
        epg.trim().lowercase(java.util.Locale.ROOT).takeIf { it.isNotEmpty() }?.let { add("id:$it") }
        EpgName.key(name).takeIf { it.isNotEmpty() }?.let { add("n:$it") }
    }

    private fun shortKey(p: Playlist, sid: String) = p.id + ":" + sid
    private fun storeKey(epg: String, name: String): String = channelKeys(epg, name).joinToString("|")
    private fun liveAfterNow(list: List<Prog>, limit: Int): List<Prog> {
        val now = System.currentTimeMillis()
        return list.asSequence().filter { it.end > now }.take(limit.coerceIn(1, 60)).toList()
    }

    fun shortCached(p: Playlist?, sid: String, limit: Int = 4): List<Prog>? {
        if (p == null || sid.isEmpty()) return null
        val e = shortCache.get(shortKey(p, sid)) ?: return null
        if (System.currentTimeMillis() - e.t > CACHE_MS) return null
        return liveAfterNow(e.list, limit)
    }

    fun cached(p: Playlist?, sid: String, limit: Int, epg: String = "", name: String = ""): List<Prog>? {
        val short = shortCached(p, sid, limit)
        if (!short.isNullOrEmpty()) return short
        val key = storeKey(epg, name)
        if (key.isEmpty()) return short
        val now = System.currentTimeMillis()
        rowCache.get(key)?.takeIf { now - it.t <= 5 * 60_000L }
            ?.let { liveAfterNow(it.list, limit) }?.takeIf { it.isNotEmpty() }?.let { return it }
        return storeCache.get(key)?.takeIf { now - it.t <= 60_000L }
            ?.let { liveAfterNow(it.list, limit) }?.takeIf { it.isNotEmpty() } ?: short
    }

    fun now(p: Playlist?, sid: String, epg: String, name: String): Prog? {
        val items = cached(p, sid, 4, epg, name).orEmpty()
        val now = System.currentTimeMillis()
        return items.firstOrNull { it.start <= now && it.end > now } ?: items.firstOrNull()
    }

    fun clearCaches() {
        shortCache.evictAll()
        storeCache.evictAll()
        rowCache.evictAll()
    }

    /** programmi da adesso in poi: la risposta breve resta prioritaria, XMLTV copre la guida mancante */
    fun get(p: Playlist?, sid: String, limit: Int = 4, epg: String = "", name: String = ""): List<Prog> {
        if (p != null && sid.isNotEmpty()) {
            val cached = shortCached(p, sid, limit)
            if (!cached.isNullOrEmpty()) return cached
            val entry = shortCache.get(shortKey(p, sid))
            if (entry == null || System.currentTimeMillis() - entry.t > CACHE_MS) {
                val url = p.api("get_short_epg", "&stream_id=$sid&limit=${limit.coerceIn(1, 60)}")
                if (url != null) {
                    val items = try {
                        val response = Http.text(url, connectMs = 10_000, readMs = 15_000)
                        if (response.code == 200) parse(response.text) else emptyList()
                    } catch (_: Exception) { emptyList() }
                    shortCache.put(shortKey(p, sid), Entry(System.currentTimeMillis(), items))
                    liveAfterNow(items, limit).takeIf { it.isNotEmpty() }?.let { return it }
                }
            }
        }
        return xmltvCached(epg, name, limit)
    }

    fun fromXmltv(epg: String, name: String, fromSeconds: Long, toSeconds: Long, limit: Int = 120): List<Prog> {
        val keys = channelKeys(epg, name)
        if (keys.isEmpty()) return emptyList()
        val shift = EpgShift.seconds(Remote.player("o.epgShift", "0"))
        val out = LinkedHashMap<Long, Prog>()
        Db.scanProgrammes(keys, fromSeconds - shift, toSeconds - shift, limit.coerceIn(1, 240)) { row ->
            val item = Prog(row.title, row.description, (row.start + shift) * 1000L, (row.stop + shift) * 1000L)
            if (!out.containsKey(item.start)) out[item.start] = item
            out.size < limit
        }
        return out.values.sortedBy { it.start }
    }

    private fun xmltvCached(epg: String, name: String, limit: Int): List<Prog> {
        val keys = channelKeys(epg, name)
        if (keys.isEmpty()) return emptyList()
        val cacheKey = keys.joinToString("|")
        val now = System.currentTimeMillis()
        storeCache.get(cacheKey)?.takeIf { now - it.t <= 60_000L }?.let { return liveAfterNow(it.list, limit) }
        val nowSeconds = now / 1000L
        val ahead = if (App.lite || App.memTight) 12L else 24L
        val list = fromXmltv(epg, name, nowSeconds - 2 * 3600L, nowSeconds + ahead * 3600L, 120)
        storeCache.put(cacheKey, Entry(now, list))
        return liveAfterNow(list, limit)
    }

    private fun parse(t: String): List<Prog> {
        val a = try { JSONObject(t).optJSONArray("epg_listings") } catch (_: Exception) { null } ?: return emptyList()
        val out = ArrayList<Prog>()
        val shift = EpgShift.millis(Remote.player("o.epgShift", "0"))
        for (i in 0 until a.length()) {
            val o = a.optJSONObject(i) ?: continue
            val s = o.optString("start_timestamp").toLongOrNull()?.times(1000) ?: continue
            val e = (o.optString("stop_timestamp").ifEmpty { o.optString("end_timestamp") }).toLongOrNull()?.times(1000) ?: continue
            out.add(Prog(b64(o.optString("title")), b64(o.optString("description")), s + shift, e + shift))
        }
        return out.sortedBy { it.start }
    }

    private fun b64(s: String): String = try {
        String(Base64.decode(s, Base64.DEFAULT), Charsets.UTF_8).trim()
    } catch (_: Exception) { s }

    private enum class BuildResult { DONE, ABORTED, FAILED }

    // ultimo controllo andato a buon fine: con le stesse liste e le stesse fonti non si ripete prima di 10 minuti
    // (schedule arriva a ogni battito del pannello e a ogni ritorno in Home: non deve rileggere ogni volta l'elenco dei canali)
    private var checkedKey = ""
    private var checkedAt = 0L

    fun schedule(pls: List<Playlist> = K.active()) {
        if (pls.isEmpty()) return
        synchronized(scheduleLock) {
            if (!queued && requestKey(pls) == checkedKey && System.currentTimeMillis() - checkedAt < 10 * 60_000L) return
            val changed = pending?.let(::requestKey) != requestKey(pls)
            if (changed) { failedSignature = ""; retryAt = 0L }
            pending = pls.toList()
            if (queued) {
                if (changed) worker.execute { runPending() }
                return
            }
            queued = true
            worker.execute { runPending() }
        }
    }

    fun syncCompleted(pls: List<Playlist>) {
        shortCache.evictAll()
        storeCache.evictAll()
        rowCache.evictAll()
        prefetchAt = 0L
        schedule(pls)
    }

    private fun requestKey(pls: List<Playlist>): String =
        pls.joinToString("|") { it.id + ":" + it.sig() } + ":" + Store.syncAtOf(pls.map { it.id }) + ":" + Store.externalEpgUrl

    private fun sourceUrls(pls: List<Playlist>): List<String> {
        val external = Store.externalEpgUrl.trim().takeIf {
            (Panel.state ?: Panel.loadCached())?.perm("epgUrl") != false &&
                (it.startsWith("http://", true) || it.startsWith("https://", true))
        }
        // prima la guida del server, poi quella esterna: a parità di canale e orario resta il programma letto per primo
        return (pls.asSequence().mapNotNull { it.xmltvUrl() }.toList() + listOfNotNull(external)).distinct().take(4)
    }

    private fun deferMs(): Long = when {
        App.memTight -> 60_000L
        App.isPlayer(App.top) || Players.previewing || Sync.running -> 15_000L
        Net.type() == "none" -> 5 * 60_000L
        else -> 0L
    }

    private fun runPending() {
        val pls = synchronized(scheduleLock) { pending.orEmpty() }
        if (pls.isEmpty()) { finish(pls); return }
        val delay = deferMs()
        if (delay > 0) {
            worker.schedule({ runPending() }, delay, TimeUnit.MILLISECONDS)
            return
        }
        val sources = sourceUrls(pls)
        // nessuna guida del fornitore (lista M3U): resta la guida di riserva
        if (sources.isEmpty()) { scheduleBackup(pls, 4_000L); finish(pls); return }
        val now = System.currentTimeMillis()
        if (failedSignature == "unavailable" && now < retryAt) {
            worker.schedule({ runPending() }, retryAt - now, TimeUnit.MILLISECONDS)
            return
        }
        val ids = pls.map { it.id }
        val channels = try { Db.epgChannels(ids) } catch (_: Throwable) {
            failedSignature = "unavailable"
            retryAt = System.currentTimeMillis() + 30 * 60_000L
            worker.schedule({ runPending() }, 30, TimeUnit.MINUTES)
            return
        }
        val sourceSignature = try { sourceSignature(pls, channels) } catch (_: Throwable) {
            failedSignature = "unavailable"
            retryAt = System.currentTimeMillis() + 30 * 60_000L
            worker.schedule({ runPending() }, 30, TimeUnit.MINUTES)
            return
        }
        val signatureNow = System.currentTimeMillis()
        if (Store.epgSourceSig == sourceSignature && Store.epgBuiltAt > 0L && signatureNow - Store.epgBuiltAt < BUILD_MS) {
            prefetchCurrent(pls)
            scheduleBackup(pls, 4_000L)
            finish(pls)
            return
        }
        if (failedSignature == sourceSignature && signatureNow < retryAt) {
            worker.schedule({ runPending() }, retryAt - signatureNow, TimeUnit.MILLISECONDS)
            return
        }
        prefetchCurrent(pls)
        when (build(pls, sourceSignature, channels)) {
            BuildResult.DONE -> { failedSignature = ""; retryAt = 0L; scheduleBackup(pls, 4_000L); finish(pls) }
            BuildResult.ABORTED -> worker.schedule({ runPending() }, 30, TimeUnit.SECONDS)
            BuildResult.FAILED -> {
                failedSignature = sourceSignature
                retryAt = System.currentTimeMillis() + 30 * 60_000L
                worker.schedule({ runPending() }, 30, TimeUnit.MINUTES)
                scheduleBackup(pls, 4_000L) // la guida del fornitore non si è scaricata: la riserva copre i canali senza programmi
            }
        }
    }

    private fun finish(pls: List<Playlist>) {
        var rerun = false
        synchronized(scheduleLock) {
            val latest = pending
            if (latest != null && requestKey(latest) != requestKey(pls)) {
                rerun = true
            } else {
                pending = null
                queued = false
                checkedKey = requestKey(pls)
                checkedAt = System.currentTimeMillis()
            }
        }
        if (rerun) worker.execute { runPending() }
    }

    private fun sourceSignature(pls: List<Playlist>, channels: List<Db.EpgChannel>): String {
        val digest = MessageDigest.getInstance("SHA-256")
        fun add(value: String) {
            digest.update(value.toByteArray(Charsets.UTF_8))
            digest.update(0.toByte())
        }
        pls.take(4).forEach { p ->
            add(p.id)
            add(p.sig())
        }
        sourceUrls(pls).forEach(::add)
        channels.forEach { channel -> add(channel.epg.lowercase(java.util.Locale.ROOT)); add(EpgName.key(channel.name)) }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun build(pls: List<Playlist>, signature: String, channels: List<Db.EpgChannel>): BuildResult {
        val ids = pls.map { it.id }
        val sources = sourceUrls(pls)
        if (sources.isEmpty() || K.activeIds().toSet() != ids.toSet()) return BuildResult.DONE
        if (App.memTight || App.isPlayer(App.top) || Players.previewing || Sync.running) return BuildResult.ABORTED
        if (channels.isEmpty()) return BuildResult.FAILED
        val idMap = HashMap<String, MutableSet<String>>()
        val nameMap = HashMap<String, MutableSet<String>>()
        for (channel in channels) {
            val epgId = channel.epg.trim().lowercase(java.util.Locale.ROOT)
            val nameKey = EpgName.key(channel.name)
            val key = when {
                epgId.isNotEmpty() -> "id:$epgId"
                nameKey.isNotEmpty() -> "n:$nameKey"
                else -> ""
            }
            if (epgId.isNotEmpty()) idMap.getOrPut(epgId) { LinkedHashSet() }.add(key)
            if (nameKey.isNotEmpty()) nameMap.getOrPut(nameKey) { LinkedHashSet() }.add(key)
        }
        val index = XmltvIndex(idMap, nameMap)
        val now = System.currentTimeMillis() / 1000L
        val ahead = if (App.lite || App.memTight) 12L else 24L
        val limiter = XmltvLimiter(perChannel = 60, total = if (App.lite || App.memTight) 16_000 else 40_000)
        val db = try { Db.w } catch (_: Throwable) { return BuildResult.FAILED }
        var statement: android.database.sqlite.SQLiteStatement? = null
        var inTransaction = false
        var batch = 0
        var inserted = 0
        fun flush() {
            if (!inTransaction) return
            db.setTransactionSuccessful()
            db.endTransaction()
            inTransaction = false
            batch = 0
        }
        fun clearStage() {
            var opened = false
            try {
                if (inTransaction) { db.endTransaction(); inTransaction = false }
                db.beginTransactionNonExclusive()
                opened = true
                db.delete("prog_stage", null, null)
                db.setTransactionSuccessful()
            } catch (_: Throwable) {
            } finally {
                if (opened) try { db.endTransaction() } catch (_: Throwable) {}
            }
        }
        return try {
            db.beginTransactionNonExclusive()
            try {
                db.delete("prog_stage", null, null)
                db.setTransactionSuccessful()
            } finally { db.endTransaction() }
            val insertStatement = db.compileStatement("INSERT OR IGNORE INTO prog_stage(ch, start, stop, title, descr) VALUES(?,?,?,?,?)")
            statement = insertStatement
            for (url in sources) {
                if (App.memTight || App.isPlayer(App.top) || Players.previewing || Sync.running || K.activeIds().toSet() != ids.toSet()) throw XmltvAborted()
                if (Net.type() == "none") throw java.io.IOException()
                val connection = Http.open(url, connectMs = 15_000, readMs = 20_000)
                try {
                    if (connection.responseCode != 200) throw java.io.IOException()
                    val parser = android.util.Xml.newPullParser()
                    var checks = 0
                    XmltvParser.parse(
                        Http.stream(connection),
                        parser,
                        index,
                        now - 2 * 3600L,
                        now + ahead * 3600L,
                        stopAtLimit = { limiter.full },
                        shouldAbort = {
                            checks++
                            App.memTight || App.isPlayer(App.top) || Players.previewing || Sync.running ||
                                (checks % 128 == 0 && K.activeIds().toSet() != ids.toSet())
                        },
                    ) { programme ->
                        if (!limiter.accept(programme.channel, programme.start)) return@parse
                        if (!inTransaction) {
                            db.beginTransactionNonExclusive()
                            inTransaction = true
                        }
                        insertStatement.clearBindings()
                        insertStatement.bindString(1, programme.channel)
                        insertStatement.bindLong(2, programme.start)
                        insertStatement.bindLong(3, programme.stop)
                        insertStatement.bindString(4, programme.title)
                        insertStatement.bindString(5, programme.description)
                        if (insertStatement.executeInsert() >= 0L) { inserted++; batch++ }
                        if (batch >= BATCH) flush()
                    }
                    flush()
                } finally { connection.disconnect() }
                if (limiter.full) break
            }
            insertStatement.close()
            statement = null
            val aborted = App.memTight || App.isPlayer(App.top) || Players.previewing || Sync.running
            val activeChanged = K.activeIds().toSet() != ids.toSet()
            val sourcesChanged = !aborted && !activeChanged && sourceSignature(pls, Db.epgChannels(ids)) != signature
            if (inserted == 0 || aborted || activeChanged || sourcesChanged) {
                clearStage()
                return when {
                    aborted -> BuildResult.ABORTED
                    activeChanged -> BuildResult.DONE
                    sourcesChanged -> BuildResult.ABORTED
                    else -> BuildResult.FAILED
                }
            }
            db.beginTransactionNonExclusive()
            try {
                db.delete("prog", null, null)
                db.execSQL("INSERT INTO prog(ch, start, stop, title, descr) SELECT ch, start, stop, title, descr FROM prog_stage")
                db.delete("prog_stage", null, null)
                db.setTransactionSuccessful()
            } finally { db.endTransaction() }
            Store.saveEpgBuild(signature, System.currentTimeMillis())
            shortCache.evictAll()
            storeCache.evictAll()
            rowCache.evictAll()
            prefetchAt = 0L
            prefetchCurrent(pls)
            BuildResult.DONE
        } catch (_: XmltvAborted) {
            clearStage()
            BuildResult.ABORTED
        } catch (_: Throwable) {
            clearStage()
            BuildResult.FAILED
        } finally {
            try { statement?.close() } catch (_: Throwable) {}
            if (inTransaction) try { db.endTransaction() } catch (_: Throwable) {}
        }
    }

    private fun prefetchCurrent(pls: List<Playlist>) {
        val now = System.currentTimeMillis()
        if (now - prefetchAt < 60_000L || Store.epgBuiltAt <= 0L) return
        prefetchAt = now
        val ids = pls.map { it.id }
        if (K.activeIds().toSet() != ids.toSet()) return
        try {
            val channels = Db.epgChannels(ids)
            val channelCacheKeys = HashMap<String, MutableSet<String>>()
            val cacheKeys = LinkedHashSet<String>()
            for (channel in channels) {
                val keys = channelKeys(channel.epg, channel.name)
                val cacheKey = keys.joinToString("|")
                if (cacheKey.isEmpty()) continue
                cacheKeys.add(cacheKey)
                keys.forEach { key -> channelCacheKeys.getOrPut(key) { LinkedHashSet() }.add(cacheKey) }
            }
            cacheKeys.forEach { rowCache.put(it, Entry(now, emptyList())) }
            if (channelCacheKeys.isEmpty()) return
            val nowSeconds = now / 1000L
            Db.scanProgrammes(channelCacheKeys.keys.toList(), nowSeconds, nowSeconds + 1L, 5_000) { row ->
                if (row.start <= nowSeconds && row.stop > nowSeconds) {
                    val programme = Prog(row.title, "", row.start * 1000L, row.stop * 1000L)
                    channelCacheKeys[row.channel].orEmpty().forEach { key ->
                        rowCache.put(key, Entry(now, listOf(programme)))
                    }
                }
                true
            }
        } catch (_: Throwable) {
        }
    }
}
