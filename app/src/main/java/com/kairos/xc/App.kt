package com.kairos.xc

import android.app.Activity
import android.app.ActivityManager
import android.app.Application
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Kairos XC: app nativa leggera (viste Android classiche, nessuna WebView).
 * Su dispositivi con poca memoria parte da sola in «modalità leggera»: niente animazioni,
 * niente anteprima video nella lista canali, immagini a 16 bit e buffer più piccoli.
 */
class App : Application() {
    /** processo a parte del trailer (TrailerActivity): solo la pagina del video, niente archivio, pannello o immagini */
    private var trailerProcess = false
    private fun processName(): String =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) Application.getProcessName()
        else try { java.io.File("/proc/self/cmdline").readText().substringBefore('\u0000').ifBlank { packageName } } catch (_: Exception) { packageName }

    override fun onCreate() {
        super.onCreate()
        trailerProcess = processName().endsWith(":trailer")
        if (trailerProcess) return
        I = this
        Store.init(this)
        Db.init(this)
        lite = detectLite()
        max = Store.liteMode == "max"
        // chiavetta da 1 GB circa (classe D) con la modalità leggera «Automatica»: cache e buffer più piccoli (13 novembre 2026)
        tiny = !max && Store.liteMode == "auto" && Line.device() == "D"
        // versione nuova (e APK già scaricato) dall'ultimo controllo: il blocco obbligatorio vale subito, anche senza rete
        Updater.restore()
        // immagini (vedi Img): la Home non ne carica, quindi Glide si prepara in sottofondo dopo i primi fotogrammi
        // (se una lista arriva prima, si prepara lì al primo caricamento)
        main.postDelayed({ bg { Img.glide() } }, 1_500)
        // file dell'aggiornamento già installato: via, le chiavette hanno poco spazio
        bg {
            for (d in listOfNotNull(java.io.File(cacheDir, "apk"), externalCacheDir)) java.io.File(d, "kairos-xc-update.apk").takeIf { it.exists() }?.delete()
        }
        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            override fun onActivityCreated(a: Activity, b: Bundle?) { alive.add(a) }
            override fun onActivityStarted(a: Activity) {
                started++
                if (started == 1) Pulse.foreground(true)
            }
            override fun onActivityResumed(a: Activity) { top = a }
            override fun onActivityPaused(a: Activity) { if (top === a) top = null }
            override fun onActivityStopped(a: Activity) {
                started--
                if (started <= 0) { started = 0; Pulse.foreground(false) }
            }
            override fun onActivitySaveInstanceState(a: Activity, b: Bundle) {}
            override fun onActivityDestroyed(a: Activity) { alive.remove(a) }
        })
    }

    // poca memoria o app non più visibile: la cache delle immagini si svuota
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (trailerProcess) return
        Img.trim(level)
        // sistema a corto di memoria mentre l'app è in uso: i lavori pesanti in sottofondo (guida XMLTV) aspettano
        if (level == android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW || level == android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL || level >= android.content.ComponentCallbacks2.TRIM_MEMORY_COMPLETE) memTightFor(3 * 60_000L)
    }

    override fun onLowMemory() {
        super.onLowMemory()
        if (trailerProcess) return
        Img.trim(android.content.ComponentCallbacks2.TRIM_MEMORY_COMPLETE)
        memTightFor(5 * 60_000L)
    }

    fun detectLite(): Boolean = when (Store.liteMode) {
        "on", "max" -> true
        "off" -> false
        else -> autoLite()
    }

    /** chiavette e box con meno di ~1,8 GB di RAM o Android vecchio (Fire OS 5/6) */
    fun autoLite(): Boolean {
        val am = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        if (am.isLowRamDevice) return true
        val mi = ActivityManager.MemoryInfo()
        am.getMemoryInfo(mi)
        if (mi.totalMem in 1 until 1_800_000_000L) return true
        return Build.VERSION.SDK_INT < 24
    }

    companion object {
        lateinit var I: App
        fun isPlayer(a: Activity?): Boolean = a is PlayerActivity || a is VlcActivity
        /** poca memoria segnalata dal sistema negli ultimi minuti */
        @Volatile private var memTightUntil = 0L
        val memTight: Boolean get() = android.os.SystemClock.elapsedRealtime() < memTightUntil
        fun memTightFor(ms: Long) { memTightUntil = android.os.SystemClock.elapsedRealtime() + ms }
        @Volatile var lite = false
        /** «Leggerissima»: per le chiavette più vecchie (liste senza loghi, immagini più piccole, canali in HD) */
        @Volatile var max = false
        /** pacchetto «chiavetta da 1 GB» (vedi onCreate) */
        @Volatile var tiny = false
        val main = Handler(Looper.getMainLooper())
        val io: ExecutorService = Executors.newFixedThreadPool(3) { r -> Thread(r, "kx-io").apply { isDaemon = true } }
        var started = 0
        var top: Activity? = null
        /** schermate create e non ancora distrutte (thread principale): entrando nel lettore lasciano andare le immagini */
        val alive = ArrayList<Activity>()
    }
}

fun bg(block: () -> Unit) {
    App.io.execute { try { block() } catch (t: Throwable) { Log.w("KairosXC", "bg", t) } }
}

/** rete lenta (guida TV, schede) su thread propri: non blocca le letture dell'archivio */
private val netPool = Executors.newFixedThreadPool(2) { r -> Thread(r, "kx-net").apply { isDaemon = true } }
fun net(block: () -> Unit) {
    netPool.execute { try { block() } catch (t: Throwable) { Log.w("KairosXC", "net", t) } }
}

fun ui(block: () -> Unit) {
    App.main.post { try { block() } catch (t: Throwable) { Log.w("KairosXC", "ui", t) } }
}
