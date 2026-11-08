package com.kairos.xc

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.os.SystemClock
import org.json.JSONArray
import org.json.JSONObject
import org.videolan.libvlc.LibVLC
import org.videolan.libvlc.Media
import org.videolan.libvlc.MediaPlayer
import org.videolan.libvlc.util.VLCVideoLayout

class VlcActivity : BaseActivity() {
    private lateinit var lib: LibVLC
    private lateinit var mp: MediaPlayer
    private lateinit var video: VLCVideoLayout
    private lateinit var spin: ProgressBar
    private lateinit var info: TextView
    private var live = false
    private var cat = Db.ALL
    private var curId = 0L
    private var row: Db.Row? = null
    private var ids = LongArray(0)
    private var idx = 0
    private var kind = "movie"
    private var vodId = ""
    private var title = ""
    private var url = ""
    private var startPos = 0L
    @Volatile private var released = false
    // funzioni intelligenti: lettore imparato, abitudini, zapping
    /** aperto con VLC perché imparato per il formato (non scelto dal cliente o dal pannello): se non parte si torna a ExoPlayer */
    private var learnedKey = ""
    /** arrivato da ExoPlayer (recupero automatico) */
    private var engLeft = ""
    private var engKey = ""
    private var playedAt = 0L
    private var lastErrAt = 0L
    private var engDone = false
    private var everPlayed = false
    private var habAt = 0L
    private var habDone = false
    private var prevK = ""
    private var zapDir = 0
    /** errore già mandato al pannello per questo canale */
    private var errSentFor = 0L
    private var tunes = 0
    private val io = java.util.concurrent.Executors.newSingleThreadExecutor { r -> Thread(r, "kx-vlc").apply { isDaemon = true } }
    private val hideInfo = Runnable { info.visibility = View.GONE }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        live = intent.getStringExtra("kind") == "live"
        cat = intent.getStringExtra("cat") ?: Db.ALL
        curId = intent.getLongExtra("rowId", 0)
        kind = intent.getStringExtra("kind") ?: "movie"
        vodId = intent.getStringExtra("id") ?: ""
        title = intent.getStringExtra("title") ?: ""
        url = intent.getStringExtra("url") ?: ""
        startPos = intent.getLongExtra("pos", 0)
        learnedKey = intent.getStringExtra("learned").orEmpty()
        engLeft = intent.getStringExtra("engLeft").orEmpty()
        engKey = intent.getStringExtra("engKey").orEmpty().ifEmpty { learnedKey }

        video = VLCVideoLayout(this)
        val root = FrameLayout(this).apply { setBackgroundColor(android.graphics.Color.BLACK) }
        root.addView(video, FrameLayout.LayoutParams(-1, -1))
        spin = ProgressBar(this).apply {
            isIndeterminate = true
            indeterminateTintList = android.content.res.ColorStateList.valueOf(androidx.core.content.ContextCompat.getColor(this@VlcActivity, R.color.accent))
        }
        root.addView(spin, FrameLayout.LayoutParams(Ui.dp(this, 56), Ui.dp(this, 56), Gravity.CENTER))
        info = TextView(this).apply {
            setTextColor(androidx.core.content.ContextCompat.getColor(this@VlcActivity, R.color.text))
            textSize = 20f
            setPadding(Ui.dp(this@VlcActivity, 20), Ui.dp(this@VlcActivity, 12), Ui.dp(this@VlcActivity, 20), Ui.dp(this@VlcActivity, 12))
            setBackgroundResource(R.drawable.bg_overlay)
            visibility = View.GONE
        }
        root.addView(info, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.START).apply {
            leftMargin = Ui.dp(this@VlcActivity, 24)
            topMargin = Ui.dp(this@VlcActivity, 24)
        })
        setContentView(root)
        root.isFocusableInTouchMode = true
        root.requestFocus()

        val opts = arrayListOf("--http-reconnect", "--no-stats", "--no-video-title-show")
        lib = LibVLC(applicationContext, opts)
        mp = MediaPlayer(lib)
        mp.attachViews(video, null, true, false)
        mp.setEventListener { e ->
            when (e.type) {
                MediaPlayer.Event.Opening -> ui { spin.visibility = View.VISIBLE }
                MediaPlayer.Event.Buffering -> ui { spin.visibility = if (e.buffering >= 100f) View.GONE else View.VISIBLE }
                MediaPlayer.Event.Playing -> ui { spin.visibility = View.GONE; everPlayed = true; if (playedAt == 0L) playedAt = SystemClock.elapsedRealtime() }
                MediaPlayer.Event.TimeChanged -> ui { if (mp.isPlaying) spin.visibility = View.GONE; checkSmart() }
                MediaPlayer.Event.EncounteredError -> ui { if (!isFinishing) onError() }
            }
        }
        if (live) {
            val requested = curId
            bg {
                val channels = Db.liveIds(cat, K.activeIds(), K.kids, Store.favs("live"))
                ui {
                    if (isFinishing) return@ui
                    ids = channels
                    val startId = ids.firstOrNull { it == requested } ?: ids.firstOrNull() ?: 0L
                    if (startId > 0) {
                        idx = ids.indexOf(startId)
                        tune(startId)
                    } else {
                        spin.visibility = View.GONE
                        showInfo()
                    }
                }
            }
        } else {
            watchVod()
            showInfo()
            play(url, startPos)
        }
        Pulse.stats = { stats() }
    }

    override fun onResume() {
        super.onResume()
        Pulse.stats = { stats() }
        if (live) row?.let { Pulse.watch(Pulse.Watch("live", "", title, it.s("k"), "", it.s("pl"), ck(it))) } else watchVod()
    }

    /** statistiche per il pannello: lettore e, appena nota, l'altezza del video (il pannello sa che il canale va) */
    private fun stats(): JSONObject {
        val o = JSONObject().put("engine", "vlc")
        val h = try { if (released) 0 else mp.currentVideoTrack?.height ?: 0 } catch (_: Throwable) { 0 }
        if (h > 0) o.put("res", "${h}p")
        return o
    }

    private fun ck(r: Db.Row): String = Ck.of(Play.liveUrls(r).firstOrNull() ?: "", r.s("sid"))

    /** VLC non apre il contenuto */
    private fun onError() {
        spin.visibility = View.GONE
        lastErrAt = SystemClock.elapsedRealtime()
        if (engKey.isNotEmpty() && !engDone) { engDone = true; Engines.fail("vlc", engKey) }
        // canali non funzionanti: anche VLC lo dice al pannello, con il canale «server|flusso» (una volta per canale)
        val r = row
        if (live && r != null && errSentFor != curId) {
            errSentFor = curId
            val o = JSONObject().put("t", System.currentTimeMillis()).put("problem", "error").put("action", "nessuna").put("outcome", "non risolto")
                .put("err", "vlc").put("title", r.s("name")).put("id", r.s("k")).put("k", "live").put("engine", "vlc")
            ck(r).takeIf { it.isNotEmpty() }?.let { o.put("ck", it) }
            net { Panel.errors(JSONArray().put(o)) }
        }
        // VLC scelto dall'app (imparato) che non parte: si torna da soli a ExoPlayer, che non ripassa a VLC per questo contenuto
        if (learnedKey.isNotEmpty() && !everPlayed) { backToExo(); return }
        showInfo("Il contenuto non si apre")
    }

    private fun backToExo() {
        val key = if (live) "live:${row?.s("pl").orEmpty()}:$curId" else "$kind:" + (intent.getStringExtra("recent")?.let { try { JSONObject(it).optString("pl") } catch (_: Exception) { "" } }.orEmpty()) + ":$vodId"
        PlayerActivity.vlcFallbacks.add(key)
        val i = Intent(this, PlayerActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_FORWARD_RESULT)
        if (live) i.putExtra("kind", "live").putExtra("cat", cat).putExtra("rowId", curId)
        else { i.putExtras(intent); i.removeExtra("learned") }
        try { startActivity(i) } catch (_: Exception) { showInfo("Il contenuto non si apre"); return }
        finish()
    }

    /** solo conti: riuscita di VLC per il formato (lettore imparato) e abitudini (3 minuti di fila, 5 per film e serie) */
    private fun checkSmart() {
        if (!Smart.on()) return
        val now = SystemClock.elapsedRealtime()
        if (!engDone && playedAt > 0 && engKey.isNotEmpty() && now - lastErrAt > 30_000L && now - playedAt >= (if (engLeft == "exo") 30_000L else 60_000L)) {
            engDone = true
            if (engLeft == "exo") Engines.fail("exo", engKey)
            Engines.ok("vlc", engKey)
        }
        if (!habDone && habAt > 0 && everPlayed && now - habAt >= (if (live) 180_000L else 300_000L)) {
            habDone = true
            val t = System.currentTimeMillis()
            if (live) row?.let { HabitStore.note(HabitStore.Note(Store.profileId, 'l', it.s("k"), it.s("name"), it.s("cat"), "", t)) }
            else {
                val series = kind == "series"
                val k = if (series) intent.getStringExtra("recent")?.let { try { JSONObject(it).optString("group") } catch (_: Exception) { "" } }.orEmpty() else vodId
                if (k.isNotEmpty()) HabitStore.note(HabitStore.Note(Store.profileId, if (series) 's' else 'm', k, title, "", "", t))
            }
        }
    }

    override fun onStart() {
        super.onStart()
        Img.playerOn()
        for (a in App.alive.toList()) if (a !== this && a is BaseActivity && a.stopped) a.releaseForPlayer()
    }

    override fun onKicked() {
        if (::mp.isInitialized) io.execute { try { mp.stop() } catch (_: Exception) {} }
        Pulse.watch(null)
        finish()
    }

    override fun onStop() {
        super.onStop()
        if (!live && vodId.isNotEmpty()) Store.setPosition(vodId, mp.time.coerceAtLeast(0L), mp.length.coerceAtLeast(0L))
        Pulse.stats = null
        Pulse.watch(null)
        Img.playerOff()
        io.execute { try { if (!released) mp.stop() } catch (_: Exception) {} }
    }

    override fun onDestroy() {
        super.onDestroy()
        App.main.removeCallbacks(hideInfo)
        released = true
        try { mp.setEventListener(null) } catch (_: Exception) {}
        try { mp.detachViews() } catch (_: Exception) {}
        io.execute {
            try { mp.stop() } catch (_: Exception) {}
            try { mp.release() } catch (_: Exception) {}
            try { lib.release() } catch (_: Exception) {}
            io.shutdown()
        }
    }

    private fun tune(id: Long) {
        curId = id
        bg {
            val r = Db.one("live", id) ?: return@bg
            val u = Play.liveUrls(r).firstOrNull().orEmpty()
            ui {
                if (isFinishing || id != curId) return@ui
                row = r
                title = r.s("name")
                info.text = title
                idx = ids.indexOf(id).coerceAtLeast(0)
                Pulse.watch(Pulse.Watch("live", "", title, r.s("k"), "", r.s("pl"), ck(r)))
                // canale nuovo: abitudine e riuscita da contare da capo; sequenza dello zapping e DNS del successivo
                playedAt = 0L; everPlayed = false; habAt = SystemClock.elapsedRealtime(); habDone = false
                // dopo lo zapping il lettore imparato non riguarda più il canale da cui si era partiti
                if (tunes++ > 0) { engKey = ""; learnedKey = ""; engLeft = "" }
                if (Smart.on()) {
                    if (prevK.isNotEmpty() && prevK != r.s("k")) Zap.learn(prevK, r.s("k"))
                    prevK = r.s("k")
                    if (zapDir != 0 && ids.size > 1) { val nx = ids[(idx + zapDir).mod(ids.size)]; bg { Db.one("live", nx)?.let { n -> Play.liveUrls(n).firstOrNull()?.let { u -> Zap.warm(u) } } } }
                }
                if (u.isEmpty()) { spin.visibility = View.GONE; showInfo(); return@ui }
                play(u, 0)
                showInfo()
            }
        }
    }

    private fun watchVod() {
        if (habAt == 0L) habAt = SystemClock.elapsedRealtime()
        info.text = title
        val pl = intent.getStringExtra("recent")?.let { try { JSONObject(it).optString("pl") } catch (_: Exception) { "" } }.orEmpty()
        Pulse.watch(Pulse.Watch(kind, title, "", vodId, "", pl))
    }

    private fun play(value: String, position: Long) {
        if (isFinishing) return
        if (value.isEmpty()) { spin.visibility = View.GONE; showInfo("Indirizzo non valido"); return }
        url = value
        spin.visibility = View.VISIBLE
        io.execute {
            if (released) return@execute
            try {
                val media = Media(lib, Uri.parse(value))
                media.setHWDecoderEnabled(Remote.player("o.hw", "auto") != "off", false)
                val buffer = Remote.player("p.buffer", "balanced")
                val cache = if (live) when (buffer) { "fast" -> 1000; "stable" -> 3000; else -> 1500 } else when (buffer) { "fast" -> 1000; "stable" -> 5000; else -> 2500 }
                media.addOption(":network-caching=$cache")
                media.addOption(if (live) ":live-caching=$cache" else ":file-caching=$cache")
                Remote.player("p.quality", "auto").toIntOrNull()?.takeIf { it in listOf(480, 720, 1080) }?.let { media.addOption(":adaptive-maxheight=$it") }
                if (position > 0) media.addOption(":start-time=${position / 1000.0}")
                mp.media = media
                media.release()
                mp.play()
            } catch (_: Exception) {
                ui { spin.visibility = View.GONE; showInfo() }
            }
        }
    }

    private fun zap(delta: Int) {
        if (!live || ids.isEmpty()) return
        zapDir = delta
        idx = (idx + delta).mod(ids.size)
        tune(ids[idx])
    }

    private fun showInfo(message: String? = null) {
        info.text = message ?: title
        info.visibility = View.VISIBLE
        App.main.removeCallbacks(hideInfo)
        App.main.postDelayed(hideInfo, 5_000)
    }

    private fun leave() {
        if (live) setResult(RESULT_OK, Intent().putExtra("ch", curId).putExtra("chK", row?.s("k") ?: ""))
        finish()
    }

    @Suppress("DEPRECATION")
    override fun onBackPressed() = leave()

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        when (keyCode) {
            KeyEvent.KEYCODE_BACK -> { leave(); return true }
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_INFO -> {
                if (info.visibility == View.VISIBLE) { App.main.removeCallbacks(hideInfo); info.visibility = View.GONE } else showInfo()
                return true
            }
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_CHANNEL_UP -> { zap(1); return true }
            KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_CHANNEL_DOWN -> { zap(-1); return true }
        }
        return super.onKeyDown(keyCode, event)
    }
}
