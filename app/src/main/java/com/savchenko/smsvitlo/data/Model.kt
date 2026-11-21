package com.savchenko.smsvitlo.data

import org.json.JSONObject

/** Стан одного півгодинного слоту. */
enum class Half { ON, OFF, MAYBE, UNKNOWN }

data class Snapshot(
    /** початок доби (epoch sec, Europe/Kyiv) -> група -> 48 півгодин */
    val factDays: Map<Long, Map<String, List<Half>>>,
    val factUpdate: String?,
    /** група -> день тижня (1=Пн..7=Нд) -> 48 півгодин */
    val preset: Map<String, Map<Int, List<Half>>>,
    val presetUpdate: String?,
    val names: Map<String, String>,
    val attention: String?,
    /** коли дзеркало востаннє перевіряло сайт ДТЕК (ISO) */
    val mirrorCheckedAt: String?,
    val source: String,
    val fetchedAtMillis: Long,
) {
    fun groupName(id: String): String = names[id] ?: Parser.prettyName(id)

    val allGroups: List<String>
        get() {
            val set = LinkedHashSet<String>()
            set.addAll(names.keys)
            factDays.values.forEach { set.addAll(it.keys) }
            set.addAll(preset.keys)
            return set.sortedWith(compareBy({ Parser.sortKey(it).first }, { Parser.sortKey(it).second }))
        }

    val emergency: Boolean
        get() = attention?.lowercase()?.let { "екстрен" in it || "аварійн" in it } == true

    companion object {
        val EMPTY = Snapshot(emptyMap(), null, emptyMap(), null, emptyMap(), null, null, "none", 0)
    }
}

object Parser {
    fun halves(code: String?): Pair<Half, Half> = when (code) {
        "yes" -> Half.ON to Half.ON
        "no" -> Half.OFF to Half.OFF
        "first" -> Half.OFF to Half.ON
        "second" -> Half.ON to Half.OFF
        "maybe" -> Half.MAYBE to Half.MAYBE
        "mfirst" -> Half.MAYBE to Half.ON
        "msecond" -> Half.ON to Half.MAYBE
        else -> Half.UNKNOWN to Half.UNKNOWN
    }

    private fun hours(obj: JSONObject): List<Half> {
        val out = ArrayList<Half>(48)
        for (h in 1..24) {
            val (a, b) = halves(obj.optString(h.toString(), ""))
            out.add(a); out.add(b)
        }
        return out
    }

    fun parseFact(fact: JSONObject?): Map<Long, Map<String, List<Half>>> {
        val data = fact?.optJSONObject("data") ?: return emptyMap()
        val res = HashMap<Long, Map<String, List<Half>>>()
        for (dayKey in data.keys()) {
            val day = dayKey.toLongOrNull() ?: continue
            val groups = data.optJSONObject(dayKey) ?: continue
            val m = HashMap<String, List<Half>>()
            for (g in groups.keys()) groups.optJSONObject(g)?.let { m[g] = hours(it) }
            res[day] = m
        }
        return res
    }

    fun parsePreset(preset: JSONObject?): Map<String, Map<Int, List<Half>>> {
        val data = preset?.optJSONObject("data") ?: return emptyMap()
        val res = HashMap<String, Map<Int, List<Half>>>()
        for (g in data.keys()) {
            val days = data.optJSONObject(g) ?: continue
            val m = HashMap<Int, List<Half>>()
            for (d in days.keys()) {
                val dow = d.toIntOrNull() ?: continue
                days.optJSONObject(d)?.let { m[dow] = hours(it) }
            }
            res[g] = m
        }
        return res
    }

    fun parseNames(preset: JSONObject?): Map<String, String> {
        val n = preset?.optJSONObject("sch_names") ?: return emptyMap()
        return n.keys().asSequence().associateWith { n.optString(it) }
    }

    private fun str(o: JSONObject?, k: String): String? =
        o?.optString(k)?.takeIf { it.isNotBlank() && it != "null" }

    /** Формат дзеркала outage-data-ua і формат {fact, preset} з сайту ДТЕК однакові по суті. */
    fun parse(json: String, source: String, fetchedAt: Long): Snapshot {
        val root = JSONObject(json)
        val fact = root.optJSONObject("fact")
        val preset = root.optJSONObject("preset")
        return Snapshot(
            factDays = parseFact(fact),
            factUpdate = str(fact, "update") ?: str(fact, "updateFact"),
            preset = parsePreset(preset),
            presetUpdate = str(preset, "updateFact") ?: str(preset, "update"),
            names = parseNames(preset),
            attention = root.optJSONObject("attention")?.let { str(it, "text") },
            mirrorCheckedAt = str(root, "lastUpdated"),
            source = source,
            fetchedAtMillis = fetchedAt,
        )
    }

    /** "GPV3.1" -> "3.1", "GPV1002.1" -> "ЦЕК 2.1" */
    fun prettyName(id: String): String {
        val core = id.removePrefix("GPV")
        val major = core.substringBefore('.').toIntOrNull() ?: return id
        val minor = core.substringAfter('.', "")
        return if (major > 1000) "ЦЕК ${major - 1000}.$minor" else "Черга $major.$minor"
    }

    fun shortName(id: String): String {
        val core = id.removePrefix("GPV")
        val major = core.substringBefore('.').toIntOrNull() ?: return id
        val minor = core.substringAfter('.', "")
        return if (major > 1000) "Ц${major - 1000}.$minor" else "$major.$minor"
    }

    fun sortKey(id: String): Pair<Int, Double> {
        val core = id.removePrefix("GPV")
        val major = core.substringBefore('.').toIntOrNull() ?: 9999
        val minor = core.substringAfter('.', "0").toDoubleOrNull() ?: 0.0
        return (if (major > 1000) 1 else 0) to (major % 1000 + minor / 10)
    }
}
