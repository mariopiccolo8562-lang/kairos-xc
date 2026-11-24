package com.kairos.xc

import android.os.Looper
import android.os.SystemClock
import org.json.JSONArray
import org.json.JSONObject

/**
 * App bloccata (23 novembre 2026). Il titolare, su una Fire TV Stick di 2ª generazione (AFTT, Android 5.1): scorrendo le
 * liste «resta ferma, non fa nulla», nemmeno Indietro, anche con la Leggerissima. Da lontano non si vede dove si ferma.
 *
 * Un filo a parte controlla ogni secondo che il thread principale risponda. Se resta fermo [LIMIT_MS], manda al pannello
 * («Errori delle app», tipo «crash») il punto esatto del codice dove è fermo: file e riga delle prime chiamate del thread
 * principale, la schermata aperta e lo stato del thread (BLOCKED = aspetta un lucchetto). Una volta per blocco, al massimo
 * [MAX_REPORTS] per avvio. Non cambia niente di quello che vede il cliente.
 */
object Watchdog {
    private const val LIMIT_MS = 8_000L
    private const val MAX_REPORTS = 3

    @Volatile private var pending = false
    @Volatile private var postedAt = 0L
    private var started = false
    private val ping = Runnable { pending = false }

    fun start() {
        if (started) return
        started = true
        Thread({ loop() }, "kx-watchdog").apply { isDaemon = true; priority = Thread.MIN_PRIORITY }.start()
    }

    private fun loop() {
        var reported = false
        var sent = 0
        while (sent < MAX_REPORTS) {
            try { Thread.sleep(1_000) } catch (_: InterruptedException) { return }
            val now = SystemClock.uptimeMillis()
            if (!pending) { // il controllo di prima è passato: il thread principale risponde
                reported = false
                pending = true
                postedAt = now
                App.main.post(ping)
                continue
            }
            if (reported || now - postedAt < LIMIT_MS) continue
            reported = true
            sent++
            try { report(now - postedAt) } catch (_: Throwable) {}
        }
    }

    private fun report(ms: Long) {
        val main = Looper.getMainLooper().thread
        val screen = App.top?.javaClass?.simpleName ?: "?"
        // 24 novembre 2026 (AFTT, XC 3.4.42: sempre «RUNNABLE» dentro funzioni di sistema che creano oggetti): memoria usata e massima,
        // per capire se la chiavetta è senza memoria (il sistema si ferma a liberarla di continuo)
        val rt = Runtime.getRuntime()
        val mem = "${(rt.totalMemory() - rt.freeMemory()) shr 20}/${rt.maxMemory() shr 20}MB"
        val (err, more) = describe(main.stackTrace, ms, main.state.name, screen, mem)
        val o = JSONObject().put("t", System.currentTimeMillis()).put("problem", "crash").put("action", "app bloccata")
            .put("outcome", "non risolto").put("err", err).put("title", more).put("k", "").put("engine", "watchdog")
            .put("dev", Line.device())
        Panel.errors(JSONArray().put(o))
    }

    /**
     * Testo per il pannello: [first] (al massimo 120 caratteri) con i secondi, lo stato e le prime righe con file e numero;
     * [second] (al massimo 120) con la schermata e le righe dopo. Le righe senza file (codice di sistema) si saltano.
     */
    fun describe(stack: Array<StackTraceElement>, ms: Long, state: String, screen: String, mem: String = ""): Pair<String, String> {
        // codice dell'app ridotto da R8: il file si chiama «SourceFile», quindi classe e metodo (si leggono con mapping.txt)
        val frames = stack.mapNotNull { e -> e.fileName?.let { if (it == "SourceFile") "${e.className.substringAfterLast('.')}.${e.methodName}:${e.lineNumber}" else "${it.removeSuffix(".kt").removeSuffix(".java")}:${e.lineNumber}" } }
            .fold(ArrayList<String>()) { acc, f -> if (acc.lastOrNull() != f) acc.add(f); acc }
        val head = "Bloccata ${ms / 1000}s ($state${if (mem.isNotEmpty()) ", $mem" else ""}): "
        val first = StringBuilder(head)
        var i = 0
        while (i < frames.size && first.length + frames[i].length + 3 <= 120) {
            if (i > 0) first.append(" < ")
            first.append(frames[i]); i++
        }
        val second = StringBuilder(screen.take(40))
        while (i < frames.size && second.length + frames[i].length + 3 <= 120) {
            second.append(" < ").append(frames[i]); i++
        }
        return first.toString() to second.toString()
    }
}
