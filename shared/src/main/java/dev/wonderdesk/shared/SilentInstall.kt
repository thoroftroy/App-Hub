package dev.wonderdesk.shared

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import android.os.Handler
import android.os.Looper
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Installs APKs with Android's session installer, the way Obtainium does. On Android 12 and newer an update to an app
 * Apphub installed or updated before goes through with no prompt at all; Android only asks when it must (a first install,
 * or an app another store installed). Each result comes back through [InstallResultReceiver].
 */
object SilentInstall {
    /** Result of one install: [ok], or a message for the user. */
    data class Result(val ok: Boolean, val message: String?, val needsTap: Boolean = false)
    private val waiting = ConcurrentHashMap<Int, (Result) -> Unit>()
    private val main = Handler(Looper.getMainLooper())

    /** True when Android lets Apphub update apps without asking (Android 12+). */
    val silentPossible get() = Build.VERSION.SDK_INT >= 31

    /**
     * Starts installing [file]. [onResult] runs on the main thread when Android finishes (or the user cancels).
     * [foreground]: when Android needs a confirmation, show it now; otherwise post a notification to tap.
     */
    fun install(c: Context, file: File, label: String, foreground: Boolean, onResult: (Result) -> Unit) {
        val app = c.applicationContext
        val pi = app.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        if (Build.VERSION.SDK_INT >= 31) params.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
        if (Build.VERSION.SDK_INT >= 34) params.setRequestUpdateOwnership(true)
        if (Build.VERSION.SDK_INT >= 26) params.setInstallReason(android.content.pm.PackageManager.INSTALL_REASON_USER)
        val id = pi.createSession(params)
        waiting[id] = onResult
        try {
            pi.openSession(id).use { s ->
                s.openWrite("base.apk", 0, file.length()).use { out -> file.inputStream().use { it.copyTo(out) }; s.fsync(out) }
                val back = Intent(app, InstallResultReceiver::class.java).putExtra("label", label).putExtra("foreground", foreground)
                val flags = PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
                s.commit(PendingIntent.getBroadcast(app, id, back, flags).intentSender)
            }
        } catch (e: Exception) {
            waiting.remove(id); runCatching { pi.abandonSession(id) }
            onResult(Result(false, e.message ?: "Couldn't start the install"))
        }
    }

    internal fun finish(id: Int, r: Result) { waiting.remove(id)?.let { cb -> main.post { cb(r) } } }
    internal fun hasWaiter(id: Int) = waiting.containsKey(id)

    internal fun askNotification(c: Context, label: String, confirm: Intent) {
        val nm = c.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("suite-install", "Installing updates", NotificationManager.IMPORTANCE_DEFAULT))
        val pi = PendingIntent.getActivity(c, label.hashCode(), confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        runCatching { nm.notify(label.hashCode(), android.app.Notification.Builder(c, "suite-install").setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle("Tap to finish updating $label").setContentText("Android wants you to confirm this one; later updates go through by themselves")
            .setContentIntent(pi).setAutoCancel(true).build()) }
    }
}

class InstallResultReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        val id = i.getIntExtra(PackageInstaller.EXTRA_SESSION_ID, -1)
        val label = i.getStringExtra("label") ?: "the app"
        when (val status = i.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                @Suppress("DEPRECATION") val confirm = (if (Build.VERSION.SDK_INT >= 33) i.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java) else i.getParcelableExtra(Intent.EXTRA_INTENT)) ?: return
                // In the app: show Android's prompt now. In the background (or the app was closed): leave a notification to tap.
                if (i.getBooleanExtra("foreground", false) && SilentInstall.hasWaiter(id)) runCatching { c.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.onFailure { SilentInstall.askNotification(c, label, confirm) }
                else SilentInstall.askNotification(c, label, confirm)
            }
            PackageInstaller.STATUS_SUCCESS -> SilentInstall.finish(id, SilentInstall.Result(true, null))
            PackageInstaller.STATUS_FAILURE_ABORTED -> SilentInstall.finish(id, SilentInstall.Result(false, "$label was not installed"))
            else -> SilentInstall.finish(id, SilentInstall.Result(false, i.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)?.let { "$label: $it" } ?: "$label couldn't be installed (code $status)"))
        }
    }
}

/** Runs automatic updates as a background job on an unmetered network, so long downloads aren't cut short. */
class AutoUpdateJob : android.app.job.JobService() {
    private var worker: Thread? = null
    override fun onStartJob(p: android.app.job.JobParameters): Boolean {
        worker = Thread {
            runCatching { val u = checkAllForUpdates(applicationContext); if (u.isNotEmpty()) autoUpdate(applicationContext, u) }
            jobFinished(p, false)
        }.also { it.start() }
        return true
    }
    override fun onStopJob(p: android.app.job.JobParameters) = true

    companion object {
        fun schedule(c: Context) {
            val info = android.app.job.JobInfo.Builder(0x4AB, android.content.ComponentName(c, AutoUpdateJob::class.java))
                .setRequiredNetworkType(android.app.job.JobInfo.NETWORK_TYPE_UNMETERED).setRequiresStorageNotLow(true).build()
            runCatching { c.getSystemService(android.app.job.JobScheduler::class.java).schedule(info) }
        }
    }
}
