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

/**
 * Telecomando nelle schermate «categorie a sinistra, lista a destra» (Diretta, Film, Serie).
 * 27 novembre 2026, il titolare: «vado su una categoria, scorro la lista dei film o serie o canali e mi rimanda nella lista
 * principale». Due cause insieme:
 *  - in fondo a una lista (o quando la riga dopo non c'è) Android cercava «il primo elemento più in basso» in tutta la
 *    schermata e trovava una categoria della colonna a sinistra (o un pulsante): il cursore usciva dalla lista;
 *  - una categoria che riceveva il cursore caricava da sola la sua lista, anche se il cursore ci era finito per sbaglio.
 * Regole: SU e GIÙ non escono mai dalla lista (solo SU dalla prima riga va ai pulsanti in alto). La lista cambia categoria solo
 * muovendosi con SU/GIÙ dentro la colonna delle categorie, o con OK. Un cursore che arriva sulle categorie da fuori torna sulla
 * categoria in uso. Qui solo le decisioni (provate in ListNavTest), le viste stanno più sotto.
 */
object ListNav {
    // dove andrebbe il cursore
    const val NONE = 0     // da nessuna parte
    const val INSIDE = 1   // su un altro elemento della stessa lista
    const val CATS = 2     // sulla colonna delle categorie
    const val OTHER = 3    // altrove: pulsanti in alto, riquadro dell'anteprima…
    // cosa fare del tasto SU/GIÙ premuto su un elemento della lista
    const val MOVE = 0     // fa Android come sempre
    const val STAY = 1     // tasto gestito: il cursore resta dov'è
    /** [up] = SU (altrimenti GIÙ) · [firstRow] = l'elemento è nella prima riga · [target] = dove andrebbe il cursore */
    fun vertical(up: Boolean, firstRow: Boolean, target: Int): Int = when {
        target == INSIDE -> MOVE
        up && firstRow && target == OTHER -> MOVE // dalla prima riga si sale ai pulsanti in alto, mai alle categorie
        else -> STAY
    }

    // una categoria ha preso il cursore
    const val BROWSE = 0   // si sta scorrendo la colonna: la lista segue, come sempre
    const val BACK = 1     // cursore arrivato da fuori su un'altra categoria: torna su quella in uso, la lista non cambia
    const val IGNORE = 2   // è la categoria in uso: niente da fare
    /** [byKey] = SU/GIÙ premuto su una categoria · [fromInside] = il cursore era già nella colonna · [isSelected] = categoria in uso */
    fun cat(byKey: Boolean, fromInside: Boolean, isSelected: Boolean): Int = when {
        byKey || fromInside -> BROWSE
        isSelected -> IGNORE
        else -> BACK
    }

    /**
     * Elenco aggiornato in sottofondo: dov'è finito l'elemento [key] che stava alla posizione [pos]? Di solito è lì o poco
     * lontano (qualche titolo aggiunto o tolto): si guarda [pos], poi a distanza crescente fino a [reach]. -1 = non c'è più.
     */
    fun findNear(count: Int, pos: Int, reach: Int, key: String, keyAt: (Int) -> String?): Int {
        if (key.isEmpty() || count <= 0) return -1
        val p = pos.coerceIn(0, count - 1)
        if (keyAt(p) == key) return p
        for (d in 1..reach) {
            if (p + d < count && keyAt(p + d) == key) return p + d
            if (p - d >= 0 && keyAt(p - d) == key) return p - d
            if (p + d >= count && p - d < 0) break
        }
        return -1
    }
}

/** colonna delle categorie (Diretta, Film, Serie). [onStray]: il cursore è arrivato da fuori su una categoria che non è quella in uso */
class CatAdapter(private val onFocus: (Db.Cat, Int) -> Unit, private val onClick: (Db.Cat, Int) -> Unit, private val onRight: (() -> Unit)? = null, private val onStray: (() -> Unit)? = null) : RecyclerView.Adapter<CatAdapter.H>() {
    var items: List<Db.Cat> = emptyList()
    var selected = 0
    /** SU/GIÙ appena premuto su una categoria: il cursore che arriva adesso su un'altra è uno spostamento voluto */
    private var byKey = false
    /** una categoria ha appena lasciato il cursore: se un'altra lo prende subito, ci si sta muovendo dentro la colonna */
    private var leftNow = false
    private val keyOff = Runnable { byKey = false }
    private val leftOff = Runnable { leftNow = false }

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
            if (!has) {
                // vale solo per il cursore che passa subito a un'altra categoria (stesso giro di messaggi)
                leftNow = true; App.main.removeCallbacks(leftOff); App.main.post(leftOff)
                return@setOnFocusChangeListener
            }
            val inside = leftNow
            leftNow = false
            val i = h.bindingAdapterPosition
            if (i < 0 || i >= items.size) return@setOnFocusChangeListener
            when (ListNav.cat(byKey, inside, i == selected)) {
                ListNav.BROWSE -> onFocus(items[i], i)
                // arrivato da fuori (fine di una lista, pulsanti in alto, elenco aggiornato): la lista non cambia categoria
                ListNav.BACK -> if (onStray != null) onStray.invoke() else onFocus(items[i], i)
            }
        }
        h.itemView.setOnClickListener {
            val i = h.bindingAdapterPosition
            if (i >= 0 && i < items.size) onClick(items[i], i)
        }
        // DESTRA dalle categorie: alla lista accanto, nel punto visibile (non a un elemento a caso)
        h.itemView.setOnKeyListener { _, code, e ->
            val r = onRight
            // SU/GIÙ dentro la colonna: la categoria che prende il cursore subito dopo è quella che si sta scegliendo
            if (e.action == android.view.KeyEvent.ACTION_DOWN && (code == android.view.KeyEvent.KEYCODE_DPAD_UP || code == android.view.KeyEvent.KEYCODE_DPAD_DOWN)) {
                byKey = true; App.main.removeCallbacks(keyOff); App.main.post(keyOff)
            }
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
    // la riga compare al disegno successivo: si aspetta lei. Prima, se non era ancora pronta, il cursore andava sulla prima
    // riga in vista: nella colonna delle categorie era un'altra categoria, e la lista cambiava da sola
    focusWhenReady(p)
}

/** il cursore va sull'elemento [p] appena la lista lo ha disegnato (qualche fotogramma al massimo); la lista non viene spostata */
fun RecyclerView.focusWhenReady(p: Int, tries: Int = 6) {
    post {
        val v = findViewHolderForAdapterPosition(p)?.itemView
        if (v != null) v.requestFocus() else if (tries > 1) postDelayed({ focusWhenReady(p, tries - 1) }, 30)
    }
}

/** [v] sta dentro questa vista? */
fun View.holds(v: View?): Boolean {
    var p: android.view.ViewParent? = v?.parent
    while (p != null) { if (p === this) return true; p = p.parent }
    return false
}

/**
 * SU o GIÙ premuto su un elemento [item] di questa lista: true = tasto gestito, il cursore resta dov'è (vedi [ListNav]).
 * Si chiede ad Android dove porterebbe il cursore: se è un altro elemento della lista lo fa lui come sempre; se è fuori
 * (una categoria, un pulsante) o da nessuna parte, il tasto si ferma qui. [cats] = colonna delle categorie della schermata.
 */
fun RecyclerView.keepInside(item: View, up: Boolean, firstRow: Boolean, cats: View?): Boolean {
    val next = try { item.focusSearch(if (up) View.FOCUS_UP else View.FOCUS_DOWN) } catch (_: Exception) { null }
    val target = when {
        next == null || next === item || next === this -> ListNav.NONE
        holds(next) -> ListNav.INSIDE
        cats != null && (next === cats || cats.holds(next)) -> ListNav.CATS
        else -> ListNav.OTHER
    }
    return ListNav.vertical(up, firstRow, target) == ListNav.STAY
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
