package com.kairos.xc

import android.util.Base64
import android.util.LruCache
import org.json.JSONObject

class Prog(val title: String, val desc: String, val start: Long, val end: Long) {
    fun progress(now: Long = System.currentTimeMillis()): Int =
        if (end <= start) 0 else (((now - start) * 100) / (end - start)).toInt().coerceIn(0, 100)
}

/**
 * Guida TV leggera: solo il canale che stai guardando o selezionando (Xtream get_short_epg),
 * niente file XMLTV da centinaia di MB. I risultati restano in memoria 10 minuti.
 */
object Epg {
    private class Entry(val t: Long, val list: List<Prog>)
    private val cache = LruCache<String, Entry>(if (App.lite) 120 else 400)

    fun cached(p: Playlist?, sid: String, limit: Int): List<Prog>? {
        if (p == null || sid.isEmpty()) return null
        val e = cache.get(p.id + ":" + sid + ":" + limit) ?: return null
        val now = System.currentTimeMillis()
        if (now - e.t > 10 * 60_000L) return null
        return e.list.filter { it.end > now }
    }

    /** programmi da adesso in poi (vuoto se il server non ha la guida) */
    fun get(p: Playlist?, sid: String, limit: Int = 4): List<Prog> {
        if (p == null || sid.isEmpty()) return emptyList()
        cached(p, sid, limit)?.let { if (it.isNotEmpty()) return it }
        val url = p.api("get_short_epg", "&stream_id=$sid&limit=$limit") ?: return emptyList()
        val list = try {
            val r = Http.text(url, connectMs = 10_000, readMs = 15_000)
            parse(r.text)
        } catch (_: Exception) { emptyList() }
        cache.put(p.id + ":" + sid + ":" + limit, Entry(System.currentTimeMillis(), list))
        val now = System.currentTimeMillis()
        return list.filter { it.end > now }
    }

    /** programma della guida completa (anche quelli passati), per il Catchup */
    class Arch(val title: String, val desc: String, val start: Long, val end: Long, val startServer: String, val recorded: Boolean)

    private class ArchEntry(val t: Long, val list: List<Arch>)
    private val archCache = LruCache<String, ArchEntry>(if (App.lite) 6 else 16)

    /** tutta la guida di un canale (giorni passati compresi): Xtream get_simple_data_table, in memoria 15 minuti */
    fun archive(p: Playlist?, sid: String): List<Arch> {
        if (p == null || sid.isEmpty()) return emptyList()
        val key = p.id + ":" + sid
        archCache.get(key)?.let { if (System.currentTimeMillis() - it.t < 15 * 60_000L) return it.list }
        val url = p.api("get_simple_data_table", "&stream_id=$sid") ?: return emptyList()
        val t = try { Http.text(url, connectMs = 10_000, readMs = 25_000).text } catch (_: Exception) { return emptyList() }
        val a = try { JSONObject(t).optJSONArray("epg_listings") } catch (_: Exception) { null } ?: return emptyList()
        val fmt = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US)
        val out = ArrayList<Arch>(a.length())
        for (i in 0 until a.length()) {
            val o = a.optJSONObject(i) ?: continue
            val s = o.optString("start_timestamp").toLongOrNull()?.times(1000) ?: continue
            val e = (o.optString("stop_timestamp").ifEmpty { o.optString("end_timestamp") }).toLongOrNull()?.times(1000) ?: continue
            // ora d'inizio come la scrive il server (il suo fuso orario): serve così nell'indirizzo del Catchup
            val ss = o.optString("start").takeIf { it.length >= 16 } ?: synchronized(fmt) { fmt.format(java.util.Date(s)) }
            out.add(Arch(b64(o.optString("title")), b64(o.optString("description")), s, e, ss, o.optString("has_archive") == "1"))
        }
        val list = out.sortedBy { it.start }
        archCache.put(key, ArchEntry(System.currentTimeMillis(), list))
        return list
    }

    private fun parse(t: String): List<Prog> {
        val a = try { JSONObject(t).optJSONArray("epg_listings") } catch (_: Exception) { null } ?: return emptyList()
        val out = ArrayList<Prog>()
        for (i in 0 until a.length()) {
            val o = a.optJSONObject(i) ?: continue
            val s = o.optString("start_timestamp").toLongOrNull()?.times(1000) ?: continue
            val e = (o.optString("stop_timestamp").ifEmpty { o.optString("end_timestamp") }).toLongOrNull()?.times(1000) ?: continue
            out.add(Prog(b64(o.optString("title")), b64(o.optString("description")), s, e))
        }
        return out.sortedBy { it.start }
    }

    private fun b64(s: String): String = try {
        String(Base64.decode(s, Base64.DEFAULT), Charsets.UTF_8).trim()
    } catch (_: Exception) { s }
}
