package ua.powerschedule.widget

import android.app.AlarmManager
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.*
import android.widget.RemoteViews
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.Calendar
import java.util.concurrent.TimeUnit
import java.util.regex.Pattern

class PowerWidgetProvider : AppWidgetProvider() {
    override fun onEnabled(context: Context) {
        val req = PeriodicWorkRequestBuilder<ScheduleWorker>(15, TimeUnit.MINUTES).build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork("schedule-refresh", ExistingPeriodicWorkPolicy.UPDATE, req)
        scheduleTick(context)
    }
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        updateAll(context); scheduleTick(context)
        WorkManager.getInstance(context).enqueue(OneTimeWorkRequestBuilder<ScheduleWorker>().build())
    }
    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        if (intent.action == ACTION_TICK || intent.action == ACTION_REFRESH) updateAll(context)
        if (intent.action == ACTION_TICK) scheduleTick(context)
    }
    companion object {
        const val ACTION_TICK = "ua.powerschedule.widget.TICK"
        const val ACTION_REFRESH = "ua.powerschedule.widget.REFRESH"

        fun dayKey(c: Calendar): String =
            String.format("%04d-%02d-%02d", c.get(Calendar.YEAR), c.get(Calendar.MONTH) + 1, c.get(Calendar.DAY_OF_MONTH))

        fun updateAll(c: Context) {
            val mgr = AppWidgetManager.getInstance(c)
            val ids = mgr.getAppWidgetIds(ComponentName(c, PowerWidgetProvider::class.java))
            if (ids.isEmpty()) return
            val p = c.getSharedPreferences("schedule", Context.MODE_PRIVATE)
            val now = Calendar.getInstance()
            val minute = now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE)

            // Saved data is only valid for the day it was fetched; after midnight "tomorrow" becomes "today".
            val savedDay = p.getString("day", "") ?: ""
            val yesterday = (now.clone() as Calendar).apply { add(Calendar.DAY_OF_MONTH, -1) }
            val savedToday: List<Period>? = if (p.getBoolean("today_ok", false)) parse(p.getString("today", "") ?: "") else null
            val savedTomorrow: List<Period>? = if (p.getBoolean("tomorrow_ok", false)) parse(p.getString("tomorrow", "") ?: "") else null
            val (today, tomorrow) = when (savedDay) {
                dayKey(now) -> Pair(savedToday, savedTomorrow)
                dayKey(yesterday) -> Pair<List<Period>?, List<Period>?>(savedTomorrow, null)
                else -> Pair<List<Period>?, List<Period>?>(null, null)
            }
            val loaded = today != null
            val todayList = today ?: emptyList()

            // Today + tomorrow on one axis (tomorrow shifted by 1440), adjacent periods merged.
            val combined = if (today == null) emptyList() else
                merge(todayList + (tomorrow ?: emptyList()).map { Period(it.first + 1440, it.second + 1440) })
            val cur = combined.firstOrNull { minute >= it.first && minute < it.second }
            val off = cur != null
            val target: Int? = if (cur != null) cur.second else combined.firstOrNull { it.first > minute }?.first

            val event = if (off) "увімкнення" else "відключення"
            val countdown = if (target != null) String.format("%02d:%02d", (target - minute) / 60, (target - minute) % 60) else "--:--"
            val label = when {
                !loaded -> "очікуємо графік"
                target != null -> "до $event ${fmt(target)}"
                tomorrow == null -> "на сьогодні більше немає"
                else -> "відключень не заплановано"
            }
            val bmp = drawTimeline(todayList, minute, loaded)
            val selectedQueue = p.getString("queue", "6.1") ?: "6.1"
            ids.forEach { id ->
                val rv = RemoteViews(c.packageName, R.layout.widget_power)
                rv.setTextViewText(R.id.location, "ЧЕРГА $selectedQueue · ENERGY-UA")
                rv.setImageViewResource(R.id.status_icon, if (!loaded) R.drawable.ic_bulb_nodata else if (off) R.drawable.ic_bulb_off else R.drawable.ic_bulb_on)
                rv.setContentDescription(R.id.status_icon, if (!loaded) "Немає даних" else if (off) "Немає світла" else "Є світло")
                rv.setTextColor(R.id.countdown, if (!loaded) Color.rgb(200, 208, 214) else if (off) Color.rgb(240, 160, 164) else Color.rgb(185, 226, 201))
                rv.setTextViewText(R.id.countdown, countdown)
                rv.setTextViewText(R.id.countdown_label, label)
                rv.setImageViewBitmap(R.id.timeline, bmp)
                val open = PendingIntent.getActivity(c, 0, Intent(c, MainActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
                rv.setOnClickPendingIntent(R.id.status_icon, open); rv.setOnClickPendingIntent(R.id.timeline, open)
                mgr.updateAppWidget(id, rv)
            }
        }

        private data class Period(val first: Int, val second: Int)

        private fun merge(list: List<Period>): List<Period> {
            val out = mutableListOf<Period>()
            for (per in list.sortedBy { it.first }) {
                val last = out.lastOrNull()
                if (last != null && per.first <= last.second) out[out.size - 1] = Period(last.first, maxOf(last.second, per.second))
                else out.add(per)
            }
            return out
        }

        private fun parse(s: String): List<Period> {
            val out = mutableListOf<Period>()
            val m = Pattern.compile("З\\s*(\\d{2}):(\\d{2})\\s*до\\s*(\\d{2}):(\\d{2})").matcher(s)
            while (m.find()) {
                val a = m.group(1)!!.toInt() * 60 + m.group(2)!!.toInt()
                var b = m.group(3)!!.toInt() * 60 + m.group(4)!!.toInt()
                if (b == 0 && a > 0) b = 1440
                if (b > a) out.add(Period(a, b))
                else if (b < a) { out.add(Period(a, 1440)); if (b > 0) out.add(Period(0, b)) }
            }
            return merge(out)
        }

        private fun fmt(m: Int) = (if (m >= 1440) "завтра " else "") + String.format("%02d:%02d", (m / 60) % 24, m % 60)

        private fun drawTimeline(periods: List<Period>, minute: Int, loaded: Boolean): Bitmap {
            // 1200x144 matches the aspect ratio of a ~385dp x 46dp bar (full-width widget), so shapes are not stretched.
            val w = 1200; val h = 144
            val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            val cv = Canvas(bmp)
            val p = Paint(Paint.ANTI_ALIAS_FLAG)
            val left = 12f; val right = w - 12f
            val top = 26f; val bottom = 118f
            val radius = (bottom - top) / 2f
            val track = RectF(left, top, right, bottom)
            p.color = Color.argb(235, 48, 60, 65)
            cv.drawRoundRect(track, radius, radius, p)

            // Everything inside the bar is clipped to its rounded shape, so outages at 00:00 / 24:00 keep round ends.
            val inner = RectF(left + 5, top + 5, right - 5, bottom - 5)
            val clip = Path().apply { addRoundRect(inner, radius - 5, radius - 5, Path.Direction.CW) }
            cv.save()
            cv.clipPath(clip)
            p.color = if (loaded) Color.rgb(111, 190, 143) else Color.rgb(96, 108, 114)
            cv.drawRect(inner, p)
            periods.forEach { per ->
                if (per.first < 1440) {
                    val x1 = left + (right - left) * per.first / 1440f
                    val x2 = left + (right - left) * minOf(per.second, 1440) / 1440f
                    p.color = Color.rgb(207, 119, 124)
                    cv.drawRect(x1, inner.top, x2, inner.bottom, p)
                    p.color = Color.argb(38, 255, 255, 255)
                    cv.drawRect(x1, inner.top + 1f, x2, inner.top + 14f, p)
                }
            }
            cv.restore()

            p.style = Paint.Style.STROKE
            p.strokeWidth = 2.5f
            p.color = Color.argb(170, 184, 201, 204)
            cv.drawRoundRect(track, radius, radius, p)
            p.style = Paint.Style.FILL

            val x = left + (right - left) * minute.coerceIn(0, 1440) / 1440f
            val marker = RectF(x - 5f, 6f, x + 5f, 138f)
            p.color = Color.rgb(235, 199, 112)
            cv.drawRoundRect(marker, 5f, 5f, p)
            p.style = Paint.Style.STROKE
            p.strokeWidth = 2f
            p.color = Color.rgb(255, 226, 158)
            cv.drawRoundRect(marker, 5f, 5f, p)
            p.style = Paint.Style.FILL
            return bmp
        }

        fun scheduleTick(c: Context) {
            val am = c.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val i = Intent(c, PowerWidgetProvider::class.java).setAction(ACTION_TICK)
            val pi = PendingIntent.getBroadcast(c, 71, i, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            am.setAndAllowWhileIdle(AlarmManager.RTC, System.currentTimeMillis() + 60_000, pi)
        }
    }
}
