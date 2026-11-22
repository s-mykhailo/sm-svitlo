package com.savchenko.smsvitlo.data

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.os.CancellationSignal
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import kotlin.coroutines.resume
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** Один будинок з відповіді ДТЕК getHomeNum. */
data class House(
    val num: String,
    val groups: List<String>,
    val subType: String?,
    val start: String?,
    val end: String?,
    val type: String?,
) {
    /** Зараз діє відключення саме за цією адресою (екстрене, аварійне, планове...). */
    val active: Boolean get() = !subType.isNullOrBlank()

    val shortType: String
        get() {
            val s = subType?.lowercase() ?: return ""
            return when {
                "екстрен" in s -> "Екстрене відключення"
                "аварійн" in s -> "Аварійне відключення"
                "стабілізац" in s -> "Стабілізаційне відключення"
                "планов" in s -> "Планові роботи"
                else -> subType!!
            }
        }

    /** "до 19:30" або "до 19:30 23.11" */
    val untilText: String?
        get() {
            val e = end?.trim()?.takeIf { it.isNotBlank() } ?: return null
            val parts = e.split(' ')
            val time = parts.getOrNull(0) ?: return null
            val date = parts.getOrNull(1)
            val today = java.time.LocalDate.now(com.savchenko.smsvitlo.logic.Timeline.ZONE)
            val todayStr = "%02d.%02d.%d".format(today.dayOfMonth, today.monthValue, today.year)
            return if (date == null || date == todayStr) "до $time" else "до $time ${date.substringBeforeLast('.')}"
        }

    fun toJson(): JSONObject = JSONObject().put("num", num).put("groups", JSONArray(groups))
        .put("subType", subType ?: JSONObject.NULL).put("start", start ?: JSONObject.NULL)
        .put("end", end ?: JSONObject.NULL).put("type", type ?: JSONObject.NULL)

    companion object {
        private fun s(o: JSONObject, k: String) = o.optString(k).takeIf { it.isNotBlank() && it != "null" }

        fun fromDtek(num: String, o: JSONObject): House {
            val g = o.optJSONArray("sub_type_reason")
            val groups = (0 until (g?.length() ?: 0)).map { g!!.optString(it) }.filter { it.isNotBlank() }
            return House(num, groups, s(o, "sub_type"), s(o, "start_date"), s(o, "end_date"), s(o, "type"))
        }

        fun fromJson(o: JSONObject): House {
            val g = o.optJSONArray("groups")
            return House(o.optString("num"), (0 until (g?.length() ?: 0)).map { g!!.optString(it) },
                s(o, "subType"), s(o, "start"), s(o, "end"), s(o, "type"))
        }
    }
}

/** Результат пошуку групи (точний за адресою або приблизний за GPS). */
data class Lookup(
    val city: String,
    val street: String,
    val house: String,
    val group: String,
    val status: House?,
    val approx: Boolean,
    val distanceKm: Double? = null,
    val otherGroups: List<String> = emptyList(),
)

object Address {
    private fun streetsFile(c: Context) = File(c.filesDir, "streets.json")
    @Volatile private var streetsCache: JSONObject? = null

    /** Нормалізація назв для порівняння: "вул. Центральна" == "вулиця Центральна" == "Центральна вулиця". */
    fun core(s: String): String {
        val x = s.lowercase().replace('’', '\'').replace('ʼ', '\'').replace('`', '\'').replace('ё', 'е')
            .replace(Regex("\\(.*?\\)"), " ")
        val drop = setOf(
            "вулиця", "вул", "провулок", "пров", "проспект", "просп", "пр-т", "площа", "пл", "бульвар", "бульв", "б-р",
            "узвіз", "шосе", "тупик", "туп", "набережна", "наб", "проїзд", "майдан", "алея", "в'їзд", "лінія", "квартал", "кв-л",
            "село", "с", "м", "місто", "смт", "селище", "с-ще", "сщ", "хутір", "х", "с-ще", "сел",
        )
        return x.split(Regex("[\\s.,]+")).filter { it.isNotBlank() && it !in drop }.joinToString(" ")
    }

    /** Довідник міст і вулиць ДТЕК (кеш на тиждень). */
    suspend fun streets(c: Context, force: Boolean = false): JSONObject? {
        streetsCache?.let { if (!force) return it }
        val f = streetsFile(c)
        if (!force && f.exists() && System.currentTimeMillis() - f.lastModified() < 7 * 86_400_000L) {
            val o = withContext(Dispatchers.IO) { try { JSONObject(f.readText()) } catch (_: Exception) { null } }
            if (o != null && o.length() > 0) { streetsCache = o; return o }
        }
        val boot = DtekWeb.bootstrap(c, withStreets = true) ?: return f.takeIf { it.exists() }?.let {
            withContext(Dispatchers.IO) { try { JSONObject(it.readText()) } catch (_: Exception) { null } }
        }
        boot.scheduleJson?.let { Repository.saveDirect(c, it) }
        val st = boot.streets ?: return null
        withContext(Dispatchers.IO) { f.writeText(st.toString()) }
        streetsCache = st
        return st
    }

    fun cities(st: JSONObject): List<String> = st.keys().asSequence().toList().sorted()

    fun streetsOf(st: JSONObject, city: String): List<String> {
        val a = st.optJSONArray(city) ?: return emptyList()
        return (0 until a.length()).map { a.optString(it) }.filter { it.isNotBlank() }.sorted()
    }

    /** Пошук по списку: спочатку ті, що починаються з запиту. */
    fun filter(list: List<String>, q: String, limit: Int = 30): List<String> {
        val c = core(q)
        if (c.isBlank()) return list.take(limit)
        val starts = list.filter { core(it).startsWith(c) }
        val contains = list.filter { it !in starts && core(it).contains(c) }
        return (starts + contains).take(limit)
    }

    suspend fun houses(c: Context, city: String, street: String): List<House>? {
        val data = DtekWeb.houses(c, city, street) ?: return null
        return data.keys().asSequence().map { House.fromDtek(it, data.getJSONObject(it)) }
            .sortedWith(compareBy({ it.num.takeWhile { ch -> ch.isDigit() }.toIntOrNull() ?: Int.MAX_VALUE }, { it.num }))
            .toList()
    }

    // ---------------- GPS ----------------

    @SuppressLint("MissingPermission")
    suspend fun location(c: Context): Location? {
        val lm = c.getSystemService(LocationManager::class.java)
        val providers = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER, LocationManager.FUSED_PROVIDER)
            .filter { runCatching { lm.isProviderEnabled(it) }.getOrDefault(false) }
        for (p in providers) {
            val l = withTimeoutOrNull(if (p == LocationManager.GPS_PROVIDER) 20_000 else 10_000) {
                suspendCancellableCoroutine<Location?> { cont ->
                    if (Build.VERSION.SDK_INT >= 30) {
                        val cs = CancellationSignal()
                        cont.invokeOnCancellation { cs.cancel() }
                        lm.getCurrentLocation(p, cs, c.mainExecutor) { if (cont.isActive) cont.resume(it) }
                    } else {
                        @Suppress("DEPRECATION")
                        lm.requestSingleUpdate(p, { if (cont.isActive) cont.resume(it) }, android.os.Looper.getMainLooper())
                    }
                }
            }
            if (l != null) return l
        }
        return providers.mapNotNull { runCatching { lm.getLastKnownLocation(it) }.getOrNull() }.maxByOrNull { it.time }
    }

    fun distKm(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val r = 6371.0
        val dLat = Math.toRadians(lat2 - lat1); val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2) * sin(dLat / 2) + cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2) * sin(dLon / 2)
        return 2 * r * atan2(sqrt(a), sqrt(1 - a))
    }

    private data class OsmPlace(val name: String, val km: Double)
    private data class OsmStreet(val name: String, val km: Double)

    /** Найближчі населені пункти (до 25 км) і вулиці (до 3 км) з OpenStreetMap. */
    private suspend fun overpass(lat: Double, lon: Double): Pair<List<OsmPlace>, List<OsmStreet>>? = withContext(Dispatchers.IO) {
        val q = """
            [out:json][timeout:25];
            (
              node(around:25000,$lat,$lon)["place"~"^(city|town|village|hamlet|suburb|isolated_dwelling)$"];
              way(around:3000,$lat,$lon)["highway"]["name"];
            );
            out tags geom qt;
        """.trimIndent()
        for (server in listOf("https://overpass-api.de/api/interpreter", "https://overpass.kumi.systems/api/interpreter")) {
            try {
                val conn = (URL(server).openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"; doOutput = true
                    connectTimeout = 15000; readTimeout = 30000
                    setRequestProperty("User-Agent", "SM-Svitlo-Android (github.com/s-mykhailo/sm-svitlo)")
                    setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
                }
                conn.outputStream.use { it.write(("data=" + URLEncoder.encode(q, "UTF-8")).toByteArray()) }
                if (conn.responseCode != 200) continue
                val o = JSONObject(conn.inputStream.bufferedReader().readText())
                val els = o.optJSONArray("elements") ?: continue
                val places = ArrayList<OsmPlace>(); val streets = ArrayList<OsmStreet>()
                for (i in 0 until els.length()) {
                    val e = els.getJSONObject(i)
                    val tags = e.optJSONObject("tags") ?: continue
                    val name = tags.optString("name:uk").ifBlank { tags.optString("name") }
                    if (name.isBlank()) continue
                    when (e.optString("type")) {
                        "node" -> places.add(OsmPlace(name, distKm(lat, lon, e.optDouble("lat"), e.optDouble("lon"))))
                        "way" -> {
                            val g = e.optJSONArray("geometry") ?: continue
                            var best = Double.MAX_VALUE
                            for (k in 0 until g.length()) {
                                val p = g.getJSONObject(k)
                                best = minOf(best, distKm(lat, lon, p.optDouble("lat"), p.optDouble("lon")))
                            }
                            streets.add(OsmStreet(name, best))
                        }
                    }
                }
                return@withContext places.sortedBy { it.km } to streets.sortedBy { it.km }
            } catch (_: Exception) { }
        }
        null
    }

    class LookupError(msg: String) : Exception(msg)

    /**
     * Приблизна група за GPS: найближчий населений пункт і вулиця, які є в базі ДТЕК.
     * Для садових товариств і т.п. дає сусіднє село/вулицю - тому завжди "приблизно".
     */
    suspend fun nearest(c: Context, lat: Double, lon: Double): Lookup {
        val st = streets(c) ?: throw LookupError("Не вдалося завантажити довідник адрес ДТЕК")
        val osm = overpass(lat, lon) ?: throw LookupError("Немає зв'язку з картою OpenStreetMap")
        val cityByCore = HashMap<String, MutableList<String>>()
        for (city in cities(st)) cityByCore.getOrPut(core(city)) { ArrayList() }.add(city)

        // кандидати-міста за відстанню; на кожне пробуємо знайти вулицю поруч
        val cityCandidates = osm.first.mapNotNull { p -> cityByCore[core(p.name)]?.let { it to p.km } }
            .distinctBy { it.first }.take(4)
        if (cityCandidates.isEmpty()) throw LookupError("Поруч немає населених пунктів з бази ДТЕК")

        for ((cities, _) in cityCandidates) {
            for (city in cities) {
                val list = streetsOf(st, city)
                if (list.isEmpty()) continue
                val streetByCore = list.associateBy { core(it) }
                val match = osm.second.firstNotNullOfOrNull { s -> streetByCore[core(s.name)]?.let { it to s.km } }
                val (street, km) = match ?: continue
                val r = fromStreet(c, city, street, km) ?: continue
                return r
            }
        }
        // вулиць з OSM не знайшли в ДТЕК - беремо найближче місто і першу вулицю зі списку
        val (cities, km) = cityCandidates.first()
        for (city in cities) {
            for (street in streetsOf(st, city).take(3)) {
                fromStreet(c, city, street, km)?.let { return it }
            }
        }
        throw LookupError("ДТЕК не повернув групу для найближчих адрес")
    }

    private suspend fun fromStreet(c: Context, city: String, street: String, km: Double): Lookup? {
        val hs = houses(c, city, street)?.filter { it.groups.isNotEmpty() } ?: return null
        if (hs.isEmpty()) return null
        val counts = hs.flatMap { it.groups }.groupingBy { it }.eachCount()
        val group = counts.maxBy { it.value }.key
        val h = hs.first { group in it.groups }
        return Lookup(city, street, h.num, group, h, approx = true, distanceKm = km,
            otherGroups = counts.keys.filter { it != group }.sorted())
    }

    // ---------------- статус адрес у збережених графіках ----------------

    private fun prefs(c: Context) = c.getSharedPreferences("houses", Context.MODE_PRIVATE)

    fun status(c: Context, key: String): House? =
        prefs(c).getString(key, null)?.let { runCatching { House.fromJson(JSONObject(it)) }.getOrNull() }

    fun saveStatus(c: Context, key: String, h: House) =
        prefs(c).edit().putString(key, h.toJson().toString()).putLong("$key@t", System.currentTimeMillis()).apply()

    fun checkedAt(c: Context, key: String): Long = prefs(c).getLong("$key@t", 0)

    /** Активне відключення за адресою першого графіка з цією групою (для віджета/шторки). */
    fun activeForGroup(c: Context, group: String): House? =
        Settings(c).entries.filter { it.id == group && it.hasAddress }
            .mapNotNull { status(c, it.key) }.firstOrNull { it.active }

    /** Оновити статуси всіх адрес (одна вулиця - один запит). Повертає змінені. */
    suspend fun refreshAll(c: Context): Int {
        val st = Settings(c)
        val entries = st.entries.filter { it.hasAddress }
        if (entries.isEmpty()) return 0
        var changed = 0
        var list = st.entries
        for ((cs, group) in entries.groupBy { it.city!! to it.street!! }) {
            val hs = houses(c, cs.first, cs.second) ?: continue
            for (e in group) {
                val h = hs.firstOrNull { it.num == e.house } ?: continue
                val old = status(c, e.key)
                prefs(c).edit().putString(e.key, h.toJson().toString()).putLong("${e.key}@t", System.currentTimeMillis()).apply()
                if (old != null && e.notify) com.savchenko.smsvitlo.work.Notifier.houseChanged(c, e, old, h)
                if (old?.active != h.active || old?.end != h.end) changed++
                // ДТЕК переніс адресу в іншу групу
                val g = h.groups.firstOrNull()
                if (g != null && g != e.id && !e.approx) {
                    list = list.map { if (it.key == e.key) it.copy(id = g) else it }
                    if (e.notify) com.savchenko.smsvitlo.work.Notifier.post(c, com.savchenko.smsvitlo.work.Notifier.CH_CHANGES,
                        ("grp" + e.key).hashCode(), "Змінилась група", "${e.address}: тепер ${Parser.prettyName(g)}")
                }
            }
        }
        if (list != st.entries) st.entries = list
        return changed
    }
}
