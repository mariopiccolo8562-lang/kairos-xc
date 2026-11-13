package com.kairos.xc

import android.content.Context
import android.database.Cursor
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

/** Adapter sopra un Cursor SQLite: legge solo le righe che si vedono */
abstract class CursorAdapter<VH : RecyclerView.ViewHolder> : RecyclerView.Adapter<VH>() {
    var cursor: Cursor? = null
        private set

    init { setHasStableIds(true) }

    fun swap(c: Cursor?) {
        val old = cursor
        cursor = c
        notifyDataSetChanged()
        if (old != null && old !== c) try { old.close() } catch (_: Exception) {}
    }

    override fun getItemCount(): Int = try { cursor?.let { if (it.isClosed) 0 else it.count } ?: 0 } catch (_: Exception) { 0 }

    override fun getItemId(position: Int): Long {
        val c = cursor ?: return RecyclerView.NO_ID
        return try { if (!c.isClosed && c.moveToPosition(position)) c.getLong(0) else RecyclerView.NO_ID } catch (_: Exception) { RecyclerView.NO_ID }
    }

    // l'aggiornamento delle liste in background può cambiare le righe sotto un Cursor aperto: mai un crash
    override fun onBindViewHolder(h: VH, position: Int) {
        val c = cursor ?: return
        try { if (!c.isClosed && c.moveToPosition(position)) bind(h, c, position) } catch (_: Exception) {}
    }

    abstract fun bind(h: VH, c: Cursor, position: Int)
}

/** righe fuori schermo tenute pronte per tornare indietro al volo */
private fun viewCache(): Int = if (App.tiny) 4 else if (App.lite) 6 else 14

/** liste veloci: niente animazioni, cache di viste, dimensione fissa */
fun RecyclerView.fast(horizontal: Boolean = false) {
    itemAnimator = null
    setHasFixedSize(true)
    setItemViewCacheSize(viewCache())
    if (layoutManager == null) layoutManager = LinearLayoutManager(context, if (horizontal) RecyclerView.HORIZONTAL else RecyclerView.VERTICAL, false)
    // in modalità leggera si scorre senza caricare le immagini, che arrivano appena ci si ferma (tasto tenuto premuto: sempre, vedi Img)
    addOnScrollListener(object : RecyclerView.OnScrollListener() {
        private var on = false
        override fun onScrollStateChanged(rv: RecyclerView, state: Int) {
            val now = state != RecyclerView.SCROLL_STATE_IDLE
            if (now != on) { on = now; Img.scrolling(rv, now) }
        }
    })
}

/** sopra c'è il lettore: le immagini della lista (righe visibili, tenute pronte e da riusare) si lasciano andare */
fun RecyclerView.releaseImages() {
    for (i in 0 until childCount) Img.clearAll(getChildAt(i))
    // le righe tenute pronte passano fra quelle da riusare (onViewRecycled le svuota), poi si buttano
    setItemViewCacheSize(0)
    recycledViewPool.clear()
    setItemViewCacheSize(viewCache())
}

/** di ritorno dal lettore: le righe visibili si ridisegnano e ricaricano le immagini (dalla cache su disco) */
fun RecyclerView.restoreImages() {
    val n = adapter?.itemCount ?: 0
    if (n > 0) adapter?.notifyItemRangeChanged(0, n)
}

/** colonna delle categorie (Diretta, Film, Serie) */
class CatAdapter(private val onFocus: (Db.Cat, Int) -> Unit, private val onClick: (Db.Cat, Int) -> Unit, private val onRight: (() -> Unit)? = null) : RecyclerView.Adapter<CatAdapter.H>() {
    var items: List<Db.Cat> = emptyList()
    var selected = 0

    class H(v: View) : RecyclerView.ViewHolder(v) {
        val name: TextView = v.findViewById(R.id.name)
        val count: TextView = v.findViewById(R.id.count)
    }

    fun set(list: List<Db.Cat>, sel: Int) {
        items = list
        selected = sel.coerceIn(0, (list.size - 1).coerceAtLeast(0))
        notifyDataSetChanged()
    }

    fun select(i: Int) {
        if (i == selected) return
        val old = selected
        selected = i
        notifyItemChanged(old)
        notifyItemChanged(i)
    }

    override fun getItemCount() = items.size

    override fun onCreateViewHolder(p: ViewGroup, t: Int): H {
        val h = H(LayoutInflater.from(p.context).inflate(R.layout.item_cat, p, false))
        h.itemView.setOnFocusChangeListener { _, has ->
            // nome lungo: sulla categoria dove sei scorre per intero (prima restava tagliato con «…»)
            h.name.isSelected = has
            val i = h.bindingAdapterPosition
            if (has && i >= 0 && i < items.size) onFocus(items[i], i)
        }
        h.itemView.setOnClickListener {
            val i = h.bindingAdapterPosition
            if (i >= 0 && i < items.size) onClick(items[i], i)
        }
        // DESTRA dalle categorie: alla lista accanto, nel punto visibile (non a un elemento a caso)
        h.itemView.setOnKeyListener { _, code, e ->
            val r = onRight
            if (r != null && code == android.view.KeyEvent.KEYCODE_DPAD_RIGHT && e.action == android.view.KeyEvent.ACTION_DOWN) { r(); true } else false
        }
        return h
    }

    override fun onBindViewHolder(h: H, i: Int) {
        val c = items[i]
        h.name.text = c.name
        h.name.isSelected = h.itemView.isFocused
        h.count.text = if (c.count >= 0) Ui.num(c.count) else ""
        h.itemView.isActivated = i == selected
    }
}

/** porta il cursore sull'elemento [i] della lista (scorrendola se serve) */
fun RecyclerView.focusAt(i: Int) {
    val n = adapter?.itemCount ?: 0
    if (n == 0) return
    val p = i.coerceIn(0, n - 1)
    val v = findViewHolderForAdapterPosition(p)?.itemView
    if (v != null) { v.requestFocus(); return }
    scrollToPosition(p)
    post { (findViewHolderForAdapterPosition(p)?.itemView ?: getChildAt(0))?.requestFocus() }
}

/** il primo elemento visibile della lista prende il cursore */
fun RecyclerView.focusVisible() {
    val lm = layoutManager as? androidx.recyclerview.widget.LinearLayoutManager
    val first = lm?.findFirstCompletelyVisibleItemPosition()?.takeIf { it >= 0 } ?: lm?.findFirstVisibleItemPosition() ?: -1
    ((if (first >= 0) findViewHolderForAdapterPosition(first)?.itemView else null) ?: getChildAt(0))?.requestFocus()
}

/** categorie fisse in testa alle liste */
fun fixedCats(ctx: Context, kind: String, total: Int, favs: Int, recent: Int = 0): List<Db.Cat> {
    val out = ArrayList<Db.Cat>()
    out.add(Db.Cat(Db.ALL, if (kind == "live") "Tutti i canali" else "Tutti", total))
    out.add(Db.Cat(Db.FAV, "Preferiti", favs))
    if (kind != "live" && recent > 0) out.add(Db.Cat(Db.REC, "Continua a guardare", recent))
    if (kind != "live") out.add(Db.Cat(Db.NEW, "Aggiunti di recente", -1))
    return out
}
