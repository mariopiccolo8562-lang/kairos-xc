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
        return p
    }

    /** un altro pulsante uguale subito dopo [after] (ROTAX DNS dopo CRIPTO DNS) */
    fun after(after: Pill, icon: Int, top: String, bottom: String, count: Boolean): Pill? {
        (after.box.getTag(R.id.kx_pill) as? Pill)?.let { return it }
        val parent = after.box.parent as? ViewGroup ?: return null
        val p = make(after.box, icon, top, bottom, count)
        parent.addView(p.box, parent.indexOfChild(after.box) + 1)
        after.box.setTag(R.id.kx_pill, p)
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
            duplicateParentState = true
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

    /** colore di icona e scritta (verde, azzurro, giallo, bianco tenue) */
    fun paint(p: Pill, color: Int) {
        p.icon.imageTintList = ColorStateList.valueOf(color)
        p.top.setTextColor(color); p.bottom.setTextColor(color)
        p.count?.background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(color) }
    }
}
