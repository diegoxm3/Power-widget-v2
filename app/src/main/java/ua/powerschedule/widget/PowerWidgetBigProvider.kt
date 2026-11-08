package ua.powerschedule.widget

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context

/** The larger 5x2 variant. All drawing and scheduling lives in PowerWidgetProvider. */
class PowerWidgetBigProvider : AppWidgetProvider() {
    override fun onEnabled(context: Context) = PowerWidgetProvider.start(context)
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) = PowerWidgetProvider.refresh(context)
}
