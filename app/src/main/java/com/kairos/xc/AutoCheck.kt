package com.kairos.xc

import android.os.StatFs
import android.os.SystemClock
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs

/**
 * Auto-controlli silenziosi (funzioni intelligenti accese): dopo i primi 60 s e ogni 30 minuti (60 sulle chiavette deboli),
 * solo nei menu, mai durante la visione, sul thread a bassa priorità. Esito al pannello nel battito: campo «chk» con lo
 * stesso formato della Kairos Pro (srv, acc, exp, con, epg, dsk, mb, clk, eng, cap, fix), che il pannello mostra già in
 * «Salute delle app» e in «Dati» › Auto-controlli. Al cliente solo i messaggi neutri della Kairos Pro: «Abbonamento da
 * rinnovare» (al più una volta al giorno) e l'orologio sbagliato (ogni 3 giorni). Mai «fornitore».
 */
object AutoCheck {
    /** minuti di differenza fra l'orologio del dispositivo e quello del pannello (misurati nel battito) */
    @Volatile var skew: Int? = null
        private set
    @Volatile private var res: JSONObject? = null
    @Volatile private var resStr = ""
    @Volatile private var sentStr = ""
    @Volatile private var sentAt = 0L
    @Volatile private var sending = ""

    fun load() {
        if (res != null) return
        val s = Smart.prefs().getString("chk", "") ?: ""
        if (s.isNotEmpty()) try { res = JSONObject(s); resStr = s } catch (_: Exception) {}
    }

    /** campo «chk» del battito: quando cambia e ogni 15 minuti; null = niente da mandare */
    fun toSend(): JSONObject? {
        if (!Smart.on()) return null
        val r = res ?: return null
        val s = resStr
        if (s == sentStr && SystemClock.elapsedRealtime() - sentAt < 15 * 60_000L) return null
        sending = s
        return r
    }

    /** battito accettato dal pannello */
    fun sent() {
        val s = sending
        if (s.isEmpty()) return
        sentStr = s
        sentAt = SystemClock.elapsedRealtime()
        sending = ""
    }

    /** ora del pannello ([ts]) nella risposta del battito partito a [t0] e arrivato a [t1] (orologio del dispositivo) */
    fun clock(ts: Long, t0: Long, t1: Long) {
        if (ts > 1_000_000_000_000L && t1 - t0 in 0..10_000L) skew = Math.round(((t0 + t1) / 2.0 - ts) / 60_000.0).toInt()
    }

    /** lavoro CHECK (thread di lavoro): false = interrotto da una visione, si riprova presto */
    fun run(): Boolean {
        if (!Smart.on() || Panel.state?.active != true) return true
        val r = JSONObject()
        val fix = ArrayList<String>()
        var skewX: Int? = null
        // 1. server della lista e linea Xtream: una sola richiesta piccola (player_api.php senza azione)
        val p = K.active().firstOrNull()
        val x = p?.xt
        if (p != null && x != null) {
            var u = info(x.first, x.second, x.third)
            if (u == null && p.mode == "auto") {
                // server giù: il server di riserva del pannello, se c'è e risponde (il lettore parte direttamente da quello)
                for (alt in Portals.cands(p).filter { it != x.first }.take(3)) {
                    if (Smart.playing()) return false
                    val v = info(alt, x.second, x.third) ?: continue
                    if (v.first.opt("auth")?.toString() == "0") continue
                    Portals.markBad(x.first, 30 * 60_000L)
                    Store.setPortalChoice(p.id, alt)
                    u = v
                    r.put("srv", "alt")
                    fix.add("srv")
                    break
                }
            }
            if (u == null) r.put("srv", "down") else {
                val ui = u.first
                if (!r.has("srv")) r.put("srv", "ok")
                val auth = ui.opt("auth")?.toString() ?: "1"
                if (auth == "0" || auth == "false") r.put("srv", "auth") else {
                    val now = System.currentTimeMillis()
                    val st = ui.optString("status").lowercase(Locale.ROOT)
                    val exp = (ui.optString("exp_date").toLongOrNull() ?: 0L) * 1000L
                    if (exp > 0) r.put("exp", Math.floorDiv(exp - now, 86_400_000L).toInt())
                    val a = ui.optString("active_cons").toIntOrNull() ?: 0
                    val m = ui.optString("max_connections").toIntOrNull() ?: 0
                    if (m > 0) r.put("con", "$a/$m")
                    r.put("acc", when {
                        exp in 1 until now || st == "expired" -> "exp"
                        st.isNotEmpty() && st != "active" -> "off"
                        m > 0 && a >= m -> "full"
                        else -> "ok"
                    })
                }
                val sn = u.second?.optLong("timestamp_now", 0L) ?: 0L
                if (sn > 1_000_000_000L) skewX = Math.round((System.currentTimeMillis() - sn * 1000.0) / 60_000.0).toInt()
            }
        }
        if (Smart.playing()) return false
        // 2. orologio: l'ora del pannello (battito), altrimenti quella del server della lista
        (skew ?: skewX)?.let { r.put("clk", it) }
        // 3. guida piccola: si riscarica da sola quando è vecchia (lavoro GUIDE); qui l'esito dell'ultimo giro
        Guide.state.takeIf { it.isNotEmpty() }?.let { r.put("epg", it); if (it == "fix") fix.add("epg") }
        // 4. spazio libero: sotto 300 MB si puliscono le cache
        disk(r, fix)
        // 5. lettore imparato e decoder del dispositivo
        Engines.summary().takeIf { it.isNotEmpty() }?.let { r.put("eng", it) }
        DevCaps.caps?.let { r.put("cap", it.text()) }
        if (fix.isNotEmpty()) r.put("fix", fix.distinct().joinToString(","))
        val s = r.toString()
        res = r
        resStr = s
        val sp = Smart.prefs()
        val e = sp.edit().putString("chk", s)
        // al cliente solo quando serve una sua azione, con parole neutre
        val now = System.currentTimeMillis()
        val acc = r.optString("acc")
        var title = ""
        var msg = ""
        if ((acc == "exp" || acc == "off") && now - sp.getLong("acExp", 0L) > 86_400_000L) {
            e.putLong("acExp", now)
            title = "Abbonamento da rinnovare"
            val days = r.optInt("exp", 0)
            val dt = if (r.has("exp") && days < 0) " dal " + SimpleDateFormat("dd/MM/yyyy", Locale.ITALY).format(Date(now + days * 86_400_000L)) else ""
            msg = if (acc == "exp") "La linea dei canali risulta scaduta$dt. Per continuare a guardare va rinnovata." else "La linea dei canali risulta sospesa. Per continuare a guardare va riattivata."
        }
        val clk = r.optInt("clk", 0)
        val clock = r.has("clk") && abs(clk) >= 15 && now - sp.getLong("acClk", 0L) > 3 * 86_400_000L
        if (clock) e.putLong("acClk", now)
        e.apply()
        if (title.isNotEmpty() || clock) ui {
            val a = App.top as? BaseActivity
            if (a != null && Smart.menu()) {
                if (title.isNotEmpty()) Ui.dialog(a, title, msg)
                if (clock) App.main.postDelayed({ Ui.toast("La data o l'ora del dispositivo non sono giuste: correggile nelle impostazioni del dispositivo (la guida TV sarebbe sfasata)") }, 4_000)
            }
        }
        return true
    }

    /** user_info e server_info del server Xtream [host]; null = non risponde o risposta non valida */
    private fun info(host: String, user: String, pass: String): Pair<JSONObject, JSONObject?>? {
        return try {
            val r = Http.text("$host/player_api.php?username=${Playlist.enc(user)}&password=${Playlist.enc(pass)}", connectMs = 6_000, readMs = 8_000)
            if (r.code != 200) return null
            val j = JSONObject(r.text)
            val ui = j.optJSONObject("user_info") ?: return null
            ui to j.optJSONObject("server_info")
        } catch (_: Exception) { null }
    }

    private fun freeMb(): Long = try { StatFs(App.I.filesDir.path).availableBytes / 1_048_576L } catch (_: Throwable) { -1L }

    private fun disk(r: JSONObject, fix: MutableList<String>) {
        var mb = freeMb()
        if (mb < 0) return
        if (mb < 300) {
            trim()
            val after = freeMb()
            if (after >= 300) { r.put("dsk", "fix"); fix.add("disk") } else r.put("dsk", "low")
            if (after >= 0) mb = after
        } else r.put("dsk", "ok")
        r.put("mb", mb)
    }

    /** cache delle immagini (si riscaricano) e file temporanei fermi da 10 minuti; mai le liste, le impostazioni o l'aggiornamento */
    private fun trim() {
        try { Img.glide().clearDiskCache() } catch (_: Throwable) {}
        val old = System.currentTimeMillis() - 10 * 60_000L
        try { App.I.cacheDir.listFiles()?.forEach { f -> if (f.isFile && f.lastModified() < old && !f.name.endsWith(".apk")) f.delete() } } catch (_: Throwable) {}
    }
}
