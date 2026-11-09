package ua.powerschedule.widget

import android.app.Activity
import android.os.Bundle
import android.view.Gravity
import android.widget.*
import androidx.work.Data
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager

class MainActivity : Activity() {
    private var statusView: TextView? = null
    private lateinit var prefsRef: android.content.SharedPreferences

    override fun onResume() {
        super.onResume()
        statusView?.text = prefsRef.getString("status", "Ще не оновлювалось")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefs = getSharedPreferences("schedule", MODE_PRIVATE)
        prefsRef = prefs
        val queues = (1..6).flatMap { q -> (1..2).map { sub -> "$q.$sub" } }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 56, 48, 40)
            gravity = Gravity.CENTER_HORIZONTAL
        }
        root.addView(TextView(this).apply {
            text = "Графік відключень"
            textSize = 24f
            setPadding(0, 0, 0, 24)
        })
        root.addView(TextView(this).apply {
            text = "Оберіть чергу та підгрупу. Адреса не використовується — графік Полтаваобленерго завантажується для вибраної черги (alerts.energy, запасне джерело — Energy-UA)."
            textSize = 16f
            setPadding(0, 0, 0, 20)
        })
        root.addView(TextView(this).apply { text = "Черга / підгрупа"; textSize = 16f })
        val spinner = Spinner(this)
        spinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, queues)
        val current = prefs.getString("queue", "6.1") ?: "6.1"
        spinner.setSelection(queues.indexOf(current).coerceAtLeast(0))
        root.addView(spinner)
        root.addView(Button(this).apply {
            text = "Зберегти та оновити"
            setOnClickListener {
                val selected = spinner.selectedItem.toString()
                prefs.edit().putString("queue", selected).apply()
                val forced = OneTimeWorkRequestBuilder<ScheduleWorker>()
                    .setInputData(Data.Builder().putBoolean("force", true).build()).build()
                WorkManager.getInstance(this@MainActivity).enqueue(forced)
                Toast.makeText(this@MainActivity, "Обрано чергу $selected", Toast.LENGTH_SHORT).show()
                PowerWidgetProvider.updateAll(this@MainActivity)
            }
        })
        root.addView(TextView(this).apply {
            text = "Віджет оновлюватиме графік автоматично. Натисніть «Зберегти та оновити» після зміни черги."
            textSize = 14f
        })
        statusView = TextView(this).apply {
            textSize = 13f
            setPadding(0, 24, 0, 0)
            text = prefs.getString("status", "Ще не оновлювалось")
        }
        root.addView(statusView)
        setContentView(root)
        WorkManager.getInstance(this).enqueue(OneTimeWorkRequestBuilder<ScheduleWorker>().build())
    }
}
