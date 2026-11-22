package com.savchenko.smsvitlo.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.util.SizeF
import android.widget.RemoteViews
import com.savchenko.smsvitlo.R
import com.savchenko.smsvitlo.data.Half
import com.savchenko.smsvitlo.data.Parser
import com.savchenko.smsvitlo.data.Repository
import com.savchenko.smsvitlo.data.Settings
import com.savchenko.smsvitlo.data.Snapshot
import com.savchenko.smsvitlo.logic.Timeline
import com.savchenko.smsvitlo.ui.MainActivity
import com.savchenko.smsvitlo.work.Alarms
import java.text.SimpleDateFormat
import java.time.Duration
import java.util.Locale

class ScheduleWidget : AppWidgetProvider() {
    override fun onUpdate(c: Context, m: AppWidgetManager, ids: IntArray) {
        ids.forEach { update(c, m, it) }
        Alarms.scheduleTick(c)
    }

    override fun onAppWidgetOptionsChanged(c: Context, m: AppWidgetManager, id: Int, o: Bundle?) = update(c, m, id)

    override fun onDeleted(c: Context, ids: IntArray) { ids.forEach { Settings(c).deleteWidget(it) } }

    override fun onEnabled(c: Context) = Alarms.scheduleTick(c)

    companion object {
        fun ids(c: Context): IntArray =
            AppWidgetManager.getInstance(c).getAppWidgetIds(ComponentName(c, ScheduleWidget::class.java))

        fun hasWidgets(c: Context) = ids(c).isNotEmpty()

        fun updateAll(c: Context) {
            val m = AppWidgetManager.getInstance(c)
            ids(c).forEach { update(c, m, it) }
        }

        fun update(c: Context, m: AppWidgetManager, id: Int) {
            val o = m.getAppWidgetOptions(id)
            val cfg = Settings(c).widget(id)
            val snap = Repository.current(c)
            val pi = PendingIntent.getActivity(
                c, id, Intent(c, MainActivity::class.java).putExtra("refresh", true)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            fun views(wDp: Int, hDp: Int, maxPx: Float): RemoteViews {
                val rv = RemoteViews(c.packageName, R.layout.widget)
                try { rv.setImageViewBitmap(R.id.img, WidgetRenderer.render(c, snap, cfg, wDp, hDp, maxPx)) } catch (_: Exception) {}
                rv.setOnClickPendingIntent(R.id.img, pi)
                return rv
            }
            // Android 12+: лаунчер дає точні розміри - малюємо під кожен, без розтягування
            if (Build.VERSION.SDK_INT >= 31) {
                val sizes: List<SizeF>? = if (Build.VERSION.SDK_INT >= 33)
                    o.getParcelableArrayList(AppWidgetManager.OPTION_APPWIDGET_SIZES, SizeF::class.java)
                else @Suppress("DEPRECATION") o.getParcelableArrayList(AppWidgetManager.OPTION_APPWIDGET_SIZES)
                val list = sizes?.filter { it.width > 0 && it.height > 0 }?.distinctBy { it.width.toInt() to it.height.toInt() }?.take(3)
                if (!list.isNullOrEmpty()) {
                    val maxPx = 1_800_000f / list.size
                    m.updateAppWidget(id, RemoteViews(list.associateWith { views(it.width.toInt(), it.height.toInt(), maxPx) }))
                    return
                }
            }
            val portrait = c.resources.configuration.orientation != android.content.res.Configuration.ORIENTATION_LANDSCAPE
            var wDp = if (portrait) o.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH) else o.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH)
            var hDp = if (portrait) o.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT) else o.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT)
            if (wDp <= 0) wDp = 300
            if (hDp <= 0) hDp = 110
            m.updateAppWidget(id, views(wDp, hDp, 1_400_000f))
        }
    }
}

object WidgetRenderer {
    class Palette(
        val bg: Int, val text: Int, val sub: Int,
        val on: Int, val off: Int, val maybe: Int, val unknown: Int,
        val marker: Int, val hatch: Boolean, val outline: Int?,
    )

    fun palette(style: Int, alpha: Int): Palette {
        val a = (alpha.coerceIn(0, 100) * 255 / 100)
        return when (style) {
            Settings.STYLE_BW_DARK -> Palette(
                Color.argb(a, 0, 0, 0), Color.WHITE, Color.rgb(160, 160, 160),
                Color.rgb(242, 242, 242), Color.rgb(44, 44, 46), Color.rgb(142, 142, 147), Color.rgb(28, 28, 30),
                Color.WHITE, true, Color.rgb(90, 90, 94)
            )
            Settings.STYLE_BW_LIGHT -> Palette(
                Color.argb(a, 255, 255, 255), Color.BLACK, Color.rgb(90, 90, 95),
                Color.rgb(212, 212, 218), Color.rgb(20, 20, 22), Color.rgb(150, 150, 156), Color.rgb(240, 240, 243),
                Color.BLACK, true, Color.rgb(150, 150, 156)
            )
            else -> Palette(
                Color.argb(a, 22, 22, 26), Color.WHITE, Color.rgb(176, 176, 182),
                Color.rgb(48, 209, 88), Color.rgb(255, 69, 58), Color.rgb(255, 196, 0), Color.rgb(72, 72, 74),
                Color.WHITE, false, null
            )
        }
    }

    fun colorOf(p: Palette, h: Half) = when (h) {
        Half.ON -> p.on; Half.OFF -> p.off; Half.MAYBE -> p.maybe; Half.UNKNOWN -> p.unknown
    }

    fun render(c: Context, s: Snapshot, cfg: Settings.WidgetCfg, wDp: Int, hDp: Int, maxPx: Float = 1_400_000f): Bitmap {
        val dm = c.resources.displayMetrics
        var d = dm.density
        // обмеження пам'яті RemoteViews
        if (wDp * hDp * d * d > maxPx) d = kotlin.math.sqrt(maxPx / (wDp * hDp))
        val W = (wDp * d).toInt().coerceAtLeast(50)
        val H = (hDp * d).toInt().coerceAtLeast(30)
        val bmp = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888)
        val cv = Canvas(bmp)
        val p = palette(cfg.style, cfg.alpha)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)

        paint.color = p.bg
        cv.drawRoundRect(RectF(0f, 0f, W.toFloat(), H.toFloat()), 22 * d, 22 * d, paint)

        val pad = 11 * d
        val groups = cfg.groups.ifEmpty { Settings(c).groups.take(3) }
        val txt = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = p.text }
        val sub = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = p.sub }

        if (groups.isEmpty() || s.factDays.isEmpty() && s.preset.isEmpty()) {
            txt.textSize = 13 * d
            cv.drawText(if (groups.isEmpty()) "Додайте групу в SM Світло" else "Завантаження…", pad, H / 2f + 5 * d, txt)
            return bmp
        }

        val now = Timeline.now()
        val today = now.toLocalDate()
        var top = pad
        val showHeader = hDp >= 100 + (groups.size - 1) * 30
        if (showHeader) {
            sub.textSize = 10 * d
            val upd = "ДТЕК " + (s.factUpdate?.substringAfter(' ') ?: "—") + " · перев. " +
                SimpleDateFormat("HH:mm", Locale.US).format(s.fetchedAtMillis)
            sub.textAlign = Paint.Align.RIGHT
            cv.drawText(upd, W - pad, top + 9 * d, sub)
            sub.textAlign = Paint.Align.LEFT
            if (s.emergency) {
                val warn = Paint(txt).apply { textSize = 10 * d; typeface = Typeface.DEFAULT_BOLD
                    color = if (cfg.style == Settings.STYLE_COLOR) Color.rgb(255, 196, 0) else p.text }
                cv.drawText("⚠ Екстрені відключення", pad, top + 9 * d, warn)
            }
            top += 15 * d
        }

        val rowsH = H - top - pad * 0.8f
        val bars = if (cfg.tomorrow) 2 else 1
        val rowH = rowsH / groups.size
        val compact = rowH < (if (bars == 2) 62 else 44) * d
        for ((i, g) in groups.withIndex()) {
            val y0 = top + i * rowH
            drawRow(cv, s, g, today, now, p, d, pad, y0, W - pad, rowH, compact, cfg.tomorrow,
                com.savchenko.smsvitlo.data.Address.activeForGroup(c, g))
        }
        return bmp
    }

    private fun drawRow(
        cv: Canvas, s: Snapshot, g: String, today: java.time.LocalDate, now: java.time.ZonedDateTime,
        p: Palette, d: Float, x0: Float, y0: Float, x1: Float, rowH: Float, compact: Boolean, tomorrow: Boolean,
        house: com.savchenko.smsvitlo.data.House? = null,
    ) {
        val st = Timeline.status(s, g, now)
        val big = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = p.text; typeface = Typeface.DEFAULT_BOLD }
        val sub = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = p.sub }
        val dot = Paint(Paint.ANTI_ALIAS_FLAG)

        val titleSize = if (compact) (rowH * 0.36f).coerceIn(10 * d, 14 * d) else 15 * d
        big.textSize = titleSize
        sub.textSize = titleSize * 0.8f
        val baseY = y0 + titleSize + (if (compact) 1 * d else 2 * d)

        // кружечок стану
        val r = titleSize * 0.32f
        dot.color = if (house != null) p.off else st?.let { colorOf(p, it.current) } ?: p.unknown
        cv.drawCircle(x0 + r, baseY - titleSize * 0.35f, r, dot)
        if (p.hatch) {
            dot.style = Paint.Style.STROKE; dot.strokeWidth = 1.2f * d; dot.color = p.text
            cv.drawCircle(x0 + r, baseY - titleSize * 0.35f, r, dot)
        }
        var x = x0 + r * 2 + 6 * d
        val label = Parser.shortName(g)
        cv.drawText(label, x, baseY, big)
        x += big.measureText(label) + 8 * d

        val stateTxt = house?.shortType ?: st?.let { Timeline.stateText(it.current) } ?: "Немає даних"
        val change = if (house != null) house.untilText else st?.let { Timeline.changeText(it) }
        val left = Duration.between(now, st?.changeAt ?: now)
        val right = if (house != null) "" else st?.changeAt?.let { Timeline.inShort(left) } ?: ""

        big.textAlign = Paint.Align.RIGHT
        val rightSize = titleSize * 0.95f
        val rp = Paint(big).apply { textSize = rightSize }
        if (right.isNotEmpty()) cv.drawText(right, x1, baseY, rp)
        big.textAlign = Paint.Align.LEFT
        val avail = x1 - x - (if (right.isNotEmpty()) rp.measureText(right) + 8 * d else 0f)
        val line = listOfNotNull(stateTxt, change).joinToString(" · ")
        cv.drawText(ellipsize(line, sub, avail), x, baseY, sub)

        // смуги
        val barTop = baseY + (if (compact) 4 * d else 7 * d)
        val labelsH = if (compact) 0f else 11 * d
        val bottom = y0 + rowH - 2 * d
        val nBars = if (tomorrow && Timeline.day(s, g, today.plusDays(1), allowPreset = false) != null) 2 else 1
        val gap = 3 * d
        val barH = ((bottom - barTop - labelsH - (nBars - 1) * gap) / nBars).coerceIn(3 * d, 16 * d)
        val todayView = Timeline.day(s, g, today)
        if (todayView != null) drawBar(cv, todayView.halves, p, d, x0, barTop, x1, barH, Timeline.slotIndex(now), now.minute % 30 / 30f, !todayView.exact)
        if (nBars == 2) {
            val tv = Timeline.day(s, g, today.plusDays(1), allowPreset = false)!!
            drawBar(cv, tv.halves, p, d, x0, barTop + barH + gap, x1, barH, -1, 0f, false)
        }
        if (!compact) {
            val lp = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = p.sub; textSize = 8.5f * d; textAlign = Paint.Align.CENTER }
            val ly = barTop + nBars * barH + (nBars - 1) * gap + 9.5f * d
            val step = if (x1 - x0 < 230 * d) 6 else 3
            for (hh in 0..24 step step) {
                val xx = x0 + (x1 - x0) * hh / 24f
                lp.textAlign = when (hh) { 0 -> Paint.Align.LEFT; 24 -> Paint.Align.RIGHT; else -> Paint.Align.CENTER }
                cv.drawText(hh.toString(), xx, ly, lp)
            }
        }
    }

    private fun ellipsize(t: String, p: Paint, w: Float): String {
        if (w <= 0) return ""
        if (p.measureText(t) <= w) return t
        var s = t
        while (s.isNotEmpty() && p.measureText("$s…") > w) s = s.dropLast(1)
        return "$s…"
    }

    fun drawBar(
        cv: Canvas, halves: List<Half>, p: Palette, d: Float, x0: Float, y: Float, x1: Float, h: Float,
        nowIdx: Int, nowFrac: Float, possible: Boolean,
    ) {
        val n = halves.size
        val cw = (x1 - x0) / n
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        val rad = (h * 0.3f).coerceAtMost(4 * d)
        // фон смуги
        paint.color = p.unknown
        cv.drawRoundRect(RectF(x0, y, x1, y + h), rad, rad, paint)
        cv.save()
        val clip = Path().apply { addRoundRect(RectF(x0, y, x1, y + h), rad, rad, Path.Direction.CW) }
        cv.clipPath(clip)
        var i = 0
        while (i < n) {
            val st = halves[i]
            var j = i
            while (j < n && halves[j] == st) j++
            val l = x0 + i * cw; val r = x0 + j * cw
            paint.style = Paint.Style.FILL
            paint.color = colorOf(p, st)
            if (nowIdx >= 0 && j <= nowIdx) paint.alpha = if (p.hatch) 170 else 110
            cv.drawRect(l, y, r, y + h, paint)
            if (p.hatch && st == Half.MAYBE) {
                val hp = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = p.off; strokeWidth = 1.3f * d }
                var xx = l - h
                while (xx < r) { cv.drawLine(xx, y + h, xx + h, y, hp); xx += 4 * d }
            }
            i = j
        }
        // роздільники годин
        val sep = Paint().apply { color = p.bg or (0xFF shl 24); strokeWidth = (0.8f * d).coerceAtLeast(1f) }
        sep.alpha = 120
        for (k in 1 until 24) { val xx = x0 + k * 2 * cw; cv.drawLine(xx, y, xx, y + h, sep) }
        cv.restore()
        p.outline?.let {
            val o = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = it; strokeWidth = 1f * d }
            cv.drawRoundRect(RectF(x0, y, x1, y + h), rad, rad, o)
        }
        if (possible) {
            val o = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE; color = p.sub; strokeWidth = 1f * d
                pathEffect = DashPathEffect(floatArrayOf(3 * d, 3 * d), 0f)
            }
            cv.drawRoundRect(RectF(x0, y, x1, y + h), rad, rad, o)
        }
        if (nowIdx in 0 until n) {
            val xx = x0 + (nowIdx + nowFrac) * cw
            val m = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = p.marker; strokeWidth = 2f * d }
            val shadow = Paint(m).apply { color = Color.argb(140, 0, 0, 0); strokeWidth = 4f * d }
            if (p.marker == Color.WHITE) cv.drawLine(xx, y - 1.5f * d, xx, y + h + 1.5f * d, shadow)
            cv.drawLine(xx, y - 1.5f * d, xx, y + h + 1.5f * d, m)
        }
    }
}
