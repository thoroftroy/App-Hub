package dev.wonderdesk.apphub

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import dev.wonderdesk.shared.*

/** How many Wonderdesk apps have updates, with a Check button. Reads the last check; only Check goes online. */
class UpdatesWidget : AppWidgetProvider() {
    companion object {
        const val CHECK = "dev.wonderdesk.apphub.WIDGET_CHECK"
        val IDS = CardIds(R.layout.widget_card, R.layout.widget_card_small, R.id.w_root, R.id.w_img, R.id.w_label, R.id.w_title, R.id.w_text, R.id.w_bar, R.id.w_btn,
            Triple(R.drawable.wd_widget_bg, R.drawable.wd_widget_bg_dark, R.drawable.wd_widget_bg_clear))
        @Volatile private var checking = false
        fun build(c: Context, id: Int): android.widget.RemoteViews {
            val look = runCatching { WidgetLook.valueOf(WidgetPrefs(c, id).str("look", "GLASS")) }.getOrDefault(WidgetLook.GLASS)
            val updates = SUITE_ENTRIES.mapNotNull { e -> val inst = installedVersion(c, e.pkg) ?: return@mapNotNull null
                cachedRelease(c, e.pkg)?.takeIf { compareVersions(it.version, inst) > 0 }?.let { e } }
            val last = lastCheckTime(c)
            val open = widgetOpen(c, MainActivity::class.java, 9700 + id % 100)
            val check = widgetBroadcast(c, UpdatesWidget::class.java, 9800 + id % 100, CHECK)
            return cardWidget(c, IDS, look, Card("Wonderdesk Apphub",
                if (checking) "Checking…" else if (updates.isEmpty()) "Everything's up to date" else if (updates.size == 1) "1 update ready" else "${updates.size} updates ready",
                if (updates.isNotEmpty()) updates.joinToString(", ") { it.name.removePrefix("Wonderdesk ") } else if (last > 0) "Checked " + relativeTime(last) else "Not checked yet",
                button = if (updates.isNotEmpty()) "Update" else "Check", open = open, action = if (updates.isNotEmpty()) open else check))
        }
    }
    override fun onUpdate(c: Context, mgr: AppWidgetManager, ids: IntArray) { ids.forEach { runCatching { mgr.updateAppWidget(it, build(c, it)) } } }
    override fun onReceive(c: Context, i: Intent) {
        super.onReceive(c, i)
        if (i.action == CHECK && !checking) {
            checking = true; refreshWidgets(c, UpdatesWidget::class.java)
            val r = goAsync()
            Thread { runCatching { checkReleases(c, force = true) }; checking = false; refreshWidgets(c, UpdatesWidget::class.java); r.finish() }.start()
        }
    }
    override fun onDeleted(c: Context, ids: IntArray) { ids.forEach { WidgetPrefs(c, it).clear() } }
}

class UpdatesWidgetSetup : WidgetSetupActivity() {
    override fun redraw() { AppWidgetManager.getInstance(this).updateAppWidget(widgetId, UpdatesWidget.build(this, widgetId)) }
    @Composable override fun Content() {
        val c = LocalContext.current
        val s = remember { WidgetPrefs(c, widgetId) }
        var look by remember { mutableStateOf(runCatching { WidgetLook.valueOf(s.str("look", "GLASS")) }.getOrDefault(WidgetLook.GLASS)) }
        WidgetSetup("Wonderdesk Apphub", "Updates widget", onCancel = { finish() }, onSave = { s.put("look" to look.name); save() }) {
            Text("Shows how many Wonderdesk apps have updates, from Apphub's last check. Tap Check to look again.")
            WidgetLookPicker(look) { look = it }
        }
    }
}
