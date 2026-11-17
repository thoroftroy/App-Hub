package dev.wonderdesk.shared

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Update check that runs by itself: notifies when installed suite apps have newer releases. */
class UpdateCheckReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext
        if (intent.action == Intent.ACTION_BOOT_COMPLETED || intent.action == Intent.ACTION_MY_PACKAGE_REPLACED) {
            if (dailyCheckEnabled(app)) scheduleDailyCheck(app)
            return
        }
        if (intent.action == UPDATE_NOW_ACTION) { AutoUpdateJob.schedule(app, now = true); return }
        if (!dailyCheckEnabled(app)) return
        if (autoUpdateEnabled(app)) { AutoUpdateJob.schedule(app); return }
        val done = goAsync()
        Thread {
            try {
                val updates = checkAllForUpdates(app, 50 * 60_000L)
                if (updates.isNotEmpty()) { if (autoUpdateEnabled(app)) autoUpdate(app, updates) else notifyNewUpdates(app, updates) }
            } catch (_: Exception) {
            } finally {
                done.finish()
            }
        }.start()
    }
}
