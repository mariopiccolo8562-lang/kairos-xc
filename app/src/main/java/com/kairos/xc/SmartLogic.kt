package com.kairos.xc

import java.net.URL
import java.util.Locale

/*
 * Funzioni intelligenti di Kairos XC, parte senza Android (la provano i test JVM di app/src/test):
 * decoder del dispositivo e versione di partenza, lettore imparato per formato e scelta del lettore, chiave dei canali per il
 * pannello («server|flusso»), elenco dei canali non funzionanti, regole del lavoro in sottofondo (budget di leggerezza).
 */

/** Decoder hardware del dispositivo (HEVC, 10 bit, 4K, 1080p a 50 fps) e altezza massima dello schermo (0 = non nota). */
class Caps(val hevc: Boolean, val b10: Boolean, val uhd: Boolean, val fps60: Boolean, val maxH: Int, val disp: Int) {
    /** forma salvata: «1010;2160;1080» (hevc, b10, uhd, fps60; altezza massima del decoder; altezza dello schermo) */
    fun save(): String = listOf(hevc, b10, uhd, fps60).joinToString("") { if (it) "1" else "0" } + ";" + maxH + ";" + disp

    /** per il pannello (chk.cap, «Decodifica del dispositivo»): es. «hevc,uhd,b10,fps60» oppure «nohevc,nouhd» */
    fun text(): String = listOf(if (hevc) "hevc" else "nohevc", if (uhd) "uhd" else "nouhd", if (b10) "b10" else "", if (fps60) "fps60" else "")
        .filter { it.isNotEmpty() }.joinToString(",")

    companion object {
        fun load(s: String?): Caps? {
            val p = (s ?: "").split(';')
            if (p.size < 3 || p[0].length != 4 || p[0].any { it != '0' && it != '1' }) return null
            val f = p[0].map { it == '1' }
            return Caps(f[0], f[1], f[2], f[3], p[1].toIntOrNull() ?: 0, p[2].toIntOrNull() ?: 0)
        }
    }
}

/**
 * Versione di partenza di un canale secondo il dispositivo, fra le versioni sorelle che Kairos XC già conosce (Quality):
 * 4K su schermo o decoder che non lo reggono → FHD; HEVC senza decoder HEVC → la versione H.264; HDR / 10 bit senza Main10 e
 * 50/60 fps in FHD senza il decoder adatto → un'altra versione. Mai più pesante di quella scelta; senza decoder letti, niente.
 */
object VerPick {
    private val RX_HDR = Regex("(?i)\\b(HDR(10)?|10 ?BIT|DOLBY ?VISION)\\b")
    private val RX_HFR = Regex("(?i)\\b(50|60) ?FPS\\b")

    class V(val id: Long, val name: String, val q: Int, val hevc: Boolean)

    fun canPlay(c: Caps?, name: String, q: Int, hevc: Boolean): Boolean {
        if (c == null) return true
        if (q >= 4 && (!c.uhd || c.disp in 1 until 1440)) return false
        if (hevc && !c.hevc) return false
        if (!c.b10 && RX_HDR.containsMatchIn(name)) return false
        if (!c.fps60 && q >= 3 && RX_HFR.containsMatchIn(name)) return false
        return true
    }

    /** [cur] non va su questo dispositivo: la sorella migliore che va, non più pesante (a parità quella non HEVC); null = va bene così */
    fun pick(c: Caps?, cur: V, all: List<V>): V? {
        if (canPlay(c, cur.name, cur.q, cur.hevc)) return null
        return all.filter { it.id != cur.id && it.q <= cur.q && canPlay(c, it.name, it.q, it.hevc) }
            .maxWithOrNull(compareBy<V>({ it.q }, { if (it.hevc) 0 else 1 }))
    }
}

/**
 * Lettore che funziona meglio su questo dispositivo, per formato (ts, hls, mp4, mkv e le varianti «hevc»), come la Kairos Pro:
 * una visione di un minuto senza problemi conta come riuscita del suo lettore; quando il recupero passa da ExoPlayer a VLC e
 * con VLC il canale va, ExoPlayer ha fallito e VLC è riuscito. Si cambia lettore di base solo con prove sufficienti e un
 * vantaggio chiaro; se VLC comincia a fallire si torna da soli a ExoPlayer. Si azzera a ogni versione dell'app ([ver]).
 */
class EngineBook(saved: String = "", private val ver: Int = 0) {
    /** formato → [riuscite ExoPlayer, fallite ExoPlayer, riuscite VLC, fallite VLC]; in fondo quelli usati da poco */
    private val m = LinkedHashMap<String, IntArray>()

    init { load(saved) }

    fun ok(engine: String, key: String) = add(engine, key, 0)
    fun fail(engine: String, key: String) = add(engine, key, 1)

    private fun add(engine: String, key: String, slot: Int) {
        if (key.isEmpty() || (engine != "exo" && engine != "vlc")) return
        val a = m.remove(key) ?: IntArray(4)
        m[key] = a
        val i = (if (engine == "vlc") 2 else 0) + slot
        a[i] = minOf(a[i] + 1, 500)
        // contano di più le prove recenti: oltre 200 in tutto si dimezzano
        if (a.sum() > 200) for (j in 0..3) a[j] = a[j] / 2
        while (m.size > MAX_KEYS) m.remove(m.keys.first())
    }

    private fun rate(ok: Int, ko: Int): Double = (ko + 0.5) / (ok + ko + 2.0)

    /** lettore da usare di base per [key] se uno è chiaramente migliore (null = prove insufficienti) */
    fun best(key: String): String? {
        val a = m[key] ?: return null
        val exo = rate(a[0], a[1])
        val vlc = rate(a[2], a[3])
        return when {
            a[1] >= 2 && a[2] + a[3] >= 2 && vlc + 0.15 < exo -> "vlc"
            a[3] >= 2 && a[0] + a[1] >= 2 && exo + 0.15 < vlc -> "exo"
            else -> null
        }
    }

    /** per il pannello (chk.eng, «Lettore imparato»): solo i formati dove si parte con VLC, es. «ts:vlc,hlshevc:vlc» */
    fun summary(): String = m.keys.filter { best(it) == "vlc" }.sorted().joinToString(",") { "$it:vlc" }.take(40)

    fun size(): Int = m.size

    fun save(): String = "v$ver" + m.entries.joinToString("") { "\n" + it.key + "\t" + it.value.joinToString("\t") }

    private fun load(s: String) {
        val lines = s.split('\n')
        if (lines.firstOrNull() != "v$ver") return // versione dell'app diversa: lettori e decoder possono essere cambiati
        for (l in lines.drop(1)) {
            val p = l.split('\t')
            if (p.size != 5 || p[0].isEmpty() || p[0].length > 12) continue
            m[p[0]] = IntArray(4) { (p[it + 1].toIntOrNull() ?: 0).coerceIn(0, 500) }
        }
        while (m.size > MAX_KEYS) m.remove(m.keys.first())
    }

    companion object {
        const val MAX_KEYS = 12
        private val RX_HEVC = Regex("(?i)\\b(HEVC|H\\.?265|X265)\\b")

        /** formato dall'indirizzo: hls / ts / mkv / mp4 / "" (l'estensione vince sul percorso, come nella Kairos Pro) */
        fun fmt(url: String): String {
            val u = url.lowercase(Locale.ROOT).substringBefore('?')
            return when {
                u.endsWith(".ts") -> "ts"
                u.endsWith(".m3u8") || u.contains("/hls/") -> "hls"
                u.endsWith(".mkv") -> "mkv"
                u.endsWith(".mp4") || u.endsWith(".m4v") -> "mp4"
                else -> ""
            }
        }

        /** chiave del formato: dall'indirizzo, dal nome e (se noto) dal codec in onda; "" = formato che non si impara */
        fun key(url: String, title: String, codec: String = ""): String {
            val f = fmt(url)
            if (f.isEmpty()) return ""
            return if (codec.equals("HEVC", true) || RX_HEVC.containsMatchIn(title)) f + "hevc" else f
        }
    }
}

/** Scelta del lettore all'apertura: vince sempre quello scelto dal cliente o imposto dal pannello; l'imparato solo con «Automatico» */
object EngineChoice {
    /** [setting] = Remote.engine(kind): auto | exo | vlc · [smart] = funzioni intelligenti accese · [learned] = EngineBook.best */
    fun pick(setting: String, smart: Boolean, learned: String?): String = when (setting) {
        "vlc" -> "vlc"
        "exo" -> "exo"
        else -> if (smart && learned == "vlc") "vlc" else "exo"
    }
}

/** Canale come lo conosce il pannello: «host[:porta]|numero del flusso», uguale per tutti i clienti dello stesso server */
object Ck {
    private val RX_NUM = Regex("^\\d{1,12}$")
    private val RX_TAIL = Regex("/(\\d{1,12})(?:\\.[A-Za-z0-9]+)?$")
    private val RX_CK = Regex("^[a-z0-9.-]{1,200}(:\\d{1,5})?\\|\\d{1,12}$")

    /** «host[:porta]» come lo scrive il pannello: minuscolo, senza schema, senza la porta predefinita ("" se non valido) */
    fun hostKey(url: String): String {
        val u = try { URL(if (url.contains("://")) url else "http://$url") } catch (_: Exception) { return "" }
        val pr = u.protocol.lowercase(Locale.ROOT)
        if (pr != "http" && pr != "https") return ""
        val h = (u.host ?: "").lowercase(Locale.ROOT)
        if (h.isEmpty()) return ""
        val def = if (pr == "https") 443 else 80
        return if (u.port > 0 && u.port != def) h + ":" + u.port else h
    }

    /** dal numero del flusso Xtream [sid] o, nelle liste M3U di tipo Xtream (/live/utente/password/123.ts), dall'indirizzo */
    fun of(url: String, sid: String): String {
        val hk = hostKey(url)
        if (hk.isEmpty()) return ""
        val path = try { URL(url).path ?: "" } catch (_: Exception) { "" }
        val s = sid.takeIf { RX_NUM.matches(it) } ?: RX_TAIL.find(path)?.groupValues?.get(1) ?: return ""
        val out = "$hk|$s"
        return if (RX_CK.matches(out)) out else ""
    }

    fun valid(ck: String): Boolean = RX_CK.matches(ck)
}

/**
 * Canali che non funzionano per nessuno, come li manda il pannello nel battito (solo quando l'elenco cambia): impronta [v] e
 * «host|flusso» (al massimo [MAX]). [at] = ultima volta che il pannello l'ha confermato: oltre un giorno senza conferme non vale più.
 */
class DeadBook(val v: String = "", list: List<String> = emptyList(), var at: Long = 0L) {
    val all: List<String> = list.filter { Ck.valid(it) }.distinct().take(MAX)

    /** numeri dei flussi non funzionanti dei server [hosts] (chiavi «host[:porta]» del portale e dei server di riserva) */
    fun sids(hosts: Collection<String>): Set<String> {
        if (all.isEmpty() || hosts.isEmpty()) return emptySet()
        val hs = hosts.toHashSet()
        val out = LinkedHashSet<String>()
        for (ck in all) { val i = ck.lastIndexOf('|'); if (hs.contains(ck.substring(0, i))) out.add(ck.substring(i + 1)) }
        return out
    }

    fun fresh(now: Long): Boolean = all.isNotEmpty() && now - at < STALE_MS

    fun save(): String = v + "\n" + at + all.joinToString("") { "\n" + it }

    companion object {
        const val MAX = 300
        const val STALE_MS = 24 * 3600_000L
        fun load(s: String?): DeadBook {
            val l = (s ?: "").split('\n')
            if (l.size < 2) return DeadBook()
            return DeadBook(l[0].take(16), l.drop(2), l[1].toLongOrNull() ?: 0L)
        }
    }
}

/**
 * Budget di leggerezza: quale lavoro in sottofondo tocca adesso (uno alla volta). Mai nei primi [START_MS] dopo l'avvio, mai
 * durante la visione, solo nei menu; con le funzioni intelligenti spente solo la lettura del file e dei decoder (una volta).
 */
object Budget {
    const val START_MS = 60_000L
    const val CHECK_MS = 30 * 60_000L
    const val CHECK_D_MS = 60 * 60_000L
    const val REMIND_MS = 15 * 60_000L
    const val GUIDE_MS = 15 * 60_000L

    enum class Job { LOAD, CAPS, FLUSH, CHECK, GUIDE, REMIND }

    /** auto-controlli: ogni 30 minuti, ogni 60 sulle chiavette deboli (classe D) */
    fun checkEvery(dev: String): Long = if (dev == "D") CHECK_D_MS else CHECK_MS

    /** canali della guida piccola (quelli che si guardano) e quanti se ne aggiornano per giro */
    fun guideChannels(dev: String): Int = if (dev == "D") 4 else 8
    fun guidePerRun(dev: String): Int = if (dev == "D") 2 else 4

    class S(
        val now: Long, val boot: Long, val menu: Boolean, val playing: Boolean, val smart: Boolean, val dev: String,
        val loaded: Boolean, val capsKnown: Boolean, val dirty: Boolean,
        val lastCheck: Long, val lastGuide: Long, val lastRemind: Long, val remindOn: Boolean,
        /** canali guardati di solito (la guida piccola li copre) · programmi o squadre seguiti (promemoria) */
        val chans: Boolean, val follows: Boolean,
    )

    /** si può lavorare adesso? */
    fun canWork(s: S): Boolean = s.now - s.boot >= START_MS && s.menu && !s.playing

    /** il prossimo lavoro dovuto (null = niente); i tempi «last» sono 0 se mai fatto */
    fun next(s: S): Job? {
        if (!canWork(s)) return null
        if (!s.loaded) return Job.LOAD
        if (!s.capsKnown) return Job.CAPS
        if (!s.smart) return null
        if (s.dirty) return Job.FLUSH
        if (s.lastCheck == 0L || s.now - s.lastCheck >= checkEvery(s.dev)) return Job.CHECK
        if (s.chans && (s.lastGuide == 0L || s.now - s.lastGuide >= GUIDE_MS)) return Job.GUIDE
        if (s.remindOn && s.follows && (s.lastRemind == 0L || s.now - s.lastRemind >= REMIND_MS)) return Job.REMIND
        return null
    }
}

/**
 * Cambio canale: sequenze imparate (canale → canale successivo), come la Kairos Pro. Serve solo a risolvere in anticipo il DNS
 * del canale più probabile: niente secondo lettore. Al massimo [MAX] canali di partenza, ognuno con 4 successivi.
 */
class ZapBook(saved: String = "") {
    private val m = LinkedHashMap<String, LinkedHashMap<String, Int>>()

    init {
        for (l in saved.split('\n')) {
            val p = l.split('\t')
            if (p.size < 2 || p[0].isEmpty()) continue
            val to = LinkedHashMap<String, Int>()
            for (x in p.drop(1)) { val i = x.lastIndexOf('='); if (i > 0) x.substring(i + 1).toIntOrNull()?.let { to[x.substring(0, i)] = it.coerceIn(1, 1000) } }
            if (to.isNotEmpty()) m[p[0]] = to
        }
        while (m.size > MAX) m.remove(m.keys.first())
    }

    fun learn(from: String, to: String) {
        if (from.isEmpty() || to.isEmpty() || from == to || from.contains('\t') || to.contains('\t')) return
        val x = m.remove(from) ?: LinkedHashMap()
        x[to] = minOf((x[to] ?: 0) + 1, 1000)
        while (x.size > 4) x.minByOrNull { it.value }?.let { x.remove(it.key) }
        m[from] = x // in fondo = usato da poco
        while (m.size > MAX) m.remove(m.keys.first())
    }

    /** successivo imparato di [from]: almeno 3 volte e almeno il 40% dei passaggi; null = nessuno */
    fun predict(from: String): String? {
        val x = m[from] ?: return null
        val tot = x.values.sum()
        return x.maxByOrNull { it.value }?.takeIf { it.value >= 3 && it.value * 10 >= tot * 4 }?.key
    }

    fun size(): Int = m.size

    fun save(): String = m.entries.joinToString("\n") { e -> e.key + "\t" + e.value.entries.joinToString("\t") { it.key + "=" + it.value } }

    companion object { const val MAX = 100 }
}
