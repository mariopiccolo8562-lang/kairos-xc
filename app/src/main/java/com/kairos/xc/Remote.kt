package com.kairos.xc

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import org.json.JSONObject
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLException
import kotlin.concurrent.thread
import kotlin.system.exitProcess

/**
 * Controllo remoto dal pannello Kairos Control.
 * - Impostazioni imposte (rc): arrivano con l'apertura (register) e con ogni battito; vincono su quelle scelte
 *   nell'app, che le mostra come «scelte dal fornitore». Oggi: modalità leggera, anteprima canali, aggiornamento in background.
 * - Comandi (cmds): riavvia, svuota cache e riavvia, aggiorna liste, apri impostazioni, aggiorna l'app, speed test, permesso
 *   per gli aggiornamenti, reimposta PIN, chiudi. L'app li esegue e li conferma al pannello (POST /api/player/cmd); il pannello
 *   li rimanda a ogni battito finché non arriva la conferma: doppioni, attese e comandi già eseguiti li tiene [CmdBook].
 *   Durante la visione liste, permesso e speed test aspettano la chiusura del lettore (lo speed test anche la fine
 *   dell'anteprima nella lista canali) senza conferma; poi partono e si confermano.
 *   I comandi sono invisibili al cliente: nessun avviso, nessun messaggio che citi il fornitore o il pannello. Si vede solo
 *   ciò che per natura si vede: riavvio, chiusura, impostazioni aperte, schermata del permesso di Android (con una riga
 *   neutra), proposta di aggiornamento e, dopo «Reimposta PIN», la scelta del PIN nuovo (il blocco non resta a 0000 in silenzio).
 * - L'app riporta le sue impostazioni attuali (cfg) e la versione della configurazione applicata (rcv).
 */
object Remote {
    private val playerValues = mapOf(
        "o.engLive" to listOf("auto", "exo", "vlc"),
        "o.engMovie" to listOf("auto", "exo", "vlc"),
        "o.engSeries" to listOf("auto", "exo", "vlc"),
        "o.hw" to listOf("auto", "on", "off"),
        "o.adaptQ" to listOf("true", "false"),
        "o.drainQ" to listOf("true", "false"),
        "p.quality" to listOf("auto", "max", "1080", "720", "480"),
        "p.buffer" to listOf("fast", "balanced", "stable"),
    )
    @Volatile private var cache: JSONObject? = null
    @Volatile private var cacheRaw: String? = null

    fun rc(): JSONObject {
        val raw = Store.rcJson
        if (raw != cacheRaw || cache == null) { cacheRaw = raw; cache = try { if (raw.isEmpty()) JSONObject() else JSONObject(raw) } catch (_: Exception) { JSONObject() } }
        return cache!!
    }
    private fun str(k: String): String? = rc().optString(k, "").ifEmpty { null }
    /** modalità leggera imposta dal pannello (auto | on | max | off), null = decide il cliente */
    val lite: String? get() = str("lite")?.takeIf { it == "auto" || it == "on" || it == "max" || it == "off" }
    /** anteprima canali imposta dal pannello (click | off) */
    val preview: String? get() = str("preview")?.takeIf { it == "click" || it == "off" }
    /** aggiornamento in background imposto dal pannello (on | off) */
    val bgUpdate: Boolean? get() = str("bgupd")?.let { if (it == "on") true else if (it == "off") false else null }
    /** aggiornamento obbligatorio deciso dal pannello: giorni di grazia dal primo avviso, null = il cliente può rimandare */
    val forceDays: Int? get() = when (str("force")) { "now" -> 0; "3d" -> 3; "7d" -> 7; else -> null }
    /** finestra «Privacy e condizioni»: skip = il fornitore ha deciso di non mostrarla */
    val privacy: String? get() = str("privacy")?.takeIf { it == "show" || it == "skip" }
    fun imposed(k: String): Boolean = str(k) != null
    fun value(k: String): String? {
        val values = playerValues[k] ?: return null
        return rc().opt(k)?.toString()?.takeIf { it in values }
    }
    fun allow(k: String): List<String>? {
        val values = playerValues[k] ?: return null
        val a = rc().optJSONArray("$k:allow") ?: return null
        return (0 until a.length()).mapNotNull { a.opt(it)?.toString()?.takeIf { v -> v in values } }.distinct().takeIf { it.isNotEmpty() }
    }
    fun eff(k: String, stored: String?, default: String): String {
        value(k)?.let { return it }
        val allowed = allow(k) ?: return stored?.takeIf { it in (playerValues[k] ?: emptyList()) } ?: default
        return stored?.takeIf { it in allowed } ?: default.takeIf { it in allowed } ?: allowed.first()
    }
    fun player(k: String, default: String): String = eff(k, Store.playerOption(k, default), default)
    fun engineKey(kind: String): String = when (kind) { "live" -> "o.engLive"; "series" -> "o.engSeries"; else -> "o.engMovie" }
    fun engine(kind: String): String = player(engineKey(kind), "auto")

    /** permesso di Android «Installa app sconosciute» (serve per installare gli aggiornamenti): nessuna app può darselo da sola */
    fun canInstall(): Boolean = Build.VERSION.SDK_INT < 26 || App.I.packageManager.canRequestPackageInstalls()
    /**
     * Schermata del permesso (o, se manca, quelle di sicurezza o generali di Android) con il suggerimento [hint] a video:
     * false = non se n'è aperta nessuna.
     */
    fun openInstallPerm(hint: String = "Attiva «Consenti da questa fonte», poi premi INDIETRO"): Boolean {
        Ui.toast(hint)
        val tries = listOf(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + App.I.packageName)), Intent(Settings.ACTION_SECURITY_SETTINGS), Intent(Settings.ACTION_SETTINGS))
        for (i in tries) { try { App.I.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); return true } catch (_: Exception) {} }
        return false
    }

    /** nuova configurazione dal pannello: si salva; se cambia la modalità leggera l'app si riavvia (mai durante la visione) */
    fun apply(rc: JSONObject?, rcv: String) {
        val raw = rc?.toString() ?: ""
        if (raw == Store.rcJson && rcv == Store.rcv) return
        val liteBefore = Store.liteMode
        Store.rcJson = raw
        Store.rcv = rcv
        cacheRaw = null
        ui {
            val wasLite = App.lite
            val wasMax = App.max
            App.lite = App.I.detectLite()
            App.max = Store.liteMode == "max"
            if (Store.liteMode != liteBefore && (App.lite != wasLite || App.max != wasMax) && !App.isPlayer(App.top)) restart()
            else (App.top as? BaseActivity)?.stateChanged()
        }
    }
    fun applyRaw(rcStr: String?, rcv: String) {
        if (rcStr == null) return
        apply(try { JSONObject(rcStr) } catch (_: Exception) { JSONObject() }, rcv)
    }

    /** impostazioni attuali dell'app, con le stesse chiavi del pannello */
    fun snapshot(): JSONObject = JSONObject()
        .put("lite", Store.liteMode)
        .put("liteOn", if (App.max) "max" else if (App.lite) "on" else "off")
        .put("preview", Store.preview)
        .put("bgupd", if (Store.bgUpdate) "on" else "off")
        .put("o.engLive", player("o.engLive", "auto"))
        .put("o.engMovie", player("o.engMovie", "auto"))
        .put("o.engSeries", player("o.engSeries", "auto"))
        .put("o.hw", player("o.hw", "auto"))
        .put("o.adaptQ", player("o.adaptQ", "true"))
        .put("o.drainQ", player("o.drainQ", "true"))
        .put("p.quality", player("p.quality", "auto"))
        .put("p.buffer", player("p.buffer", "balanced"))
        .put("inst", if (canInstall()) "on" else "off")

    /* ---------------- comandi ---------------- */

    /** riavvio e chiusura aspettano la conferma al pannello al massimo così */
    private const val ACK_WAIT_MS = 3_000L

    /** registro dei comandi (solo sul thread principale): gli esiti degli ultimi 30 tornano dal disco */
    private val book by lazy { CmdBook(Store.cmdDone) }
    /** conferme al pannello in fila su un thread loro: non aspettano dietro ad archivio, immagini o scaricamenti */
    private val ackPool = Executors.newSingleThreadExecutor { r -> Thread(r, "kx-cmd").apply { isDaemon = true } }
    /** riavvio o chiusura in corso: i comandi che arrivano ora li rimanda il pannello dopo la riapertura */
    private var closing = false
    /** PIN reimpostato fuori dai menu (durante la visione): la scelta del PIN nuovo compare alla chiusura del lettore */
    private var pinAsk = false
    /** comandi «speed» che aspettano la misura in corso (una sola alla volta) */
    private val speedIds = ArrayList<Long>()

    private fun now(): Long = SystemClock.elapsedRealtime()

    /** risposta del battito (thread di rete): configurazione e comandi */
    fun onBeat(j: JSONObject) {
        if (j.has("rc")) applyRaw(j.optString("rc"), j.optString("rcv"))
        // «Invia aggiornamento» dal pannello (o aggiornamento automatico scelto dal fornitore)
        val upd = j.optLong("upd", 0)
        if (upd > 0 && upd != Store.updSeen) { Store.updSeen = upd; Updater.fromPanel() }
        val cmds = j.optJSONArray("cmds") ?: return
        val list = ArrayList<Pair<Long, String>>()
        for (i in 0 until cmds.length()) { val c = cmds.optJSONObject(i) ?: continue; list.add(c.optLong("id") to c.optString("c")) }
        if (list.isNotEmpty()) ui { for ((id, c) in list) arrive(id, c) }
    }

    private fun arrive(id: Long, c: String) {
        if (closing) return
        when (book.arrive(id, c, blocked(c), now())) {
            CmdBook.Act.RUN -> start(id, c)
            // già eseguito (conferma persa o riavvio prima della conferma): si conferma soltanto, con lo stesso esito
            CmdBook.Act.ACK -> book.doneOf(id)?.let { send(it) }
            CmdBook.Act.WAIT, CmdBook.Act.SKIP -> {}
        }
    }

    /** una schermata dei menu è in primo piano (non il lettore) */
    private fun menuOn(): Boolean {
        val a = App.top
        return a != null && !App.isPlayer(a) && !a.isFinishing
    }

    /** un lettore è attivo: schermo intero o anteprima nella lista canali (la banda serve al video) */
    private fun playing(): Boolean = App.isPlayer(App.top) || Players.previewing

    /**
     * Il comando [c] deve aspettare: liste e permesso finché c'è il lettore, lo speed test anche durante l'anteprima nella
     * lista canali. Nel passaggio tra due schermate (nessuna in primo piano) si aspetta l'aggancio successivo.
     */
    private fun blocked(c: String): Boolean = when (c) {
        "sync" -> !menuOn()
        "perm" -> !canInstall() && !menuOn()
        "speed" -> !menuOn() || Players.previewing
        else -> false
    }

    /**
     * Visione finita (Pulse.watch(null)) o schermata dei menu di nuovo in primo piano (BaseActivity.onResume): poco dopo, se
     * si è ancora nei menu, partono i comandi rimasti in attesa e la scelta del PIN reimpostato durante la visione.
     */
    fun idle() {
        App.main.removeCallbacks(idleR)
        App.main.postDelayed(idleR, 800)
    }

    private val idleR = Runnable {
        if (!closing && menuOn()) {
            askPin()
            for ((id, c) in book.release(now()) { blocked(it) }) start(id, c)
        }
    }

    private fun start(id: Long, c: String) {
        try {
            when (c) {
                "reload" -> close(id, "riavvio") { restart() }
                "cache" -> close(id, "cache svuotata", { clearAll() }) { restart() }
                // prima il battito «vado in sfondo» (al massimo 1,5 s di attesa), poi si chiude
                "exit" -> close(id, "chiusa") { Pulse.leave(1_500) { quit() } }
                // in silenzio: niente avanzamento né errori a video (vedi Sync.quiet)
                "sync" -> { Sync.force(quiet = true); done(id, true, "liste in aggiornamento") }
                "settings" -> settings(id)
                "update" -> Updater.fromPanel { ok, info -> ui { done(id, ok, info) } }
                // la schermata di Android con una riga neutra, senza citare il pannello
                "perm" -> when {
                    canInstall() -> done(id, true, "permesso già concesso")
                    openInstallPerm("Attiva «Consenti da questa fonte» per gli aggiornamenti dell'app, poi premi INDIETRO") ->
                        done(id, true, "schermata del permesso aperta sulla TV")
                    else -> done(id, false, "schermata del permesso non disponibile su questo dispositivo")
                }
                "pin" -> pin(id)
                "speed" -> speed(id)
                else -> done(id, false, "comando non supportato")
            }
        } catch (t: Throwable) {
            Log.w("KairosXC", "cmd $c", t)
            closing = false
            done(id, false, "errore: " + (t.message ?: t.javaClass.simpleName))
        }
    }

    /** esito di [id]: registrato (sul disco gli ultimi 30) e confermato al pannello */
    private fun done(id: Long, ok: Boolean, info: String) {
        val d = book.finish(id, ok, info)
        Store.saveCmdDone(book.save())
        send(d)
    }

    /** conferma al pannello sul thread dei comandi; se non arriva il pannello rimanda il comando e si conferma di nuovo */
    private fun send(d: CmdBook.Done) {
        if (!book.sending(d.id)) return // conferma uguale già in viaggio
        ackPool.execute {
            try { Panel.cmdAck(d.id, d.ok, d.info) } catch (_: Throwable) {}
            ui { book.sent(d.id) }
        }
    }

    /**
     * Riavvio e chiusura: l'esito va sul disco subito (non in sospeso) e si aspetta la conferma al pannello, al massimo
     * [ACK_WAIT_MS]; poi [then] sul thread principale. Prima la conferma partiva in sottofondo e il processo si chiudeva dopo
     * 0,7-0,9 s: con la rete lenta non arrivava, il pannello rimandava il comando e l'app si riavviava di nuovo. Ora, se non
     * fa in tempo, il comando che torna dopo la riapertura si conferma soltanto. [work] (svuotare la cache) gira intanto.
     */
    private fun close(id: Long, info: String, work: (() -> Unit)? = null, then: () -> Unit) {
        closing = true
        App.main.removeCallbacks(idleR)
        val d = book.finish(id, true, info)
        val saved = book.save()
        thread(name = "kx-cmd-close", isDaemon = true) {
            val t0 = now()
            val acked = CountDownLatch(1)
            thread(name = "kx-cmd-ack", isDaemon = true) {
                try { Panel.cmdAck(d.id, d.ok, d.info) } catch (_: Throwable) {} finally { acked.countDown() }
            }
            try { work?.invoke() } catch (t: Throwable) { Log.w("KairosXC", "cmd", t) }
            // dopo [work]: la scrittura immediata porta sul disco anche quelle rimaste in sospeso (es. liste da riscaricare)
            try { Store.saveCmdDone(saved, now = true) } catch (_: Throwable) {}
            try { acked.await((ACK_WAIT_MS - (now() - t0)).coerceAtLeast(0L), TimeUnit.MILLISECONDS) } catch (_: InterruptedException) {}
            ui { then() }
        }
    }

    /** chiude e riapre l'app (stessa schermata iniziale di un avvio normale) */
    fun restart() {
        val i = App.I.packageManager.getLaunchIntentForPackage(App.I.packageName) ?: Intent(App.I, ActivationActivity::class.java)
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        try { App.I.startActivity(i) } catch (t: Throwable) { Log.w("KairosXC", "restart", t); closing = false; return }
        App.main.postDelayed({ exitProcess(0) }, 200)
    }

    /** chiusura: tutte le schermate, poi il processo (con una schermata rimasta aperta Android riaprirebbe l'app) */
    private fun quit() {
        val all = App.alive.toList()
        try { (App.top ?: all.lastOrNull())?.finishAffinity() } catch (_: Exception) {}
        for (a in all) try { if (!a.isFinishing) a.finish() } catch (_: Exception) {}
        App.main.postDelayed({ exitProcess(0) }, 300)
    }

    /** liste, immagini e file temporanei: tutto da riscaricare */
    private fun clearAll() {
        try { Db.clear(Db.w) } catch (_: Exception) {}
        try { Store.clearSync() } catch (_: Exception) {}
        try { Img.glide().clearDiskCache() } catch (_: Throwable) {}
        try { App.I.cacheDir.deleteRecursively() } catch (_: Exception) {}
    }

    private fun settings(id: Long) {
        val top = App.top
        if (top is SettingsActivity && !top.isFinishing) { done(id, true, "impostazioni già aperte"); return }
        if (top != null && App.isPlayer(top)) top.finish() // la visione si chiude, come in Kairos Pro
        App.I.startActivity(Intent(App.I, SettingsActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        done(id, true, "impostazioni aperte")
    }

    /**
     * PIN del controllo genitori di nuovo 0000, senza avvisi sul fornitore; ma il blocco non resta a 0000 in silenzio: si
     * chiede subito di sceglierne uno nuovo (la schermata del primo avvio, con «Più tardi»), nei menu subito, durante la
     * visione alla chiusura del lettore (vedi [idle]). Come Kairos Pro.
     */
    private fun pin(id: Long) {
        Parental.reset()
        done(id, true, "PIN riportato a 0000")
        pinAsk = true
        App.main.postDelayed({ askPin() }, 1_000)
    }

    /** scelta del PIN nuovo (Parental.firstRun) solo in una schermata dei menu, mai sopra il lettore */
    private fun askPin() {
        if (!pinAsk || closing || !menuOn()) return
        val a = App.top ?: return
        pinAsk = false
        Parental.firstRun(a, true)
    }

    /**
     * Speed test in sottofondo, senza schermate né avvisi: una misura alla volta (un secondo comando arrivato intanto riceve
     * lo stesso risultato). Se durante la misura il cliente avvia una visione, la misura si ferma e il comando torna in attesa
     * della fine della visione, senza conferma intermedia.
     */
    private fun speed(id: Long) {
        speedIds.add(id)
        if (speedIds.size > 1) return
        thread(name = "kx-speed", isDaemon = true) {
            val out: Any = try {
                Vpns.awaitForced() // VPN «sempre attiva»: si misura la strada che fanno i video
                SpeedTest.measure { playing() }
            } catch (t: Throwable) { t }
            ui { speedEnd(out) }
        }
    }

    private fun speedEnd(out: Any) {
        val ids = speedIds.toList()
        speedIds.clear()
        when (out) {
            is SpeedTest.Result -> {
                SpeedTest.publish(out)
                val txt = SpeedCalc.summary(out.dl, out.ul, out.ping, out.jitter, out.server)
                for (id in ids) done(id, true, txt)
                Pulse.beat(true) // il risultato arriva subito al pannello (oggetto «speed» del battito), come in Kairos Pro
            }
            // visione iniziata durante la misura: si rifà quando finisce, la conferma arriva dopo
            is SpeedTest.Paused -> for (id in ids) book.requeue(id, "speed")
            is Throwable -> {
                val why = why(out)
                for (id in ids) done(id, false, "speed test non riuscito: $why")
            }
        }
    }

    /** motivo leggibile di un errore di rete per la conferma al pannello */
    private fun why(t: Throwable): String {
        var e: Throwable? = t
        while (e != null) {
            when (e) {
                is UnknownHostException -> return "nome del server non trovato (DNS o rete assente)"
                is SocketTimeoutException -> return "il server di misura non risponde (tempo scaduto)"
                is ConnectException, is NoRouteToHostException -> return "connessione al server di misura non riuscita"
                is SSLException -> return "connessione sicura non riuscita (data e ora del dispositivo?)"
            }
            e = e.cause
        }
        return t.message?.take(120) ?: t.javaClass.simpleName
    }
}
