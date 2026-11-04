package com.kairos.xc

import android.content.Intent
import android.database.Cursor
import android.os.Bundle
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Catchup (come in MirkoTeam): rivedere i programmi già andati in onda.
 * A sinistra solo i canali che il server registra; a destra i giorni (Oggi, Ieri…) e i programmi.
 * La guida completa si scarica solo per il canale su cui ci si ferma, e resta in memoria 15 minuti.
 */
class CatchupActivity : BaseActivity() {
    private lateinit var channels: RecyclerView
    private lateinit var days: RecyclerView
    private lateinit var progs: RecyclerView
    private lateinit var empty: TextView
    private lateinit var progEmpty: TextView
    private lateinit var spin: ProgressBar
    private lateinit var chName: TextView

    private val chAdapter = ChAdapter()
    private val dayAdapter = DayAdapter()
    private val progAdapter = ProgAdapter()

    private class Day(val key: String, val label: String, val items: List<Epg.Arch>)

    private var curRow: Db.Row? = null
    private var curChPos = 0
    private var allDays: List<Day> = emptyList()
    private var dayIdx = 0
    private var seq = 0
    private val loadR = Runnable { loadGuide() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_catchup)
        clock(findViewById(R.id.clock))
        channels = findViewById(R.id.channels)
        days = findViewById(R.id.days)
        progs = findViewById(R.id.progs)
        empty = findViewById(R.id.empty)
        progEmpty = findViewById(R.id.progEmpty)
        spin = findViewById(R.id.spin)
        chName = findViewById(R.id.chName)
        findViewById<View>(R.id.back).setOnClickListener { finish() }

        channels.fast()
        channels.adapter = chAdapter
        days.layoutManager = LinearLayoutManager(this, RecyclerView.HORIZONTAL, false)
        days.fast(horizontal = true)
        days.adapter = dayAdapter
        progs.fast()
        progs.adapter = progAdapter
        loadChannels()
    }

    override fun onDestroy() {
        super.onDestroy()
        App.main.removeCallbacks(loadR)
        chAdapter.swap(null)
    }

    private fun loadChannels() {
        val pls = K.activeIds()
        val kids = K.kids
        bg {
            val c = Db.catchup(pls, kids)
            ui {
                if (isFinishing) { c.close(); return@ui }
                chAdapter.swap(c)
                if (c.count == 0) {
                    empty.visibility = View.VISIBLE
                    empty.text = if (Sync.running) "Liste in aggiornamento…\nRiprova tra poco."
                    else "Nessun canale con Catchup nella tua lista.\nIl Catchup c'è solo sui canali che il tuo fornitore registra."
                    progEmpty.text = ""
                } else {
                    empty.visibility = View.GONE
                    channels.post { if (!isFinishing) channels.focusAt(0) }
                }
            }
        }
    }

    /* ---------------- canale scelto: guida completa ---------------- */
    private fun onChannelFocus(pos: Int) {
        val c = chAdapter.cursor ?: return
        if (pos < 0 || !c.moveToPosition(pos)) return
        val r = Db.current(c)
        if (curRow?.s("k") == r.s("k")) return
        curRow = r
        curChPos = pos
        chName.text = r.s("name")
        allDays = emptyList()
        dayAdapter.notifyDataSetChanged()
        progAdapter.items = emptyList()
        progAdapter.notifyDataSetChanged()
        progEmpty.visibility = View.GONE
        spin.visibility = View.VISIBLE
        // ci si muove veloce tra i canali: la guida si chiede solo quando ci si ferma
        App.main.removeCallbacks(loadR)
        App.main.postDelayed(loadR, if (App.lite) 450 else 300)
    }

    private fun sidOf(r: Db.Row): String {
        val s = r.s("sid")
        if (s.isNotEmpty()) return s
        // canali delle liste M3U: l'ultimo pezzo dell'indirizzo è il numero del canale (…/12345.ts)
        return r.s("url").substringBefore('?').substringAfterLast('/').substringBefore('.').takeIf { it.isNotEmpty() && it.all { ch -> ch.isDigit() } } ?: ""
    }

    private fun loadGuide() {
        val r = curRow ?: return
        val p = K.pl(r.s("pl"))
        val sid = sidOf(r)
        val arch = r.l("arch").coerceIn(1, 14).toInt()
        val my = ++seq
        net {
            val list = Epg.archive(p, sid)
            val built = group(list, arch)
            ui {
                if (isFinishing || my != seq) return@ui
                spin.visibility = View.GONE
                allDays = built
                if (built.isEmpty()) {
                    progEmpty.visibility = View.VISIBLE
                    progEmpty.text = if (p?.xt == null) "Il Catchup funziona con le liste Xtream." else "Nessun programma registrato per questo canale."
                    dayAdapter.notifyDataSetChanged()
                    return@ui
                }
                // si parte da oggi (o dall'ultimo giorno con programmi già andati in onda)
                dayIdx = built.indexOfFirst { it.key == dayKey(System.currentTimeMillis()) }.takeIf { it >= 0 } ?: 0
                dayAdapter.notifyDataSetChanged()
                days.scrollToPosition(dayIdx)
                showDay(dayIdx, focus = false)
            }
        }
    }

    private val dayFmt = SimpleDateFormat("yyyy-MM-dd", Locale.US)
    private val labelFmt = SimpleDateFormat("EEE d MMM", Locale.ITALY)
    private fun dayKey(ms: Long): String = synchronized(dayFmt) { dayFmt.format(Date(ms)) }

    /** programmi già iniziati negli ultimi [arch] giorni, divisi per giorno (dal più recente) */
    private fun group(list: List<Epg.Arch>, arch: Int): List<Day> {
        val now = System.currentTimeMillis()
        val from = now - arch * 24L * 3600_000L
        val byDay = LinkedHashMap<String, ArrayList<Epg.Arch>>()
        for (a in list) {
            if (a.start > now || a.end < from) continue
            byDay.getOrPut(dayKey(a.start)) { ArrayList() }.add(a)
        }
        val today = dayKey(now)
        val yesterday = dayKey(now - 24L * 3600_000L)
        return byDay.entries.sortedByDescending { it.key }.map { (k, v) ->
            val label = when (k) {
                today -> "Oggi"
                yesterday -> "Ieri"
                else -> try { synchronized(labelFmt) { labelFmt.format(dayFmt.parse(k)!!) }.replaceFirstChar { it.uppercase() } } catch (_: Exception) { k }
            }
            Day(k, label, v.sortedBy { it.start })
        }
    }

    private fun showDay(i: Int, focus: Boolean) {
        val d = allDays.getOrNull(i) ?: return
        val old = dayIdx
        dayIdx = i
        dayAdapter.notifyItemChanged(old)
        dayAdapter.notifyItemChanged(i)
        progAdapter.items = d.items
        progAdapter.notifyDataSetChanged()
        progEmpty.visibility = if (d.items.isEmpty()) View.VISIBLE else View.GONE
        // oggi: si parte dal programma in onda adesso (gli ultimi andati in onda sono lì sopra)
        val now = System.currentTimeMillis()
        val at = if (i == 0 || d.key == dayKey(now)) d.items.indexOfLast { it.start <= now }.coerceAtLeast(0) else 0
        (progs.layoutManager as? LinearLayoutManager)?.scrollToPositionWithOffset(at, Ui.dp(this, 60))
        if (focus) progs.post { if (!isFinishing) progs.focusAt(at) }
    }

    private fun play(a: Epg.Arch) {
        val r = curRow ?: return
        val p = K.pl(r.s("pl")) ?: return
        val now = System.currentTimeMillis()
        if (a.start > now) { Ui.toast("Questo programma non è ancora andato in onda"); return }
        val mins = (((a.end.coerceAtMost(now) - a.start) / 60_000L).toInt() + 2).coerceAtLeast(5)
        val url = p.timeshiftUrl(sidOf(r), a.startServer, mins)
        if (url == null) { Ui.toast("Catchup non disponibile per questo canale"); return }
        startActivity(Intent(this, PlayerActivity::class.java)
            .putExtra("kind", "catchup")
            .putExtra("title", a.title.ifEmpty { r.s("name") })
            .putExtra("sub", r.s("name") + "  ·  " + Ui.hm(a.start) + "–" + Ui.hm(a.end))
            .putExtra("url", url))
    }

    /* ---------------- canali ---------------- */
    private inner class ChAdapter : CursorAdapter<ChAdapter.H>() {
        inner class H(v: View) : RecyclerView.ViewHolder(v) {
            val num: TextView = v.findViewById(R.id.num)
            val logo: ImageView = v.findViewById(R.id.logo)
            val initials: TextView = v.findViewById(R.id.initials)
            val name: TextView = v.findViewById(R.id.name)
            val now: TextView = v.findViewById(R.id.now)
        }

        override fun onCreateViewHolder(p: ViewGroup, t: Int): H {
            val h = H(LayoutInflater.from(p.context).inflate(R.layout.item_channel, p, false))
            h.itemView.setOnFocusChangeListener { _, has -> if (has) onChannelFocus(h.bindingAdapterPosition) }
            h.itemView.setOnClickListener { if (progAdapter.itemCount > 0) progs.focusVisible() }
            h.itemView.setOnKeyListener { _, code, e ->
                if (code == KeyEvent.KEYCODE_DPAD_RIGHT && e.action == KeyEvent.ACTION_DOWN) {
                    when {
                        progAdapter.itemCount > 0 -> progs.focusVisible()
                        dayAdapter.itemCount > 0 -> days.focusAt(dayIdx)
                    }
                    true
                } else false
            }
            return h
        }

        override fun bind(h: H, c: Cursor, position: Int) {
            val num = c.getLong(4)
            val name = c.getString(5) ?: ""
            h.num.text = if (num > 0) num.toString() else ""
            h.name.text = name
            h.initials.text = Ui.initials(name)
            val size = Ui.dp(h.itemView.context, 38)
            Ui.img(h.logo, if (App.max) null else c.getString(6), size, size)
            val d = c.getLong(13)
            h.now.visibility = View.VISIBLE
            h.now.text = if (d == 1L) "Ultimo giorno" else "Ultimi $d giorni"
        }
    }

    /* ---------------- giorni ---------------- */
    private inner class DayAdapter : RecyclerView.Adapter<DayAdapter.H>() {
        inner class H(val t: TextView) : RecyclerView.ViewHolder(t)

        override fun getItemCount() = allDays.size

        override fun onCreateViewHolder(p: ViewGroup, t: Int): H {
            val h = H(LayoutInflater.from(p.context).inflate(R.layout.item_pill, p, false) as TextView)
            h.t.setOnClickListener { showDay(h.bindingAdapterPosition, focus = true) }
            h.t.setOnKeyListener { _, code, e ->
                if (e.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener false
                when (code) {
                    KeyEvent.KEYCODE_DPAD_DOWN -> { if (progAdapter.itemCount > 0) progs.focusVisible(); true }
                    KeyEvent.KEYCODE_DPAD_LEFT -> if (h.bindingAdapterPosition == 0) { channels.focusAt(curChPos); true } else false
                    KeyEvent.KEYCODE_DPAD_RIGHT -> h.bindingAdapterPosition >= itemCount - 1
                    else -> false
                }
            }
            return h
        }

        override fun onBindViewHolder(h: H, i: Int) {
            h.t.text = allDays[i].label
            h.t.isActivated = i == dayIdx
        }
    }

    /* ---------------- programmi ---------------- */
    private inner class ProgAdapter : RecyclerView.Adapter<ProgAdapter.H>() {
        var items: List<Epg.Arch> = emptyList()

        inner class H(v: View) : RecyclerView.ViewHolder(v) {
            val time: TextView = v.findViewById(R.id.time)
            val title: TextView = v.findViewById(R.id.title)
            val desc: TextView = v.findViewById(R.id.desc)
            val tag: TextView = v.findViewById(R.id.tag)
        }

        override fun getItemCount() = items.size

        override fun onCreateViewHolder(p: ViewGroup, t: Int): H {
            val h = H(LayoutInflater.from(p.context).inflate(R.layout.item_prog, p, false))
            h.itemView.setOnClickListener { items.getOrNull(h.bindingAdapterPosition)?.let { play(it) } }
            h.itemView.setOnKeyListener { _, code, e ->
                if (e.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener false
                when (code) {
                    KeyEvent.KEYCODE_DPAD_LEFT -> { channels.focusAt(curChPos); true }
                    KeyEvent.KEYCODE_DPAD_RIGHT -> true
                    KeyEvent.KEYCODE_DPAD_UP -> if (h.bindingAdapterPosition == 0) { days.focusAt(dayIdx); true } else false
                    KeyEvent.KEYCODE_DPAD_DOWN -> h.bindingAdapterPosition >= itemCount - 1
                    else -> false
                }
            }
            return h
        }

        override fun onBindViewHolder(h: H, i: Int) {
            val a = items[i]
            val now = System.currentTimeMillis()
            h.time.text = Ui.hm(a.start)
            h.title.text = a.title.ifEmpty { "Programma" }
            h.desc.text = a.desc.ifEmpty { Ui.hm(a.start) + " – " + Ui.hm(a.end) }
            val onAir = a.start <= now && a.end > now
            h.tag.text = if (onAir) "IN ONDA" else "▶ RIVEDI"
            h.tag.setTextColor(if (onAir) 0xFFFF6A6A.toInt() else 0xFFFFFFFF.toInt())
            h.itemView.alpha = 1f
        }
    }
}
