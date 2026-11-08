package com.kairos.xc

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

/**
 * Pagine «Per te» (quello che guardi di solito a quest'ora) e «Stasera» (da 3 a 5 proposte con il perché; senza IA le sceglie
 * l'app). Funzioni intelligenti: si aprono solo quando si preme il loro pulsante; righe leggere come la ricerca (immagini di
 * 36 dp), tutto letto dall'archivio con LIMIT su un thread di lavoro.
 */
class SmartActivity : BaseActivity() {
    private lateinit var empty: TextView
    private lateinit var subtitle: TextView
    private lateinit var list: RecyclerView
    private val adapter = PickAdapter { onPick(it) }
    private var tonight = false
    private var seq = 0
    /** proposte già viste: «Altre idee» non le ripete */
    private val shown = HashSet<String>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_smart)
        tonight = intent.getStringExtra("mode") == "tonight"
        findViewById<View>(R.id.back).setOnClickListener { finish() }
        findViewById<TextView>(R.id.title).text = if (tonight) "✦ Stasera" else "✦ Per te"
        subtitle = findViewById(R.id.subtitle)
        empty = findViewById(R.id.empty)
        list = findViewById(R.id.list)
        list.fast()
        list.adapter = adapter
        load(false)
    }

    override fun onDestroy() {
        super.onDestroy()
        seq++
    }

    override fun releaseImages() = list.releaseImages()
    override fun restoreImages() = list.restoreImages()

    private fun load(more: Boolean) {
        if (!Smart.on()) { finish(); return }
        val my = ++seq
        adapter.items = emptyList()
        adapter.notifyDataSetChanged()
        empty.visibility = View.VISIBLE
        empty.text = if (tonight && Ai.available()) "✦ Sto scegliendo per te…" else "Un attimo…"
        if (tonight) {
            val skip = if (more) HashSet(shown) else emptySet<String>()
            net {
                val r = try { Ai.tonight(skip) } catch (_: Exception) { Ai.Tonight(emptyList(), "") }
                ui { if (my == seq && !isFinishing) showTonight(r, more) }
            }
        } else bg {
            val r = try { Ai.forYou() } catch (_: Exception) { "" to emptyList() }
            ui { if (my == seq && !isFinishing) showForYou(r.first, r.second) }
        }
    }

    private fun showTonight(r: Ai.Tonight, more: Boolean) {
        subtitle.text = r.note.ifEmpty { "Da 3 a 5 proposte per te, ognuna con il perché" }
        r.picks.forEach { shown.add(it.table + ":" + it.k + ":" + it.start) }
        val rows = ArrayList(r.picks)
        if (r.picks.isNotEmpty()) rows.add(Pick("Altre idee", "Altre proposte per stasera", "", action = "more"))
        if (Ai.available()) rows.add(Pick("Chiedi all'assistente", "Una domanda libera: «film simili a quelli che guardo», «a che ora c'è la F1?»", "", action = "ask"))
        show(rows, if (r.picks.isEmpty()) (if (more) "Per ora non ho altre idee: riprova più tardi." else "Non ho ancora proposte: guarda qualche canale, film o serie e riprova.") else "")
    }

    private fun showForYou(sub: String, rows: List<Pick>) {
        subtitle.text = sub
        show(rows, "Qui compaiono i canali, i film e le serie che guardi di solito a quest'ora.\nGuarda qualcosa per qualche minuto: in pochi giorni la pagina si riempie da sola.")
    }

    private fun show(rows: List<Pick>, none: String) {
        adapter.items = rows
        adapter.notifyDataSetChanged()
        empty.visibility = if (rows.isEmpty() || none.isNotEmpty()) View.VISIBLE else View.GONE
        empty.text = none
        if (rows.isNotEmpty()) list.post { list.findViewHolderForAdapterPosition(0)?.itemView?.requestFocus() }
    }

    private fun onPick(p: Pick) {
        when (p.action) {
            "more" -> { load(true); return }
            "ask" -> { open(SearchActivity::class.java) { putExtra("ask", true) }; return }
        }
        PickAdapter.open(this, p)
    }
}

/** righe dell'assistente e delle pagine «Per te» / «Stasera» */
class PickAdapter(private val click: (Pick) -> Unit) : RecyclerView.Adapter<PickAdapter.H>() {
    var items: List<Pick> = emptyList()

    class H(v: View) : RecyclerView.ViewHolder(v) {
        val img: ImageView = v.findViewById(R.id.img)
        val icon: ImageView = v.findViewById(R.id.icon)
        val name: TextView = v.findViewById(R.id.name)
        val sub: TextView = v.findViewById(R.id.sub)
        val why: TextView = v.findViewById(R.id.why)
        val kind: TextView = v.findViewById(R.id.kind)
    }

    override fun getItemCount() = items.size

    override fun onCreateViewHolder(p: ViewGroup, t: Int): H {
        val h = H(LayoutInflater.from(p.context).inflate(R.layout.item_pick, p, false))
        h.itemView.setOnClickListener { val i = h.bindingAdapterPosition; if (i in items.indices) click(items[i]) }
        return h
    }

    override fun onBindViewHolder(h: H, i: Int) {
        val x = items[i]
        h.name.text = x.title
        h.sub.text = x.sub
        h.sub.visibility = if (x.sub.isEmpty()) View.GONE else View.VISIBLE
        h.sub.maxLines = if (x.action == "answer") 10 else 3
        h.why.text = if (x.why.isEmpty()) "" else "✦ " + x.why
        h.why.visibility = if (x.why.isEmpty()) View.GONE else View.VISIBLE
        h.kind.text = x.tag
        if (x.table.isEmpty()) {
            h.icon.visibility = View.VISIBLE
            Img.clear(h.img)
            h.img.setImageDrawable(null)
        } else {
            h.icon.visibility = View.GONE
            val s = Ui.dp(h.itemView.context, 36)
            // «Leggerissima»: niente loghi da scaricare, come nella lista canali
            Ui.img(h.img, if (App.max && x.imgKind == Img.LOGO) null else x.img, s, s, x.imgKind)
        }
    }

    override fun onViewRecycled(h: H) { Img.clear(h.img) }

    companion object {
        /** apre una proposta: canale (con l'orario se inizia dopo), scheda del titolo, serie da riprendere */
        fun open(a: Activity, p: Pick) {
            when {
                p.resume != null -> Play.resume(a, p.resume)
                p.table == "live" -> {
                    if (p.start > System.currentTimeMillis()) Ui.toast("Inizia alle " + Ui.hm(p.start) + (p.sub.substringAfter(" · ", "").let { if (it.isNotEmpty()) " su $it" else "" }))
                    bg {
                        val r = try { Db.oneByK("live", p.k) } catch (_: Exception) { null }
                        ui { if (a.isFinishing) return@ui; if (r == null) Ui.toast("Canale non più disponibile") else Play.openLive(a, r, r.s("cat")) }
                    }
                }
                p.table == "vod" || p.table == "series" -> a.startActivity(Intent(a, DetailActivity::class.java).putExtra("table", p.table).putExtra("id", p.id).putExtra("k", p.k))
            }
        }
    }
}
