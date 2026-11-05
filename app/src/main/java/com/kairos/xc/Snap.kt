package com.kairos.xc

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * «Fotografia» dell'ultima Home / Film / Serie costruita: righe, titoli e prime locandine.
 * All'apertura si legge questo file (pochi KB) e la schermata compare subito, senza aspettare il database;
 * intanto in sottofondo si ricostruisce tutto e, solo se qualcosa è cambiato, si aggiorna.
 * Si scarta da sola quando cambiano playlist, profilo o liste (firma diversa).
 */
object Snap {
    class Data(val hero: Db.Card?, val rows: MutableList<BrowseActivity.Row>)

    private const val MAX_ROWS = 12

    private fun file(kind: String) = File(App.I.filesDir, "snap-$kind.json")

    fun load(kind: String, sig: String): Data? = try {
        val f = file(kind)
        if (!f.exists() || f.length() > 400_000) null else {
            val j = JSONObject(f.readText())
            if (j.optString("sig") != sig) null else {
                val rows = ArrayList<BrowseActivity.Row>()
                val a = j.optJSONArray("rows") ?: JSONArray()
                for (i in 0 until a.length()) {
                    val o = a.optJSONObject(i) ?: continue
                    val r = BrowseActivity.Row(o.optString("key"), o.optString("title"), o.optInt("type"), o.optString("table"), o.optString("cat"))
                    r.more = o.optBoolean("more")
                    r.count = o.optInt("count")
                    o.optJSONArray("cards")?.let { ca -> r.cards = (0 until ca.length()).mapNotNull { card(ca.optJSONArray(it)) } }
                    if (r.type == BrowseActivity.T_RECENT) {
                        r.recent = Store.recent()
                        if (r.recent.isEmpty()) continue
                    }
                    rows.add(r)
                }
                if (rows.isEmpty()) null else Data(card(j.optJSONArray("hero")), rows)
            }
        }
    } catch (_: Throwable) { null }

    fun save(kind: String, sig: String, hero: Db.Card?, rows: List<BrowseActivity.Row>) {
        try {
            val a = JSONArray()
            var withCards = 0
            for (r in rows) {
                val o = JSONObject().put("key", r.key).put("title", r.title).put("type", r.type)
                    .put("table", r.table).put("cat", r.cat).put("more", r.more).put("count", r.count)
                val cs = r.cards
                if (cs != null && withCards < MAX_ROWS) {
                    withCards++
                    o.put("cards", JSONArray().also { ca -> cs.forEach { ca.put(json(it)) } })
                }
                a.put(o)
            }
            val j = JSONObject().put("sig", sig).put("rows", a)
            if (hero != null) j.put("hero", json(hero))
            val f = file(kind)
            val tmp = File(f.path + ".tmp")
            tmp.writeText(j.toString())
            tmp.renameTo(f)
        } catch (_: Throwable) {}
    }

    /** la Home appena ricostruita è uguale a quella già sullo schermo? (allora non si tocca niente) */
    fun same(shown: List<BrowseActivity.Row>, heroShown: Db.Card?, fresh: List<BrowseActivity.Row>, heroFresh: Db.Card?): Boolean {
        if ((heroShown?.k ?: "") != (heroFresh?.k ?: "") || (heroShown?.id ?: 0L) != (heroFresh?.id ?: 0L) || shown.size != fresh.size) return false
        for (i in shown.indices) {
            val a = shown[i]
            val b = fresh[i]
            if (a.key != b.key || a.title != b.title || a.count != b.count) return false
            val ca = a.cards
            val cb = b.cards
            if (ca != null && cb != null && (ca.size != cb.size || ca.indices.any { ca[it].k != cb[it].k || ca[it].id != cb[it].id || ca[it].img != cb[it].img })) return false
        }
        return true
    }

    fun clear() {
        try { App.I.filesDir.listFiles()?.filter { it.name.startsWith("snap-") }?.forEach { it.delete() } } catch (_: Throwable) {}
    }

    private fun json(c: Db.Card) = JSONArray().put(c.table).put(c.id).put(c.k).put(c.name).put(c.img).put(c.year).put(c.rating).put(c.plot.take(300))

    private fun card(a: JSONArray?): Db.Card? {
        if (a == null || a.length() < 8) return null
        return Db.Card(a.optString(0), a.optLong(1), a.optString(2), a.optString(3), a.optString(4), a.optString(5), a.optString(6), a.optString(7))
    }
}
