package ua.powerschedule.widget

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import org.jsoup.Jsoup
import java.net.HttpURLConnection
import java.net.URL
import java.util.Calendar
import java.util.regex.Pattern

class ScheduleWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {

    /** Periods are stored as "З HH:MM до HH:MM. ..." text, which PowerWidgetProvider.parse() understands. */
    private class Fetched(val today: String?, val tomorrow: String?, val updateTomorrow: Boolean, val emergency: Boolean, val source: String)

    private var alertsError: String = ""

    override suspend fun doWork(): Result = try { refresh() } catch (_: Exception) { Result.retry() }

    private fun refresh(): Result {
        val ctx = applicationContext
        val prefs = ctx.getSharedPreferences("schedule", Context.MODE_PRIVATE)
        val queue = prefs.getString("queue", "6.1") ?: "6.1"
        val force = inputData.getBoolean("force", false)
        val nowMs = System.currentTimeMillis()

        // Be polite to the source: at most one fetch per 5 minutes unless the person pressed the save button.
        if (!force && nowMs - prefs.getLong("last_attempt", 0L) < 5 * 60_000L) {
            PowerWidgetProvider.updateAll(ctx)
            return Result.success()
        }
        prefs.edit().putLong("last_attempt", nowMs).apply()

        val cal = Calendar.getInstance()
        val todayKey = PowerWidgetProvider.dayKey(cal)
        val yesterdayKey = PowerWidgetProvider.dayKey((cal.clone() as Calendar).apply { add(Calendar.DAY_OF_MONTH, -1) })
        val savedDay = prefs.getString("day", "") ?: ""
        val sameQueue = prefs.getString("fetched_queue", "") == queue
        val haveSaved = sameQueue &&
            ((savedDay == todayKey && prefs.getBoolean("today_ok", false)) ||
                (savedDay == yesterdayKey && prefs.getBoolean("tomorrow_ok", false)))
        val clock = String.format("%02d:%02d", cal.get(Calendar.HOUR_OF_DAY), cal.get(Calendar.MINUTE))

        val alerts = fromAlerts(queue, cal)
        val fetched: Fetched
        val status: String
        if (alerts != null) {
            fetched = alerts
            status = "Оновлено $clock · alerts.energy"
        } else {
            val fb = try { fromEnergyUa(queue) } catch (_: Exception) { null }
            val fbHasData = fb?.today?.let { Regex("\\d{2}:\\d{2}").containsMatchIn(it) } == true
            if (fb != null && (fbHasData || !haveSaved)) {
                fetched = fb
                status = "$clock · alerts.energy: $alertsError. Взято Energy-UA"
            } else {
                // Do not replace good saved data with an empty fallback.
                prefs.edit().putString("status", "$clock · alerts.energy: $alertsError. Показано збережене").apply()
                PowerWidgetProvider.updateAll(ctx)
                return Result.success()
            }
        }

        val ed = prefs.edit()
            .putString("today", fetched.today ?: "").putBoolean("today_ok", fetched.today != null)
        // A failed tomorrow request must not wipe tomorrow's schedule that was already saved today.
        if (fetched.updateTomorrow || savedDay != todayKey || !sameQueue) {
            ed.putString("tomorrow", fetched.tomorrow ?: "").putBoolean("tomorrow_ok", fetched.tomorrow != null)
        }
        ed.putBoolean("emergency", fetched.emergency)
            .putString("source", fetched.source)
            .putString("fetched_queue", queue)
            .putString("status", status)
            .putString("day", todayKey)
            .putLong("updated", nowMs).apply()
        PowerWidgetProvider.updateAll(ctx)
        return Result.success()
    }

    private fun fromAlerts(queue: String, cal: Calendar): Fetched? {
        val base = "https://alerts.energy/poltavska-oblast/schedule/poltavska_oblast_oblenergo/$queue"
        val todayHtml = try { download(base) } catch (e: Exception) {
            alertsError = e.message ?: e.javaClass.simpleName
            return null
        }
        if (todayHtml == null) { alertsError = "HTTP 404"; return null }
        val todayText = Jsoup.parse(todayHtml).text()
        val today = alertsPeriods(todayText, dateWords(cal, 0))
        if (today == null) { alertsError = "сторінка без графіка на сьогодні"; return null }

        var tomorrow: String? = null
        var updateTomorrow = true
        try {
            val html = download("$base/zavtra")
            if (html != null) tomorrow = alertsPeriods(Jsoup.parse(html).text(), dateWords(cal, 1))
        } catch (_: Exception) { updateTomorrow = false }

        return Fetched(today, tomorrow, updateTomorrow, todayText.contains("Діють аварійні відключення"), "alerts")
    }

    private fun fromEnergyUa(queue: String): Fetched {
        val html = download(energyUaPage(queue)) ?: return Fetched(null, null, true, false, "energy-ua")
        val text = Jsoup.parse(html).text()
        val today = section(text, "Періоди відключень на сьогодні", "Періоди відключень на завтра")
        val tomorrow = section(text, "Періоди відключень на завтра", "Темний дизайн")
        return Fetched(today, tomorrow?.takeIf { it.isNotBlank() }, true, false, "energy-ua")
    }

    /**
     * Reads the "Мій план відключень" block of an alerts.energy queue page. Returns null when the block or its
     * end marker is missing, or when the page is for another date (stale page), so the caller can fall back.
     */
    private fun alertsPeriods(text: String, dateWords: String): String? {
        val startMarker = "Мій план відключень"
        val a = text.indexOf(startMarker)
        if (a < 0) return null
        val from = a + startMarker.length
        val b = text.indexOf("Усього без світла", from)
        if (b < 0) return null
        val block = text.substring(from, b)
        if (!block.contains(dateWords)) return null
        val m = Pattern.compile("(\\d{2}:\\d{2})\\s*[-–—]\\s*(\\d{2}:\\d{2})").matcher(block)
        val sb = StringBuilder()
        while (m.find()) sb.append("З ").append(m.group(1)).append(" до ").append(m.group(2)).append(". ")
        return sb.toString()
    }

    private fun dateWords(cal: Calendar, plusDays: Int): String {
        val c = cal.clone() as Calendar
        c.add(Calendar.DAY_OF_MONTH, plusDays)
        val months = listOf("січня", "лютого", "березня", "квітня", "травня", "червня", "липня", "серпня", "вересня", "жовтня", "листопада", "грудня")
        return "${c.get(Calendar.DAY_OF_MONTH)} ${months[c.get(Calendar.MONTH)]} ${c.get(Calendar.YEAR)}"
    }

    /** Returns null on HTTP 404 (page not published yet); throws on other network errors. */
    private fun download(url: String): String? {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 12000; readTimeout = 12000
            setRequestProperty("User-Agent", "Mozilla/5.0 (Android) PowerScheduleWidget/1.0")
            setRequestProperty("Accept-Language", "uk")
        }
        try {
            val code = conn.responseCode
            if (code == 404) return null
            if (code != 200) throw java.io.IOException("HTTP $code")
            return conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }

    private fun section(text: String, start: String, end: String): String? {
        val a = text.indexOf(start, ignoreCase = true)
        if (a < 0) return null
        val from = a + start.length
        val b = text.indexOf(end, from, ignoreCase = true).let { if (it < 0) text.length else it }
        return text.substring(from, b)
    }

    companion object {
        // Energy-UA publishes queue-level pages independently of a street address.
        fun energyUaPage(queue: String): String {
            val parts = queue.split(".")
            val main = parts.getOrNull(0)?.toIntOrNull()?.coerceIn(1, 6) ?: 6
            val sub = parts.getOrNull(1)?.toIntOrNull()?.coerceIn(1, 2) ?: 1
            return "https://energy-ua.info/cherga/$main-$sub"
        }
    }
}
