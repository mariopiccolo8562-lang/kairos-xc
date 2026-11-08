package com.kairos.xc

import android.os.Bundle
import android.view.View
import android.widget.ImageView
import android.widget.TextView

/**
 * Home a cerchi (stessa struttura di MirkoTeam):
 * - in alto: logo Kairos XC, nome del portale in uso, Cerca · VPN · Messaggi · Aggiorna;
 * - al centro: Diretta Live TV · Film On Demand · Serie TV;
 * - in basso: Account (scelta del portale) · Preferiti, Impostazioni.
 * Leggera: niente liste né database qui, solo pulsanti; lo sfondo è un'unica immagine piccola.
 */
class HomeActivity : BaseActivity() {
    private companion object { val CIRCLES = intArrayOf(R.id.live, R.id.film, R.id.series) }
    private lateinit var portal: TextView
    private lateinit var portalSub: TextView
    private lateinit var sync: TextView
    private val syncL: () -> Unit = { showSync() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        App.main.postDelayed({ Parental.firstRun(this) }, 2500) // primo avvio: scelta del PIN del controllo genitori
        setContentView(R.layout.activity_home)
        clock(findViewById(R.id.clock))
        portal = findViewById(R.id.portal)
        portalSub = findViewById(R.id.portalSub)
        sync = findViewById(R.id.sync)
        // «Leggerissima»: niente immagine di sfondo (memoria minima)
        if (App.max) findViewById<ImageView>(R.id.bg).setImageDrawable(null)
        sizeCircles()

        val live = findViewById<View>(R.id.live)
        live.setOnClickListener { open(LiveActivity::class.java) }
        // Film e Serie: categorie a sinistra e locandine a griglia (come MirkoTeam)
        findViewById<View>(R.id.film).setOnClickListener { open(VodActivity::class.java) { putExtra("kind", "vod") } }
        findViewById<View>(R.id.series).setOnClickListener { open(VodActivity::class.java) { putExtra("kind", "series") } }
        findViewById<View>(R.id.search).setOnClickListener { open(SearchActivity::class.java) }
        findViewById<View>(R.id.vpn).setOnClickListener { toggleVpn() }
        findViewById<View>(R.id.msg).setOnClickListener { showMessages() }
        findViewById<View>(R.id.update).setOnClickListener { update() }
        findViewById<View>(R.id.account).setOnClickListener { portals() }
        findViewById<View>(R.id.favorite).setOnClickListener { favorites() }
        findViewById<View>(R.id.settings).setOnClickListener { open(SettingsActivity::class.java) }

        for (id in listOf(R.id.live, R.id.film, R.id.series)) Ui.zoom(findViewById(id), 1.08f)
        for (id in listOf(R.id.search, R.id.vpn, R.id.msg, R.id.update, R.id.account, R.id.favorite, R.id.settings)) Ui.zoom(findViewById(id), 1.08f)

        Sync.listen(syncL)
        live.requestFocus()
    }

    /** cerchi grandi quanto lo schermo permette (TV, chiavette e telefoni in orizzontale) */
    private fun sizeCircles() {
        val dm = resources.displayMetrics
        // diametro del cerchio visibile; l'immagine è più grande perché contiene anche l'alone (cerchio = 80%)
        val vis = minOf(dm.heightPixels * 0.38f, dm.widthPixels * 0.2f)
        val size = (vis / 0.80f).toInt()
        val gap = 0
        for ((i, id) in listOf(R.id.live, R.id.film, R.id.series).withIndex()) {
            val v = findViewById<View>(id)
            val lp = v.layoutParams as android.widget.LinearLayout.LayoutParams
            lp.width = size
            lp.height = size
            lp.leftMargin = if (i == 0) 0 else gap
            v.layoutParams = lp
            val icon = findViewById<View>(when (id) { R.id.live -> R.id.liveIcon; R.id.film -> R.id.filmIcon; else -> R.id.seriesIcon })
            icon.layoutParams.width = (vis * 0.26f).toInt()
            icon.layoutParams.height = (vis * 0.26f).toInt()
        }
    }

    override fun onResume() {
        super.onResume()
        if (isFinishing) return
        // aggiornamenti: Android chiede al cliente il permesso una volta; lo guidiamo noi (al massimo una volta a settimana)
        if (!isFinishing && !Remote.canInstall() && System.currentTimeMillis() - Store.permAskT > 7 * 86_400_000L) {
            Store.permAskT = System.currentTimeMillis()
            Ui.dialog(this, "Aggiornamenti automatici", "Per ricevere da sola le nuove versioni, Android chiede il tuo permesso una volta sola.\n\nPremi «Apri», attiva «Consenti da questa fonte» e poi torna indietro.", "Apri", "Dopo") { Remote.openInstallPerm() }
        }
        fill()
        Sync.start()
        showSync()
        newMessages()
    }

    override fun onDestroy() {
        super.onDestroy()
        Sync.unlisten(syncL)
    }

    // sotto il lettore: via lo sfondo (1280×720) e i cerchi (360×360), tornano appena la Home si rivede
    override fun releaseImages() {
        findViewById<ImageView>(R.id.bg).setImageDrawable(null)
        for (id in CIRCLES) findViewById<View>(id).background = null
    }

    override fun restoreImages() {
        if (!App.max) findViewById<ImageView>(R.id.bg).setImageResource(R.drawable.home_bg)
        for (id in CIRCLES) findViewById<View>(id).setBackgroundResource(R.drawable.home_circle)
    }

    override fun onStateChanged() {
        super.onStateChanged()
        fill()
        newMessages()
    }

    /* ---------------- intestazione ---------------- */
    private fun fill() {
        val s = Panel.state
        val pl = K.active().firstOrNull()
        portal.text = (pl?.name ?: "").uppercase().ifEmpty { "KAIROS XC" }
        portalSub.text = when {
            s == null -> ""
            s.expires.isEmpty() -> "Abbonamento attivo"
            else -> "Scade il " + Ui.date(s.expires)
        }
        // VPN: solo se il fornitore la concede; accesa = piena, spenta = trasparente
        val vpn = findViewById<View>(R.id.vpn)
        val show = s != null && s.vpnConf.isNotEmpty() && (s.vpnMode == "force" || (s.vpnMode == "allow" && s.perm("vpn")))
        vpn.visibility = if (show) View.VISIBLE else View.GONE
        vpn.alpha = if (s?.vpnMode == "force" || KxVpn.active() || KxVpn.state == "on") 1f else 0.6f
        findViewById<View>(R.id.msgDot).visibility = if (K.unseenMessages().isNotEmpty()) View.VISIBLE else View.GONE
    }

    private fun showSync() {
        when {
            Sync.running && Sync.quiet -> sync.visibility = View.GONE // chiesto dal pannello: in silenzio
            Sync.running -> { sync.visibility = View.VISIBLE; sync.text = "Aggiornamento liste… ${Sync.pct}%" }
            Sync.error.isNotEmpty() -> { sync.visibility = View.VISIBLE; sync.text = "Liste non aggiornate" }
            else -> sync.visibility = View.GONE
        }
    }

    /* ---------------- Account = portali ---------------- */
    private fun portals() {
        val s = Panel.state ?: return
        val all = K.playlists()
        val cur = K.active().firstOrNull()
        val head = (if (s.name.isNotEmpty()) s.name + " · " else "") + (if (s.expires.isEmpty()) "abbonamento attivo" else "scade il " + Ui.date(s.expires))
        if (all.isEmpty()) { Ui.dialog(this, "Account", "$head\n\nNessun portale assegnato: chiedi al tuo fornitore."); return }
        if (!K.canSwitchPl()) {
            val why = if (all.size <= 1) "" else "\n\nIl portale lo sceglie il tuo fornitore."
            Ui.dialog(this, "Account", "$head\n\nPortale in uso: ${cur?.name ?: all[0].name}$why")
            return
        }
        val names = all.map { it.name.ifEmpty { "Portale" } }.toTypedArray()
        Ui.choose(this, "Account · scegli il portale", names, all.indexOfFirst { it.id == cur?.id }) { i ->
            val p = all[i]
            if (p.id == cur?.id) return@choose
            if (cur != null) Store.plPrev = cur.id
            Store.plChoice = p.id
            fill()
            // le liste del portale si scaricano solo se non ci sono già (o sono vecchie)
            Sync.start()
            showSync()
            Ui.toast("Portale: " + p.name)
        }
    }

    /* ---------------- altri pulsanti ---------------- */
    private fun favorites() {
        Ui.choose(this, "Preferiti", arrayOf("Canali preferiti", "Film preferiti", "Serie TV preferite"), -1) { i ->
            when (i) {
                0 -> open(LiveActivity::class.java) { putExtra("cat", Db.FAV) }
                1 -> open(VodActivity::class.java) { putExtra("kind", "vod"); putExtra("cat", Db.FAV) }
                else -> open(VodActivity::class.java) { putExtra("kind", "series"); putExtra("cat", Db.FAV) }
            }
        }
    }

    private fun update() {
        Sync.force()
        showSync()
        Ui.toast("Aggiornamento delle liste avviato")
        val a = this
        bg { Updater.check(a) }
    }

    private fun toggleVpn() {
        val s = Panel.state ?: return
        if (s.vpnMode == "force") {
            // «sempre attiva»: se è spenta (permesso negato o errore) la si richiede di nuovo
            if (KxVpn.state != "on") { Vpns.retry(); Vpns.apply(this); Ui.toast("VPN in attivazione…") }
            else Ui.toast("VPN sempre attiva, impostata dal tuo fornitore")
            return
        }
        Store.vpnUser = !Store.vpnUser
        if (Store.vpnUser) Vpns.retry()
        Vpns.apply(this)
        Ui.toast(if (Store.vpnUser) "VPN in attivazione…" else "VPN spenta")
        App.main.postDelayed({ if (!isFinishing) fill() }, 1500)
        fill()
    }

    private fun showMessages() {
        val m = Panel.state?.messages ?: emptyList()
        if (m.isEmpty()) { Ui.dialog(this, "Messaggi", "Nessun messaggio dal tuo fornitore."); return }
        Store.seenMsgs = Store.seenMsgs + m.map { it.id }
        fill()
        Ui.dialog(this, "Messaggi", m.joinToString("\n\n") { (if (it.title.isNotEmpty()) it.title + "\n" else "") + it.body })
    }

    /** messaggi nuovi del fornitore: compaiono da soli una volta */
    private fun newMessages() {
        val m = K.unseenMessages()
        if (m.isEmpty()) return
        val text = m.joinToString("\n\n") { (if (it.title.isNotEmpty()) it.title + "\n" else "") + it.body }
        Store.seenMsgs = Store.seenMsgs + m.map { it.id }
        fill()
        Ui.dialog(this, if (m.size == 1) "Messaggio dal tuo fornitore" else "Messaggi dal tuo fornitore", text)
    }

    /** INDIETRO sulla Home: si chiede conferma prima di chiudere l'app */
    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        Ui.dialog(this, "Vuoi uscire?", "Kairos XC si chiude. Canali e preferiti restano pronti per la prossima volta.", "Esci", "Annulla") {
            finishAffinity()
        }
    }
}
