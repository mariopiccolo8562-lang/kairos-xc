package com.kairos.xc

import android.app.Activity
import android.app.ActivityManager
import android.content.ComponentCallbacks2
import android.content.Context
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import com.bumptech.glide.Glide
import com.bumptech.glide.GlideBuilder
import com.bumptech.glide.MemoryCategory
import com.bumptech.glide.RequestManager
import com.bumptech.glide.load.DecodeFormat
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.bumptech.glide.load.engine.bitmap_recycle.LruBitmapPool
import com.bumptech.glide.load.engine.cache.InternalCacheDiskCacheFactory
import com.bumptech.glide.load.engine.cache.LruResourceCache
import com.bumptech.glide.request.RequestOptions

/**
 * Immagini (locandine, sfondi, loghi) con poca memoria: sulle chiavette da 1 GB sono loro a riempirla.
 * - Decodificate alla misura mostrata (override), mai all'originale; a 16 bit (RGB_565) sui dispositivi deboli: classe D di
 *   Kairos Adaptive o modalità leggera. Le immagini con trasparenza (molti loghi PNG) restano a 32 bit: lo decide Glide dal file.
 * - Locandine e sfondi di TMDB (image.tmdb.org/t/p/<misura>/…) chiesti già piccoli: locandine w185/w342 (classe D sempre w185),
 *   sfondi e fotogrammi w780. Se la variante piccola non arriva si ripiega sull'indirizzo originale.
 * - Cache in memoria per classe (D: mai oltre 1/8 della memoria dell'app) e su disco, nella cartella cache dell'app
 *   (Android la svuota da sola se manca spazio), con un tetto: D 150 MB, N/O 250 MB.
 * - Tasto del telecomando tenuto premuto (scorrimento veloce): i caricamenti aspettano che ci si fermi; le righe uscite dallo
 *   schermo annullano il loro caricamento (onViewRecycled).
 * - Entrando nel lettore la memoria delle immagini si libera e le schermate sotto lasciano andare sfondi e locandine
 *   (si ricaricano dal disco al ritorno): il buffer video ha spazio.
 */
object Img {
    /** loghi e icone: indirizzo mai cambiato */
    const val LOGO = 0
    /** locandine (2:3) */
    const val POSTER = 1
    /** sfondi e fotogrammi degli episodi (16:9) */
    const val BACKDROP = 2

    /** dispositivo debole (classe D) o modalità leggera: locandine e loghi a 16 bit, metà memoria */
    fun low(): Boolean = App.lite || Line.device() == "D"

    /* ---------------- Glide con le misure dell'app ---------------- */
    private val lock = Any()
    @Volatile private var ready = false

    /** Glide pronto con le nostre cache (la prima chiamata lo prepara: da qualunque thread) */
    fun glide(): Glide {
        if (!ready) synchronized(lock) {
            if (!ready) {
                // se qualcosa va storto Glide parte comunque, con le sue misure
                try { Glide.init(App.I, builder(App.I)) } catch (_: Throwable) {}
                ready = true
            }
        }
        return Glide.get(App.I)
    }

    fun with(v: View): RequestManager {
        glide()
        return Glide.with(v)
    }

    private fun builder(c: Context): GlideBuilder {
        val low = low()
        // memoria: come prima (4 / 6 / 20 MB), ma sulla classe D mai oltre 1/8 della memoria concessa all'app
        var mem = if (App.max) 4L shl 20 else if (App.lite) 6L shl 20 else 20L shl 20
        if (Line.device() == "D") mem = minOf(mem, (appMemory(c) / 8).coerceAtLeast(4L shl 20))
        // disco: cartella cache predefinita di Glide (quella di sempre), tetto per classe
        val disk = if (low) 150L shl 20 else 250L shl 20
        return GlideBuilder()
            .setMemoryCache(LruResourceCache(mem))
            .setBitmapPool(LruBitmapPool(mem / 2))
            .setDiskCache(InternalCacheDiskCacheFactory(c, disk))
            .setDefaultRequestOptions(RequestOptions().format(if (low) DecodeFormat.PREFER_RGB_565 else DecodeFormat.PREFER_ARGB_8888))
            .setLogLevel(android.util.Log.ERROR)
    }

    private fun appMemory(c: Context): Long = try {
        (c.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).memoryClass.toLong() shl 20
    } catch (_: Exception) { 128L shl 20 }

    /* ---------------- caricamento ---------------- */
    /** [url] in [v] decodificata a [w]×[h] pixel (la misura della vista); [kind] = LOGO, POSTER o BACKDROP */
    fun load(v: ImageView, url: String?, w: Int, h: Int, kind: Int = LOGO) {
        if (url.isNullOrBlank() || !url.startsWith("http")) {
            clear(v)
            v.setImageDrawable(null)
            return
        }
        val rm = with(v)
        val low = low()
        val small = if (kind == LOGO) null else tmdb(url, kind, w)
        var rq = rm.load(small ?: url).override(w, h).dontAnimate().diskCacheStrategy(DiskCacheStrategy.AUTOMATIC)
        if (low) rq = rq.format(DecodeFormat.PREFER_RGB_565)
        if (small != null) {
            // la misura piccola non c'è o non risponde: l'indirizzo originale, decodificato alla stessa misura
            var err = rm.load(url).override(w, h).dontAnimate().diskCacheStrategy(DiskCacheStrategy.AUTOMATIC)
            if (low) err = err.format(DecodeFormat.PREFER_RGB_565)
            // come fa Glide per la richiesta principale: il ritaglio segue lo scaleType della vista
            err = when (v.scaleType) {
                ImageView.ScaleType.CENTER_CROP -> err.optionalCenterCrop()
                ImageView.ScaleType.CENTER_INSIDE, ImageView.ScaleType.FIT_XY -> err.optionalCenterInside()
                ImageView.ScaleType.FIT_CENTER, ImageView.ScaleType.FIT_START, ImageView.ScaleType.FIT_END -> err.optionalFitCenter()
                else -> err
            }
            rq = rq.error(err)
        }
        rq.into(v)
    }

    /** caricamento annullato e immagine lasciata andare (riga uscita dallo schermo, schermata sotto il lettore) */
    fun clear(v: ImageView) {
        try { with(v).clear(v) } catch (_: Exception) {}
    }

    /** tutte le immagini di Glide dentro [v] (le icone fisse non si toccano: per Glide non sono sue) */
    fun clearAll(v: View) {
        if (v is ImageView) clear(v)
        else if (v is ViewGroup) for (i in 0 until v.childCount) clearAll(v.getChildAt(i))
    }

    /* ---------------- TMDB ---------------- */
    private val TMDB = Regex("^(https?://image\\.tmdb\\.org/t/p/)([A-Za-z0-9_]+)(/.+)$", RegexOption.IGNORE_CASE)
    private val RX_W = Regex("^w(\\d+)")
    private val RX_H = Regex("^h(\\d+)")

    /**
     * Indirizzo TMDB con la misura più piccola adatta alla vista larga [w] pixel; null = da lasciare com'è
     * (non è TMDB, misura sconosciuta, oppure è già piccola: mai una misura più grande di quella del pannello).
     */
    fun tmdb(url: String, kind: Int, w: Int): String? {
        if (!url.contains("image.tmdb.org", ignoreCase = true)) return null
        val m = TMDB.find(url) ?: return null
        val size = m.groupValues[2].lowercase()
        val want = if (kind == POSTER) (if (w <= 185 || Line.device() == "D") 185 else 342) else 780
        val cur = if (size == "original") Int.MAX_VALUE
        else RX_W.find(size)?.groupValues?.get(1)?.toIntOrNull()
            // misure per altezza (es. h632): larghezza ricavata dalle proporzioni di locandine (2:3) e sfondi (16:9)
            ?: RX_H.find(size)?.groupValues?.get(1)?.toIntOrNull()?.let { if (kind == POSTER) it * 2 / 3 else it * 16 / 9 }
            ?: return null
        if (cur <= want) return null
        return m.groupValues[1] + "w" + want + m.groupValues[3]
    }

    /* ---------------- scorrimento ---------------- */
    /** tasto tenuto premuto (dalla terza ripetizione): scorrimento veloce, i caricamenti aspettano il rilascio */
    private var held = false
    /** liste che stanno scorrendo (in modalità leggera i caricamenti aspettano anche lì, come prima) */
    private var moving = 0

    /** ogni tasto delle schermate (thread principale) */
    fun key(a: Activity, e: KeyEvent) {
        when (e.keyCode) {
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT,
            KeyEvent.KEYCODE_CHANNEL_UP, KeyEvent.KEYCODE_CHANNEL_DOWN -> {}
            else -> return
        }
        if (e.action == KeyEvent.ACTION_DOWN && e.repeatCount >= 2) { if (!held) { held = true; update(root(a)) } }
        else if (e.action == KeyEvent.ACTION_UP && held) { held = false; update(root(a)) }
    }

    /** una lista comincia ([on]) o finisce di scorrere */
    fun scrolling(v: View, on: Boolean) {
        moving = (moving + if (on) 1 else -1).coerceAtLeast(0)
        update(v)
    }

    /** schermata appena aperta o ripresa: nessun tasto tenuto, nessuna lista in movimento */
    fun reset(a: Activity) {
        if (!held && moving == 0) return
        held = false
        moving = 0
        update(root(a))
    }

    /** una vista della schermata (con il contesto dell'Activity, come le righe delle liste) */
    private fun root(a: Activity): View = a.findViewById<View>(android.R.id.content) ?: a.window.decorView

    private fun update(v: View) {
        if (!ready) return // Glide non ancora partito: niente in corso da fermare
        try {
            val rm = Glide.with(v)
            if (held || (App.lite && moving > 0)) rm.pauseRequests() else rm.resumeRequests()
        } catch (_: Exception) {}
    }

    /* ---------------- memoria ---------------- */
    /** lettori aperti: finché ce n'è uno le cache delle immagini restano a metà */
    private var players = 0

    /** entrando nel lettore (thread principale) */
    fun playerOn() {
        players++
        if (!ready) return
        try {
            val g = Glide.get(App.I)
            g.setMemoryCategory(MemoryCategory.LOW)
            free(g)
        } catch (_: Throwable) {}
    }

    /** uscendo dal lettore (thread principale) */
    fun playerOff() {
        players = (players - 1).coerceAtLeast(0)
        if (players > 0 || !ready) return
        try { Glide.get(App.I).setMemoryCategory(MemoryCategory.NORMAL) } catch (_: Throwable) {}
    }

    /** memoria delle immagini vuota (thread principale) */
    fun free() {
        if (!ready) return
        try { free(Glide.get(App.I)) } catch (_: Throwable) {}
    }

    /**
     * Prima la cache, poi le bitmap da riusare. Le immagini tolte dalla cache tornano fra quelle da riusare un attimo dopo
     * (Glide le rimette al giro successivo del thread principale): quindi le bitmap da riusare si svuotano anche lì.
     */
    private fun free(g: Glide) {
        g.clearMemory()
        App.main.post { try { g.bitmapPool.clearMemory() } catch (_: Throwable) {} }
    }

    /** poca memoria nel sistema (anche con l'app davanti) o app non più visibile: cache delle immagini vuota */
    fun trim(level: Int) {
        if (!ready) return
        try {
            val g = Glide.get(App.I)
            if (level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) free(g) else g.trimMemory(level)
        } catch (_: Throwable) {}
    }
}
