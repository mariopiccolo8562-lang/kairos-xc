package com.kairos.xc

import org.json.JSONObject
import java.net.URL

object Portals {
    private const val BAD_MS = 30 * 60_000L
    private const val PROBE_MS = 6 * 60 * 60_000L

    private fun origin(value: String): String = try {
        Playlist.origin(URL(Playlist.fixUrl(value)))
    } catch (_: Exception) { "" }

    private fun hostKey(value: String): String = value.trim().replace(Regex("^https?://", RegexOption.IGNORE_CASE), "").trimEnd('/').lowercase()

    fun cands(p: Playlist): List<String> {
        val main = p.host.ifBlank { p.url }
        return (listOf(main) + p.alts).mapNotNull { origin(it).takeIf { host -> host.isNotEmpty() } }.distinctBy(::hostKey)
    }

    fun host(p: Playlist): String {
        val candidates = cands(p)
        val main = candidates.firstOrNull().orEmpty()
        return when (p.mode) {
            "main" -> main
            "alt" -> candidates.getOrElse(1) { main }
            else -> {
                val chosen = Store.portalChoice(p.id)
                if (candidates.any { it == chosen && !isBad(it) }) chosen
                else candidates.firstOrNull { !isBad(it) } ?: main
            }
        }
    }

    fun markBad(origin: String, ms: Long) {
        val normalized = this.origin(origin)
        if (normalized.isNotEmpty()) Store.setPortalBadUntil(normalized, System.currentTimeMillis() + ms)
    }

    fun isBad(origin: String): Boolean {
        val normalized = this.origin(origin)
        return normalized.isNotEmpty() && Store.portalBadUntil(normalized) > System.currentTimeMillis()
    }

    fun next(p: Playlist): Pair<String, String>? {
        if (p.mode != "auto") return null
        val candidates = cands(p)
        if (candidates.size < 2) return null
        val old = host(p)
        val current = candidates.indexOfFirst { hostKey(it) == hostKey(old) }
        if (current < 0) return null
        markBad(old, BAD_MS)
        val next = (1..candidates.size).asSequence()
            .map { candidates[(current + it) % candidates.size] }
            .firstOrNull { !isBad(it) } ?: return null
        Store.setPortalChoice(p.id, next)
        return old to next
    }

    fun name(p: Playlist, origin: String): String {
        val i = cands(p).indexOfFirst { hostKey(it) == hostKey(origin) }
        return when {
            i == 0 -> p.mainName
            i > 0 -> p.altNames.getOrNull(i - 1).orEmpty()
            else -> ""
        }
    }

    fun probe(pls: List<Playlist>) {
        if (App.isPlayer(App.top)) return
        for (p in pls) {
            if (App.isPlayer(App.top) || p.mode != "auto") continue
            val candidates = cands(p)
            if (candidates.size < 2) continue
            val now = System.currentTimeMillis()
            if (now - Store.portalProbeAt(p.id) < PROBE_MS) continue
            val credentials = p.xt ?: continue
            var best = ""
            var fastest = Long.MAX_VALUE
            for (candidate in candidates) {
                if (App.isPlayer(App.top)) break
                if (isBad(candidate)) continue
                val started = System.nanoTime()
                try {
                    val url = "$candidate/player_api.php?username=${Playlist.enc(credentials.second)}&password=${Playlist.enc(credentials.third)}"
                    val response = Http.text(url, connectMs = 5_000, readMs = 8_000)
                    if (response.code != 200) continue
                    val info = JSONObject(response.text).optJSONObject("user_info") ?: continue
                    val auth = info.opt("auth") ?: continue
                    if (auth == false || auth.toString() == "0" || auth.toString().equals("false", true)) continue
                    val elapsed = (System.nanoTime() - started) / 1_000_000L
                    if (elapsed < fastest) { fastest = elapsed; best = candidate }
                } catch (_: Exception) {}
            }
            Store.setPortalProbeAt(p.id, System.currentTimeMillis())
            if (best.isNotEmpty()) Store.setPortalChoice(p.id, best)
        }
    }
}
