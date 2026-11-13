package dev.wonderdesk.shared

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Daily update check: notifies when installed suite apps have newer releases. */
class UpdateCheckReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            if (dailyCheckEnabled(app)) scheduleDailyCheck(app)
            return
        }
        if (!dailyCheckEnabled(app)) return
        if (autoUpdateEnabled(app)) { AutoUpdateJob.schedule(app); return }
        val done = goAsync()
        Thread {
            try {
                val updates = checkAllForUpdates(app)
                if (updates.isNotEmpty()) { if (autoUpdateEnabled(app)) autoUpdate(app, updates) else notifyUpdatesAvailable(app, updates.map { it.first.name }) }
            } catch (_: Exception) {
            } finally {
                done.finish()
            }
        }.start()
    }
}
