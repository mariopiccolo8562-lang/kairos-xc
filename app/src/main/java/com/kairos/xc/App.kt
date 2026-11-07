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
    override fun onCreate() {
        super.onCreate()
        I = this
        Store.init(this)
        Db.init(this)
        lite = detectLite()
        max = Store.liteMode == "max"
        // immagini: memoria e spazio su disco con un tetto per classe del dispositivo (vedi Img)
        Img.glide()
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
        Img.trim(level)
    }

    override fun onLowMemory() {
        super.onLowMemory()
        Img.trim(android.content.ComponentCallbacks2.TRIM_MEMORY_COMPLETE)
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
        @Volatile var lite = false
        /** «Leggerissima»: per le chiavette più vecchie (liste senza loghi, immagini più piccole, canali in HD) */
        @Volatile var max = false
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
