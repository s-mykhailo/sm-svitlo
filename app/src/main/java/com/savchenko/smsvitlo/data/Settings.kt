package com.savchenko.smsvitlo.data

import android.content.Context
import android.content.SharedPreferences

class Settings(ctx: Context) {
    val p: SharedPreferences = ctx.applicationContext.getSharedPreferences("settings", Context.MODE_PRIVATE)

    /** Запис у списку "Мої графіки": група + своя назва ("дім") + пуші для неї. */
    data class Entry(
        val key: String, val id: String, val label: String, val notify: Boolean,
        /** адреса з бази ДТЕК (для точної групи і статусу екстрених відключень) */
        val city: String? = null, val street: String? = null, val house: String? = null,
        /** група визначена приблизно (за GPS, найближча адреса ДТЕК) */
        val approx: Boolean = false,
    ) {
        val hasAddress: Boolean get() = city != null && street != null && house != null
        val address: String get() = listOfNotNull(city, street?.let { if (house != null) "$it, $house" else it }).joinToString(", ")
    }

    var entries: List<Entry>
        get() {
            val raw = p.getString("entries", null)
            if (raw == null) {
                // міграція зі старої версії
                return p.getString("groups", "")!!.split(',').filter { it.isNotBlank() }
                    .map { Entry(it + "#" + it.hashCode(), it, "", true) }
            }
            return try {
                val a = org.json.JSONArray(raw)
                (0 until a.length()).map { i ->
                    val o = a.getJSONObject(i)
                    fun opt(k: String) = o.optString(k).takeIf { it.isNotBlank() && it != "null" }
                    Entry(o.getString("key"), o.getString("id"), o.optString("label"), o.optBoolean("notify", true),
                        opt("city"), opt("street"), opt("house"), o.optBoolean("approx", false))
                }
            } catch (_: Exception) { emptyList() }
        }
        set(v) {
            val a = org.json.JSONArray()
            v.forEach {
                a.put(org.json.JSONObject().put("key", it.key).put("id", it.id).put("label", it.label).put("notify", it.notify)
                    .put("city", it.city ?: "").put("street", it.street ?: "").put("house", it.house ?: "").put("approx", it.approx))
            }
            p.edit().putString("entries", a.toString()).apply()
        }

    /** Унікальні групи в порядку списку. */
    var groups: List<String>
        get() = entries.map { it.id }.distinct()
        set(v) {
            val old = entries
            entries = v.map { id -> old.firstOrNull { it.id == id } ?: Entry(id + "#" + System.nanoTime(), id, "", true) }
        }

    val notifyGroups: List<String> get() = entries.filter { it.notify }.map { it.id }.distinct()

    fun displayName(id: String, s: Snapshot): String {
        val l = entries.firstOrNull { it.id == id && it.label.isNotBlank() }?.label
        return if (l != null) "$l (${s.groupName(id)})" else s.groupName(id)
    }

    // --- вигляд ---
    var theme: Int  // 0 системна, 1 світла, 2 темна
        get() = p.getInt("theme", 0)
        set(v) = p.edit().putInt("theme", v).apply()
    var dynamicColor: Boolean
        get() = p.getBoolean("dynamicColor", false)
        set(v) = p.edit().putBoolean("dynamicColor", v).apply()
    var accent: Int
        get() = p.getInt("accent", 0)
        set(v) = p.edit().putInt("accent", v).apply()
    var amoled: Boolean
        get() = p.getBoolean("amoled", false)
        set(v) = p.edit().putBoolean("amoled", v).apply()
    var snow: Boolean
        get() = p.getBoolean("snow", true)
        set(v) = p.edit().putBoolean("snow", v).apply()
    var snowAmount: Int // 0 легкий, 1 середній, 2 завірюха
        get() = p.getInt("snowAmount", 1)
        set(v) = p.edit().putInt("snowAmount", v).apply()
    var tileStyle: Int // 0 мінімал (іконки), 1 кольорові
        get() = p.getInt("tileStyle", 0)
        set(v) = p.edit().putInt("tileStyle", v).apply()
    var columns: Int
        get() = p.getInt("columns", 4)
        set(v) = p.edit().putInt("columns", v).apply()
    var scale: Int // 0 дрібний, 1 звичайний, 2 великий
        get() = p.getInt("scale", 1)
        set(v) = p.edit().putInt("scale", v).apply()
    var cardTimeline: Boolean
        get() = p.getBoolean("cardTimeline", true)
        set(v) = p.edit().putBoolean("cardTimeline", v).apply()

    var notifyChanges: Boolean
        get() = p.getBoolean("notifyChanges", true)
        set(v) = p.edit().putBoolean("notifyChanges", v).apply()
    var notifyReminders: Boolean
        get() = p.getBoolean("notifyReminders", true)
        set(v) = p.edit().putBoolean("notifyReminders", v).apply()
    var notifyRestore: Boolean
        get() = p.getBoolean("notifyRestore", true)
        set(v) = p.edit().putBoolean("notifyRestore", v).apply()
    var notifyAttention: Boolean
        get() = p.getBoolean("notifyAttention", true)
        set(v) = p.edit().putBoolean("notifyAttention", v).apply()
    var leadMinutes: Int
        get() = p.getInt("leadMinutes", 15)
        set(v) = p.edit().putInt("leadMinutes", v).apply()

    var liveMode: Boolean
        get() = p.getBoolean("liveMode", false)
        set(v) = p.edit().putBoolean("liveMode", v).apply()
    var liveIntervalMin: Int
        get() = p.getInt("liveInterval", 2)
        set(v) = p.edit().putInt("liveInterval", v).apply()
    var directDtek: Boolean
        get() = p.getBoolean("directDtek", true)
        set(v) = p.edit().putBoolean("directDtek", v).apply()

    // --- віджети ---
    data class WidgetCfg(val groups: List<String>, val style: Int, val alpha: Int, val tomorrow: Boolean)

    fun widget(id: Int): WidgetCfg = WidgetCfg(
        groups = p.getString("w${id}_groups", null)?.split(',')?.filter { it.isNotBlank() } ?: groups.take(3),
        style = p.getInt("w${id}_style", STYLE_COLOR),
        alpha = p.getInt("w${id}_alpha", 85),
        tomorrow = p.getBoolean("w${id}_tomorrow", false),
    )

    fun saveWidget(id: Int, c: WidgetCfg) = p.edit()
        .putString("w${id}_groups", c.groups.joinToString(","))
        .putInt("w${id}_style", c.style)
        .putInt("w${id}_alpha", c.alpha)
        .putBoolean("w${id}_tomorrow", c.tomorrow)
        .apply()

    fun deleteWidget(id: Int) = p.edit()
        .remove("w${id}_groups").remove("w${id}_style").remove("w${id}_alpha").remove("w${id}_tomorrow").apply()

    companion object {
        const val STYLE_COLOR = 0
        const val STYLE_BW_DARK = 1
        const val STYLE_BW_LIGHT = 2
    }
}
