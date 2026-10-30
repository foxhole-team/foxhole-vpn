package com.foxhole.vpn.widget

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.os.Bundle
import com.foxhole.vpn.VpnApplication

class FoxStatusWidgetReceiver : AppWidgetProvider() {
    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        val controller = (context.applicationContext as VpnApplication).controller
        renderFoxWidgets(context, controller.state.value, controller.settings.value, appWidgetIds)
        requestWidgetRefresh()
    }

    override fun onAppWidgetOptionsChanged(context: Context, manager: AppWidgetManager, appWidgetId: Int, newOptions: Bundle) {
        onUpdate(context, manager, intArrayOf(appWidgetId))
    }
}
