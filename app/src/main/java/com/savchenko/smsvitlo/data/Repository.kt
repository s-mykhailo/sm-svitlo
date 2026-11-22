package com.savchenko.smsvitlo.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

object Repository {
    private const val API =
        "https://api.github.com/repos/Baskerville42/outage-data-ua/contents/data/dnipro.json?ref=main"
    private const val RAW =
        "https://raw.githubusercontent.com/Baskerville42/outage-data-ua/main/data/dnipro.json"

    @Volatile private var cached: Snapshot? = null

    private fun mirrorFile(c: Context) = File(c.filesDir, "mirror.json")
    private fun directFile(c: Context) = File(c.filesDir, "direct.json")
    private fun prefs(c: Context) = c.getSharedPreferences("repo", Context.MODE_PRIVATE)

    data class FetchResult(val ok: Boolean, val changed: Boolean, val message: String)

    /** Дзеркало: GitHub API з ETag (304 не з'їдає ліміт, без 5-хв кешу CDN), запасний — raw. */
    suspend fun fetchMirror(c: Context): FetchResult = withContext(Dispatchers.IO) {
        val pr = prefs(c)
        val etag = pr.getString("etag", null)
        try {
            val conn = (URL(API).openConnection() as HttpURLConnection).apply {
                connectTimeout = 12000; readTimeout = 15000
                setRequestProperty("Accept", "application/vnd.github.raw")
                setRequestProperty("User-Agent", "SM-Svitlo-Android")
                setRequestProperty("Cache-Control", "no-cache")
                if (etag != null && mirrorFile(c).exists()) setRequestProperty("If-None-Match", etag)
            }
            when (val code = conn.responseCode) {
                304 -> {
                    pr.edit().putLong("mirrorChecked", System.currentTimeMillis()).apply()
                    cached = null
                    return@withContext FetchResult(true, false, "Без змін")
                }
                200 -> {
                    val body = conn.inputStream.bufferedReader().readText()
                    JSONObject(body) // валідація
                    val changed = body != mirrorFile(c).takeIf { it.exists() }?.readText()
                    mirrorFile(c).writeText(body)
                    pr.edit().putString("etag", conn.getHeaderField("ETag"))
                        .putLong("mirrorChecked", System.currentTimeMillis()).apply()
                    cached = null
                    return@withContext FetchResult(true, changed, "Оновлено")
                }
                else -> conn.disconnect().also { if (code != 403 && code != 429) error("HTTP $code") }
            }
        } catch (_: Exception) { }
        // запасний шлях
        try {
            val conn = (URL(RAW + "?t=" + System.currentTimeMillis() / 60000).openConnection() as HttpURLConnection).apply {
                connectTimeout = 12000; readTimeout = 15000
                setRequestProperty("User-Agent", "SM-Svitlo-Android")
            }
            if (conn.responseCode != 200) return@withContext FetchResult(false, false, "HTTP ${conn.responseCode}")
            val body = conn.inputStream.bufferedReader().readText()
            JSONObject(body)
            val changed = body != mirrorFile(c).takeIf { it.exists() }?.readText()
            mirrorFile(c).writeText(body)
            pr.edit().putLong("mirrorChecked", System.currentTimeMillis()).apply()
            cached = null
            FetchResult(true, changed, "Оновлено (raw)")
        } catch (e: Exception) {
            FetchResult(false, false, "Немає зв'язку")
        }
    }

    /** Пряме джерело: сирий HTML сторінки ДТЕК через сесію у WebView. */
    suspend fun fetchDirect(c: Context): FetchResult {
        val boot = withTimeoutOrNull(45_000) { DtekWeb.bootstrap(c.applicationContext, withStreets = false) }
        val json = boot?.scheduleJson ?: return FetchResult(false, false, "ДТЕК напряму недоступний")
        return saveDirect(c, json)
    }

    suspend fun saveDirect(c: Context, json: String): FetchResult = withContext(Dispatchers.IO) {
        val changed = json != directFile(c).takeIf { it.exists() }?.readText()
        directFile(c).writeText(json)
        prefs(c).edit().putLong("directAt", System.currentTimeMillis()).apply()
        cached = null
        FetchResult(true, changed, "ДТЕК напряму")
    }

    private val DTEK_FMT = SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("Europe/Kyiv")
    }

    fun parseDtekTime(s: String?): Long = try { s?.let { DTEK_FMT.parse(it)?.time } ?: 0 } catch (_: Exception) { 0 }

    /** Об'єднаний знімок: береться свіжіший графік з двох джерел. */
    fun current(c: Context): Snapshot {
        cached?.let { return it }
        val pr = prefs(c)
        val mirror = mirrorFile(c).takeIf { it.exists() }?.let {
            try { Parser.parse(it.readText(), "mirror", pr.getLong("mirrorChecked", it.lastModified())) } catch (_: Exception) { null }
        }
        val direct = directFile(c).takeIf { it.exists() }?.let {
            try { Parser.parse(it.readText(), "dtek", pr.getLong("directAt", it.lastModified())) } catch (_: Exception) { null }
        }
        val s = when {
            mirror == null && direct == null -> Snapshot.EMPTY
            mirror == null -> direct!!
            direct == null || direct.factDays.isEmpty() -> mirror
            else -> {
                val dNewer = parseDtekTime(direct.factUpdate) > parseDtekTime(mirror.factUpdate) ||
                    (parseDtekTime(direct.factUpdate) == parseDtekTime(mirror.factUpdate) &&
                        direct.fetchedAtMillis > mirror.fetchedAtMillis)
                if (dNewer) mirror.copy(
                    factDays = direct.factDays,
                    factUpdate = direct.factUpdate,
                    preset = direct.preset.ifEmpty { mirror.preset },
                    names = direct.names.ifEmpty { mirror.names },
                    source = "dtek",
                    fetchedAtMillis = maxOf(direct.fetchedAtMillis, mirror.fetchedAtMillis),
                ) else mirror.copy(fetchedAtMillis = maxOf(direct.fetchedAtMillis, mirror.fetchedAtMillis))
            }
        }
        cached = s
        return s
    }

    fun invalidate() { cached = null }
}
