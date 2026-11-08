package ua.powerschedule.widget

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import org.jsoup.Jsoup
import java.net.HttpURLConnection
import java.net.URL

class ScheduleWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result = try {
        val prefs = applicationContext.getSharedPreferences("schedule", Context.MODE_PRIVATE)
        val queue = prefs.getString("queue", "6.1") ?: "6.1"
        val conn = (URL(page(queue)).openConnection() as HttpURLConnection).apply {
            connectTimeout = 12000; readTimeout = 12000
            setRequestProperty("User-Agent", "Mozilla/5.0 (Android) PowerScheduleWidget/1.0")
        }
        val html = conn.inputStream.bufferedReader().use { it.readText() }
        conn.disconnect()
        val text = Jsoup.parse(html).text()
        val today = section(text, "Періоди відключень на сьогодні", "Періоди відключень на завтра")
        val tomorrow = section(text, "Періоди відключень на завтра", "Темний дизайн")
        prefs.edit()
            .putString("today", today ?: "").putBoolean("today_ok", today != null)
            .putString("tomorrow", tomorrow ?: "").putBoolean("tomorrow_ok", tomorrow != null && tomorrow.isNotBlank())
            .putString("day", PowerWidgetProvider.dayKey(java.util.Calendar.getInstance()))
            .putLong("updated", System.currentTimeMillis()).apply()
        PowerWidgetProvider.updateAll(applicationContext)
        Result.success()
    } catch (_: Exception) { Result.retry() }

    private fun section(text: String, start: String, end: String): String? {
        val a = text.indexOf(start, ignoreCase = true)
        if (a < 0) return null
        val from = a + start.length
        val b = text.indexOf(end, from, ignoreCase = true).let { if (it < 0) text.length else it }
        return text.substring(from, b)
    }
    companion object {
        // Energy-UA publishes queue-level pages independently of a street address.
        fun page(queue: String): String {
            val parts = queue.split(".")
            val main = parts.getOrNull(0)?.toIntOrNull()?.coerceIn(1, 6) ?: 6
            val sub = parts.getOrNull(1)?.toIntOrNull()?.coerceIn(1, 2) ?: 1
            return "https://energy-ua.info/cherga/$main-$sub"
        }
    }
}
