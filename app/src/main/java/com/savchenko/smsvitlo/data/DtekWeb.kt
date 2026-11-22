package com.savchenko.smsvitlo.data

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Сесія сайту ДТЕК у прихованому WebView.
 * Сайт закритий антиботом і CSRF, тому всі запити робимо з контексту самої сторінки
 * (ті самі cookies і токен, що й у звичайному браузері).
 * Дані графіка читаємо з сирого HTML: після рендеру скрипти сторінки їх прибирають.
 */
object DtekWeb {
    const val ORIGIN = "https://www.dtek-dnem.com.ua"
    const val PAGE = "$ORIGIN/ua/shutdowns"

    private val main = Handler(Looper.getMainLooper())
    private val mutex = Mutex()
    private val pending = ConcurrentHashMap<String, CompletableDeferred<String?>>()
    private var web: WebView? = null
    private var pageReady: CompletableDeferred<Boolean>? = null
    private var loadedAt = 0L
    private var lastUse = 0L

    @Volatile var csrf: String? = null
        private set
    @Volatile var updateFact: String? = null
        private set

    private class Bridge {
        @JavascriptInterface
        fun post(id: String, payload: String?) { pending.remove(id)?.complete(payload) }
    }

    @SuppressLint("SetJavaScriptEnabled", "JavascriptInterface")
    private fun createOnMain(ctx: Context) {
        val wv = WebView(ctx.applicationContext)
        wv.settings.javaScriptEnabled = true
        wv.settings.domStorageEnabled = true
        wv.addJavascriptInterface(Bridge(), "SMBridge")
        wv.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                // сторінка-заглушка антибота сама перезавантажиться; чекаємо на справжню (з csrf)
                wv.evaluateJavascript("!!document.querySelector('meta[name=\"csrf-token\"]')") { r ->
                    if (r == "true") pageReady?.complete(true)
                }
            }
        }
        web = wv
    }

    private fun scheduleCleanup() {
        main.postDelayed({
            if (System.currentTimeMillis() - lastUse >= 110_000 && !mutex.isLocked) {
                web?.destroy(); web = null; loadedAt = 0
            }
        }, 120_000)
    }

    /** Завантажити сторінку (раз на 10 хв або якщо WebView ще немає). */
    private suspend fun ensurePage(ctx: Context): Boolean {
        val fresh = web != null && System.currentTimeMillis() - loadedAt < 10 * 60_000
        if (fresh) return true
        val ready = CompletableDeferred<Boolean>()
        pageReady = ready
        main.post {
            try {
                if (web == null) createOnMain(ctx)
                web!!.loadUrl(PAGE)
            } catch (e: Exception) { ready.complete(false) }
        }
        val ok = withTimeoutOrNull(35_000) { ready.await() } == true
        if (ok) loadedAt = System.currentTimeMillis()
        return ok
    }

    /** Виконати async-JS у сторінці; тіло має зробити `return` рядка або об'єкта. */
    private suspend fun run(jsBody: String, timeoutMs: Long = 30_000): String? {
        val id = UUID.randomUUID().toString()
        val d = CompletableDeferred<String?>()
        pending[id] = d
        val js = """(async function(){try{
            const __r = await (async function(){ $jsBody })();
            SMBridge.post('$id', __r==null?null:(typeof __r==='string'?__r:JSON.stringify(__r)));
          }catch(e){ SMBridge.post('$id', JSON.stringify({__error:String(e)})); }})();"""
        main.post { web?.evaluateJavascript(js, null) ?: d.complete(null) }
        val r = withTimeoutOrNull(timeoutMs) { d.await() }
        pending.remove(id)
        return r
    }

    private const val EXTRACT_JS = """
      function __ex(html, m){
        let i = html.indexOf(m); if (i < 0) return null; i += m.length;
        while (i < html.length && /[\s=]/.test(html[i])) i++;
        const s = i, o = html[i]; if (o !== '{' && o !== '[') return null;
        let d = 0, ins = false, esc = false;
        for (; i < html.length; i++) {
          const c = html[i];
          if (ins) { if (esc) esc = false; else if (c === '\\') esc = true; else if (c === '"') ins = false; continue; }
          if (c === '"') ins = true;
          else if (c === '{' || c === '[') d++;
          else if (c === '}' || c === ']') { d--; if (d === 0) { i++; break; } }
        }
        try { return JSON.parse(html.slice(s, i)); } catch (e) { return null; }
      }"""

    data class Boot(val scheduleJson: String?, val streets: JSONObject?)

    /** Графік (fact + preset) і довідник вулиць прямо з сайту ДТЕК. */
    suspend fun bootstrap(ctx: Context, withStreets: Boolean): Boot? = mutex.withLock {
        lastUse = System.currentTimeMillis()
        try {
            if (!ensurePage(ctx)) return null
            val raw = run(
                EXTRACT_JS + """
                const r = await fetch(location.pathname, {credentials: 'include', cache: 'no-store'});
                const html = await r.text();
                const doc = new DOMParser().parseFromString(html, 'text/html');
                const meta = doc.querySelector('meta[name="csrf-token"]') || document.querySelector('meta[name="csrf-token"]');
                return {
                  fact: __ex(html, 'DisconSchedule.fact'),
                  preset: __ex(html, 'DisconSchedule.preset'),
                  streets: ${if (withStreets) "__ex(html, 'DisconSchedule.streets')" else "null"},
                  csrf: meta ? meta.content : null
                };"""
            ) ?: return null
            val o = JSONObject(raw)
            if (o.has("__error")) return null
            csrf = o.optString("csrf").takeIf { it.isNotBlank() && it != "null" }
            val fact = o.optJSONObject("fact")
            updateFact = fact?.optString("update")?.takeIf { it.isNotBlank() }
            val schedule = if (fact?.optJSONObject("data") != null)
                JSONObject().put("fact", fact).put("preset", o.opt("preset") ?: JSONObject.NULL).toString() else null
            // вулиці: {місто: [вулиці]} або [вулиці] для однієї громади
            val streets = when (val s = o.opt("streets")) {
                is JSONObject -> s
                is org.json.JSONArray -> JSONObject().put("м. Дніпро", s)
                else -> null
            }
            Boot(schedule, streets)
        } finally {
            lastUse = System.currentTimeMillis(); main.post { scheduleCleanup() }
        }
    }

    /** Усі будинки вулиці: номер -> група(и) і поточне відключення. */
    suspend fun houses(ctx: Context, city: String, street: String): JSONObject? = mutex.withLock {
        lastUse = System.currentTimeMillis()
        try {
            if (!ensurePage(ctx)) return null
            if (csrf == null || updateFact == null) {
                // токен і updateFact беремо з поточної сторінки
                val meta = run("""
                    const m = document.querySelector('meta[name="csrf-token"]');
                    const r = await fetch(location.pathname, {credentials: 'include', cache: 'no-store'});
                    const html = await r.text();
                    const u = html.match(/"update"\s*:\s*"([^"]+)"/);
                    return {csrf: m ? m.content : null, update: u ? u[1] : null};""") ?: return null
                val mo = JSONObject(meta)
                csrf = mo.optString("csrf").takeIf { it.isNotBlank() && it != "null" }
                updateFact = mo.optString("update").takeIf { it.isNotBlank() && it != "null" }
            }
            val q = { s: String? -> JSONObject.quote(s ?: "") }
            val raw = run("""
                const b = new URLSearchParams();
                b.set('method', 'getHomeNum');
                b.set('data[0][name]', 'city'); b.set('data[0][value]', ${q(city)});
                b.set('data[1][name]', 'street'); b.set('data[1][value]', ${q(street)});
                b.set('data[2][name]', 'updateFact'); b.set('data[2][value]', ${q(updateFact)});
                const r = await fetch('/ua/ajax', {
                  method: 'POST', credentials: 'include',
                  headers: {
                    'content-type': 'application/x-www-form-urlencoded; charset=UTF-8',
                    'x-requested-with': 'XMLHttpRequest',
                    'x-csrf-token': ${q(csrf)},
                    'accept': 'application/json, text/javascript, */*; q=0.01'
                  },
                  body: b.toString()
                });
                return await r.text();""") ?: return null
            val o = try { JSONObject(raw) } catch (_: Exception) { null }
            if (o == null || !o.optBoolean("result", false)) {
                // токен міг протухнути - наступного разу перезавантажимо сторінку
                loadedAt = 0; csrf = null
                return null
            }
            o.optJSONObject("data")
        } finally {
            lastUse = System.currentTimeMillis(); main.post { scheduleCleanup() }
        }
    }
}
