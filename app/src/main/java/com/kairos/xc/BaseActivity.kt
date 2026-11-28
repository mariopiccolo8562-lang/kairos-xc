package com.kairos.xc

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView

/** Base di tutte le schermate: schermo intero, stato dell'abbonamento, VPN, «Disconnetti» dal pannello */
abstract class BaseActivity : Activity() {
    /** le schermate dei contenuti richiedono un abbonamento attivo */
    open val needsActive = true

    private var clockView: TextView? = null
    private val clockTick = object : Runnable {
        override fun run() {
            clockView?.text = Ui.hm(System.currentTimeMillis())
            shieldRefresh()
            App.main.postDelayed(this, 20_000)
        }
    }
    private var shieldView: ImageView? = null
    private var dnsView: ImageView? = null
    private var debtPill: TextView? = null

    override fun onContentChanged() {
        super.onContentChanged()
        val content = findViewById<ViewGroup>(android.R.id.content) ?: return
        debtPill?.let { if (it.parent === content) content.removeView(it) }
        val pill = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 12f
            typeface = Typeface.DEFAULT_BOLD
            includeFontPadding = false
            gravity = Gravity.CENTER
            setPadding(Ui.dp(this@BaseActivity, 12), Ui.dp(this@BaseActivity, 4), Ui.dp(this@BaseActivity, 12), Ui.dp(this@BaseActivity, 4))
            background = GradientDrawable().apply {
                cornerRadius = Ui.dp(this@BaseActivity, 18).toFloat()
                setColor(Color.argb(200, 150, 20, 30))
                setStroke(Ui.dp(this@BaseActivity, 1), Color.argb(200, 255, 150, 160))
            }
            isFocusable = false
            isFocusableInTouchMode = false
            isClickable = false
            isLongClickable = false
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            elevation = Ui.dp(this@BaseActivity, 8).toFloat()
        }
        debtPill = pill
        val params = FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.CENTER_HORIZONTAL)
        params.topMargin = Ui.dp(this, if (this is HomeActivity) 88 else 8)
        content.addView(pill, params)
        content.post {
            if (this is HomeActivity) {
                val headerHeight = findViewById<View>(R.id.topBar)?.height ?: 0
                params.topMargin = if (headerHeight > 0) headerHeight + Ui.dp(this, 8) else Ui.dp(this, 88)
                pill.layoutParams = params
            }
            debtRefresh()
        }
        debtRefresh()
    }

    fun debtRefresh() {
        val text = Panel.state?.debt.orEmpty()
        debtPill?.apply {
            this.text = if (text.isEmpty()) "" else "⚠ $text"
            visibility = if (text.isEmpty()) View.GONE else View.VISIBLE
        }
        if (this is PlayerActivity) {
            val notice = findViewById<View>(R.id.notice)
            val params = notice?.layoutParams as? ViewGroup.MarginLayoutParams
            if (params != null) {
                val pillHeight = debtPill?.height?.takeIf { it > 0 } ?: Ui.dp(this, 32)
                params.topMargin = Ui.dp(this, 28) + if (text.isEmpty()) 0 else pillHeight + Ui.dp(this, 8)
                notice.layoutParams = params
            }
        }
    }

    /**
     * Lucchetto «DNS» accanto allo scudo (13 novembre 2026): c'è quando il fornitore ha acceso il DNS cifrato dal pannello.
     * Colori e frasi in [DnsBadge]. Al tocco dice lo stato; dopo un rifiuto o un errore riprova.
     */
    private var dnsPill: Pills.Pill? = null
    private var rotPill: Pills.Pill? = null
    private var ponPill: Pills.Pill? = null // «PONTE» (24 novembre 2026)
    fun dnsBadge(v: ImageView?) {
        v ?: return
        // 23 novembre 2026 (il titolare: «scrivi CRIPTO DNS, falli della stessa dimensione»): al posto del lucchetto il pulsante
        // largo «CRIPTO DNS» e accanto «ROTAX DNS» (Pills, RotBadge)
        val p = Pills.swap(v, R.drawable.ic_dns_lock, "CRIPTO", "DNS")
        if (p != null) {
            dnsPill = p; dnsView = p.icon
            p.box.setOnClickListener { dnsTap() }
            rotPill = Pills.after(p, R.drawable.ic_dns_rot, "ROTAX", "DNS", false)?.also { r -> r.box.setOnClickListener { rotTap() } }
            ponPill = rotPill?.let { r -> Pills.after(r, R.drawable.ic_ponte, "PONTE", "KAIROS", false) }?.also { q -> q.box.setOnClickListener { ponteTap() } }
        } else { dnsView = v; v.setOnClickListener { dnsTap() } }
        dnsRefresh()
    }
    /** «ROTAX DNS»: c'è quando la lista in uso ha server di riserva; il numero = quanti indirizzi */
    fun rotRefresh() {
        ponteRefresh()
        val r = rotPill ?: return
        val s = RotBadge.of(K.active().firstOrNull())
        r.box.visibility = View.VISIBLE // 24 novembre 2026: sempre in vista, grigio senza lista
        if (s == null) { Pills.paint(r, 0xCCFFFFFF.toInt()); r.box.alpha = 0.75f; r.box.contentDescription = "ROTAX DNS"; return }
        r.box.alpha = 1f
        r.count?.text = s.second.toString()
        Pills.paint(r, RotBadge.color(s.first))
        r.box.contentDescription = RotBadge.text(s.first, s.third, s.second)
    }
    fun rotTap() { val s = RotBadge.of(K.active().firstOrNull()); Ui.toast(if (s == null) RotBadge.MSG else RotBadge.text(s.first, s.third, s.second)) }
    /** «PONTE» (24 novembre 2026, il titolare: «icona con una figura di un ponte»): c'è quando la lista passa dal Ponte del
     *  fornitore, verde se il Ponte va bene, giallo se usa una riserva. Al tocco niente dettagli. */
    fun ponteRefresh() {
        val q = ponPill ?: return
        val st = RotBadge.ponte(K.active().firstOrNull())
        q.box.visibility = View.VISIBLE // sempre in vista: grigio se la lista non passa dal Ponte
        Pills.paint(q, if (st == null) 0xCCFFFFFF.toInt() else RotBadge.color(st))
        q.box.alpha = if (st == null) 0.75f else 1f
        q.box.contentDescription = "PONTE " + RotBadge.ponteText(st ?: "")
    }
    fun ponteTap() { Ui.toast(RotBadge.ponteText(RotBadge.ponte(K.active().firstOrNull()) ?: "")) }
    /** stato del DNS cifrato da mostrare ("" = il fornitore non lo ha acceso: niente lucchetto) */
    fun dnsState(): String = DnsBadge.state(Remote.sdns && Panel.state?.active == true, KxVpn.state == "on", DnsShield.state)
    fun dnsRefresh() {
        onDnsChanged()
        rotRefresh()
        val v = dnsView ?: return
        val box: View = dnsPill?.box ?: v
        val st = dnsState()
        box.visibility = View.VISIBLE // 24 novembre 2026: sempre in vista, grigio se il fornitore non lo ha acceso
        box.isFocusable = true; box.isClickable = true
        v.imageTintList = android.content.res.ColorStateList.valueOf(if (st.isEmpty()) 0xCCFFFFFF.toInt() else DnsBadge.color(st))
        dnsPill?.let { Pills.paint(it, if (st.isEmpty()) 0xCCFFFFFF.toInt() else DnsBadge.color(st)) }
        box.alpha = if (st == "off" || st.isEmpty()) 0.75f else 1f
        box.contentDescription = if (st.isEmpty()) "DNS cifrato non attivo" else DnsBadge.text(st)
    }
    fun dnsTap() {
        val st = dnsState()
        if (st.isEmpty()) { Ui.toast("DNS cifrato non attivo: lo decide il fornitore"); return }
        if (!DnsBadge.canRetry(st)) { Ui.toast(DnsBadge.text(st)); return }
        DnsShield.retry()
        Vpns.apply(this)
        Ui.toast(if (st == "denied") "DNS cifrato: riprovo. Conferma la richiesta di Android" else "DNS cifrato: riprovo ad attivarlo…")
        for (ms in longArrayOf(800, 2_000, 4_000, 8_000)) App.main.postDelayed({ if (!isFinishing) dnsRefresh() }, ms)
    }
    /** lo stato del DNS cifrato può essere cambiato: le Home che lo disegnano a modo loro si aggiornano qui */
    open fun onDnsChanged() {}


    /** Scudo VPN nella barra in alto. Compare solo se il fornitore lo ha permesso dal pannello: VPN «Consentita» + permesso
     *  «Accendere/spegnere la VPN». Con «Sempre attiva» è un semplice indicatore (acceso, non si tocca); altrimenti è nascosto. */
    private var vpnPill: Pills.Pill? = null
    fun vpnShield(v: ImageView?) {
        v ?: return
        // 24 novembre 2026 (il titolare: «è scomparso lo scudo della VPN… a tutte le app devono esserci in PRIMO PIANO PONTE,
        // ROTEX, DNS e VPN»): al posto dell'icona il pulsante largo «SCUDO VPN», uguale a CRIPTO DNS / ROTAX DNS / PONTE
        val p = Pills.swap(v, R.drawable.ic_shield, "SCUDO", "VPN")
        if (p != null) { vpnPill = p; shieldView = p.icon; p.box.setOnClickListener { vpnToggle() } }
        else { shieldView = v; v.setOnClickListener { vpnToggle() } }
        shieldRefresh()
    }
    fun shieldRefresh() {
        dnsRefresh()
        val v = shieldView ?: return
        val s = Panel.state
        val ok = s != null && s.active && s.vpnConf.isNotEmpty()
        val can = ok && s!!.vpnMode == "allow" && s.perm("vpn")
        val forced = ok && s!!.vpnMode == "force"
        // la VPN la gestisce il fornitore (sempre attiva, consentita senza permesso, spenta): lo scudo c'è lo stesso e si può
        // premere, per leggere «VPN gestita dal fornitore» (voce «shield» del pannello)
        val managed = s != null && s.active && s.vpnShield
        @Suppress("UNUSED_VARIABLE") val shown = can || forced || managed // 24 novembre 2026: lo scudo c'è sempre (grigio se la VPN non c'è)
        val box: View = vpnPill?.box ?: v
        box.visibility = View.VISIBLE
        box.isFocusable = true; box.isClickable = true
        val st = KxVpn.state
        val on = st == "on" || (forced && KxVpn.active())
        val col = if (on) 0xFF2EE59D.toInt() else if (st == "connecting" || st == "error" || st == "denied") 0xFFF5B041.toInt() else 0xCCFFFFFF.toInt()
        v.imageTintList = android.content.res.ColorStateList.valueOf(col)
        vpnPill?.let { Pills.paint(it, col) }
        box.alpha = if (on || st == "connecting") 1f else 0.75f
        box.contentDescription = if (!can) "VPN gestita dal fornitore" else if (on) "VPN attiva: premi per spegnerla" else "VPN spenta: premi per accenderla"
    }
    private fun vpnToggle() {
        val s = Panel.state ?: return
        if (!(s.active && s.vpnConf.isNotEmpty() && s.vpnMode == "allow" && s.perm("vpn"))) { Ui.toast("VPN gestita dal fornitore"); return }
        Store.vpnUser = !Store.vpnUser
        if (Store.vpnUser) Vpns.retry()
        Ui.toast(if (Store.vpnUser) "Attivo la VPN…" else "VPN spenta")
        Vpns.apply(this)
        shieldRefresh()
        for (ms in longArrayOf(800, 2_000, 4_000, 8_000)) App.main.postDelayed({ if (!isFinishing) shieldRefresh() }, ms)
    }
    /** lo stato dal pannello è cambiato: prima lo scudo, poi la schermata */
    fun stateChanged() { shieldRefresh(); debtRefresh(); onStateChanged() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // dopo una chiusura forzata di Android (poca memoria) si riparte dall'ultimo stato salvato
        if (Panel.state == null) Panel.loadCached()
        Vpns.forgetAsk()
        if (App.lite) overridePendingTransition(0, 0)
    }

    /** schermata non visibile (dopo onStop): entrando nel lettore lascia andare le immagini */
    var stopped = false
        private set
    /** immagini lasciate andare perché sopra c'è il lettore: si ricaricano al ritorno */
    private var imgReleased = false
    /** sopra c'è il lettore: le immagini che arrivano adesso (dalla rete) si caricano solo al ritorno */
    val imagesReleased: Boolean get() = imgReleased

    override fun onStart() {
        super.onStart()
        stopped = false
        if (imgReleased) {
            imgReleased = false
            window.setBackgroundDrawableResource(R.drawable.app_window)
            restoreImages()
        }
    }

    override fun onStop() {
        super.onStop()
        stopped = true
        // si è appena aperto il lettore sopra questa schermata
        if (!App.isPlayer(this) && App.isPlayer(App.top)) releaseForPlayer()
    }

    /** sopra c'è il lettore: sfondi e locandine di questa schermata via dalla memoria (il buffer video ha spazio) */
    fun releaseForPlayer() {
        if (imgReleased || App.isPlayer(this) || this is ActivationActivity) return
        imgReleased = true
        releaseImages()
        // sfondo della finestra (immagine 960×540): un colore pieno finché la schermata è sotto il lettore
        window.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(androidx.core.content.ContextCompat.getColor(this, R.color.bg)))
        Img.free()
    }

    /** immagini grandi della schermata da lasciare andare / da ricaricare (dalla cache su disco) */
    open fun releaseImages() {}
    open fun restoreImages() {}

    override fun dispatchKeyEvent(event: android.view.KeyEvent): Boolean {
        Img.key(this, event) // tasto tenuto premuto: i caricamenti delle immagini aspettano il rilascio
        return super.dispatchKeyEvent(event)
    }

    override fun onResume() {
        super.onResume()
        Img.reset(this)
        if (needsActive) {
            val s = Panel.state ?: Panel.loadCached()
            if (s == null || !s.active) { goActivation(); return }
            if (checkProfile()) return // profilo tolto dal pannello mentre si guardava
            Vpns.apply(this)
        }
        if (clockView != null) { App.main.removeCallbacks(clockTick); clockTick.run() }
        shieldRefresh()
        debtRefresh()
        // «Disconnetti» arrivato mentre si passava da una schermata all'altra
        if (Pulse.pendingKick) { Pulse.pendingKick = false; onKicked() }
        Updater.offer(this) // versione nuova trovata mentre si guardava un canale: la si propone qui
        // comandi del pannello rimasti in attesa durante la visione (liste, permesso, speed test)
        if (!App.isPlayer(this)) Remote.idle()
    }

    override fun onPause() {
        super.onPause()
        App.main.removeCallbacks(clockTick)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) immersive()
        // una finestra (messaggio, PIN…) prende i tasti: il rilascio di un tasto tenuto premuto non arriverebbe qui
        else Img.reset(this)
    }

    @Suppress("DEPRECATION")
    fun immersive() {
        window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or View.SYSTEM_UI_FLAG_FULLSCREEN
            or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
            or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN)
    }

    fun clock(v: TextView?) {
        clockView = v
        if (v != null) v.text = Ui.hm(System.currentTimeMillis())
    }

    /**
     * Il profilo in uso non c'è più (tolto dal pannello): si torna a «Chi guarda?». Mai nel lettore (si applica al ritorno
     * nei menu) né nella scelta del profilo stessa. true = questa schermata è stata lasciata.
     */
    fun checkProfile(): Boolean {
        if (!needsActive || App.isPlayer(this) || this is ProfilesActivity || isFinishing) return false
        if (!K.profileGone()) return false
        startActivity(Intent(this, ProfilesActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        finish()
        return true
    }

    fun goActivation() {
        if (this is ActivationActivity) return
        startActivity(Intent(this, ActivationActivity::class.java).putExtra("stay", true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        finish()
    }

    /**
     * Il fornitore ha premuto «Disconnetti» nel pannello: si ferma la visione senza nessun avviso (i controlli a distanza sono
     * invisibili al cliente). Nei menu non c'è niente da fermare; il lettore e l'anteprima della lista canali lo fanno da sé.
     */
    open fun onKicked() {}

    /** lo stato dal pannello è cambiato (playlist, permessi, messaggi…) */
    open fun onStateChanged() {}

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == Vpns.REQ) Vpns.onConsent(resultCode == RESULT_OK)
        if (requestCode == DnsShield.REQ) DnsShield.onConsent(resultCode == RESULT_OK)
    }

    override fun finish() {
        super.finish()
        if (App.lite) overridePendingTransition(0, 0)
    }

    fun open(c: Class<out Activity>, fill: (Intent.() -> Unit)? = null) {
        val i = Intent(this, c)
        fill?.invoke(i)
        startActivity(i)
    }
}
