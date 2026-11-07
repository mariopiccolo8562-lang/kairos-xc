package com.kairos.xc

import android.os.SystemClock
import android.util.Log
import org.json.JSONObject
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Collegamento continuo col pannello mentre l'app è aperta:
 * - battito ogni 60 s nei menu, ogni 20 s durante la visione (Utenti online, «Disconnetti»), sempre con «vis: true»
 * - app in sfondo (nessuna sua schermata visibile): il battito si ferma e parte un solo battito «vis: false» (state idle),
 *   così il pannello la mostra subito non in linea; al ritorno in primo piano un battito subito, senza aspettare il minuto
 * - se il pannello cambia qualcosa (DNS, permessi, VPN, limiti, messaggi) l'app si riallinea subito
 * - ogni 20 minuti si rilegge lo stato (scadenza, playlist)
 */
object Pulse {
    /** battito importante (ritorno in primo piano, inizio/fine visione) rimandato dal limite di frequenza */
    private val lateBeat = Runnable { beat(true) }
    /** «Disconnetti» da mostrare appena si apre una schermata */
    @Volatile var pendingKick = false
    class Watch(val state: String, val title: String, val ch: String, val chId: String, val group: String, val pl: String)

    @Volatile var watching: Watch? = null
    /** statistiche della visione in corso (le fornisce il player) */
    @Volatile var stats: (() -> JSONObject?)? = null
    @Volatile private var on = false
    /** ultimo battito accettato dal pannello (limite di frequenza): un battito fallito, un 429 o il «vado in sfondo» non lo toccano */
    @Volatile private var lastHb = 0L
    @Volatile private var lastSig = ""
    private var lastReg = SystemClock.elapsedRealtime()
    @Volatile private var kickAck = 0L
    private var pv = ""
    /** tornati in primo piano: il prossimo battito parte subito (anche prima del minuto dei menu) finché il pannello non lo accetta */
    @Volatile private var fgDue = false
    /** passaggi in primo piano: la risposta di un battito partito prima di andare in sfondo non vale per il ritorno */
    @Volatile private var fgSeq = 0
    /** il pannello ha risposto 429 con «wait»: prima di questo istante non si riprova */
    @Volatile private var waitUntil = 0L
    /** un battito alla volta e nell'ordine in cui partono: il «vado in sfondo» arriva sempre dopo quello già in viaggio */
    private val busy = AtomicBoolean(false)
    private val hbPool = Executors.newSingleThreadExecutor { r -> Thread(r, "kx-hb").apply { isDaemon = true } }

    private val tick = object : Runnable {
        override fun run() {
            if (!on) return
            beat(false)
            Updater.maybeCheck()
            App.main.postDelayed(this, 20_000)
        }
    }

    private fun later(ms: Long) {
        App.main.removeCallbacks(lateBeat)
        App.main.postDelayed(lateBeat, ms.coerceIn(1_000L, 60_000L))
    }

    fun foreground(fg: Boolean) {
        on = fg
        App.main.removeCallbacks(tick)
        App.main.removeCallbacks(lateBeat)
        if (fg) {
            fgSeq++
            fgDue = true
            App.main.postDelayed(tick, 1_000) // battito subito (entro 1-2 s), poi il giro solito ogni 20 s
            Updater.onForeground()
        } else {
            watching = null
            fgDue = false
            gone()
        }
    }

    /** chiamato dal player quando inizia o finisce una visione */
    fun watch(w: Watch?) {
        watching = w
        beat(true)
    }

    fun beat(force: Boolean) {
        if (!on) return // in sfondo vale solo il «vado in sfondo» (fine visione compresa: lo stato è già idle)
        val st = Panel.state ?: return
        if (!st.active) return
        val w = watching
        val sig = (w?.state ?: "idle") + "|" + (w?.chId ?: "")
        val now = SystemClock.elapsedRealtime()
        val due = force || fgDue
        if (!due && sig == lastSig && now - lastHb < (if (w == null) 60_000 else 20_000)) return
        // il pannello accetta al massimo un battito ogni 15 s (con un 429 dice quanto aspettare): quello importante parte appena si può
        val wait = maxOf(if (lastHb > 0) lastHb + 15_000 - now else 0L, waitUntil - now)
        if (wait > 0) { if (due) later(wait + 300); return }
        // il battito di prima è ancora in viaggio (rete lenta): niente doppioni, quello importante si riprova tra poco
        if (!busy.compareAndSet(false, true)) { if (due) later(5_000); return }
        val screen = App.top?.javaClass?.simpleName?.removeSuffix("Activity")?.lowercase() ?: ""
        val st2 = if (w != null) try { stats?.invoke() } catch (_: Exception) { null } else null
        val ack = kickAck
        val vpnOn = KxVpn.state == "on"
        val gen = fgSeq
        // thread proprio: il battito non resta in coda dietro alle letture dell'archivio né alla guida TV
        hbPool.execute {
            var r: Pair<Int, JSONObject?> = 0 to null
            try { r = Panel.heartbeat(w, ack, vpnOn, screen, st2, vis = true) } catch (t: Throwable) { Log.w("KairosXC", "hb", t) } finally { busy.set(false) }
            val (c, j) = r
            // dispositivo eliminato o non più riconosciuto (401/403): si rilegge subito lo stato
            if (c != 200 && Panel.hbDenied) { Panel.hbDenied = false; net { refresh() } }
            when {
                c == 200 && j != null -> {
                    // solo un battito accettato conta per il limite di frequenza
                    lastHb = now
                    lastSig = sig
                    waitUntil = 0L
                    if (gen == fgSeq) fgDue = false
                    net { handle(j) }
                }
                // troppo presto per il pannello: si riprova dopo «wait» secondi (o al giro successivo)
                c == 429 -> {
                    val s = j?.optLong("wait", 0L) ?: 0L
                    if (s > 0) {
                        waitUntil = SystemClock.elapsedRealtime() + s * 1000L
                        if (due) later(s * 1000L + 300)
                    }
                }
                // rete assente o errore: l'istante dell'ultimo battito resta quello di prima, al giro successivo si riprova
                else -> {}
            }
        }
    }

    /**
     * L'app è andata in sfondo: un solo battito «vis: false» con state idle (stessi campi di sempre), sul thread dei battiti
     * e senza aspettare la risposta. Non conta per il limite di frequenza, quindi non tocca l'istante dell'ultimo battito.
     */
    private fun gone() {
        val st = Panel.state ?: return
        if (!st.active) return
        val ack = kickAck
        val vpnOn = KxVpn.state == "on"
        hbPool.execute {
            try { Panel.heartbeat(null, ack, vpnOn, "", null, vis = false) } catch (t: Throwable) { Log.w("KairosXC", "hb", t) }
        }
    }

    private fun handle(j: JSONObject) {
        Remote.onBeat(j) // controllo remoto: impostazioni imposte e comandi
        val kick = j.optLong("kick", 0)
        if (kick > 0) {
            kickAck = kick
            if (kick != Store.kickSeen) {
                Store.kickSeen = kick
                ui { val a = App.top as? BaseActivity; if (a != null) a.onKicked() else pendingKick = true }
            }
        }
        val s = Panel.state ?: return
        var changed = false
        val vm = j.optString("vm", "")
        if (vm.isNotEmpty() && vm != s.vpnMode) changed = true
        val acc = s.account
        if (j.has("ms") && acc != null && j.optInt("ms") != acc.maxStreams) changed = true
        val pm = j.optString("pm", "")
        if (pm.isNotEmpty() && acc != null && pm.split("|").take(2).joinToString("|") != acc.plMode + "|" + acc.plDefault) changed = true
        if (j.has("ph")) {
            val off = try { val o = JSONObject(j.optString("ph").ifEmpty { "{}" }); o.keys().asSequence().toList().sorted().joinToString(",") } catch (_: Exception) { "" }
            if (off != s.offPerms()) changed = true
        }
        val nv = j.optString("pv", "")
        if (nv.isNotEmpty()) { if (pv.isNotEmpty() && nv != pv) changed = true; pv = nv }
        // manutenzione accesa dal pannello: si passa subito alla schermata di attesa
        if (j.optInt("mt", 0) == 1) changed = true
        val unseen = j.optInt("unseen", -1)
        if (unseen > K.unseenMessages().size) changed = true
        if (changed || SystemClock.elapsedRealtime() - lastReg > 20 * 60_000L) refresh()
    }

    /** rilegge lo stato dal pannello e riallinea l'app */
    fun refresh() {
        lastReg = SystemClock.elapsedRealtime()
        val s = try { Panel.register() } catch (_: Exception) { return }
        ui { applyState(s) }
    }

    fun applyState(s: State) {
        val a = App.top as? BaseActivity
        if (!s.active) { a?.goActivation(); return }
        // permesso «lite» cambiato dal pannello: la modalità leggera effettiva si ricalcola (come una scelta dalle Impostazioni)
        App.lite = App.I.detectLite()
        App.max = Store.liteMode == "max"
        Vpns.apply(a)
        if (App.top !is PlayerActivity) Sync.start() // mai durante la visione
        a?.stateChanged()
    }
}
