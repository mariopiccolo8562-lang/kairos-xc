package com.kairos.xc

import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Pulsanti «CRIPTO DNS» e «ROTAX DNS» nella barra in alto (23 novembre 2026, il titolare: «l'icona su tutte le app… la scritta
 * ROTAX DNS»; «l'altro pulsante DNS, scrivi CRIPTO DNS, falli della stessa dimensione»). Icona e due righe di scritta, larghi
 * uguali. Si fanno al posto del lucchetto di prima (stesso posto, stesso sfondo e stessa altezza), così ogni app li ha
 * senza rifare la sua barra.
 */
object Pills {
    class Pill(val box: LinearLayout, val icon: ImageView, val top: TextView, val bottom: TextView, val count: TextView?)

    private const val TAG = "kx-pill"
    private fun dp(v: View, x: Float): Int = (x * v.resources.displayMetrics.density + .5f).toInt()

    /** il pulsante largo al posto di [old] (che resta nascosto al suo posto); se c'è già, lo stesso */
    fun swap(old: View, icon: Int, top: String, bottom: String): Pill? {
        (old.getTag(R.id.kx_pill) as? Pill)?.let { return it }
        val parent = old.parent as? ViewGroup ?: return null
        val p = make(old, icon, top, bottom, false)
        parent.addView(p.box, parent.indexOfChild(old) + 1)
        old.visibility = View.GONE
        old.setTag(R.id.kx_pill, p)
        autoFit(parent)
        return p
    }

    /** un altro pulsante uguale subito dopo [after] (ROTAX DNS dopo CRIPTO DNS) */
    fun after(after: Pill, icon: Int, top: String, bottom: String, count: Boolean): Pill? {
        (after.box.getTag(R.id.kx_pill) as? Pill)?.let { return it }
        val parent = after.box.parent as? ViewGroup ?: return null
        val p = make(after.box, icon, top, bottom, count)
        parent.addView(p.box, parent.indexOfChild(after.box) + 1)
        after.box.setTag(R.id.kx_pill, p)
        autoFit(parent)
        return p
    }

    private fun make(like: View, icon: Int, top: String, bottom: String, withCount: Boolean): Pill {
        val ctx = like.context
        val lp0 = like.layoutParams
        val h = if (lp0 != null && lp0.height > 0) lp0.height else dp(like, 40f)
        val box = LinearLayout(ctx).apply {
            tag = TAG
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            isFocusable = true; isClickable = true
            background = like.background?.constantState?.newDrawable()?.mutate()
                ?: GradientDrawable().apply { cornerRadius = h / 2f; setColor(0x22FFFFFF) }
            setPadding(dp(like, 8f), 0, dp(like, 8f), 0)
            visibility = View.GONE
            contentDescription = "$top $bottom"
            layoutParams = (if (lp0 is ViewGroup.MarginLayoutParams) LinearLayout.LayoutParams(lp0) else LinearLayout.LayoutParams(0, 0)).apply {
                width = dp(like, 112f); height = h; gravity = Gravity.CENTER_VERTICAL
            }
            setOnFocusChangeListener { v, f -> v.animate().scaleX(if (f) 1.06f else 1f).scaleY(if (f) 1.06f else 1f).setDuration(120).start() }
        }
        val ic = ImageView(ctx).apply {
            setImageResource(icon)
            layoutParams = LinearLayout.LayoutParams(dp(like, 20f), dp(like, 20f)).apply { rightMargin = dp(like, 6f) }
            isDuplicateParentStateEnabled = true
        }
        val col = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_VERTICAL or Gravity.START }
        val t1 = TextView(ctx).apply { text = top; textSize = 11f; typeface = Typeface.DEFAULT_BOLD; letterSpacing = .07f; includeFontPadding = false; isSingleLine = true }
        val t2 = TextView(ctx).apply { text = bottom; textSize = 8.5f; typeface = Typeface.DEFAULT_BOLD; letterSpacing = .16f; alpha = .85f; includeFontPadding = false; isSingleLine = true }
        col.addView(t1); col.addView(t2)
        box.addView(ic); box.addView(col, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val n = if (!withCount) null else TextView(ctx).apply {
            textSize = 10f; typeface = Typeface.DEFAULT_BOLD; gravity = Gravity.CENTER; includeFontPadding = false
            setTextColor(0xFF0B0B10.toInt())
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(0xFFFFFFFF.toInt()) }
            layoutParams = LinearLayout.LayoutParams(dp(like, 17f), dp(like, 17f)).apply { leftMargin = dp(like, 4f) }
        }
        if (n != null) box.addView(n)
        return Pill(box, ic, t1, t2, n)
    }

    /**
     * 23 novembre 2026 (il titolare: XC «le icone coprono il nome dell'abbonamento», Premium «icone in alto a destra tagliate»,
     * Ultimate «la scritta si sovrappone alle icone» e «un'icona tagliata»): i due pulsanti si stringono a gradini finché la riga
     * ci sta. 0 = normale (112 dp), 1 = stretto (86 dp, scritta più piccola), 2 = solo l'icona colorata. Si scende e basta:
     * niente su e giù a ogni ridisegno. [mode] lo usa anche la barra della Ultimate e della HEAVY.
     */
    fun mode(box: View, m: Int) {
        val b = box as? LinearLayout ?: return
        if (b.getTag(R.id.kx_pill_mode) == m) return
        b.setTag(R.id.kx_pill_mode, m)
        val ic = b.getChildAt(0) as? ImageView ?: return
        val col = b.getChildAt(1) as? LinearLayout ?: return
        val t1 = col.getChildAt(0) as? TextView; val t2 = col.getChildAt(1) as? TextView
        val lp = b.layoutParams
        val h = if (lp != null && lp.height > 0) lp.height else dp(b, 40f)
        when (m) {
            0 -> { lp?.width = dp(b, 112f); b.setPadding(dp(b, 8f), 0, dp(b, 8f), 0); col.visibility = View.VISIBLE; t1?.textSize = 11f; t2?.textSize = 8.5f; icSize(ic, 20f, 6f) }
            1 -> { lp?.width = dp(b, 86f); b.setPadding(dp(b, 6f), 0, dp(b, 6f), 0); col.visibility = View.VISIBLE; t1?.textSize = 9f; t2?.textSize = 7f; icSize(ic, 16f, 4f) }
            else -> { lp?.width = maxOf(h, dp(b, 36f)); b.setPadding(0, 0, 0, 0); col.visibility = View.GONE; icSize(ic, 20f, 0f) }
        }
        b.layoutParams = lp
    }

    private fun icSize(ic: ImageView, size: Float, gap: Float) {
        val l = ic.layoutParams as? LinearLayout.LayoutParams ?: return
        l.width = dp(ic, size); l.height = dp(ic, size); l.rightMargin = dp(ic, gap); ic.layoutParams = l
    }

    private fun pillsIn(row: ViewGroup): List<View> = (0 until row.childCount).map { row.getChildAt(it) }.filter { it.tag == TAG }

    /** la riga con i pulsanti non ci sta: figli più larghi della riga, riga che esce dallo schermo, o un figlio tagliato */
    fun crowded(row: ViewGroup): Boolean {
        if (row.width <= 0 || !row.isShown) return false
        var need = row.paddingLeft + row.paddingRight
        for (i in 0 until row.childCount) {
            val c = row.getChildAt(i); if (c.visibility == View.GONE) continue
            val m = c.layoutParams as? ViewGroup.MarginLayoutParams
            need += c.width + (m?.leftMargin ?: 0) + (m?.rightMargin ?: 0)
        }
        if (row is LinearLayout && row.orientation == LinearLayout.HORIZONTAL && need > row.width + dp(row, 2f)) return true
        val loc = IntArray(2); row.getLocationOnScreen(loc)
        val screenW = row.rootView.width
        if (screenW > 0 && loc[0] + row.width > screenW + dp(row, 1f)) return true
        val r = android.graphics.Rect()
        for (i in 0 until row.childCount) {
            val c = row.getChildAt(i); if (c.visibility != View.VISIBLE || c.width <= 0) continue
            // striscia delle sezioni che scorre (TV, Premium): se non ci stanno tutte, prima si stringono i pulsanti DNS
            if (c is android.widget.HorizontalScrollView && c.childCount > 0 && c.getChildAt(0).width > c.width + dp(row, 2f)) return true
            // fuori dallo spazio della riga (anche quando la riga non taglia i figli e ci finirebbero sopra altre scritte)
            if (c.left < row.paddingLeft - dp(row, 1f) || c.right > row.width - row.paddingRight + dp(row, 1f)) return true
            if (!c.getGlobalVisibleRect(r) || r.width() < c.width - dp(row, 2f)) return true
        }
        return false
    }

    /** una volta per riga: dopo ogni disegno, se non ci sta, i pulsanti scendono di un gradino */
    fun autoFit(row: ViewGroup) {
        if (row.getTag(R.id.kx_pill_fit) != null) return
        row.setTag(R.id.kx_pill_fit, true)
        row.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            row.post {
                val ps = pillsIn(row).filter { it.visibility == View.VISIBLE }
                if (ps.isEmpty() || !crowded(row)) return@post
                val cur = ps.maxOf { (it.getTag(R.id.kx_pill_mode) as? Int) ?: 0 }
                if (cur >= 2) return@post
                ps.forEach { mode(it, cur + 1) }
            }
        }
    }

    /** colore di icona e scritta (verde, azzurro, giallo, bianco tenue) */
    fun paint(p: Pill, color: Int) {
        p.icon.imageTintList = ColorStateList.valueOf(color)
        p.top.setTextColor(color); p.bottom.setTextColor(color)
        p.count?.background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(color) }
    }
}
