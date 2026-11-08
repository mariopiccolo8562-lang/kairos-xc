package com.kairos.xc

import android.app.AlertDialog
import android.content.Context
import android.content.SharedPreferences
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.os.Build
import android.os.Process
import android.os.SystemClock
import android.util.Log
import org.json.JSONObject
import java.net.InetAddress
import java.net.URL
import java.util.Calendar
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * Funzioni intelligenti di Kairos XC (pannello › Controllo remoto › «Funzioni intelligenti», profilo Kairos XC o singolo
 * cliente; spente di serie). Budget di leggerezza:
 * - niente nel percorso d'avvio: il file «smart» si apre la prima volta dopo 60 s, solo nei menu, su un thread a bassa priorità;
 * - un lavoro alla volta ([Budget.next]), mai durante la visione (né con l'anteprima nella lista canali);
 * - nessuna lista in memoria: archivio SQLite con LIMIT; abitudini e promemoria con un tetto di [HabitBook.MAX] voci.
 * Spente: Kairos XC fa quello che faceva prima; restano solo le parti che non costano nulla e correggono soltanto
 * (decoder letti una volta e versione giusta del canale, canali non funzionanti in fondo alla lista).
 */
object Smart {
    fun on(): Boolean = Remote.smart == "on"

    /* ---------------- file piccolo (SharedPreferences «smart»), separato da quello delle impostazioni ---------------- */
    @Volatile var loaded = false
        private set
    private val sp: SharedPreferences by lazy { App.I.getSharedPreferences("smart", Context.MODE_PRIVATE).also { it.all; loaded = true } }

    /** solo da un thread di lavoro: alla prima chiamata il file si legge dal disco */
    fun prefs(): SharedPreferences = sp

    /* ---------------- lavoro in sottofondo ---------------- */
    private val exec = Executors.newSingleThreadExecutor { r ->
        Thread({ try { Process.setThreadPriority(Process.THREAD_PRIORITY_LOWEST) } catch (_: Throwable) {}; r.run() }, "kx-smart").apply { isDaemon = true; priority = Thread.MIN_PRIORITY }
    }
    private val busy = AtomicBoolean(false)
    @Volatile private var lastCheck = 0L
    @Volatile private var lastGuide = 0L
    @Volatile private var lastRemind = 0L

    /** una schermata dei menu in primo piano: niente lettore, niente anteprima nella lista canali */
    fun menu(): Boolean {
        val a = App.top
        return a is BaseActivity && !App.isPlayer(a) && !a.isFinishing && !Players.previewing && Pulse.inFront
    }

    fun playing(): Boolean = App.isPlayer(App.top) || Players.previewing

    private fun dirty(): Boolean = HabitStore.dirty() || Engines.dirty || Zap.dirty

    /** memorie delle funzioni accese (lettore imparato, zapping, auto-controlli, abitudini) già lette dal file */
    @Volatile private var mem = false

    /** file letto (e, con le funzioni accese, anche le loro memorie): se no il prossimo lavoro è leggerlo */
    private fun ready(): Boolean = loaded && Dead.ready && (mem || !on())

    /** dal battito (ogni 20 s in primo piano), thread principale: promemoria dovuti e, se tocca, un lavoro */
    fun tick() {
        try { Reminders.showDue() } catch (t: Throwable) { Log.w("KairosXC", "rem", t) }
        if (busy.get()) return
        val now = SystemClock.elapsedRealtime()
        val s = Budget.S(
            now, App.bootAt, menu(), playing(), on(), Line.device(), ready(), DevCaps.known, dirty(), Dead.dirty,
            lastCheck, lastGuide, lastRemind, Store.autoRem, HabitStore.chans(), HabitStore.follows(),
        )
        val job = Budget.next(s) ?: return
        if (!busy.compareAndSet(false, true)) return
        exec.execute {
            try { run(job) } catch (t: Throwable) { Log.w("KairosXC", "smart $job", t) } finally { busy.set(false) }
        }
    }

    private fun run(j: Budget.Job) {
        val t = SystemClock.elapsedRealtime()
        when (j) {
            Budget.Job.LOAD -> { prefs(); Dead.load(); DevCaps.load(); if (on()) { Engines.load(); Zap.load(); AutoCheck.load(); HabitStore.book(); mem = true } }
            Budget.Job.CAPS -> DevCaps.read()
            Budget.Job.FLUSH -> { Dead.save(); if (on()) { HabitStore.commit(); Engines.save(); Zap.save() } }
            // interrotto da una visione: si riprova tra 2 minuti, non tra mezz'ora
            Budget.Job.CHECK -> lastCheck = if (AutoCheck.run()) t else t - Budget.checkEvery(Line.device()) + 2 * 60_000L
            Budget.Job.GUIDE -> { Guide.refresh(); lastGuide = t }
            Budget.Job.REMIND -> { Reminders.compute(); lastRemind = t }
        }
    }

    /**
     * App appena andata in sfondo (nessuna schermata visibile, lettore già chiuso): abitudini e memorie del lettore ancora in
     * sospeso vanno sul disco subito, altrimenti Android potrebbe chiudere l'app prima del prossimo giro nei menu.
     */
    fun background() {
        if (!ready() || !(Dead.dirty || (on() && dirty())) || !busy.compareAndSet(false, true)) return
        exec.execute { try { run(Budget.Job.FLUSH) } catch (t: Throwable) { Log.w("KairosXC", "smart flush", t) } finally { busy.set(false) } }
    }

    /** funzioni spente dal pannello: niente più promemoria in attesa; accese: le memorie si leggono al prossimo giro nei menu */
    fun stateChanged() {
        if (!on()) Reminders.clear()
    }
}

/** Decoder hardware del dispositivo: letti una volta (dopo i primi 60 s, nei menu) e salvati; di nuovo solo se Android si aggiorna */
object DevCaps {
    @Volatile var caps: Caps? = null
        private set
    @Volatile var known = false
        private set

    fun load() {
        val p = Smart.prefs()
        if (p.getString("capsFp", "") != Build.FINGERPRINT) return
        caps = Caps.load(p.getString("caps", ""))
        known = true
    }

    fun read() {
        val c = try { detect() } catch (_: Throwable) { null }
        caps = c
        known = true
        Smart.prefs().edit().putString("caps", c?.save() ?: "").putString("capsFp", Build.FINGERPRINT).apply()
    }

    private fun software(ci: MediaCodecInfo): Boolean {
        if (Build.VERSION.SDK_INT >= 29) return ci.isSoftwareOnly
        val n = ci.name.lowercase(Locale.ROOT)
        return n.startsWith("omx.google.") || n.startsWith("c2.android.") || n.contains(".sw.") || n.contains("ffmpeg")
    }

    /** come la Kairos Pro: solo i decoder hardware (quelli software sulle chiavette non reggono HEVC o 4K) */
    private fun detect(): Caps {
        var hevc = false; var b10 = false; var uhd = false; var fps60 = false; var maxH = 0
        for (ci in MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos) {
            if (ci.isEncoder || software(ci)) continue
            for (t in ci.supportedTypes) {
                val type = t.lowercase(Locale.ROOT)
                if (type != "video/hevc" && type != "video/avc") continue
                val caps = try { ci.getCapabilitiesForType(t) } catch (_: Throwable) { null } ?: continue
                val vc = caps.videoCapabilities ?: continue
                try { maxH = maxOf(maxH, vc.supportedHeights.upper) } catch (_: Throwable) {}
                try { if (vc.isSizeSupported(3840, 2160)) uhd = true } catch (_: Throwable) {}
                try { if (vc.areSizeAndRateSupported(1920, 1080, 50.0)) fps60 = true } catch (_: Throwable) {}
                if (type == "video/hevc") {
                    hevc = true
                    if (caps.profileLevels.any { it.profile == MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10 || it.profile == MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10HDR10 }) b10 = true
                }
            }
        }
        val disp = try {
            if (Build.VERSION.SDK_INT >= 23) {
                val dm = App.I.getSystemService(Context.DISPLAY_SERVICE) as android.hardware.display.DisplayManager
                dm.getDisplay(android.view.Display.DEFAULT_DISPLAY)?.supportedModes?.maxOfOrNull { minOf(it.physicalWidth, it.physicalHeight) } ?: 0
            } else 0
        } catch (_: Throwable) { 0 }
        return Caps(hevc, b10, uhd, fps60, maxH, disp)
    }
}

/** Lettore imparato per formato (solo con «Automatico» e funzioni intelligenti accese): in memoria, sul disco nei menu */
object Engines {
    @Volatile private var book: EngineBook? = null
    @Volatile var dirty = false
        private set

    fun load() { if (book == null) book = EngineBook(Smart.prefs().getString("eng", "") ?: "", BuildConfig.VERSION_CODE) }

    fun ok(engine: String, key: String) { val b = book ?: return; if (!Smart.on() || key.isEmpty()) return; synchronized(b) { b.ok(engine, key) }; dirty = true }
    fun fail(engine: String, key: String) { val b = book ?: return; if (!Smart.on() || key.isEmpty()) return; synchronized(b) { b.fail(engine, key) }; dirty = true }
    private fun best(key: String): String? { val b = book ?: return null; return synchronized(b) { b.best(key) } }
    fun summary(): String { val b = book ?: return ""; return synchronized(b) { b.summary() } }

    fun save() {
        val b = book ?: return
        if (!dirty) return
        dirty = false
        Smart.prefs().edit().putString("eng", synchronized(b) { b.save() }).apply()
    }

    /** lettore con cui aprire un contenuto di [kind] (live | movie | series): exo | vlc */
    fun pick(kind: String, url: String, title: String): String = EngineChoice.pick(Remote.engine(kind), Smart.on(), best(EngineBook.key(url, title)))

    /** il lettore aperto è quello imparato (non scelto dal cliente o dal pannello): se fallisce si torna da soli a ExoPlayer */
    fun learnedVlc(kind: String, url: String, title: String): Boolean = Remote.engine(kind) == "auto" && pick(kind, url, title) == "vlc"
}

/**
 * Canali non funzionanti per nessuno (pannello › Errori delle app › «Canali non funzionanti»), stesso contratto della Kairos Pro:
 * l'impronta [v] va nel battito (dv), l'elenco arriva solo quando cambia, i canali vanno in fondo alla lista con
 * «non disponibile» (Db.live). Sempre attivo: non costa nulla e corregge soltanto.
 */
object Dead {
    @Volatile private var book: DeadBook? = null
    /** elenco arrivato prima che il file fosse letto (primi 60 s): si applica alla lettura */
    @Volatile private var pending: JSONObject? = null
    private var savedAt = 0L
    /** elenco nuovo (o confermato da molto) da scrivere sul disco: nei menu, mai durante la visione */
    @Volatile var dirty = false
        private set
    /** cambia con l'elenco: le liste aperte si rileggono */
    @Volatile var gen = 0
        private set
    val ready: Boolean get() = book != null
    /** impronta per il battito; null = file non ancora letto (allora non si manda, come le app vecchie) */
    val v: String? get() = book?.v

    /** risposta del battito (thread di rete) */
    fun offer(d: JSONObject) {
        pending = d
        if (book != null) apply()
    }

    /** battito accettato senza elenco nuovo: quello che c'è vale ancora (sul disco al più ogni 6 ore) */
    fun confirm() {
        val b = book ?: return
        val now = System.currentTimeMillis()
        b.at = now
        if (b.all.isNotEmpty() && now - savedAt > 6 * 3_600_000L) dirty = true
    }

    /** lavoro FLUSH (thread di lavoro) */
    fun save() {
        val b = book ?: return
        if (!dirty) return
        dirty = false
        savedAt = System.currentTimeMillis()
        Smart.prefs().edit().putString("dead", b.save()).apply()
    }

    /** thread di lavoro */
    fun load() {
        if (book == null) synchronized(this) { if (book == null) book = DeadBook.load(Smart.prefs().getString("dead", "")) }
        apply()
    }

    @Synchronized private fun apply() {
        val d = pending ?: return
        pending = null
        val a = d.optJSONArray("l")
        val l = ArrayList<String>()
        if (a != null) for (i in 0 until minOf(a.length(), DeadBook.MAX)) a.optString(i).takeIf { it.isNotEmpty() }?.let { l.add(it.lowercase(Locale.ROOT)) }
        book = DeadBook(d.optString("v").take(16), l, System.currentTimeMillis())
        gen++
        dirty = true
    }

    /** numero del flusso nelle liste M3U di tipo Xtream (ultimo pezzo dell'indirizzo, senza estensione), calcolato da SQLite */
    private const val SEG = "replace(url, rtrim(url, replace(url, '/', '')), '')"
    private const val M3U_NUM = "(CASE WHEN instr($SEG, '.') > 0 THEN substr($SEG, 1, instr($SEG, '.') - 1) ELSE $SEG END)"

    /** condizione SQL dei canali non funzionanti dei portali [pls] (null = nessuno, o file non ancora letto: primi 60 s) */
    fun where(pls: List<String>): String? {
        val b = book ?: return null
        if (!b.fresh(System.currentTimeMillis())) return null
        val parts = ArrayList<String>()
        for (id in pls) {
            val p = K.pl(id) ?: continue
            val sids = b.sids(Portals.cands(p).map { Ck.hostKey(it) }.filter { it.isNotEmpty() })
            if (sids.isEmpty()) continue
            // solo cifre (controllate da DeadBook): niente da temere nel testo della query
            val list = sids.joinToString(",") { "'$it'" }
            parts.add("(pl = '" + id.replace("'", "''") + "' AND " + (if (p.isXtream) "sid" else M3U_NUM) + " IN ($list))")
        }
        return if (parts.isEmpty()) null else parts.joinToString(" OR ")
    }
}

/** Cambio canale: risolve in anticipo il DNS del canale più probabile (al più una volta al minuto per server). Niente secondo lettore */
object Zap {
    @Volatile private var book: ZapBook? = null
    @Volatile var dirty = false
        private set
    private val warmed = HashMap<String, Long>()

    fun load() { if (book == null) book = ZapBook(Smart.prefs().getString("zap", "") ?: "") }

    fun learn(from: String, to: String) { val b = book ?: return; if (!Smart.on()) return; synchronized(b) { b.learn(from, to) }; dirty = true }
    fun predict(from: String): String? { val b = book ?: return null; return synchronized(b) { b.predict(from) } }

    fun save() {
        val b = book ?: return
        if (!dirty) return
        dirty = false
        Smart.prefs().edit().putString("zap", synchronized(b) { b.save() }).apply()
    }

    /** solo la risoluzione del nome del server: nessun dato del canale, nessuna connessione al server della linea */
    fun warm(url: String) {
        if (!Smart.on()) return
        val host = try { URL(url).host ?: "" } catch (_: Exception) { "" }
        if (host.isEmpty()) return
        val now = SystemClock.elapsedRealtime()
        synchronized(warmed) {
            if (now - (warmed[host] ?: -120_000L) < 60_000L) return
            warmed[host] = now
            if (warmed.size > 30) warmed.keys.firstOrNull()?.let { warmed.remove(it) }
        }
        thread(isDaemon = true, name = "kx-dns", priority = Thread.MIN_PRIORITY) { try { InetAddress.getAllByName(host) } catch (_: Throwable) {} }
    }
}

/** Abitudini per profilo: il lettore annota in memoria, il libro si aggiorna e si salva nei menu (lavoro FLUSH) */
object HabitStore {
    /** [kind] l = canale (3 minuti di fila), m = film, s = serie (5 minuti); [cat] = categoria; [prog] = programma in onda */
    class Note(val prof: String, val kind: Char, val k: String, val name: String, val cat: String, val prog: String, val t: Long)

    private val pending = ArrayList<Note>()
    @Volatile private var book: HabitBook? = null
    @Volatile private var prof = "\u0000"
    @Volatile private var changed = false

    fun note(n: Note) { if (!Smart.on()) return; synchronized(pending) { if (pending.size < 30) pending.add(n) } }
    fun dirty(): Boolean = changed || synchronized(pending) { pending.isNotEmpty() }

    /** solo memoria (anche dal thread principale) */
    fun chans(): Boolean = book?.c?.isNotEmpty() == true
    fun follows(): Boolean = book?.follows() == true

    private fun keyOf(p: String) = "hab_" + p.ifEmpty { "_" }.take(40)

    /** libro del profilo in uso; thread di lavoro */
    @Synchronized fun book(): HabitBook {
        val p = Store.profileId
        val b = book
        if (b != null && prof == p) return b
        val nb = HabitBook.load(Smart.prefs().getString(keyOf(p), ""))
        book = nb
        prof = p
        return nb
    }

    @Synchronized fun markShown(key: String) { book().markShown(key, System.currentTimeMillis()); changed = true }
    @Synchronized fun mute(key: String) { book().mute(key); changed = true }

    /** annotazioni del lettore nel libro (categorie e sport dall'archivio, una riga ciascuna), poi sul disco */
    @Synchronized fun commit() {
        val list = synchronized(pending) { ArrayList(pending).also { pending.clear() } }
        val cur = book()
        for (n in list) {
            val b = if (n.prof == prof) cur else continue // profilo cambiato nel frattempo: l'annotazione si lascia
            val c = Calendar.getInstance().apply { timeInMillis = n.t }
            val slot = HabitBook.slot(c)
            when (n.kind) {
                'l' -> {
                    val catName = catName(n.cat)
                    b.watchedChannel(n.k, n.name, HabitBook.RX_SPORT_CH.containsMatchIn("$catName ${n.name}"), n.prog, n.t, slot, HabitBook.day(c))
                }
                else -> {
                    val table = if (n.kind == 's') "series" else "vod"
                    val cat = n.cat.ifEmpty { try { Db.r.rawQuery("SELECT cat FROM $table WHERE k = ? LIMIT 1", arrayOf(n.k)).use { q -> if (q.moveToFirst()) q.getString(0) ?: "" else "" } } catch (_: Exception) { "" } }
                    if (cat.isNotEmpty()) b.watchedVod(n.kind + "|" + cat, catName(cat), n.t, slot)
                }
            }
            changed = true
        }
        if (!changed) return
        changed = false
        val e = Smart.prefs().edit().putString(keyOf(prof), cur.save())
        // al massimo 5 profili sul dispositivo: via le abitudini di quelli non usati da più tempo
        val order = (listOf(prof) + (Smart.prefs().getString("habProfs", "") ?: "").split('\u0001').filter { it != prof && it != "\u0000" }).take(6)
        if (order.size > 5) e.remove(keyOf(order[5]))
        e.putString("habProfs", order.take(5).joinToString("\u0001")).apply()
    }

    private fun catName(id: String): String = if (id.isEmpty()) "" else try {
        Db.r.rawQuery("SELECT name FROM cat WHERE id = ? LIMIT 1", arrayOf(id)).use { q -> if (q.moveToFirst()) q.getString(0) ?: "" else "" }
    } catch (_: Exception) { "" }
}

/** Promemoria automatici: calcolati dalla guida piccola al massimo ogni 15 minuti, proposti nei menu con «Guarda» */
object Reminders {
    class Due(val c: Remind.Cand, val chName: String)

    @Volatile private var list: List<Due> = emptyList()
    private var dlg: AlertDialog? = null

    fun clear() { list = emptyList() }

    /** lavoro REMIND (thread di lavoro) */
    fun compute() {
        if (!Smart.on() || !Store.autoRem) { list = emptyList(); return }
        val now = System.currentTimeMillis()
        val b = HabitStore.book()
        val cands = Remind.candidates(b, Guide.upcoming(now - Remind.AFTER, now + Remind.AHEAD), now)
        list = cands.take(5).map { c -> Due(c, b.c[c.ch]?.n ?: (try { Db.oneByK("live", c.ch)?.s("name") } catch (_: Exception) { null } ?: "")) }
    }

    /** dal battito (thread principale): solo nei menu, un avviso alla volta */
    fun showDue() {
        if (list.isEmpty()) return
        if (!Smart.on() || !Store.autoRem) { list = emptyList(); return }
        if (!Smart.menu() || dlg?.isShowing == true) return
        val a = App.top as? BaseActivity ?: return
        val now = System.currentTimeMillis()
        list = list.filter { !it.c.gone(now) }
        val d = list.firstOrNull { it.c.due(now) } ?: return
        list = list - d
        bg { HabitStore.markShown(d.c.key) }
        val soon = d.c.start > now
        val msg = d.c.title + "\n\n" + (if (soon) "Inizia alle " else "Iniziato alle ") + Ui.hm(d.c.start) +
            (if (d.chName.isNotEmpty()) " su " + d.chName else "") + " · te lo proponiamo perché lo guardi spesso"
        dlg = try {
            AlertDialog.Builder(a).setTitle(if (soon) "🔔 Tra poco" else "🔔 È appena iniziato").setMessage(msg)
                .setPositiveButton("▶ Guarda") { _, _ -> watch(a, d.c.ch) }
                .setNegativeButton("Ignora", null)
                .setNeutralButton("Non avvisarmi più") { _, _ -> bg { HabitStore.mute(d.c.off) }; Ui.toast("Non ti avviseremo più per questo") }
                .show()
        } catch (_: Exception) { null }
    }

    private fun watch(a: BaseActivity, k: String) {
        bg {
            val r = try { Db.oneByK("live", k) } catch (_: Exception) { null }
            ui {
                if (a.isFinishing) return@ui
                if (r == null) { Ui.toast("Canale non più disponibile"); return@ui }
                Play.openLive(a, r, r.s("cat"))
            }
        }
    }
}
