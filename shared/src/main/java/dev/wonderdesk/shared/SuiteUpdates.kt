package dev.wonderdesk.shared

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.core.content.FileProvider
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.regex.Pattern
import org.json.JSONObject

const val SUITE_GITHUB_OWNER = "thoroftroy"

/** Every installable suite app and the GitHub repo that publishes its releases. */
data class SuiteEntry(val name: String, val pkg: String, val repo: String, val tagline: String = "", val id: String = pkg.substringAfterLast('.'))

val SUITE_ENTRIES = listOf(
    SuiteEntry("Abacus", "dev.wonderdesk.abacus", "Abacus", "Scientific calculator with algebra, graphing and unit conversion"),
    SuiteEntry("Beacon", "dev.wonderdesk.beacon", "Beacon", "Search your apps, contacts, settings and the web from one box"),
    SuiteEntry("Bliss Player", "com.blissplayer.music.debug", "BlissPlayer", "Music player with even loudness, themes and a retro deck", "blissplayer"),
    SuiteEntry("Chronicle", "dev.wonderdesk.chronicle", "Chronicle", "Calendar with tasks, reminders, widgets and Google sync"),
    SuiteEntry("Cinema", "dev.wonderdesk.cinema", "Cinema", "Video player and editor for every video on your phone"),
    SuiteEntry("Control Room", "dev.wonderdesk.controlroom", "Control-Room", "Quick controls, every setting and the suite-wide look"),
    SuiteEntry("Launcher", "dev.wonderdesk.launcher", "Wonderdesk-Launcher", "An Aqua home screen with Dock, Launchpad and widgets"),
    SuiteEntry("Memories", "dev.wonderdesk.memories", "Memories", "Every photo and video, with albums and a full editor"),
    SuiteEntry("Packrat", "dev.wonderdesk.packrat", "Packrat", "Open, browse, extract and create archives"),
    SuiteEntry("Pages", "dev.wonderdesk.pages", "Pages", "PDF reader with highlights, notes and PDF making"),
    SuiteEntry("Pulse", "dev.wonderdesk.pulse", "Pulse", "Live graphs, device details and app usage"),
    SuiteEntry("Safekeeper", "dev.wonderdesk.safekeeper", "Safekeeper", "Automatic snapshots and verified backups"),
    SuiteEntry("Scribble", "dev.wonderdesk.scribble", "Scribble", "Markdown notes, linked pages and sketches"),
    SuiteEntry("Timepiece", "dev.wonderdesk.timepiece", "Timepiece", "Alarms, timers, stopwatches and world clock"),
    SuiteEntry("Vault", "dev.wonderdesk.vault", "Vault", "Encrypted passwords with autofill and 2FA codes"),
    SuiteEntry("Wayfinder", "dev.wonderdesk.wayfinder", "Wayfinder", "Every file, network shares, USB drives and a safe folder"),
    SuiteEntry("Wonderdesk Apphub", "dev.wonderdesk.apphub", "App-Hub", "Install and update every Wonderdesk app", "apphub"),
)

val SUITE_PKG_SET = SUITE_ENTRIES.map { it.pkg }.toSet()

/** Suite entries not on the phone whose name matches the query. Pure for testing. */
fun missingSuiteEntries(installedPkgs: Set<String>, query: String): List<SuiteEntry> =
    SUITE_ENTRIES.filter { e -> e.pkg !in installedPkgs && e.name.contains(query.trim(), ignoreCase = true) }

/** A published release. [apkUrl] is a best guess until [resolveApkUrl] reads the real asset list. */
data class SuiteRelease(
    val tag: String, val version: String, val apkName: String, val apkUrl: String,
    /** Plain-text notes for this release ("• " bullets, "# " headings). */
    val notes: String = "",
    /** Older releases, newest first: version to notes. */
    val history: List<Pair<String, String>> = emptyList(),
) {
    /** Notes for every version newer than [installed], newest first. */
    fun notesSince(installed: String?): List<Pair<String, String>> =
        (listOf(version to notes) + history).filter { installed == null || compareVersions(it.first, installed) > 0 }.filter { it.second.isNotBlank() }
}

/** Newest release or pre-release tag from the releases atom feed. */
private val ATOM_ENTRY = Pattern.compile("<entry>.*?<id>tag:github\\.com,2008:Repository/[^<]*/(v[0-9][^<]*)</id>.*?<title>([^<]*)</title>", Pattern.DOTALL)
private val APK_HREF = Pattern.compile("href=\"(/[^\"]+/releases/download/[^\"]+\\.apk)\"")

/** Conventional asset name. Repos already named "Wonderdesk-…" are not prefixed twice. */
fun guessedApkName(repo: String, version: String): String = when {
    repo == "BlissPlayer" -> "BlissPlayer-v$version-debug.apk"
    repo.startsWith("Wonderdesk-") -> "$repo-v$version-debug.apk"
    else -> "Wonderdesk-$repo-v$version-debug.apk"
}

private val ATOM_FULL_ENTRY = Pattern.compile("<entry>(.*?)</entry>", Pattern.DOTALL)
private val ATOM_TAG = Pattern.compile("<id>tag:github\\.com,2008:Repository/[^<]*/(v[0-9][^<]*)</id>")
private val ATOM_CONTENT = Pattern.compile("<content[^>]*>(.*?)</content>", Pattern.DOTALL)

/** GitHub's atom feed has no API rate limit, unlike the JSON API, and carries each release's notes. Pure for testing. */
fun parseAtomFeed(xml: String, repo: String): SuiteRelease? {
    val entries = mutableListOf<Pair<String, String>>()
    val m = ATOM_FULL_ENTRY.matcher(xml)
    while (m.find()) {
        val body = m.group(1) ?: continue
        val t = ATOM_TAG.matcher(body); if (!t.find()) continue
        val c = ATOM_CONTENT.matcher(body)
        entries += t.group(1)!! to (if (c.find()) releaseNotesText(c.group(1) ?: "") else "")
    }
    if (entries.isEmpty()) return null
    val (tag, notes) = entries.first()
    val version = tag.removePrefix("v")
    val fileName = guessedApkName(repo, version)
    return SuiteRelease(tag, version, fileName, "https://github.com/$SUITE_GITHUB_OWNER/$repo/releases/download/$tag/$fileName",
        notes, entries.drop(1).take(12).map { it.first.removePrefix("v") to it.second })
}

/** Turns the escaped HTML of a release body into short plain text. Pure for testing. */
fun releaseNotesText(escaped: String): String {
    fun unescape(x: String) = x.replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&#39;", "'").replace("&amp;", "&")
    var h = unescape(escaped)
    h = h.replace(Regex("(?is)<h[1-6][^>]*>(.*?)</h[1-6]>")) { "\n# " + it.groupValues[1].trim() + "\n" }
    h = h.replace(Regex("(?is)<li[^>]*>"), "\n• ").replace(Regex("(?is)</li>|</p>|<br\\s*/?>"), "\n")
    h = unescape(h.replace(Regex("(?s)<[^>]+>"), ""))
    return h.lines().map { it.trim() }.filter { it.isNotEmpty() }
        .filterNot { it.startsWith("Debug-signed pre-release") || it.startsWith("Requires Android") || it.trim('-', ' ').isEmpty() }
        .joinToString("\n")
}

/** First .apk link on a release's asset list page. Pure for testing. */
fun parseExpandedAssets(html: String): String? {
    val m = APK_HREF.matcher(html)
    return if (m.find()) "https://github.com" + m.group(1) else null
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

/* ------------------------------------------ network ---------------------------------------- */

/** GitHub refused because of too many requests. [waitSeconds] is how long it asked us to wait. */
class RateLimitedException(val waitSeconds: Long) : IOException(
    "GitHub is limiting requests right now. Try again in ${if (waitSeconds >= 90) "${(waitSeconds + 59) / 60} minutes" else "a minute"}."
)

class NotFoundException(message: String) : IOException(message)

private const val USER_AGENT = "Wonderdesk-App-Hub/1.0 (Android)"

private fun open(url: String): HttpURLConnection = (URL(url).openConnection() as HttpURLConnection).apply {
    connectTimeout = 15_000
    readTimeout = 30_000
    instanceFollowRedirects = false
    setRequestProperty("User-Agent", USER_AGENT)
}

private fun retryAfter(c: HttpURLConnection): Long {
    c.getHeaderField("Retry-After")?.toLongOrNull()?.let { return it }
    c.getHeaderField("X-RateLimit-Reset")?.toLongOrNull()?.let { return (it - System.currentTimeMillis() / 1000).coerceAtLeast(30) }
    return 60
}

/**
 * GET with redirects followed by hand (so cross-host hops keep working on every Android version).
 * Rate limiting is reported, never hammered: 403/429 stop immediately. Network blips get one retry.
 */
private fun fetchText(url: String): String {
    var lastError: IOException? = null
    repeat(2) { attempt ->
        var current = url
        try {
            repeat(5) {
                val c = open(current)
                try {
                    val code = c.responseCode
                    when {
                        code in 200..299 -> return c.inputStream.bufferedReader().use { it.readText() }
                        code in 300..399 -> current = URL(URL(current), c.getHeaderField("Location") ?: throw IOException("GitHub sent a redirect without a target.")).toString()
                        code == 403 || code == 429 -> throw RateLimitedException(retryAfter(c))
                        code == 404 -> throw NotFoundException("Not found on GitHub.")
                        else -> throw IOException("GitHub answered $code.")
                    }
                } finally { c.disconnect() }
            }
            throw IOException("Too many redirects.")
        } catch (e: RateLimitedException) { throw e
        } catch (e: NotFoundException) { throw e
        } catch (e: IOException) {
            lastError = e
            if (attempt == 0) Thread.sleep(1500)
        }
    }
    throw IOException(friendlyNetwork(lastError))
}

private fun friendlyNetwork(e: Exception?): String = when (e) {
    is java.net.UnknownHostException -> "No internet connection."
    is java.net.SocketTimeoutException -> "GitHub took too long to answer."
    null -> "Network error."
    else -> e.message ?: "Network error."
}

/** Newest release or pre-release. Throws with a user-readable message on failure. */
fun fetchLatestRelease(repo: String): SuiteRelease {
    val xml = try { fetchText("https://github.com/$SUITE_GITHUB_OWNER/$repo/releases.atom") }
              catch (e: NotFoundException) { throw NotFoundException("$repo has no public releases page yet.") }
    return parseAtomFeed(xml, repo) ?: throw NotFoundException("$repo has no releases yet.")
}

/** Reads the release's actual asset list so a renamed APK never 404s. Falls back to the guess. */
fun resolveApkUrl(repo: String, release: SuiteRelease): SuiteRelease {
    val html = runCatching { fetchText("https://github.com/$SUITE_GITHUB_OWNER/$repo/releases/expanded_assets/${release.tag}") }
        .getOrElse { if (it is RateLimitedException) throw it; return release }
    val url = parseExpandedAssets(html) ?: throw NotFoundException("The ${release.tag} release doesn't have an APK attached.")
    return release.copy(apkUrl = url, apkName = Uri.decode(url.substringAfterLast('/')))
}

fun installedVersion(context: Context, pkg: String): String? =
    runCatching { context.packageManager.getPackageInfo(pkg, 0).versionName }.getOrNull()

/**
 * Downloads the APK into private cache with progress, then checks it really is the expected app.
 * Throws with a user-readable message on failure.
 */
fun downloadApk(context: Context, entry: SuiteEntry, release: SuiteRelease, onProgress: ((Long, Long) -> Unit)? = null): File {
    val resolved = resolveApkUrl(entry.repo, release)
    val dir = File(context.cacheDir, "updates").apply { mkdirs() }
    dir.listFiles()?.filter { System.currentTimeMillis() - it.lastModified() > 86_400_000L }?.forEach { it.delete() }
    val part = File(dir, "${entry.id}.part")
    var lastError: IOException? = null
    repeat(2) { attempt ->
        try {
            var current = resolved.apkUrl
            var conn: HttpURLConnection? = null
            try {
                for (hop in 0 until 6) {
                    conn?.disconnect()
                    conn = open(current)
                    val code = conn.responseCode
                    if (code in 300..399) { current = URL(URL(current), conn.getHeaderField("Location") ?: throw IOException("Bad redirect.")).toString(); continue }
                    if (code == 404) throw NotFoundException("The APK for ${entry.name} ${release.version} is missing from GitHub.")
                    if (code == 429 || (code == 403 && URL(current).host.endsWith("github.com"))) throw RateLimitedException(retryAfter(conn))
                    if (code == 403) throw IOException("The download link expired. Try again.")
                    if (code !in 200..299) throw IOException("Download failed ($code).")
                    break
                }
                val c = conn ?: throw IOException("Download failed.")
                val total = c.contentLengthLong.coerceAtLeast(0L)
                onProgress?.invoke(0L, total)
                c.inputStream.use { input ->
                    part.outputStream().use { output ->
                        val buffer = ByteArray(64 * 1024)
                        var copied = 0L
                        var lastReport = 0L
                        while (true) {
                            val read = input.read(buffer)
                            if (read < 0) break
                            output.write(buffer, 0, read)
                            copied += read
                            if (copied - lastReport >= 128 * 1024) { onProgress?.invoke(copied, total); lastReport = copied }
                        }
                        onProgress?.invoke(copied, total)
                        if (total > 0 && copied != total) throw IOException("The download was interrupted.")
                    }
                }
            } finally { conn?.disconnect() }
            val apk = File(dir, "${entry.id}-${release.version}.apk")
            apk.delete()
            if (!part.renameTo(apk)) throw IOException("Couldn't save the download.")
            verifyApk(context, entry, apk)
            return apk
        } catch (e: RateLimitedException) { part.delete(); throw e
        } catch (e: NotFoundException) { part.delete(); throw e
        } catch (e: SecurityException) { part.delete(); throw IOException(e.message)
        } catch (e: IOException) {
            part.delete()
            lastError = e
            if (attempt == 0) Thread.sleep(1500)
        }
    }
    throw IOException(friendlyNetwork(lastError))
}

/** The download must be a real APK for the expected package; signature mismatches are explained up front. */
@Suppress("DEPRECATION")
private fun verifyApk(context: Context, entry: SuiteEntry, apk: File) {
    val pm = context.packageManager
    val flags = if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES
    val info = pm.getPackageArchiveInfo(apk.path, flags) ?: run { apk.delete(); throw SecurityException("The downloaded file isn't a valid app package.") }
    if (info.packageName != entry.pkg) { apk.delete(); throw SecurityException("The download was ${info.packageName}, not ${entry.name}. It was discarded.") }
    val installed = runCatching { pm.getPackageInfo(entry.pkg, flags) }.getOrNull() ?: return
    val a = signatureDigests(info); val b = signatureDigests(installed)
    if (a.isNotEmpty() && b.isNotEmpty() && a.intersect(b).isEmpty()) throw SignatureMismatchException(entry.name)
}

class SignatureMismatchException(val appName: String) : SecurityException(
    "This update of $appName was signed with a different key than the copy on your phone, so Android won't install it over the top. Uninstall $appName first, then install it again from App Hub."
)

@Suppress("DEPRECATION")
private fun signatureDigests(info: android.content.pm.PackageInfo): Set<String> {
    val sigs = if (Build.VERSION.SDK_INT >= 28) info.signingInfo?.let { if (it.hasMultipleSigners()) it.apkContentsSigners else it.signingCertificateHistory } else info.signatures
    return sigs.orEmpty().map { s -> MessageDigest.getInstance("SHA-256").digest(s.toByteArray()).joinToString("") { "%02x".format(it) } }.toSet()
}

/** Whether Android will let App Hub open its installer. */
fun canInstallPackages(context: Context): Boolean = context.packageManager.canRequestPackageInstalls()

fun openInstallPermissionSettings(context: Context) {
    context.launchSafely(Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) {}
}

/** Hands the APK to Android's installer. Android always asks the user to confirm. */
fun installApk(context: Context, file: File) {
    val uri: Uri = FileProvider.getUriForFile(context, "${context.packageName}.updates", file)
    context.startActivity(Intent(Intent.ACTION_VIEW).apply {
        setDataAndType(uri, "application/vnd.android.package-archive")
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
    })
}

/**
 * Checks every suite app one at a time with a short pause, so GitHub never sees a burst.
 * Fresh cached answers (under [maxAgeMs]) are reused. Stops early and keeps the cache if rate limited.
 */
fun checkReleases(context: Context, force: Boolean, maxAgeMs: Long = 20 * 60_000L, onEach: (SuiteEntry, SuiteRelease?) -> Unit = { _, _ -> }): CheckOutcome {
    val found = mutableMapOf<String, SuiteRelease>()
    val failed = mutableMapOf<String, String>()
    var limited: RateLimitedException? = null
    for (e in SUITE_ENTRIES) {
        val cached = cachedRelease(context, e.pkg)
        val age = System.currentTimeMillis() - cachedAt(context, e.pkg)
        if (!force && cached != null && age < maxAgeMs) { found[e.pkg] = cached; onEach(e, cached); continue }
        if (limited != null) { cached?.let { found[e.pkg] = it }; continue }
        try {
            val rel = fetchLatestRelease(e.repo)
            cacheRelease(context, e.pkg, rel)
            found[e.pkg] = rel
            onEach(e, rel)
        } catch (ex: RateLimitedException) {
            limited = ex
            cached?.let { found[e.pkg] = it }
        } catch (ex: Exception) {
            failed[e.pkg] = ex.message ?: "Couldn't check."
            cached?.let { found[e.pkg] = it }
            onEach(e, cached)
        }
        Thread.sleep(250)
    }
    prefs(context).edit().putLong(LAST_CHECK_KEY, System.currentTimeMillis()).apply()
    return CheckOutcome(found, failed, limited?.message)
}

data class CheckOutcome(val releases: Map<String, SuiteRelease>, val failures: Map<String, String>, val rateLimitMessage: String?)

fun lastCheckTime(context: Context): Long = prefs(context).getLong(LAST_CHECK_KEY, 0L)

/** Installed suite apps that have a newer release. Used for notifications. */
fun checkAllForUpdates(context: Context): List<Pair<SuiteEntry, SuiteRelease>> {
    val outcome = checkReleases(context, force = false, maxAgeMs = 6 * 3600_000L)
    return SUITE_ENTRIES.mapNotNull { e ->
        val installed = installedVersion(context, e.pkg) ?: return@mapNotNull null
        val rel = outcome.releases[e.pkg] ?: return@mapNotNull null
        if (compareVersions(rel.version, installed) > 0) e to rel else null
    }
}

// ---- update check notifications ----

private const val UPDATE_PREFS = "suite-updates"
private const val DAILY_KEY = "daily_check"
private const val LAST_CHECK_KEY = "last_check"
private const val CACHE_PREFIX = "release_"
private const val CACHE_TIME_PREFIX = "release_at_"
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
        val hist = j.optJSONArray("history")?.let { a -> (0 until a.length()).map { a.getJSONObject(it).let { o -> o.getString("v") to o.optString("n") } } } ?: emptyList()
        SuiteRelease(j.getString("tag"), j.getString("version"), j.getString("apkName"), j.getString("apkUrl"), j.optString("notes"), hist)
    }.getOrNull()
}

private fun cachedAt(context: Context, pkg: String) = prefs(context).getLong(CACHE_TIME_PREFIX + pkg, 0L)

fun cacheRelease(context: Context, pkg: String, rel: SuiteRelease) {
    val j = JSONObject().put("tag", rel.tag).put("version", rel.version).put("apkName", rel.apkName).put("apkUrl", rel.apkUrl).put("notes", rel.notes)
        .put("history", org.json.JSONArray(rel.history.map { JSONObject().put("v", it.first).put("n", it.second) }))
    prefs(context).edit().putString(CACHE_PREFIX + pkg, j.toString()).putLong(CACHE_TIME_PREFIX + pkg, System.currentTimeMillis()).apply()
}

private fun checkPendingIntent(context: Context): PendingIntent {
    val intent = Intent(context, UpdateCheckReceiver::class.java).setAction(CHECK_ACTION)
    return PendingIntent.getBroadcast(context, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
}

fun scheduleDailyCheck(context: Context) {
    val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
    val pi = checkPendingIntent(context)
    am.cancel(pi)
    // Automatic updates check every 6 hours; plain notifications once a day. Inexact, so Android batches it with other work.
    val every = if (autoUpdateEnabled(context)) 6 * AlarmManager.INTERVAL_HOUR else AlarmManager.INTERVAL_DAY
    am.setInexactRepeating(AlarmManager.RTC, System.currentTimeMillis() + every, every, pi)
}

fun autoUpdateEnabled(context: Context): Boolean = context.getSharedPreferences(UPDATE_PREFS, Context.MODE_PRIVATE).getBoolean("auto_update", false)
fun setAutoUpdateEnabled(context: Context, on: Boolean) { context.getSharedPreferences(UPDATE_PREFS, Context.MODE_PRIVATE).edit().putBoolean("auto_update", on).apply() }

/** Background updates: downloads and installs each one in turn (silently where Android allows), then one summary notification. */
fun autoUpdate(context: Context, updates: List<Pair<SuiteEntry, SuiteRelease>>) {
    val cm = context.getSystemService(android.net.ConnectivityManager::class.java)
    if (cm?.isActiveNetworkMetered == true) { notifyUpdatesAvailable(context, updates.map { it.first.name }); return }
    val done = mutableListOf<String>(); val failed = mutableListOf<String>()
    // Apphub last, so updating itself doesn't stop the others.
    updates.sortedBy { it.first.pkg == context.packageName }.forEach { (e, rel) ->
        runCatching {
            val file = downloadApk(context, e, rel)
            val latch = java.util.concurrent.CountDownLatch(1); var ok = false
            SilentInstall.install(context, file, e.name, foreground = false) { r -> ok = r.ok; latch.countDown() }
            latch.await(3, java.util.concurrent.TimeUnit.MINUTES)
            if (ok) done += e.name else failed += e.name
        }.onFailure { failed += e.name }
    }
    val mgr = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    mgr.createNotificationChannel(NotificationChannel(CHANNEL_ID, "App updates", NotificationManager.IMPORTANCE_DEFAULT))
    if (done.isNotEmpty()) runCatching { mgr.notify(2, android.app.Notification.Builder(context, CHANNEL_ID).setSmallIcon(android.R.drawable.stat_sys_download_done)
        .setContentTitle(if (done.size == 1) "${done[0]} updated" else "${done.size} Wonderdesk apps updated").setContentText(done.joinToString(", ")).setAutoCancel(true).build()) }
    if (failed.isNotEmpty()) notifyUpdatesAvailable(context, failed)
}

fun cancelDailyCheck(context: Context) {
    (context.getSystemService(Context.ALARM_SERVICE) as AlarmManager).cancel(checkPendingIntent(context))
}

fun notifyUpdatesAvailable(context: Context, names: List<String>) {
    if (names.isEmpty()) return
    val mgr = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    mgr.createNotificationChannel(NotificationChannel(CHANNEL_ID, "App updates", NotificationManager.IMPORTANCE_DEFAULT))
    val open = context.packageManager.getLaunchIntentForPackage(context.packageName)?.let {
        PendingIntent.getActivity(context, 0, it.putExtra("tab", "updates"), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
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
