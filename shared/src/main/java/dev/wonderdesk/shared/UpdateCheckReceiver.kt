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
        val done = goAsync()
        Thread {
            try {
                val names = checkAllForUpdates(app).map { it.first.name }
                if (names.isNotEmpty()) notifyUpdatesAvailable(app, names)
            } catch (_: Exception) {
            } finally {
                done.finish()
            }
        }.start()
    }
}
