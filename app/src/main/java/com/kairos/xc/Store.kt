package com.kairos.xc

import android.content.Context
import android.content.SharedPreferences
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.security.SecureRandom

/** Impostazioni e dati locali (SharedPreferences, nessuna libreria in più) */
object Store {
    private lateinit var p: SharedPreferences
    private lateinit var pos: SharedPreferences

    fun init(c: Context) {
        p = c.getSharedPreferences("lite", Context.MODE_PRIVATE)
        pos = c.getSharedPreferences("lite_pos", Context.MODE_PRIVATE)
        if ((p.getString("code", "") ?: "").isEmpty() || (p.getString("secret", "") ?: "").isEmpty()) newIdentity(c)
    }

    /**
     * Codice di attivazione (formato KXAB-7Q2M) + segreto legato al dispositivo.
     * Si ricavano dall'identificativo Android del dispositivo (ANDROID_ID, che da Android 8 vale solo per le app
     * firmate con la nostra chiave): anche disinstallando e reinstallando l'app il codice resta lo STESSO,
     * così il cliente non va ricollegato dal pannello. Se l'identificativo manca si usa un codice casuale.
     */
    private fun newIdentity(c: Context) {
        val a = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
        val aid = try { android.provider.Settings.Secure.getString(c.contentResolver, android.provider.Settings.Secure.ANDROID_ID) ?: "" } catch (_: Exception) { "" }
        val stable = aid.length >= 8 && aid != "9774d56d682e549c" && aid.any { it != '0' } // 9774… = valore uguale per tutti su alcuni Android 2.x/box economici
        val code: String
        val secret: String
        if (stable) {
            val md = java.security.MessageDigest.getInstance("SHA-256")
            val h = md.digest(("kairos-tv:code:" + c.packageName + ":" + aid).toByteArray())
            fun ch(i: Int) = a[(h[i].toInt() and 0xFF) % a.length]
            code = "KX" + ch(0) + ch(1) + "-" + ch(2) + ch(3) + ch(4) + ch(5)
            val sb = java.security.MessageDigest.getInstance("SHA-256").digest(("kairos-tv:secret:" + c.packageName + ":" + aid + ":" + BuildConfig.APPLICATION_ID).toByteArray())
            secret = Base64.encodeToString(sb, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
        } else {
            val r = SecureRandom()
            fun ch() = a[r.nextInt(a.length)]
            code = "KX" + ch() + ch() + "-" + ch() + ch() + ch() + ch()
            val b = ByteArray(32).also { r.nextBytes(it) }
            secret = Base64.encodeToString(b, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
        }
        p.edit().putString("code", code).putString("secret", secret).apply()
    }

    val code: String get() = p.getString("code", "") ?: ""
    val secret: String get() = p.getString("secret", "") ?: ""

    private fun s(k: String, d: String = ""): String = p.getString(k, d) ?: d
    private fun put(k: String, v: String) = p.edit().putString(k, v).apply()
    fun playerOption(k: String, default: String): String = s("player_$k", default)
    fun setPlayerOption(k: String, value: String) = put("player_$k", value)
    private fun portalKey(origin: String) = origin.trim().replace(Regex("^https?://", RegexOption.IGNORE_CASE), "").trimEnd('/').lowercase()

    fun portalChoice(id: String): String = s("ptl_$id")
    fun setPortalChoice(id: String, origin: String) = put("ptl_$id", origin)
    fun portalProbeAt(id: String): Long = s("ptlt_$id").toLongOrNull() ?: 0L
    fun setPortalProbeAt(id: String, at: Long) = put("ptlt_$id", at.toString())
    fun portalBadUntil(origin: String): Long = s("ptlbad_${portalKey(origin)}").toLongOrNull() ?: 0L
    fun setPortalBadUntil(origin: String, until: Long) = put("ptlbad_${portalKey(origin)}", until.toString())

    /** indirizzo del pannello: quello della build, oppure quello nuovo indicato dal pannello stesso */
    var server: String
        get() = s("server").takeIf { it.startsWith("https://") } ?: BuildConfig.SERVER_URL
        set(v) = put("server", v)

    var stateJson: String
        get() = s("state")
        set(v) = put("state", v)
    var profileId: String
        get() = s("profile")
        set(v) = put("profile", v)
    /** auto | on | off */
    /** configurazione imposta dal pannello (controllo remoto) e sua versione */
    var rcJson: String
        get() = s("rc")
        set(v) = put("rc", v)
    var rcv: String
        get() = s("rcv")
        set(v) = put("rcv", v)
    /** auto | on | max | off (quella imposta dal pannello vince) */
    /** controllo genitori (attivo di serie), PIN scelto dal cliente (vuoto = 0000) e ultimo rinvio della scelta del PIN */
    var parental: Boolean
        get() = p.getBoolean("parental", true)
        set(v) = p.edit().putBoolean("parental", v).apply()
    var pin: String
        get() = s("pin", "")
        set(v) = put("pin", v)
    var pinLater: Long
        get() = p.getLong("pinLater", 0L)
        set(v) = p.edit().putLong("pinLater", v).apply()
    /** senza il permesso «lite» del cliente vale quella imposta dal pannello, altrimenti «auto» (la scelta salvata resta per dopo) */
    var liteMode: String
        get() = Remote.lite ?: (if (K.liteAllowed()) s("liteMode", "auto") else "auto")
        set(v) = put("liteMode", v)
    /** aggiornamento in background: la versione nuova si scarica da sola (quella imposta dal pannello vince) */
    var bgUpdate: Boolean
        get() = Remote.bgUpdate ?: p.getBoolean("bgUpdate", true)
        set(v) = p.edit().putBoolean("bgUpdate", v).apply()
    /** primo avviso al cliente della versione [updFirstCode] (da qui contano i giorni dell'aggiornamento obbligatorio) */
    var updFirstCode: Int
        get() = p.getInt("updFirstCode", 0)
        set(v) = p.edit().putInt("updFirstCode", v).apply()
    var updFirstAt: Long
        get() = p.getLong("updFirstAt", 0)
        set(v) = p.edit().putLong("updFirstAt", v).apply()
    /**
     * Versione nuova trovata dall'ultimo controllo (0 = nessuna): salvata su disco perché all'apertura, anche senza rete, il
     * blocco dell'aggiornamento obbligatorio valga subito (l'APK già scaricato si ritrova dal suo nome, vedi Updater.restore).
     */
    val updCode: Int get() = p.getInt("updCode", 0)
    val updVer: String get() = s("updVer")
    val updUrl: String get() = s("updUrl")
    fun saveUpd(code: Int, ver: String, url: String) {
        if (code == updCode && ver == updVer && url == updUrl) return // niente da riscrivere (il controllo gira ogni ora)
        p.edit().putInt("updCode", code).putString("updVer", ver).putString("updUrl", url).apply()
    }
    /** ultima richiesta «Invia aggiornamento» del pannello già gestita */
    var updSeen: Long
        get() = p.getLong("updSeen", 0)
        set(v) = p.edit().putLong("updSeen", v).apply()
    /** anteprima nella lista canali: click (primo OK = riquadro, secondo OK = schermo intero) | off */
    var preview: String
        get() = Remote.preview ?: s("previewMode", "click") // primo OK = anteprima nel riquadro, secondo OK = schermo intero
        set(v) = put("previewMode", v)
    var plChoice: String
        get() = s("plChoice")
        set(v) = put("plChoice", v)
    /* ---------------- Kairos Adaptive (vedi Line) ---------------- */
    /** classe del dispositivo D / N / O, calcolata una volta */
    var devCls: String
        get() = s("devCls")
        set(v) = put("devCls", v)
    /** classe della linea (weak / normal / good), data del giudizio e banda misurata in bit/s */
    val lineCls: String get() = s("lineCls")
    val lineAt: Long get() = p.getLong("lineAt", 0)
    val bw: Long get() = p.getLong("bw", 0)
    fun saveLine(cls: String, at: Long, bw: Long) {
        p.edit().putString("lineCls", cls).putLong("lineAt", at).putLong("bw", bw).apply()
    }
    /** versione «a scatti» imparata sul dispositivo (QualityPolicy.Heavy, vedi Line.heavy) */
    var heavy: String
        get() = s("heavyQ")
        set(v) = put("heavyQ", v)
    /** portale usato prima di quello attuale: le sue liste restano sul disco, per tornarci al volo */
    var plPrev: String
        get() = s("plPrev")
        set(v) = put("plPrev", v)
    /* liste scaricate PER PORTALE: tornando a un portale già usato le liste sono subito pronte */
    private fun syncKey(ids: List<String>) = ids.joinToString(",")
    fun syncSigOf(ids: List<String>): String = s("syncSig:" + syncKey(ids))
    fun syncAtOf(ids: List<String>): Long = p.getLong("syncAt:" + syncKey(ids), 0)
    fun setSynced(ids: List<String>, sig: String, at: Long) {
        p.edit().putString("syncSig:" + syncKey(ids), sig).putLong("syncAt:" + syncKey(ids), at).apply()
    }
    /** ultimo aggiornamento delle liste del portale in uso */
    val syncAt: Long get() = syncAtOf(K.activeIds())
    /** archivio nuovo: tutte le liste si ricaricano */
    fun clearSync() {
        val e = p.edit()
        p.all.keys.filter { it.startsWith("syncSig") || it.startsWith("syncAt") }.forEach { e.remove(it) }
        e.apply()
    }
    var kickSeen: Long
        get() = p.getLong("kickSeen", 0)
        set(v) = p.edit().putLong("kickSeen", v).apply()
    /** comandi del pannello già eseguiti, con il loro esito (gli ultimi 30, vedi CmdBook) */
    val cmdDone: String get() = s("cmdDone")
    /** [now] = subito sul disco (prima di un riavvio o di una chiusura: porta con sé anche le scritture rimaste in sospeso) */
    fun saveCmdDone(v: String, now: Boolean = false) {
        val e = p.edit().putString("cmdDone", v)
        if (now) e.commit() else e.apply()
    }
    var vpnUser: Boolean
        get() = p.getBoolean("vpnUser", false)
        set(v) = p.edit().putBoolean("vpnUser", v).apply()
    /** ultima volta che l'app ha chiesto il permesso per installare gli aggiornamenti */
    var permAskT: Long
        get() = p.getLong("permAskT", 0L)
        set(v) = p.edit().putLong("permAskT", v).apply()
    var privacyOk: Boolean
        get() = p.getBoolean("privacyOk", false)
        set(v) = p.edit().putBoolean("privacyOk", v).apply()
    var lastLiveCat: String
        get() = s("liveCat")
        set(v) = put("liveCat", v)
    /** ultimo canale guardato, per chiave stabile (portale:numero) */
    var lastLiveK: String
        get() = s("liveK")
        set(v) = put("liveK", v)
    /** preferiti del portale in uso (le chiavi iniziano con l'id della playlist) */
    fun favsIn(kind: String, pls: List<String>): Set<String> = favs(kind).filter { f -> pls.any { f.startsWith("$it:") } }.toSet()
    var lastLiveId: Long
        get() = p.getLong("liveId", 0)
        set(v) = p.edit().putLong("liveId", v).apply()
    /**
     * Ultimo canale (numero interno e chiave stabile) in una sola scrittura: a ogni cambio canale il file delle impostazioni
     * (con dentro lo stato del pannello) si riscrive una volta invece di due, e uscendo dalla schermata Android aspetta meno.
     */
    fun setLastLive(id: Long, k: String) {
        p.edit().putLong("liveId", id).putString("liveK", k).apply()
    }

    var seenMsgs: Set<String>
        get() = p.getStringSet("seenMsgs", emptySet()) ?: emptySet()
        set(v) = p.edit().putStringSet("seenMsgs", HashSet(v)).apply()

    /** server di una playlist M3U al momento del caricamento: se il pannello cambia DNS si sostituisce al volo */
    fun plOrigin(id: String): String = s("plo_$id")
    fun setPlOrigin(id: String, o: String) = put("plo_$id", o)

    private fun prof(): String = profileId.ifEmpty { "_" }

    /* ---------------- preferiti (per profilo) ---------------- */
    fun favs(kind: String): Set<String> = p.getStringSet("fav_${kind}_${prof()}", emptySet()) ?: emptySet()
    fun isFav(kind: String, k: String): Boolean = favs(kind).contains(k)
    fun toggleFav(kind: String, k: String): Boolean {
        val set = HashSet(favs(kind))
        val on = if (set.contains(k)) { set.remove(k); false } else { set.add(k); true }
        p.edit().putStringSet("fav_${kind}_${prof()}", set).apply()
        return on
    }

    /* ---------------- «Continua a guardare» (per profilo) ---------------- */
    fun recent(): List<JSONObject> {
        val t = s("recent_${prof()}")
        if (t.isEmpty()) return emptyList()
        return try {
            val a = JSONArray(t)
            (0 until a.length()).mapNotNull { a.optJSONObject(it) }
        } catch (_: Exception) { emptyList() }
    }

    fun saveRecent(o: JSONObject) {
        val id = o.optString("id")
        val group = o.optString("group")
        val list = recent().filter { it.optString("id") != id && (group.isEmpty() || it.optString("group") != group) }.toMutableList()
        list.add(0, o)
        val a = JSONArray()
        list.take(20).forEach { a.put(it) }
        put("recent_${prof()}", a.toString())
    }

    fun removeRecent(id: String) {
        val a = JSONArray()
        recent().filter { it.optString("id") != id }.forEach { a.put(it) }
        put("recent_${prof()}", a.toString())
    }

    /* ---------------- posizione di visione di film ed episodi ---------------- */
    fun position(id: String): Long = pos.getLong(id, 0)
    fun duration(id: String): Long = pos.getLong("$id#d", 0)
    fun setPosition(id: String, v: Long, dur: Long = 0) {
        if (v <= 0) pos.edit().remove(id).remove("$id#d").apply()
        else pos.edit().putLong(id, v).putLong("$id#d", dur).apply()
    }
}
