package com.kairos.xc

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.Signature
import android.net.ConnectivityManager
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import android.provider.Settings
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Aggiornamento automatico: file degli aggiornamenti da store-k.download (/get/xc.json) o dai domini di riserva,
 * niente pannello né GitHub (vedi [UpdFeed]); APK dal link che c'è dentro, poi dalle altre basi.
 * Si controlla a ogni apertura dell'app (al massimo ogni 10 minuti) e ogni ora mentre resta aperta.
 * Aggiornamento in background (scelta del cliente o imposta dal pannello, acceso di serie): la versione nuova
 * si scarica e si verifica in silenzio (solo su Wi-Fi o cavo, mai durante la visione); quando è pronta l'app
 * propone «Installa ora» appena si è in un menu e al cliente resta solo la conferma di Android.
 * Dal pannello: «Invia aggiornamento» (upd nel battito) o il comando «update» fanno partire subito il controllo.
 */
object Updater {
    class Upd(val code: Int, val ver: String, val url: String)

    private val busy = AtomicBoolean(false)
    private val checking = AtomicBoolean(false)
    /** versione nuova trovata e non ancora installata */
    @Volatile var pending: Upd? = null
        private set
    /** APK della versione [pending] già scaricato e verificato */
    @Volatile var ready: File? = null
        private set
    /** già proposta in questa apertura dell'app */
    @Volatile private var askedCode = 0
    @Volatile private var lastCheck = 0L
    @Volatile private var prefetchFailed = 0
    /** proposta (non obbligatoria) a video in questo momento e contenuto che parte dopo «Più tardi» (thread principale) */
    private var offerDlg: AlertDialog? = null
    private var afterLater: (() -> Unit)? = null
    /** «Installa ora» premuto senza il permesso di Android: l'installazione riparte da sola al ritorno nell'app */
    @Volatile private var afterPerm: File? = null
    /** «Aggiorna ora» mentre l'APK si scaricava già in sottofondo: si installa appena è pronto */
    @Volatile private var installWhenReady = false

    /**
     * All'apertura dell'app, prima di ogni schermata: la versione nuova trovata l'ultima volta torna dal disco, con il suo APK
     * se è già scaricato. Così il blocco dell'aggiornamento obbligatorio vale subito, anche senza rete e prima di qualunque
     * canale, e la proposta normale compare nel primo menu. L'APK si ricontrolla (app, versione, firma) in sottofondo.
     */
    fun restore() {
        val code = Store.updCode
        if (code <= 0) return
        val url = Store.updUrl
        if (code <= BuildConfig.VERSION_CODE || !url.startsWith("https://")) { Store.saveUpd(0, "", ""); return } // già installata
        val u = Upd(code, Store.updVer, url)
        pending = u
        val f = fileFor(u)
        if (!f.isFile || f.length() < 300_000) return
        ready = f
        bg {
            if (staged(u) == null && pending?.code == u.code && ready == f) {
                ready = null // non è più valido: si riscarica, il blocco torna quando è pronto
                ui { try { lock?.dismiss() } catch (_: Exception) {}; lock = null }
            }
        }
    }

    /** l'app torna in primo piano: nuovo controllo e, se c'è già una versione nuova, la si propone di nuovo */
    fun onForeground() {
        askedCode = 0
        maybeCheck(10 * 60_000L)
    }

    /** chiamato anche dal battito ogni 20 s: il controllo vero parte solo ogni [gap] */
    fun maybeCheck(gap: Long = 60 * 60_000L) {
        val now = SystemClock.elapsedRealtime()
        if (lastCheck != 0L && now - lastCheck < gap) return
        if (!checking.compareAndSet(false, true)) return
        lastCheck = now
        bg { try { check(null) } finally { checking.set(false) } }
    }

    /**
     * Il pannello chiede l'aggiornamento (upd nel battito o comando «update»): controllo subito, scaricamento in background e
     * proposta. [done] riceve l'esito per la conferma del comando: false = controllo non riuscito (prima, senza rete o con
     * dominio dei download e riserve irraggiungibili, la conferma diceva «già aggiornata»).
     */
    fun fromPanel(done: ((Boolean, String) -> Unit)? = null) {
        if (!checking.compareAndSet(false, true)) { done?.invoke(true, "controllo già in corso"); return }
        lastCheck = SystemClock.elapsedRealtime()
        bg {
            var ok = true
            var info = ""
            try {
                val (reached, u) = checkNow(null, force = true)
                info = when {
                    u != null -> "versione " + u.ver + when {
                        App.isPlayer(App.top) -> ": proposta a fine visione"
                        ready != null -> " pronta: proposta al cliente"
                        busy.get() -> " in scaricamento"
                        else -> ": proposta al cliente"
                    }
                    reached -> "già aggiornata (" + BuildConfig.VERSION_NAME + ")"
                    else -> { ok = false; "controllo non riuscito: il dominio dei download non è raggiungibile dal dispositivo" }
                }
            } catch (t: Throwable) {
                ok = false
                info = "controllo non riuscito: " + (t.message ?: t.javaClass.simpleName)
            } finally { checking.set(false) }
            done?.invoke(ok, info)
        }
    }

    /** [manual] = schermata da cui l'utente ha chiesto il controllo (riceve anche «nessun aggiornamento»); [force] = anche su rete a consumo */
    fun check(manual: Activity?, force: Boolean = false): Upd? = checkNow(manual, force).second

    /** come [check]; in più se la risposta è arrivata (false = dominio dei download e riserve non raggiungibili: non si sa se c'è una versione nuova) */
    private fun checkNow(manual: Activity?, force: Boolean): Pair<Boolean, Upd?> {
        val apk = Panel.update()?.optJSONObject("apk")
        val code = apk?.optInt("versionCode", 0) ?: 0
        val url = apk?.optString("url", "") ?: ""
        val ver = apk?.optString("version", "") ?: ""
        if (code > BuildConfig.VERSION_CODE && url.startsWith("https://")) {
            val u = Upd(code, ver, url)
            firstSeen(u)
            if (pending?.code != code) { ready = null; prefetchFailed = 0 }
            pending = u
            Store.saveUpd(u.code, u.ver, u.url) // su disco: alla prossima apertura vale subito, anche senza rete
            staged(u)?.let { ready = it } // già scaricato in un'apertura precedente
            if (ready == null && manual == null && (Store.bgUpdate || force) && (force || !metered()) && prefetchFailed < 3) prefetch(u)
            ui { if (manual != null) prompt(manual, u) else offer(App.top) }
            return true to u
        }
        if (apk != null) { pending = null; ready = null; Store.saveUpd(0, "", ""); try { lock?.dismiss() } catch (_: Exception) {}; lock = null }
        else if (manual == null) lastCheck = SystemClock.elapsedRealtime() - 55 * 60_000L // rete giù: si riprova tra 5 minuti
        if (manual != null) ui { Ui.toast(if (apk == null) "Impossibile controllare gli aggiornamenti adesso" else "Hai già l'ultima versione (${BuildConfig.VERSION_NAME})") }
        return (apk != null) to null
    }

    private fun dir(): File = (if (Build.VERSION.SDK_INT >= 24) File(App.I.cacheDir, "apk") else (App.I.externalCacheDir ?: File(App.I.cacheDir, "apk"))).apply { mkdirs() }
    private fun fileFor(u: Upd) = File(dir(), "kairos-xc-${u.code}.apk")

    /** APK di [u] scaricato in precedenza e ancora valido (firma e versione giuste) */
    private fun staged(u: Upd): File? {
        val f = fileFor(u)
        if (!f.exists()) return null
        if (f.length() < 300_000 || ApkCheck.verify(App.I, f) != null) { f.delete(); return null }
        return f
    }

    /** rete a consumo (dati mobili, hotspot): niente download non richiesti */
    private fun metered(): Boolean = try {
        (App.I.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager).isActiveNetworkMetered
    } catch (_: Exception) { false }

    /** visione iniziata durante lo scaricamento in background: si interrompe e si riprova più tardi */
    private class Paused : Exception()

    /** un lettore è attivo (schermo intero o anteprima nella lista canali): la banda serve al video */
    private fun watching(): Boolean = App.isPlayer(App.top) || Players.previewing

    /** scaricamento in background (chiamato da un thread di lavoro): nessuna finestra; quando è pronto si propone «Installa ora» */
    private fun prefetch(u: Upd) {
        if (watching()) return // mai durante la visione: la banda serve al video
        if (!busy.compareAndSet(false, true)) return
        try {
            val out = fileFor(u)
            fetch(u.url, out, null) { watching() }
            ready = out
            prefetchFailed = 0
            cleanOld(out)
            ui {
                // il cliente aveva già premuto «Aggiorna ora»: si installa subito, senza chiederlo di nuovo
                val a = App.top
                if (installWhenReady && a != null && !App.isPlayer(a) && !a.isFinishing) { install(a, out); return@ui }
                askedCode = 0
                offer(App.top)
            }
        } catch (_: Paused) {
            try { fileFor(u).delete() } catch (_: Exception) {}
            lastCheck = SystemClock.elapsedRealtime() - 55 * 60_000L // non è un errore: si riprova tra 5 minuti
        } catch (_: Exception) {
            prefetchFailed++ // si riprova al prossimo controllo (massimo tre volte per versione)
        } finally { busy.set(false) }
    }

    /** vecchi APK scaricati: via tutti tranne [keep] */
    private fun cleanOld(keep: File?) {
        try { dir().listFiles()?.forEach { if (it != keep && it.name.endsWith(".apk")) it.delete() } } catch (_: Exception) {}
    }

    /**
     * scarica l'APK del link [url] del file degli aggiornamenti in [out] e lo verifica; se non riesce (rete, errore HTTP,
     * file sbagliato) lo stesso APK dalle altre basi dei download (/get/xc.apk), vedi
     * [UpdFeed.apkUrls]. [progress] riceve 0-99; [pause] = vero se bisogna interrompere (visione iniziata): non è un errore,
     * si riprova più tardi senza passare alle altre basi.
     */
    private fun fetch(url: String, out: File, progress: ((Int) -> Unit)?, pause: (() -> Boolean)? = null) =
        UpdFeed.firstOk(UpdFeed.apkUrls(url, BuildConfig.DL_BASE, "xc", Panel.dlExtra()), { it is Paused }) {
            if (pause?.invoke() == true) throw Paused() // visione iniziata: nessun altro tentativo adesso
            fetchOne(it, out, progress, pause)
        }

    /** scarica [url] in [out] e lo verifica (un solo indirizzo) */
    private fun fetchOne(url: String, out: File, progress: ((Int) -> Unit)?, pause: (() -> Boolean)?) {
        val c = Http.open(url, connectMs = 10_000, readMs = 30_000)
        try {
            if (c.responseCode != 200) throw Exception(if (c.responseCode == 404) "file non trovato (HTTP 404)" else "HTTP ${c.responseCode}")
            val total = c.contentLength.toLong()
            var done = 0L
            var last = -1
            c.inputStream.use { input ->
                FileOutputStream(out).use { fo ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        if (pause != null && pause()) throw Paused()
                        fo.write(buf, 0, n)
                        done += n
                        if (total > 0 && progress != null) {
                            val p = ((done * 100) / total).toInt().coerceAtMost(99)
                            if (p != last) { last = p; progress(p) }
                        }
                    }
                }
            }
            if (total > 0 && done != total) { out.delete(); throw Exception("download interrotto: controlla la connessione e riprova") }
        } finally { c.disconnect() }
        val head = ByteArray(2)
        FileInputStream(out).use { it.read(head) }
        if (out.length() < 300_000 || head[0] != 'P'.code.toByte() || head[1] != 'K'.code.toByte()) { out.delete(); throw Exception("il link non porta a un file APK") }
        ApkCheck.verify(App.I, out)?.let { msg -> out.delete(); throw Exception(msg) }
    }

    /** primo avviso al cliente di [u]: da lì contano i giorni di grazia dell'aggiornamento obbligatorio */
    private fun firstSeen(u: Upd): Long {
        if (Store.updFirstCode != u.code || Store.updFirstAt == 0L) { Store.updFirstCode = u.code; Store.updFirstAt = System.currentTimeMillis() }
        return Store.updFirstAt
    }

    /** l'app va bloccata: termine scaduto e APK già scaricato e verificato (mai per un problema di rete) */
    fun must(): Boolean {
        val u = pending ?: return false
        val days = Remote.forceDays ?: return false
        if (ready?.exists() != true) return false
        return System.currentTimeMillis() - firstSeen(u) >= days * 86_400_000L
    }

    /**
     * Propone la versione nuova (una volta per apertura, sempre se obbligatoria) appena si è in un menu; ogni schermata la chiama
     * all'apertura e al ritorno. Una proposta arrivata durante la visione compare così al ritorno nel menu, mai dietro il lettore.
     */
    fun offer(a: Activity?) {
        if (a == null || a.isFinishing || App.isPlayer(a)) return
        if (resumeInstall(a)) return
        val u = pending ?: return
        if (must()) { if (!busy.get()) prompt(a, u); return }
        if (askedCode == u.code || busy.get()) return
        askedCode = u.code
        afterLater = null
        prompt(a, u)
    }

    /**
     * Aggiornamento obbligatorio scaduto: niente visione (canale, anteprima, film), compare il blocco. Da chiamare prima di far
     * partire qualunque video; true = fermarsi.
     */
    fun blocked(a: Activity): Boolean {
        if (!must()) return false
        val u = pending ?: return false
        if (!busy.get() && !a.isFinishing && !App.isPlayer(a)) prompt(a, u)
        return true
    }

    /**
     * Prima di far partire da solo un film o un episodio (es. «Continua a guardare»): con l'aggiornamento obbligatorio scaduto
     * compare solo il blocco e non parte niente; con una versione nuova da proporre la proposta viene prima e [go] parte dopo
     * «Più tardi» (o chiudendola con INDIETRO). Senza aggiornamenti, o se è già stata rimandata, [go] parte subito.
     */
    fun before(a: Activity, go: () -> Unit) {
        if (blocked(a)) return
        val u = pending
        if (u == null || a.isFinishing) { go(); return }
        if (offerDlg?.isShowing == true) { afterLater = go; return } // proposta già a video: il contenuto aspetta la risposta
        if (askedCode == u.code || busy.get()) { go(); return }
        askedCode = u.code
        afterLater = go
        prompt(a, u)
        if (offerDlg?.isShowing != true) { afterLater = null; go() } // la finestra non si è aperta: si va avanti
    }

    @Volatile private var lock: AlertDialog? = null

    private fun prompt(a: Activity, u: Upd) {
        if (a.isFinishing) return
        firstSeen(u)
        val r = ready
        if (must() && r != null) {
            // aggiornamento obbligatorio: si va avanti solo installando (niente «Più tardi», il tasto indietro non chiude)
            if (lock?.isShowing == true && lock?.ownerActivity === a) return
            try { lock?.dismiss() } catch (_: Exception) {}
            val d = AlertDialog.Builder(a)
                .setTitle("Aggiornamento ${u.ver} necessario")
                .setMessage("Per continuare a usare ${a.getString(R.string.app_name)} va installata la versione nuova: è già scaricata e verificata. Premi Installa e conferma nella schermata di Android, ci vuole meno di un minuto.")
                .setCancelable(false)
                .setPositiveButton("Installa ora", null)
                .setNegativeButton("Chiudi l'app") { _, _ -> a.finishAffinity() }
                .create()
            d.setOwnerActivity(a)
            d.setOnShowListener { d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener { install(a, r) } } // la finestra resta finché l'installer non riavvia l'app
            lock = d
            try { d.show() } catch (_: Exception) {}
            return
        }
        // «Più tardi» (o INDIETRO): parte il contenuto che aspettava la risposta, se c'è
        val later = { offerDlg = null; val g = afterLater; afterLater = null; g?.invoke(); Unit }
        offerDlg = if (r != null && r.exists()) {
            Ui.dialog(a, "Aggiornamento ${u.ver} pronto", "La versione nuova di ${a.getString(R.string.app_name)} è già scaricata e verificata: premi Installa e conferma nella schermata di Android. Ci vuole meno di un minuto.", "Installa ora", "Più tardi", later) {
                offerDlg = null; afterLater = null
                install(a, r)
            }
        } else {
            Ui.dialog(a, "Nuova versione disponibile", "È pronta la versione ${u.ver} di ${a.getString(R.string.app_name)}. L'aggiornamento richiede meno di un minuto.", "Aggiorna ora", "Più tardi", later) {
                offerDlg = null; afterLater = null
                download(a, u.url)
            }
        }
    }

    fun download(a: Activity, url: String) {
        // già scaricato in background: si passa subito all'installazione
        val u = pending
        val r = ready
        if (u != null && u.url == url && r != null && r.exists()) { install(a, r); return }
        if (!busy.compareAndSet(false, true)) {
            // l'APK si sta già scaricando in sottofondo: prima qui non succedeva niente, ora si installa appena è pronto
            installWhenReady = true
            Ui.toast("La versione nuova si sta già scaricando: l'installazione parte appena è pronta")
            return
        }
        val box = LinearLayout(a)
        box.orientation = LinearLayout.VERTICAL
        val pad = Ui.dp(a, 24)
        box.setPadding(pad, Ui.dp(a, 12), pad, Ui.dp(a, 8))
        val bar = ProgressBar(a, null, android.R.attr.progressBarStyleHorizontal)
        bar.max = 100
        val txt = TextView(a)
        txt.text = "Scaricamento…"
        txt.setTextColor(androidx.core.content.ContextCompat.getColor(a, R.color.muted))
        box.addView(bar)
        box.addView(txt)
        val d: AlertDialog? = try { AlertDialog.Builder(a).setTitle("Aggiornamento").setView(box).setCancelable(false).show() } catch (_: Exception) { null }
        bg {
            try {
                val out = if (u != null && u.url == url) fileFor(u) else File(dir(), "kairos-xc-update.apk")
                fetch(url, out, { p -> ui { bar.progress = p; txt.text = "Scaricamento… $p%" } })
                if (u != null && u.url == url) ready = out
                ui { try { d?.dismiss() } catch (_: Exception) {}; install(if (a.isFinishing) App.top ?: a else a, out) }
            } catch (e: Exception) {
                ui { try { d?.dismiss() } catch (_: Exception) {}; Ui.toast("Aggiornamento non riuscito: " + (e.message ?: "errore")) }
            } finally {
                busy.set(false)
            }
        }
    }

    private fun install(a: Activity, apk: File) {
        installWhenReady = false
        if (Build.VERSION.SDK_INT >= 26 && !a.packageManager.canRequestPackageInstalls()) {
            // senza il permesso Android non installa: lo si dice chiaramente, si apre la sua schermata e al ritorno si riparte da soli
            afterPerm = apk
            Ui.toast("Manca il permesso «Installa app sconosciute»: attiva «Consenti da questa fonte» per ${a.getString(R.string.app_name)} e torna indietro, l'installazione riparte da sola")
            val tries = listOf(
                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + a.packageName)),
                Intent(Settings.ACTION_SECURITY_SETTINGS),
                Intent(Settings.ACTION_SETTINGS),
            )
            for (i in tries) { try { a.startActivity(i); return } catch (_: Exception) {} }
            afterPerm = null
            return
        }
        afterPerm = null
        val uri: Uri = if (Build.VERSION.SDK_INT >= 24) FileProvider.getUriForFile(a, a.packageName + ".files", apk) else Uri.fromFile(apk)
        val type = "application/vnd.android.package-archive"
        val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK
        // di solito apre l'installatore di Android; se sul dispositivo nessuno risponde, la richiesta di installazione diretta
        @Suppress("DEPRECATION")
        val tries = listOf(Intent(Intent.ACTION_VIEW).setDataAndType(uri, type).addFlags(flags), Intent(Intent.ACTION_INSTALL_PACKAGE).setDataAndType(uri, type).addFlags(flags))
        for (i in tries) { try { a.startActivity(i); return } catch (_: Exception) {} }
        // schermata già in chiusura: si parte dall'app
        for (i in tries) { try { App.I.startActivity(i); return } catch (_: Exception) {} }
        Ui.toast("Installazione non disponibile su questo dispositivo")
    }

    /** di ritorno dalla schermata del permesso: se ora c'è, l'installazione rimasta in attesa riparte da sola */
    private fun resumeInstall(a: Activity): Boolean {
        val f = afterPerm ?: return false
        afterPerm = null
        if (!f.exists()) return false
        if (Build.VERSION.SDK_INT >= 26 && !a.packageManager.canRequestPackageInstalls()) {
            Ui.toast("Senza il permesso «Installa app sconosciute» l'aggiornamento non si può installare")
            askedCode = 0 // la proposta torna, per riprovare quando si vuole (il blocco obbligatorio resta comunque)
            return false
        }
        install(a, f)
        return true
    }
}

/** Controlli sull'APK scaricato (stessi di KAIROS TV): app giusta, versione più nuova, stessa firma */
object ApkCheck {
    fun verify(ctx: Context, apk: File): String? {
        val pm = ctx.packageManager
        val info = archiveInfo(pm, apk.absolutePath, 0) ?: return "Il file scaricato è danneggiato: riprova"
        if (info.packageName != ctx.packageName) return "Il file scaricato non è un aggiornamento di questa app"
        val newCode = code(info)
        val myCode = BuildConfig.VERSION_CODE.toLong()
        if (newCode == myCode) return "È la stessa versione già installata"
        if (newCode < myCode) return "La versione scaricata è più vecchia di quella installata"
        val mine = certs(installedInfo(pm, ctx.packageName))
        val theirs = certs(archiveInfo(pm, apk.absolutePath, sigFlags()))
        if (mine.isNotEmpty() && theirs.isNotEmpty() && mine.intersect(theirs).isEmpty()) return "L'aggiornamento è firmato con una chiave diversa: disinstalla l'app e installala di nuovo dallo store"
        return null
    }

    @Suppress("DEPRECATION")
    private fun code(p: PackageInfo): Long = if (Build.VERSION.SDK_INT >= 28) p.longVersionCode else p.versionCode.toLong()

    @Suppress("DEPRECATION")
    private fun sigFlags(): Int = if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES or PackageManager.GET_SIGNATURES else PackageManager.GET_SIGNATURES

    private fun archiveInfo(pm: PackageManager, path: String, flags: Int): PackageInfo? = try { pm.getPackageArchiveInfo(path, flags) } catch (_: Throwable) { null }

    private fun installedInfo(pm: PackageManager, pkg: String): PackageInfo? = try { pm.getPackageInfo(pkg, sigFlags()) } catch (_: Throwable) { null }

    @Suppress("DEPRECATION")
    private fun certs(p: PackageInfo?): Set<String> {
        if (p == null) return emptySet()
        val list = ArrayList<Signature>()
        if (Build.VERSION.SDK_INT >= 28) {
            val si = p.signingInfo
            if (si != null) {
                val arr = if (si.hasMultipleSigners()) si.apkContentsSigners else si.signingCertificateHistory
                if (arr != null) list.addAll(arr)
            }
        }
        if (list.isEmpty()) p.signatures?.let { list.addAll(it) }
        val out = HashSet<String>()
        for (s in list) {
            try {
                val d = MessageDigest.getInstance("SHA-256").digest(s.toByteArray())
                out.add(d.joinToString("") { String.format("%02x", it) })
            } catch (_: Exception) {}
        }
        return out
    }
}
