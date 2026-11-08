package com.kairos.xc

import java.util.Locale
import kotlin.math.abs

/**
 * Registro dei comandi del controllo remoto (pannello → Controllo remoto → Comandi). Logica pura, senza Android: la provano
 * i test JVM (app/src/test). Il pannello consegna i comandi nel battito e li rimanda a ogni battito finché l'app non li
 * conferma, quindi:
 * - un comando già in corso o già in attesa non riparte: i doppioni si ignorano;
 * - un comando che deve aspettare la fine della visione resta in attesa, senza conferma, e parte quando il lettore si chiude;
 * - un comando già eseguito che torna (conferma persa, riavvio prima della conferma) si conferma soltanto, con lo stesso
 *   esito, senza rieseguirlo: gli ultimi [keep] esiti restano sul disco ([save], poi di nuovo nel costruttore).
 * Un comando «in corso» da più di [staleMs] (lavoro rimasto appeso) si considera perso e può ripartire.
 * Da usare su un solo thread (quello principale).
 */
class CmdBook(saved: String = "", private val keep: Int = 30, private val staleMs: Long = 5 * 60_000L) {
    class Done(val id: Long, val ok: Boolean, val info: String)

    enum class Act {
        /** da eseguire adesso: ora risulta «in corso» */
        RUN,
        /** messo in attesa della fine della visione, senza conferma */
        WAIT,
        /** niente da fare: già in corso, già in attesa o conferma già in viaggio */
        SKIP,
        /** già eseguito: si conferma di nuovo con l'esito di [doneOf] */
        ACK,
    }

    /** comandi in corso: id → istante di partenza (orologio monotono, ms) */
    private val running = HashMap<Long, Long>()
    /** comandi rimandati alla fine della visione, nell'ordine di arrivo: id → comando */
    private val waiting = LinkedHashMap<Long, String>()
    /** ultimi esiti, dal più vecchio al più recente */
    private val past = ArrayDeque<Done>()
    /** conferme in viaggio: la stessa non parte due volte */
    private val acking = HashSet<Long>()

    init { load(saved) }

    /**
     * Comando [c] con [id] arrivato col battito all'istante [now]. [blocked] = in questo momento il comando deve aspettare
     * (visione in corso). La risposta dice che cosa fare (vedi [Act]).
     */
    fun arrive(id: Long, c: String, blocked: Boolean, now: Long): Act {
        if (id <= 0 || c.isEmpty()) return Act.SKIP
        if (doneOf(id) != null) return if (id in acking) Act.SKIP else Act.ACK
        val t0 = running[id]
        if (t0 != null) {
            if (now - t0 < staleMs) return Act.SKIP
            running.remove(id) // in corso da troppo: perso, si rifà
        }
        if (blocked) {
            if (waiting.containsKey(id)) return Act.SKIP
            waiting[id] = c
            return Act.WAIT
        }
        waiting.remove(id)
        running[id] = now
        return Act.RUN
    }

    /** comandi in attesa che adesso possono partire ([blocked] = deve ancora aspettare): diventano «in corso», in ordine di arrivo */
    fun release(now: Long, blocked: (String) -> Boolean): List<Pair<Long, String>> {
        if (waiting.isEmpty()) return emptyList()
        val out = ArrayList<Pair<Long, String>>()
        val it = waiting.entries.iterator()
        while (it.hasNext()) {
            val e = it.next()
            if (blocked(e.value)) continue
            it.remove()
            running[e.key] = now
            out.add(e.key to e.value)
        }
        return out
    }

    /** comando in corso che torna in attesa (es. una visione iniziata durante lo speed test): riparte quando finisce */
    fun requeue(id: Long, c: String) {
        if (running.remove(id) != null) waiting[id] = c
    }

    /** esito di [id]: non è più in corso né in attesa; restano gli ultimi [keep] esiti */
    fun finish(id: Long, ok: Boolean, info: String): Done {
        running.remove(id)
        waiting.remove(id)
        past.removeAll { it.id == id }
        val d = Done(id, ok, clean(info))
        past.addLast(d)
        while (past.size > keep) past.removeFirst()
        return d
    }

    fun doneOf(id: Long): Done? = past.lastOrNull { it.id == id }
    fun isRunning(id: Long): Boolean = running.containsKey(id)
    fun isWaiting(id: Long): Boolean = waiting.containsKey(id)

    /** parte la conferma di [id]: false = ce n'è già una in viaggio */
    fun sending(id: Long): Boolean = acking.add(id)

    /** conferma di [id] conclusa (arrivata o no: se non è arrivata il pannello rimanda il comando e si conferma di nuovo) */
    fun sent(id: Long) {
        acking.remove(id)
    }

    /** forma salvata: una riga per esito, «id TAB 1|0 TAB testo» */
    fun save(): String = past.joinToString("\n") { it.id.toString() + "\t" + (if (it.ok) "1" else "0") + "\t" + it.info }

    private fun load(s: String) {
        if (s.isEmpty()) return
        for (line in s.split('\n')) {
            val p = line.split('\t', limit = 3)
            if (p.size < 2) continue
            val id = p[0].toLongOrNull() ?: continue
            if (id <= 0) continue
            past.removeAll { it.id == id }
            past.addLast(Done(id, p[1] == "1", clean(p.getOrElse(2) { "" })))
        }
        while (past.size > keep) past.removeFirst()
    }

    /** testo della conferma su una riga (il pannello ne tiene 200 caratteri) */
    private fun clean(s: String): String = s.replace('\t', ' ').replace('\n', ' ').replace('\r', ' ').take(200)
}

/** Conti dello speed test (senza Android, provati dai test JVM): latenza, velocità, testo per il pannello */
object SpeedCalc {
    fun median(s: List<Double>): Double {
        if (s.isEmpty()) return 0.0
        val a = s.sorted()
        val m = a.size / 2
        return if (a.size % 2 == 1) a[m] else (a[m - 1] + a[m]) / 2.0
    }

    /** media delle differenze tra misure consecutive */
    fun jitter(s: List<Double>): Double {
        if (s.size < 2) return 0.0
        var sum = 0.0
        for (i in 1 until s.size) sum += abs(s[i] - s[i - 1])
        return sum / (s.size - 1)
    }

    /**
     * Mbps da un contatore di byte (istanti in nanosecondi): dalla fine dell'avvio ([warmB] byte all'istante [warmT];
     * -1 = avvio non finito) alla fine ([endB] all'istante [endT]); se dopo l'avvio resta meno di mezzo secondo, su tutta
     * la durata da [t0].
     */
    fun rate(t0: Long, warmB: Long, warmT: Long, endB: Long, endT: Long): Double = when {
        warmB >= 0 && endT - warmT > 500_000_000L -> (endB - warmB) * 8.0 / ((endT - warmT) / 1e9) / 1e6
        endT > t0 -> endB * 8.0 / ((endT - t0) / 1e9) / 1e6
        else -> 0.0
    }

    private fun ok(x: Double): Double = if (x.isNaN() || x.isInfinite()) 0.0 else x

    /** un decimale (NaN e infiniti → 0) */
    fun r1(x: Double): Double = Math.round(ok(x) * 10.0) / 10.0

    /** millisecondi interi (NaN e infiniti → 0) */
    fun ms(x: Double): Long = Math.round(ok(x))

    /** «42 Mbps», «9,4 Mbps», «0,85 Mbps» */
    fun mbps(v: Double): String {
        val x = ok(v).coerceAtLeast(0.0)
        return when {
            x >= 10 -> Math.round(x).toString() + " Mbps"
            x >= 1 -> String.format(Locale.ITALY, "%.1f Mbps", x)
            else -> String.format(Locale.ITALY, "%.2f Mbps", x)
        }
    }

    /** riassunto per la conferma al pannello: «↓ 42 Mbps · ↑ 9,4 Mbps · ping 18 ms · jitter 3 ms · Cloudflare MXP» (upload < 0 = non misurato) */
    fun summary(dl: Double, ul: Double, ping: Double, jitter: Double, server: String): String =
        "↓ " + mbps(dl) + " · ↑ " + (if (ul >= 0) mbps(ul) else "n.d.") + " · ping " + ms(ping) + " ms · jitter " + ms(jitter) + " ms" +
            (if (server.isNotEmpty()) " · $server" else "")
}
