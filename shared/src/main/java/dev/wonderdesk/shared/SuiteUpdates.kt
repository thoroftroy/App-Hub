package dev.wonderdesk.shared

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.regex.Pattern
import org.json.JSONObject

const val SUITE_GITHUB_OWNER = "thoroftroy"

/** Every installable suite app and the GitHub repo that publishes its releases. */
data class SuiteEntry(val name: String, val pkg: String, val repo: String)

val SUITE_ENTRIES = listOf(
    SuiteEntry("Abacus", "dev.wonderdesk.abacus", "Abacus"),
    SuiteEntry("Beacon", "dev.wonderdesk.beacon", "Beacon"),
    SuiteEntry("Bliss Player", "com.blissplayer.music.debug", "BlissPlayer"),
    SuiteEntry("Chronicle", "dev.wonderdesk.chronicle", "Chronicle"),
    SuiteEntry("Cinema", "dev.wonderdesk.cinema", "Cinema"),
    SuiteEntry("Control Room", "dev.wonderdesk.controlroom", "Control-Room"),
    SuiteEntry("Launcher", "dev.wonderdesk.launcher", "Wonderdesk-Launcher"),
    SuiteEntry("Memories", "dev.wonderdesk.memories", "Memories"),
    SuiteEntry("Packrat", "dev.wonderdesk.packrat", "Packrat"),
    SuiteEntry("Pages", "dev.wonderdesk.pages", "Pages"),
    SuiteEntry("Pulse", "dev.wonderdesk.pulse", "Pulse"),
    SuiteEntry("Safekeeper", "dev.wonderdesk.safekeeper", "Safekeeper"),
    SuiteEntry("Scribble", "dev.wonderdesk.scribble", "Scribble"),
    SuiteEntry("Timepiece", "dev.wonderdesk.timepiece", "Timepiece"),
    SuiteEntry("Vault", "dev.wonderdesk.vault", "Vault"),
    SuiteEntry("Wayfinder", "dev.wonderdesk.wayfinder", "Wayfinder"),
    SuiteEntry("Wonderdesk App Hub", "dev.wonderdesk.apphub", "App-Hub"),
)

val SUITE_PKG_SET = SUITE_ENTRIES.map { it.pkg }.toSet()

/** Suite entries not on the phone whose name matches the query. Pure for testing. */
fun missingSuiteEntries(installedPkgs: Set<String>, query: String): List<SuiteEntry> =
    SUITE_ENTRIES.filter { e -> e.pkg !in installedPkgs && e.name.contains(query.trim(), ignoreCase = true) }

/** A published release carrying an APK. Pure for testing. */
data class SuiteRelease(val tag: String, val version: String, val apkName: String, val apkUrl: String)

/** Newest release or pre-release tag from the releases atom feed. */
private val ATOM_ENTRY = Pattern.compile("<entry>.*?<id>tag:github\\.com,2008:Repository/[^<]*/(v[0-9][^<]*)</id>.*?<title>([^<]*)</title>", Pattern.DOTALL)

/** GitHub's atom feed has no API rate limit, unlike the JSON API.
 *  The repo slug is passed in; the newest tag, asset name and download URL
 *  are derived deterministically (release assets always follow the same naming). */
fun parseAtomFeed(xml: String, repo: String): SuiteRelease? {
    val m = ATOM_ENTRY.matcher(xml)
    if (!m.find()) return null
    val tag = m.group(1) ?: return null
    val version = tag.removePrefix("v")
    val fileName = if (repo == "BlissPlayer") "BlissPlayer-v$version-debug.apk" else "Wonderdesk-$repo-v$version-debug.apk"
    return SuiteRelease(tag, version, fileName, "https://github.com/$SUITE_GITHUB_OWNER/$repo/releases/download/$tag/$fileName")
}

/** Numeric dot-separated compare: 0.2.10 > 0.2.9. Non-numeric tails compare as strings. Pure for testing. */
fun compareVersions(a: String, b: String): Int {
    val pa = a.trim().split('.')
    val pb = b.trim().split('.')
    for (i in 0 until maxOf(pa.size, pb.size)) {
        val x = pa.getOrElse(i) { "0" }
        val y = pb.getOrElse(i) { "0" }
        val xn = x.toIntOrNull()
        val yn = y.toIntOrNull()
        val c = if (xn != null && yn != null) xn.compareTo(yn) else x.compareTo(y)
        if (c != 0) return c
    }
    return 0
}

private fun openConnection(url: String): HttpURLConnection {
    val c = URL(url).openConnection() as HttpURLConnection
    c.connectTimeout = 15_000
    c.readTimeout = 30_000
    c.instanceFollowRedirects = false
    c.setRequestProperty("User-Agent", "Wonderdesk-App-Hub")
    c.setRequestProperty("Accept", "*/*")
    return c
}

/** Reads a URL following redirects manually, retrying throttled answers with backoff. */
private fun read(url: String): String {
    var attempt = 0
    var last: Exception? = null
    while (attempt < 3) {
        var conn: HttpURLConnection? = null
        try {
            var current = url
            for (redirect in 0 until 3) {
                conn = openConnection(current)
                val code = conn.responseCode
                when {
                    code == 429 || code == 403 -> throw IllegalStateException("GitHub is busy ($code); wait a moment and try again.")
                    code in 300..399 -> {
                        val next = conn.getHeaderField("Location") ?: throw IllegalStateException("Redirect without a target.")
                        conn.disconnect()
                        current = next
                    }
                    code !in 200..299 -> throw IllegalStateException("GitHub answered $code.")
                    else -> return conn.inputStream.bufferedReader().use { it.readText() }
                }
            }
            throw IllegalStateException("Too many redirects.")
        } catch (e: Exception) {
            last = e
            attempt++
            try { Thread.sleep(800L * attempt) } catch (_: InterruptedException) {}
        } finally {
            conn?.disconnect()
        }
    }
    throw IllegalStateException(last?.message ?: "Network failed.")
}

/** Newest release or pre-release carrying an APK. Throws with a user-readable message on failure. */
fun fetchLatestRelease(repo: String): SuiteRelease {
    val xml = read("https://github.com/$SUITE_GITHUB_OWNER/$repo/releases.atom")
    return parseAtomFeed(xml, repo) ?: throw IllegalStateException("No releases found for $repo yet.")
}

fun installedVersion(context: Context, pkg: String): String? =
    runCatching { context.packageManager.getPackageInfo(pkg, 0).versionName }.getOrNull()

/** Downloads the APK into private cache with progress callbacks. Throws with a user-readable message on failure. */
fun downloadApk(context: Context, release: SuiteRelease, onProgress: ((Long, Long) -> Unit)? = null): File {
    val dir = File(context.cacheDir, "updates").apply { mkdirs() }
    val out = File(dir, "${release.apkName}.part")
    var attempt = 0
    var last: Exception? = null
    while (attempt < 3) {
        var conn: HttpURLConnection? = null
        try {
            out.delete()
            // Follow the 302 to the signed storage host automatically; each
            // throttled answer (403/429) is retried with a growing pause.
            conn = openConnection(release.apkUrl)
            conn.instanceFollowRedirects = true
            when (conn.responseCode) {
                403, 429 -> throw IllegalStateException("GitHub is busy (${conn.responseCode}); waiting a moment and retrying.")
                !in 200..299 -> throw IllegalStateException("Download failed (${conn.responseCode}).")
            }
            val total = conn.contentLengthLong.coerceAtLeast(0L)
            onProgress?.invoke(0L, total)
            conn.inputStream.use { input ->
                out.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var copied = 0L
                    var lastReport = 0L
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        copied += read
                        if (copied - lastReport >= 256 * 1024 || copied == total) {
                            onProgress?.invoke(copied, total)
                            lastReport = copied
                        }
                    }
                }
            }
            if (out.length() == 0L) throw IllegalStateException("Downloaded file is empty.")
            val final = File(dir, release.apkName)
            if (final.exists()) final.delete()
            out.renameTo(final)
            return final
        } catch (e: Exception) {
            last = e
            attempt++
            if (attempt < 3) try { Thread.sleep(1500L * attempt) } catch (_: InterruptedException) {}
        } finally {
            conn?.disconnect()
        }
    }
    throw IllegalStateException(last?.message ?: "Download failed after retries.")
}

/** Hands the APK to Android's installer. Android always asks the user to confirm. */
fun installApk(context: Context, file: File) {
    val uri: Uri = FileProvider.getUriForFile(context, "${context.packageName}.updates", file)
    context.startActivity(Intent(Intent.ACTION_VIEW).apply {
        setDataAndType(uri, "application/vnd.android.package-archive")
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
    })
}

/** Installed suite apps that have a newer release. Used for notifications. */
fun checkAllForUpdates(context: Context): List<Pair<SuiteEntry, SuiteRelease>> {
    val out = mutableListOf<Pair<SuiteEntry, SuiteRelease>>()
    for (e in SUITE_ENTRIES) {
        val installed = installedVersion(context, e.pkg) ?: continue
        val rel = runCatching { fetchLatestRelease(e.repo) }.getOrNull() ?: continue
        if (runCatching { compareVersions(rel.version, installed) > 0 }.getOrDefault(false)) {
            out.add(e to rel)
        }
    }
    return out
}

// ---- update check notifications ----

private const val UPDATE_PREFS = "suite-updates"
private const val DAILY_KEY = "daily_check"
private const val CACHE_PREFIX = "release_"
private const val CHANNEL_ID = "suite-updates"
private const val CHECK_ACTION = "dev.wonderdesk.shared.CHECK_UPDATES"

fun dailyCheckEnabled(context: Context): Boolean =
    context.getSharedPreferences(UPDATE_PREFS, Context.MODE_PRIVATE).getBoolean(DAILY_KEY, true)

fun setDailyCheckEnabled(context: Context, enabled: Boolean) {
    context.getSharedPreferences(UPDATE_PREFS, Context.MODE_PRIVATE).edit().putBoolean(DAILY_KEY, enabled).apply()
}

private fun prefs(context: Context) = context.getSharedPreferences(UPDATE_PREFS, Context.MODE_PRIVATE)

fun cachedRelease(context: Context, pkg: String): SuiteRelease? {
    val raw = prefs(context).getString(CACHE_PREFIX + pkg, null) ?: return null
    return runCatching {
        val j = JSONObject(raw)
        SuiteRelease(j.getString("tag"), j.getString("version"), j.getString("apkName"), j.getString("apkUrl"))
    }.getOrNull()
}

fun cacheRelease(context: Context, pkg: String, rel: SuiteRelease) {
    val j = JSONObject().put("tag", rel.tag).put("version", rel.version).put("apkName", rel.apkName).put("apkUrl", rel.apkUrl)
    prefs(context).edit().putString(CACHE_PREFIX + pkg, j.toString()).apply()
}

private fun checkPendingIntent(context: Context): PendingIntent {
    val intent = Intent(context, UpdateCheckReceiver::class.java).setAction(CHECK_ACTION)
    return PendingIntent.getBroadcast(context, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
}

fun scheduleDailyCheck(context: Context) {
    val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
    val pi = checkPendingIntent(context)
    am.cancel(pi)
    am.setInexactRepeating(AlarmManager.RTC_WAKEUP, System.currentTimeMillis() + AlarmManager.INTERVAL_DAY, AlarmManager.INTERVAL_DAY, pi)
}

fun cancelDailyCheck(context: Context) {
    (context.getSystemService(Context.ALARM_SERVICE) as AlarmManager).cancel(checkPendingIntent(context))
}

fun notifyUpdatesAvailable(context: Context, names: List<String>) {
    if (names.isEmpty()) return
    val mgr = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    mgr.createNotificationChannel(NotificationChannel(CHANNEL_ID, "Suite updates", NotificationManager.IMPORTANCE_DEFAULT))
    val open = context.packageManager.getLaunchIntentForPackage(context.packageName)?.let {
        PendingIntent.getActivity(context, 0, it, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }
    val text = if (names.size == 1) "${names[0]} has an update" else "${names.size} updates: ${names.take(3).joinToString(", ")}${if (names.size > 3) " and more" else ""}"
    val note = android.app.Notification.Builder(context, CHANNEL_ID)
        .setSmallIcon(android.R.drawable.stat_sys_download_done)
        .setContentTitle("Wonderdesk updates available")
        .setContentText(text)
        .setContentIntent(open)
        .setAutoCancel(true)
        .build()
    mgr.notify(1, note)
}
