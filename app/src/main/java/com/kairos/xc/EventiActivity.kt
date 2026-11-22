package com.kairos.xc

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * Eventi (22 novembre 2026, il titolare: «gli eventi devono andare a tutti gli utenti con qualunque applicazione»):
 * partite e gare in corso e in arrivo (dal pannello) e, con OK, «Dove vederla»: i canali della lista che la trasmettono,
 * con la stessa regola di Kairos Pro e KAIROS Ultimate ([EventMatch]). Schermata leggera: solo testo, niente immagini.
 */
class EventiActivity : BaseActivity() {
    private lateinit var list: LinearLayout
    private lateinit var status: TextView
    private val listener: () -> Unit = { runOnUiThread { if (!isFinishing) fill() } }
    private var dpf = 1f
    private fun dp(v: Int) = (v * dpf).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        dpf = resources.displayMetrics.density
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(0xFF0B0C12.toInt()); setPadding(dp(48), dp(26), dp(48), dp(18)) }
        root.addView(TextView(this).apply { text = "Eventi"; setTextColor(Color.WHITE); textSize = 28f; typeface = Typeface.DEFAULT_BOLD })
        status = TextView(this).apply { setTextColor(0xFF9EA6D6.toInt()); textSize = 15f; setPadding(0, dp(2), 0, dp(12)) }
        root.addView(status)
        val sv = ScrollView(this).apply { isVerticalScrollBarEnabled = false; isFocusable = false }
        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        sv.addView(list)
        root.addView(sv, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)
        Events.listen(listener)
        fill()
        Events.refresh()
    }

    override fun onResume() { super.onResume(); Events.refresh() }
    override fun onDestroy() { Events.unlisten(listener); super.onDestroy() }

    private fun fill() {
        val now = System.currentTimeMillis()
        val all = Events.visible().filter { it.live || (it.start > now - 3 * 3600_000L && it.start < now + 36 * 3600_000L) }
            .sortedWith(compareBy<SportEvent>({ !it.live }, { it.start })).take(80)
        val hadFocus = list.findFocus() != null
        list.removeAllViews()
        status.text = when {
            all.isNotEmpty() -> "In corso e nelle prossime ore · OK su un evento: «Dove vederla»"
            Events.unavailable() -> "Eventi non disponibili adesso: riprova tra poco"
            Events.lastUpdatedAt() == 0L -> "Caricamento…"
            else -> "Nessun evento in arrivo"
        }
        val fmt = SimpleDateFormat("EEE d MMM · HH:mm", Locale.ITALY)
        for (e in all) list.addView(row(e, fmt))
        if (!hadFocus) list.getChildAt(0)?.requestFocus()
    }

    private fun bgFor(focused: Boolean) = GradientDrawable().apply {
        cornerRadius = dp(12).toFloat()
        setColor(if (focused) 0xFF2A2F45.toInt() else 0xFF161824.toInt())
        if (focused) setStroke(dp(2), 0xFFFFFFFF.toInt())
    }

    private fun row(e: SportEvent, fmt: SimpleDateFormat): View {
        val r = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            isFocusable = true; isClickable = true
            setPadding(dp(16), dp(12), dp(16), dp(12))
            background = bgFor(false)
            setOnFocusChangeListener { v, f -> v.background = bgFor(f) }
            setOnClickListener { where(e) }
        }
        val badge = TextView(this).apply {
            textSize = 14f; typeface = Typeface.DEFAULT_BOLD; gravity = Gravity.CENTER
            setPadding(dp(10), dp(4), dp(10), dp(4))
            if (e.live) { text = "● LIVE" + if (e.clock.isNotEmpty()) " ${e.clock}" else ""; setTextColor(Color.WHITE); background = GradientDrawable().apply { cornerRadius = dp(8).toFloat(); setColor(0xFFE5202B.toInt()) } }
            else { text = fmt.format(java.util.Date(e.start)); setTextColor(0xFFDCE0FA.toInt()) }
        }
        r.addView(badge, LinearLayout.LayoutParams(dp(190), LinearLayout.LayoutParams.WRAP_CONTENT))
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(14), 0, dp(8), 0) }
        col.addView(TextView(this).apply { text = title(e); setTextColor(Color.WHITE); textSize = 19f; typeface = Typeface.DEFAULT_BOLD; maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END })
        val sub = listOf(e.league, e.round, e.venue).filter { it.isNotBlank() }.joinToString(" · ")
        if (sub.isNotEmpty()) col.addView(TextView(this).apply { text = sub; setTextColor(0xFF9EA6D6.toInt()); textSize = 14f; maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END })
        r.addView(col, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        r.addView(TextView(this).apply { text = icon(e.sport); textSize = 24f })
        return LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, 0, 0, dp(8)); addView(r) }.also { it.isFocusable = false; it.descendantFocusability = android.view.ViewGroup.FOCUS_AFTER_DESCENDANTS }
    }

    private fun icon(s: String) = when (s) { "calcio", "football", "soccer" -> "⚽"; "f1" -> "🏎"; "motogp", "motorcycle" -> "🏍"; "tennis" -> "🎾"; "basket", "basketball" -> "🏀"; "volley" -> "🏐"; else -> "🏆" }

    private fun title(e: SportEvent): String = when {
        e.home != null && e.away != null -> "${e.home.name} – ${e.away.name}" + if (e.live && e.home.score.isNotEmpty() && e.away.score.isNotEmpty()) "   ${e.home.score} - ${e.away.score}" else ""
        e.gp.isNotEmpty() -> e.gp + if (e.session.isNotEmpty()) " · ${e.session}" else ""
        else -> e.title.ifEmpty { e.league }
    }

    /** «Dove vederla»: ✓ = confermato dalla guida TV o dal nome del canale; ? = di solito la trasmette questa emittente */
    private fun where(e: SportEvent) {
        Ui.toast("Cerco i canali…")
        bg {
            val ch = try { Events.channelsFor(e) } catch (_: Exception) { emptyList() }
            runOnUiThread {
                if (isFinishing) return@runOnUiThread
                if (ch.isEmpty()) { Ui.dialog(this, "Dove vederla", "Nella tua lista non c'è un canale che la trasmette, o la guida TV non lo dice."); return@runOnUiThread }
                val names = ch.map { (if (it.epgHit) "✓ " else "? ") + it.name }.toTypedArray()
                Ui.choose(this, "Dove vederla · " + title(e), names, -1) { i -> LastCh.play(this, ch[i].id, ch[i].k, Db.ALL) }
            }
        }
    }
}

/** «Ultimo canale» (22 novembre 2026, il titolare: «metti in tutte le app ultimo canale visto»): riparte a schermo intero */
object LastCh {
    fun play(a: Activity, id: Long, k: String, cat: String) {
        if (id <= 0L) { a.startActivity(Intent(a, LiveActivity::class.java)); return }
        if (Updater.blocked(a)) return // aggiornamento obbligatorio: prima l'installazione
        Store.setLastLive(id, k)
        val target = if (Remote.engine("live") == "vlc") VlcActivity::class.java else PlayerActivity::class.java
        a.startActivity(Intent(a, target).putExtra("kind", "live").putExtra("cat", cat).putExtra("rowId", id))
    }
    fun playLast(a: Activity) {
        val id = Store.lastLiveId
        if (id <= 0L) { Ui.toast("Non hai ancora guardato un canale: scegline uno"); a.startActivity(Intent(a, LiveActivity::class.java)); return }
        play(a, id, Store.lastLiveK, Store.lastLiveCat.ifEmpty { Db.ALL })
    }
}
