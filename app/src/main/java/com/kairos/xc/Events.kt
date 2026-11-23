package com.kairos.xc

import org.json.JSONArray
import org.json.JSONObject
import java.time.OffsetDateTime
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.CopyOnWriteArrayList

data class EventTeam(
    val name: String,
    val logo: String = "",
    val score: String = "",
    val sets: List<Int> = emptyList(),
    val seed: Int? = null,
    val italian: Boolean = false,
    /** nomi come li scrivono le guide italiane, dal pannello (23 novembre 2026) */
    val aka: List<String> = emptyList(),
)

data class EventLeader(val name: String, val flag: String = "")

data class SportEvent(
    val id: String,
    val sport: String,
    val league: String,
    val start: Long,
    val live: Boolean,
    val state: String = "",
    val clock: String = "",
    val home: EventTeam? = null,
    val away: EventTeam? = null,
    val venue: String = "",
    val round: String = "",
    val logo: String = "",
    val title: String = "",
    val gp: String = "",
    val session: String = "",
    val top: List<EventLeader> = emptyList(),
    /** canali che secondo le guide TV pubbliche la trasmettono (confermati) e probabili, dal pannello (23 novembre 2026) */
    val tvch: List<String> = emptyList(),
    val tvp: List<String> = emptyList(),
    /** canali dell'emittente che a quell'ora, secondo le guide, trasmettono altro (23 novembre 2026) */
    val tvno: List<String> = emptyList(),
)

data class EventChannel(
    val id: Long,
    val k: String,
    val name: String,
    val logo: String,
    val reason: String,
    val epgHit: Boolean,
    /** «probabile»: in guida a quell'ora c'è una delle due squadre o il campionato */
    val probable: Boolean = false,
)

object Events {
    private const val CACHE_MS = 5 * 60_000L
    private val compactOffset = Regex("([+-]\\d{2})(\\d{2})$")
    private val lock = Any()
    private val listeners = CopyOnWriteArrayList<() -> Unit>()
    private val executor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "kx-events").apply { isDaemon = true; priority = Thread.MIN_PRIORITY }
    }
    @Volatile private var items: List<SportEvent> = emptyList()
    @Volatile private var fetchedAt = 0L
    @Volatile private var unavailable = false
    private var lastAttemptAt = 0L
    private var loading = false

    fun cached(): List<SportEvent> = items

    fun visible(): List<SportEvent> {
        val selected = Remote.player("u.sports", "").split(Regex("[,;\\s]+"))
            .mapNotNull { it.trim().lowercase(Locale.ROOT).takeIf(String::isNotEmpty) }.toSet()
        if (selected.isEmpty()) return items
        return items.filter { event ->
            val sport = event.sport.lowercase(Locale.ROOT)
            val key = when (sport) {
                "football", "soccer" -> "calcio"
                "basketball" -> "basket"
                "motorcycle" -> "motogp"
                else -> sport
            }
            key in selected
        }
    }

    fun listen(listener: () -> Unit) { listeners.addIfAbsent(listener) }

    fun unlisten(listener: () -> Unit) { listeners.remove(listener) }

    fun refresh(force: Boolean = false) {
        synchronized(lock) {
            val now = System.currentTimeMillis()
            if (loading || now - lastAttemptAt < if (force) 60_000L else CACHE_MS) return
            loading = true
            lastAttemptAt = now
        }
        executor.execute {
            val fresh = try {
                val response = Http.text("${Panel.base.trimEnd('/')}/api/player/events?v=2", connectMs = 4_000, readMs = 8_000)
                if (response.code == 200) decode(response.text) else null
            } catch (_: Exception) { null }
            synchronized(lock) {
                if (fresh != null) {
                    items = fresh.toList()
                    fetchedAt = System.currentTimeMillis()
                    unavailable = false
                } else if (fetchedAt == 0L) {
                    unavailable = true
                }
                loading = false
            }
            listeners.forEach { it() }
        }
    }

    fun lastUpdatedAt(): Long = fetchedAt

    fun unavailable(): Boolean = unavailable

    fun parse(body: String): List<SportEvent> {
        return decode(body).orEmpty()
    }

    private fun decode(body: String): List<SportEvent>? {
        val array = try { JSONObject(body).optJSONArray("events") } catch (_: Exception) { null } ?: return null
        val count = minOf(array.length(), 500)
        val out = ArrayList<SportEvent>(count)
        for (i in 0 until count) {
            val obj = array.optJSONObject(i) ?: continue
            val id = obj.string("id")
            val sport = obj.string("sport").lowercase(Locale.ROOT)
            val start = parseStart(obj.string("start"))
            if (id.isEmpty() || sport.isEmpty() || start == null) continue
            out.add(
                SportEvent(
                    id = id,
                    sport = sport,
                    league = obj.string("league"),
                    start = start,
                    live = obj.optBoolean("live", false),
                    state = obj.string("state"),
                    clock = obj.string("clock"),
                    home = obj.optJSONObject("home")?.let(::parseTeam)?.let { it.copy(aka = obj.optJSONObject("aka")?.optJSONArray("h").asStrings(6)) },
                    away = obj.optJSONObject("away")?.let(::parseTeam)?.let { it.copy(aka = obj.optJSONObject("aka")?.optJSONArray("a").asStrings(6)) },
                    venue = obj.string("venue"),
                    round = obj.string("round"),
                    logo = obj.string("logo"),
                    title = obj.string("title"),
                    gp = obj.string("gp"),
                    session = obj.string("session"),
                    top = parseTop(obj.optJSONArray("top")),
                    tvch = obj.optJSONArray("tvch").asStrings(8),
                    tvp = obj.optJSONArray("tvp").asStrings(8),
                    tvno = obj.optJSONArray("tvno").asStrings(12),
                ),
            )
        }
        return if (out.isEmpty() && array.length() > 0) null else out
    }

    /** secondo le guide TV a quell'ora nessun canale italiano la trasmette (i soliti hanno in onda altro) */
    fun offAir(event: SportEvent): Boolean = event.tvch.isEmpty() && event.tvno.isNotEmpty()
    const val OFF_AIR = "Adesso nessun canale italiano la trasmette: secondo le guide TV i canali che di solito la danno hanno in onda altro."

    fun channelsFor(event: SportEvent): List<EventChannel> {
        val active = K.activeIds()
        if (active.isEmpty()) return emptyList()
        val rows = Db.eventLiveChannels(active, K.kids)
        if (rows.isEmpty()) return emptyList()
        val byPlaylist = K.active().associateBy { it.id }
        val results = LinkedHashMap<Long, EventChannel>()
        fun add(row: Db.EventLiveChannel, reason: String, epgHit: Boolean, probable: Boolean = false) {
            if (results.size < 40 && !results.containsKey(row.id)) {
                results[row.id] = EventChannel(row.id, row.k, row.name, row.logo, reason, epgHit, probable)
            }
        }
        // 0) guide TV pubbliche lette dal pannello (23 novembre 2026): i canali che a quell'ora trasmettono la partita
        //    con tutte e due le squadre, trovati nella lista con il nome «pulito» (EventMatch.chKey)
        val keyOf = HashMap<Long, String>()
        fun byKey(names: List<String>): List<Db.EventLiveChannel> {
            val want = names.map(EventMatch::chKey).filter { it.isNotEmpty() }.toSet()
            if (want.isEmpty()) return emptyList()
            return rows.filter { want.contains(keyOf.getOrPut(it.id) { EventMatch.chKey(it.name) }) }.sortedBy { EventMatch.quality(it.name) }
        }
        byKey(event.tvch).take(8).forEach { add(it, "Trasmessa secondo la guida TV", true) }
        val half = EventMatch.halfHit(event)
        val halfRows = LinkedHashMap<Long, Db.EventLiveChannel>()
        // «Dove vederla» (22 novembre 2026, il titolare: «il canale evento non corrisponde»): regola di EventMatch, come
        // Pro e pannello. 1) canali-evento con le due squadre nel nome; 2) guida TV: programma in onda al calcio d'inizio
        // che nomina l'evento; se ce ne sono, solo quelli. 3) altrimenti al massimo 6 dell'emittente giusta, da verificare.
        if (event.home != null && event.away != null) {
            for (row in rows) if (EventMatch.titleHit(event, row.name)) add(row, "Canale dell'evento", true)
        }
        val fromMs = event.start - 30 * 60_000L
        val toMs = event.start + 150 * 60_000L
        val lookup = HashMap<String, MutableList<Db.EventLiveChannel>>()
        rows.forEach { row ->
            Epg.channelKeys(row.epg, row.name).forEach { key ->
                val matches = lookup.getOrPut(key) { ArrayList() }
                if (matches.none { it.id == row.id }) matches.add(row)
            }
        }
        Db.scanProgrammes(lookup.keys.toList(), fromMs / 1000L, toMs / 1000L, 18_000) { programme ->
            val matches = lookup[programme.channel]
            if (matches != null && EventMatch.atKick(event.start, programme.start * 1000L, programme.stop * 1000L)) {
                if (EventMatch.titleHit(event, programme.title)) matches.forEach { add(it, "Trasmessa secondo la guida TV", true) }
                else if (half != null && half(programme.title)) matches.forEach { halfRows.putIfAbsent(it.id, it) }
            }
            results.size < 40
        }
        if (results.size < 40) {
            for (row in rows) {
                val playlist = byPlaylist[row.pl] ?: continue
                val items = Epg.shortCached(playlist, row.sid, 60).orEmpty()
                if (items.any { EventMatch.atKick(event.start, it.start, it.end) && EventMatch.titleHit(event, it.title) }) {
                    add(row, "Trasmessa secondo la guida TV", true)
                } else if (half != null && items.any { EventMatch.atKick(event.start, it.start, it.end) && half(it.title) }) {
                    halfRows.putIfAbsent(row.id, row)
                }
                if (results.size >= 40) break
            }
        }
        if (results.isNotEmpty()) return results.values.toList() // confermati: solo quelli
        // 4) probabili (al massimo 4): dal pannello, poi dalla guida della lista con una sola squadra o il campionato
        byKey(event.tvp).take(4).forEach { add(it, "Probabile: a quell'ora in guida c'è questo campionato", false, true) }
        for (row in halfRows.values) {
            if (results.size >= 4) break
            if (!EventMatch.clubChannel(row.name)) add(row, "Probabile: a quell'ora in guida c'è una delle due squadre o il campionato", false, true)
        }
        // 5) da verificare: l'emittente giusta, ma non i canali che secondo le guide a quell'ora trasmettono altro (23 novembre 2026,
        //    il titolare: «gli eventi sono ancora da verificare»; Bulls-Nuggets di notte, Sky Sport NBA con un documentario)
        val broadcasterTerms = EventMatch.broadcasterTerms(event)
        val offKeys = event.tvno.map(EventMatch::chKey).filter { it.isNotEmpty() }.toSet()
        rows.asSequence()
            .filter { EventMatch.matchesBroadcaster(broadcasterTerms, it.name, it.category) && EventMatch.fallbackOk(event, it.name) }
            .filter { offKeys.isEmpty() || !offKeys.contains(keyOf.getOrPut(it.id) { EventMatch.chKey(it.name) }) }
            .sortedBy { EventMatch.quality(it.name) }
            .take(maxOf(3, 8 - results.size))
            .forEach { row -> add(row, "Da verificare: di solito la trasmette questa emittente", false) }
        return results.values.toList()
    }

    private fun parseStart(value: String): Long? = try {
        val normalized = compactOffset.replace(value) { "${it.groupValues[1]}:${it.groupValues[2]}" }
        OffsetDateTime.parse(normalized).toInstant().toEpochMilli()
    } catch (_: Exception) { null }

    private fun parseTeam(obj: JSONObject): EventTeam? {
        val name = obj.string("name")
        if (name.isEmpty()) return null
        val sets = obj.optJSONArray("sets").asInts()
        return EventTeam(
            name = name,
            logo = obj.string("logo"),
            score = obj.scalar("score"),
            sets = sets,
            seed = obj.optInt("seed").takeIf { obj.has("seed") && !obj.isNull("seed") },
            italian = obj.optBoolean("it", false),
        )
    }

    private fun parseTop(array: JSONArray?): List<EventLeader> {
        if (array == null) return emptyList()
        val out = ArrayList<EventLeader>(minOf(array.length(), 20))
        for (i in 0 until minOf(array.length(), 20)) {
            val obj = array.optJSONObject(i) ?: continue
            val name = obj.string("name")
            if (name.isNotEmpty()) out.add(EventLeader(name, obj.string("flag")))
        }
        return out
    }

    private fun JSONObject.string(key: String): String = if (isNull(key)) "" else optString(key, "").trim().take(500)

    private fun JSONObject.scalar(key: String): String = when (val value = opt(key)) {
        null, JSONObject.NULL -> ""
        is String -> value
        is Number, is Boolean -> value.toString()
        else -> ""
    }

    private fun JSONArray?.asStrings(max: Int): List<String> {
        if (this == null) return emptyList()
        val out = ArrayList<String>(minOf(length(), max))
        for (i in 0 until minOf(length(), max)) {
            val v = opt(i) as? String ?: continue
            if (v.isNotBlank()) out.add(v.trim().take(40))
        }
        return out
    }

    private fun JSONArray?.asInts(): List<Int> {
        if (this == null) return emptyList()
        val count = minOf(length(), 20)
        val out = ArrayList<Int>(count)
        for (i in 0 until count) {
            val value = opt(i)
            val parsed = when (value) {
                is Number -> value.toInt()
                is String -> value.toIntOrNull()
                else -> null
            }
            if (parsed != null) out.add(parsed)
        }
        return out
    }
}
