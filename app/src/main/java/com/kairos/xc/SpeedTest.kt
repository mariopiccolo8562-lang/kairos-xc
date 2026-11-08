package com.kairos.xc

import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.Random
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread

/**
 * Speed test chiesto dal pannello (comando «speed»): la misura di Kairos Pro su speed.cloudflare.com, con HttpURLConnection
 * e nessuna libreria in più (M-Lab, la prima scelta del Pro, richiederebbe OkHttp).
 * - latenza: 13 richieste vuote in fila sulla stessa connessione (la prima la apre e non conta);
 *   ping = mediana, jitter = media delle differenze tra misure consecutive
 * - download: 6 connessioni in parallelo per 8 s; i primi 1,5 s (avvio) non contano
 * - upload: 4 invii in parallelo da 2 MB ripetuti per 6 s; i primi 1,5 s non contano
 * Sulle chiavette da 1 GB (classe D di Kairos Adaptive) 3 e 2 connessioni: con poca CPU molte connessioni cifrate insieme
 * abbassano il risultato. [measure] occupa il thread che la chiama per circa 20 s; se [stop] diventa vero (è iniziata una
 * visione) chiude tutto e si ferma con [Paused]. Una misura alla volta: lo garantisce chi la chiama (Remote).
 */
object SpeedTest {
    class Result(val dl: Double, val ul: Double, val ping: Double, val jitter: Double, val server: String, val t: Long)

    /** misura interrotta perché è iniziata una visione: si rifà quando finisce */
    class Paused : Exception("visione iniziata")

    private const val HOST = "https://speed.cloudflare.com"
    private const val WARM_MS = 1500.0
    private const val DL_MS = 8000.0
    private const val UL_MS = 6000.0

    /**
     * Ultima misura per il battito: oggetto «speed» con i campi e le unità di Kairos Pro (dl e ul in Mbps con un decimale,
     * ul null se non misurato; ping e jitter in ms interi; t = istante della misura in ms). Come nel Pro va in ogni battito:
     * il pannello riscrive i dati solo quando cambia «t».
     */
    @Volatile var beat: JSONObject? = null
        private set

    fun publish(r: Result) {
        beat = JSONObject()
            .put("dl", SpeedCalc.r1(r.dl))
            .put("ul", if (r.ul >= 0) SpeedCalc.r1(r.ul) else JSONObject.NULL)
            .put("ping", SpeedCalc.ms(r.ping))
            .put("jitter", SpeedCalc.ms(r.jitter))
            .put("t", r.t)
    }

    /** misura completa (blocca il thread per circa 20 s): eccezione con il motivo se non riesce, [Paused] se [stop] */
    fun measure(stop: () -> Boolean): Result {
        val weak = Line.device() == "D"
        val r = Run(stop, if (weak) 3 else 6, if (weak) 2 else 4)
        try { return r.run() } finally { r.dropAll() }
    }

    private class Run(val stop: () -> Boolean, val dlConns: Int, val ulConns: Int) {
        private val conns = ConcurrentLinkedQueue<HttpURLConnection>()
        private val rnd = Random()
        @Volatile private var colo = ""
        /** visione iniziata: i lavori si fermano e le connessioni si chiudono */
        @Volatile private var halted = false
        private var ping = 0.0
        private var jitter = 0.0

        fun run(): Result {
            halt()
            measurePing()
            val dl = measureDownload()
            val ul = try { measureUpload() } catch (e: Paused) { throw e } catch (_: Exception) { -1.0 }
            halt()
            return Result(dl, ul, ping, jitter, if (colo.isNotEmpty()) "Cloudflare $colo" else "Cloudflare", System.currentTimeMillis())
        }

        /** è iniziata una visione: tutto chiuso e [Paused] */
        private fun halt() {
            if (!halted && stop()) { halted = true; dropAll() }
            if (halted) throw Paused()
        }

        fun dropAll() {
            while (true) {
                val c = conns.poll() ?: break
                try { c.disconnect() } catch (_: Throwable) {}
            }
        }

        private fun open(u: String, timeout: Int): HttpURLConnection {
            val c = URL(u).openConnection() as HttpURLConnection
            c.connectTimeout = timeout
            c.readTimeout = timeout
            c.useCaches = false
            c.setRequestProperty("User-Agent", Http.UA)
            c.setRequestProperty("Accept-Encoding", "identity")
            conns.add(c)
            if (halted) { release(c, true); throw IOException("annullato") }
            return c
        }

        private fun release(c: HttpURLConnection?, kill: Boolean) {
            if (c == null) return
            conns.remove(c)
            if (kill) try { c.disconnect() } catch (_: Throwable) {}
        }

        private fun q(): String = "r=" + java.lang.Long.toHexString(rnd.nextLong())

        /* ---------------- latenza ---------------- */
        private fun measurePing() {
            val samples = ArrayList<Double>()
            var fails = 0
            for (i in 0 until 13) {
                halt()
                var c: HttpURLConnection? = null
                try {
                    val cc = open("$HOST/__down?bytes=0&${q()}", 8_000)
                    c = cc
                    val t0 = System.nanoTime()
                    val code = cc.responseCode
                    val ms = (System.nanoTime() - t0) / 1e6
                    if (colo.isEmpty()) colo = coloOf(cc)
                    val srv = serverMs(cc)
                    val s = if (code >= 400) cc.errorStream else cc.inputStream
                    s?.use { st -> val b = ByteArray(1024); while (st.read(b) >= 0) { } }
                    release(cc, false) // connessione lasciata aperta: le misure successive non rifanno TCP/TLS
                    if (code in 200..299 && i > 0) samples.add(if (srv > 0 && srv < ms * 0.8) ms - srv else ms)
                } catch (e: Exception) {
                    release(c, true)
                    if (halted) throw Paused()
                    fails++
                    if (fails >= 5 && samples.isEmpty()) throw IOException("server di misura non raggiungibile", e)
                }
            }
            if (samples.size < 3) throw IOException("server di misura non raggiungibile")
            ping = SpeedCalc.median(samples)
            jitter = SpeedCalc.jitter(samples)
        }

        /** tempo di elaborazione del server (header server-timing), da togliere alla latenza */
        private fun serverMs(c: HttpURLConnection): Double {
            val h = c.getHeaderField("server-timing") ?: return 0.0
            return RX_CF_DUR.find(h)?.groupValues?.get(1)?.toDoubleOrNull() ?: 0.0
        }

        /** sigla del data center Cloudflare (es. MXP = Milano) */
        private fun coloOf(c: HttpURLConnection): String {
            val m = c.getHeaderField("cf-meta-colo")?.trim().orEmpty()
            if (m.length in 3..5 && m.all { it.isLetter() }) return m.uppercase()
            val ray = c.getHeaderField("cf-ray") ?: return ""
            val k = ray.substringAfterLast('-', "").trim()
            return if (k.length in 3..4 && k.all { it.isLetter() }) k.uppercase() else ""
        }

        /* ---------------- download ---------------- */
        private fun measureDownload(): Double {
            val total = AtomicLong(0)
            val errors = AtomicInteger(0)
            val done = AtomicBoolean(false)
            val workers = (1..dlConns).map { k ->
                thread(name = "kx-dl-$k", isDaemon = true) {
                    val buf = ByteArray(64 * 1024)
                    while (!done.get() && !halted) {
                        var c: HttpURLConnection? = null
                        var kill = false
                        try {
                            val cc = open("$HOST/__down?bytes=25000000&${q()}", 10_000)
                            c = cc
                            val code = cc.responseCode
                            if (code != 200) throw IOException("HTTP $code")
                            if (colo.isEmpty()) colo = coloOf(cc)
                            val s = cc.inputStream
                            while (true) {
                                if (done.get() || halted) { kill = true; break }
                                val n = s.read(buf)
                                if (n < 0) break
                                total.addAndGet(n.toLong())
                            }
                            if (!kill) s.close()
                        } catch (e: Exception) {
                            kill = true
                            if (!done.get() && !halted) {
                                errors.incrementAndGet()
                                try { Thread.sleep(150) } catch (_: InterruptedException) {}
                            }
                        } finally {
                            release(c, kill)
                        }
                    }
                }
            }
            try {
                val t0 = System.nanoTime()
                var warmB = -1L
                var warmT = 0L
                var endB = 0L
                var endT = t0
                while (true) {
                    try { Thread.sleep(200) } catch (_: InterruptedException) {}
                    halt()
                    val now = System.nanoTime()
                    val b = total.get()
                    val el = (now - t0) / 1e6
                    if (warmB < 0 && el >= WARM_MS) { warmB = b; warmT = now }
                    endB = b
                    endT = now
                    if (el >= DL_MS || errors.get() > 18) break
                }
                if (endB == 0L) throw IOException("nessun dato ricevuto dal server di misura")
                return SpeedCalc.rate(t0, warmB, warmT, endB, endT)
            } finally {
                done.set(true)
                dropAll()
                for (w in workers) try { w.join(2000) } catch (_: InterruptedException) {}
            }
        }

        /* ---------------- upload ---------------- */
        private fun measureUpload(): Double {
            val sent = AtomicLong(0)
            val errors = AtomicInteger(0)
            val oks = AtomicInteger(0)
            val done = AtomicBoolean(false)
            val chunk = ByteArray(64 * 1024).also { rnd.nextBytes(it) } // dati casuali: non comprimibili
            val size = 2_000_000
            val workers = (1..ulConns).map { k ->
                thread(name = "kx-ul-$k", isDaemon = true) {
                    while (!done.get() && !halted) {
                        var c: HttpURLConnection? = null
                        var kill = false
                        try {
                            val cc = open("$HOST/__up?${q()}", 10_000)
                            c = cc
                            cc.requestMethod = "POST"
                            cc.doOutput = true
                            cc.setFixedLengthStreamingMode(size) // invio diretto, senza copia in memoria
                            cc.setRequestProperty("Content-Type", "application/octet-stream")
                            val os = cc.outputStream
                            var left = size
                            while (left > 0) {
                                if (done.get() || halted) { kill = true; break }
                                val n = minOf(left, chunk.size)
                                os.write(chunk, 0, n)
                                sent.addAndGet(n.toLong())
                                left -= n
                            }
                            if (!kill) {
                                os.close()
                                val code = cc.responseCode
                                val s = if (code >= 400) cc.errorStream else cc.inputStream
                                s?.use { st -> val b = ByteArray(4096); while (st.read(b) >= 0) { } }
                                if (code in 200..299) oks.incrementAndGet() else throw IOException("HTTP $code")
                            }
                        } catch (e: Exception) {
                            kill = true
                            if (!done.get() && !halted) {
                                errors.incrementAndGet()
                                try { Thread.sleep(150) } catch (_: InterruptedException) {}
                            }
                        } finally {
                            release(c, kill)
                        }
                    }
                }
            }
            try {
                val t0 = System.nanoTime()
                var warmB = -1L
                var warmT = 0L
                var endB = 0L
                var endT = t0
                while (true) {
                    try { Thread.sleep(200) } catch (_: InterruptedException) {}
                    halt()
                    val now = System.nanoTime()
                    val b = sent.get()
                    val el = (now - t0) / 1e6
                    if (warmB < 0 && el >= WARM_MS) { warmB = b; warmT = now }
                    endB = b
                    endT = now
                    if (el >= UL_MS || errors.get() > 12) break
                }
                // nessun invio accettato dal server: upload non misurabile
                if (endB == 0L || (oks.get() == 0 && errors.get() > 0)) return -1.0
                return SpeedCalc.rate(t0, warmB, warmT, endB, endT)
            } finally {
                done.set(true)
                dropAll()
                for (w in workers) try { w.join(2000) } catch (_: InterruptedException) {}
            }
        }
    }

    private val RX_CF_DUR = Regex("cfReq(?:uest)?Dur(?:ation)?;\\s*dur=([0-9.]+)", RegexOption.IGNORE_CASE)
}
