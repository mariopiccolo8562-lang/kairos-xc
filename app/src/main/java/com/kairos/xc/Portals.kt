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
            "rot" -> {
                val chosen = Store.portalChoice(p.id)
                if (candidates.any { it == chosen && !isBad(it) }) chosen
                else PortalRotation.firstHealthy(candidates, Store.code, ::isBad) ?: main
            }
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

    fun playingOrigin(p: Playlist, url: String): String = playingOrigin(cands(p), url)

    internal fun playingOrigin(candidates: List<String>, url: String): String {
        val urlOrigin = origin(url)
        if (urlOrigin.isEmpty()) return ""
        return candidates.firstOrNull { candidate ->
            hostKey(candidate) == hostKey(urlOrigin) &&
                url.startsWith(candidate, true) &&
                (url.length == candidate.length || url[candidate.length] in charArrayOf('/', '?', '#'))
        } ?: ""
    }

    internal fun nextCandidate(candidates: List<String>, playing: String, bad: (String) -> Boolean): String? {
        if (candidates.size < 2) return null
        val current = candidates.indexOfFirst { hostKey(it) == hostKey(playing) }
        if (current < 0) return null
        return (1..candidates.size).asSequence()
            .map { candidates[(current + it) % candidates.size] }
            .firstOrNull { !bad(it) }
    }

    fun next(p: Playlist, playing: String = host(p)): Pair<String, String>? {
        if (p.mode != "auto" && p.mode != "rot") return null
        val candidates = if (p.mode == "rot") PortalRotation.order(cands(p), Store.code) else cands(p)
        if (candidates.size < 2) return null
        val old = candidates.firstOrNull { hostKey(it) == hostKey(playing) } ?: return null
        markBad(playing, BAD_MS)
        val next = nextCandidate(candidates, playing, ::isBad) ?: return null
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
            if (App.isPlayer(App.top) || (p.mode != "auto" && p.mode != "rot")) continue
            val candidates = cands(p)
            if (candidates.size < 2) continue
            val now = System.currentTimeMillis()
            if (now - Store.portalProbeAt(p.id) < PROBE_MS) continue
            val credentials = p.xt ?: continue
            val order = if (p.mode == "rot") PortalRotation.order(candidates, Store.code) else candidates
            val elapsedByCandidate = linkedMapOf<String, Long>()
            for (candidate in order) {
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
                    elapsedByCandidate[candidate] = elapsed
                    if (p.mode == "rot") break
                } catch (_: Exception) {}
            }
            Store.setPortalProbeAt(p.id, System.currentTimeMillis())
            PortalRotation.pickProbed(p.mode, order, elapsedByCandidate)?.let { Store.setPortalChoice(p.id, it) }
        }
    }
}

object PortalRotation {
    fun order(candidates: List<String>, deviceCode: String): List<String> {
        if (candidates.size < 2) return candidates
        var hash = 0x811C9DC5.toInt()
        deviceCode.forEach { hash = (hash xor it.code) * 0x01000193 }
        val offset = Math.floorMod(hash, candidates.size)
        return candidates.drop(offset) + candidates.take(offset)
    }

    fun firstHealthy(candidates: List<String>, deviceCode: String, bad: (String) -> Boolean): String? =
        order(candidates, deviceCode).firstOrNull { !bad(it) }

    fun pickProbed(mode: String, ordered: List<String>, elapsedByCandidate: Map<String, Long>): String? =
        if (mode == "rot") ordered.firstOrNull { it in elapsedByCandidate }
        else elapsedByCandidate.minByOrNull { it.value }?.key
}
