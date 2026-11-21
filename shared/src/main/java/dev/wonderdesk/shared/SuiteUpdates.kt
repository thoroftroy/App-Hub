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
data class SuiteEntry(val name: String, val pkg: String, val repo: String, val tagline: String = "", val id: String = pkg.substringAfterLast('.'), val iconUrl: String = "")

private val BUILTIN_ENTRIES = listOf(
    SuiteEntry("Abacus", "dev.wonderdesk.abacus", "Abacus", "Scientific calculator with algebra, graphing and unit conversion"),
    SuiteEntry("Beacon", "dev.wonderdesk.beacon", "Beacon", "Search your apps, contacts, settings and the web from one box"),
    SuiteEntry("Bliss Player", "com.blissplayer.music.debug", "BlissPlayer", "Music player with even loudness, themes and a retro deck", "blissplayer"),
    SuiteEntry("Chronicle", "dev.wonderdesk.chronicle", "Chronicle", "Calendar with tasks, reminders, widgets and Google sync"),
    SuiteEntry("Cinema", "dev.wonderdesk.cinema", "Cinema", "Video player and editor for every video on your phone"),
    SuiteEntry("Control Room", "dev.wonderdesk.controlroom", "Control-Room", "Quick controls, every setting and the suite-wide look"),
    SuiteEntry("Echo", "dev.wonderdesk.echo", "Echo", "Record, clean up and edit voice and sound with one-tap Studio Voice"),
    SuiteEntry("Launcher", "dev.wonderdesk.launcher", "Wonderdesk-Launcher", "An Aqua home screen with Dock, Launchpad and widgets"),
    SuiteEntry("Memories", "dev.wonderdesk.memories", "Memories", "Every photo and video, with albums and a full editor"),
    SuiteEntry("Packrat", "dev.wonderdesk.packrat", "Packrat", "Open, browse, extract and create archives"),
    SuiteEntry("Pages", "dev.wonderdesk.pages", "Pages", "PDF reader with highlights, notes and PDF making"),
    SuiteEntry("Pulse", "dev.wonderdesk.pulse", "Pulse", "Live graphs, device details and app usage"),
    SuiteEntry("Polaris", "dev.wonderdesk.polaris", "Polaris", "The Wonderdesk web browser: Firefox engine, uBlock Origin, private Veil tabs and extensions"),
    SuiteEntry("Ripple", "dev.wonderdesk.ripple", "Ripple", "Your computer's terminal on your phone, at home or away"),
    SuiteEntry("Safekeeper", "dev.wonderdesk.safekeeper", "Safekeeper", "Automatic snapshots and verified backups"),
    SuiteEntry("Scribble", "dev.wonderdesk.scribble", "Scribble", "Markdown notes, linked pages and sketches"),
    SuiteEntry("Timepiece", "dev.wonderdesk.timepiece", "Timepiece", "Alarms, timers, stopwatches and world clock"),
    SuiteEntry("Vault", "dev.wonderdesk.vault", "Vault", "Encrypted passwords with autofill and 2FA codes"),
    SuiteEntry("Wayfinder", "dev.wonderdesk.wayfinder", "Wayfinder", "Every file, network shares, USB drives and a safe folder"),
    SuiteEntry("Wonderdesk Apphub", "dev.wonderdesk.apphub", "App-Hub", "Install and update every Wonderdesk app", "apphub"),
)

/**
 * The apps App Hub offers: the ones built in, plus whatever the live list (suite.json in the App-Hub repo on GitHub)
 * adds or corrects. A new app only needs a line in that file; the Hub picks it up by itself.
 */
object SuiteCatalog {
    const val URL = "https://raw.githubusercontent.com/$SUITE_GITHUB_OWNER/App-Hub/main/suite.json"
    @Volatile private var live: List<SuiteEntry> = emptyList()
    @Volatile private var loaded = false

    fun all(): List<SuiteEntry> {
        val byPkg = LinkedHashMap<String, SuiteEntry>()
        BUILTIN_ENTRIES.forEach { byPkg[it.pkg] = it }
        live.forEach { byPkg[it.pkg] = it }
        return byPkg.values.sortedBy { it.name.lowercase() }
    }

    private fun parse(text: String): List<SuiteEntry> = runCatching {
        val a = org.json.JSONArray(text)
        (0 until a.length()).mapNotNull { i -> a.optJSONObject(i)?.let { o ->
            val pkg = o.optString("pkg"); val name = o.optString("name"); val repo = o.optString("repo")
            if (pkg.isBlank() || name.isBlank() || repo.isBlank()) null else SuiteEntry(name, pkg, repo, o.optString("tagline"), o.optString("id").ifBlank { pkg.substringAfterLast('.') }, o.optString("icon"))
        } }
    }.getOrDefault(emptyList())

    private fun file(c: Context) = File(c.filesDir, "suite.json")
    fun iconFile(c: Context, id: String) = File(File(c.filesDir, "suite-icons"), "$id.png")

    /** Reads the saved copy of the live list (instant, no network). */
    fun load(c: Context) { if (!loaded) { loaded = true; runCatching { if (file(c).isFile) live = parse(file(c).readText()) } } }

    /** Downloads the live list and the icons of apps that are not installed. Quietly does nothing offline. */
    fun refresh(c: Context) {
        runCatching {
            val text = fetchText(URL)
            val list = parse(text)
            if (list.isEmpty()) return
            file(c).writeText(text); live = list; loaded = true
            for (e in list) if (e.iconUrl.isNotBlank() && !iconFile(c, e.id).isFile) runCatching {
                val f = iconFile(c, e.id); f.parentFile?.mkdirs()
                val conn = open(e.iconUrl); f.writeBytes(conn.inputStream.use { it.readBytes() })
            }
        }
    }
}

val SUITE_ENTRIES: List<SuiteEntry> get() = SuiteCatalog.all()
val SUITE_PKG_SET: Set<String> get() = SUITE_ENTRIES.map { it.pkg }.toSet()

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
    connectTimeout = 8_000
    readTimeout = 15_000
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
    SuiteCatalog.load(context)
    // The live app list and the releases are fetched together, not one after the other.
    val listJob = Thread { SuiteCatalog.refresh(context) }.also { it.start() }
    val found = java.util.concurrent.ConcurrentHashMap<String, SuiteRelease>()
    val failed = java.util.concurrent.ConcurrentHashMap<String, String>()
    val limitBox = java.util.concurrent.atomic.AtomicReference<RateLimitedException?>(null)
    val pool = java.util.concurrent.Executors.newFixedThreadPool(8)
    fun one(e: SuiteEntry) {
        val cached = cachedRelease(context, e.pkg)
        val age = System.currentTimeMillis() - cachedAt(context, e.pkg)
        if (!force && cached != null && age < maxAgeMs) { found[e.pkg] = cached; onEach(e, cached); return }
        if (limitBox.get() != null) { cached?.let { found[e.pkg] = it }; return }
        try {
            val rel = fetchLatestRelease(e.repo)
            cacheRelease(context, e.pkg, rel)
            found[e.pkg] = rel
            onEach(e, rel)
        } catch (ex: RateLimitedException) {
            limitBox.set(ex)
            cached?.let { found[e.pkg] = it }
        } catch (ex: Exception) {
            failed[e.pkg] = ex.message ?: "Couldn't check."
            cached?.let { found[e.pkg] = it }
            onEach(e, cached)
        }
    }
    // Apps already known from the saved list start at once; ones the live list adds follow when it arrives.
    val done = HashSet<String>()
    val jobs = ArrayList<java.util.concurrent.Future<*>>()
    fun submit(list: List<SuiteEntry>) { for (e in list) if (done.add(e.pkg)) jobs.add(pool.submit { one(e) }) }
    submit(SUITE_ENTRIES)
    runCatching { listJob.join(8000) }
    submit(SUITE_ENTRIES)
    for (j in jobs) runCatching { j.get() }
    pool.shutdown()
    prefs(context).edit().putLong(LAST_CHECK_KEY, System.currentTimeMillis()).apply()
    return CheckOutcome(found.toMap(), failed.toMap(), limitBox.get()?.message)
}

data class CheckOutcome(val releases: Map<String, SuiteRelease>, val failures: Map<String, String>, val rateLimitMessage: String?)

fun lastCheckTime(context: Context): Long = prefs(context).getLong(LAST_CHECK_KEY, 0L)

/** Installed suite apps that have a newer release. Used for notifications. */
fun checkAllForUpdates(context: Context, maxAgeMs: Long = 6 * 3600_000L): List<Pair<SuiteEntry, SuiteRelease>> {
    val outcome = checkReleases(context, force = false, maxAgeMs = maxAgeMs)
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
const val UPDATE_NOW_ACTION = "dev.wonderdesk.shared.UPDATE_NOW"

fun dailyCheckEnabled(context: Context): Boolean =
    context.getSharedPreferences(UPDATE_PREFS, Context.MODE_PRIVATE).getBoolean(DAILY_KEY, true)

fun setDailyCheckEnabled(context: Context, enabled: Boolean) {
    context.getSharedPreferences(UPDATE_PREFS, Context.MODE_PRIVATE).edit().putBoolean(DAILY_KEY, enabled).apply()
}

/** True when Android lets our 15 minute checks run even in battery saver. Without this,
 *  updates only appear after opening the app. */
fun isBatteryExempt(context: Context): Boolean = runCatching {
    val pm = context.getSystemService(android.os.PowerManager::class.java) ?: return false
    pm.isIgnoringBatteryOptimizations(context.packageName)
}.getOrDefault(false)

fun openBatterySettings(context: Context) {
    runCatching {
        context.startActivity(android.content.Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
            android.net.Uri.parse("package:${context.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        return
    }
    context.launchSafely(android.content.Intent(android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) {}
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
    // Hourly safety net, fully built in: plain GitHub release checks, no outside services.
    // Inexact so Android batches it with other work.
    val every = AlarmManager.INTERVAL_HOUR * context.getSharedPreferences(UPDATE_PREFS, Context.MODE_PRIVATE).getInt("checkHours", 1).coerceIn(1, 24)
    am.setInexactRepeating(AlarmManager.RTC, System.currentTimeMillis() + every, every, pi)
}

fun autoUpdateEnabled(context: Context): Boolean = context.getSharedPreferences(UPDATE_PREFS, Context.MODE_PRIVATE).getBoolean("auto_update", false)
fun setAutoUpdateEnabled(context: Context, on: Boolean) { context.getSharedPreferences(UPDATE_PREFS, Context.MODE_PRIVATE).edit().putBoolean("auto_update", on).apply() }

/** One app that was updated while you were away: what it was, what it is now, and what changed. */
data class UpdateReportItem(val name: String, val pkg: String, val from: String?, val to: String, val notes: List<Pair<String, String>>)

fun recordUpdate(context: Context, e: SuiteEntry, rel: SuiteRelease, from: String?) {
    val p = prefs(context)
    val arr = runCatching { org.json.JSONArray(p.getString("report", "[]")) }.getOrDefault(org.json.JSONArray())
    val keep = org.json.JSONArray(); for (i in 0 until arr.length()) if (arr.getJSONObject(i).optString("pkg") != e.pkg) keep.put(arr.getJSONObject(i))
    keep.put(JSONObject().put("name", e.name).put("pkg", e.pkg).put("from", from ?: "").put("to", rel.version)
        .put("notes", org.json.JSONArray(rel.notesSince(from).map { JSONObject().put("v", it.first).put("n", it.second) })))
    p.edit().putString("report", keep.toString()).apply()
}

fun forgetUpdate(context: Context, pkg: String) {
    val p = prefs(context); val arr = runCatching { org.json.JSONArray(p.getString("report", "[]")) }.getOrDefault(org.json.JSONArray())
    val keep = org.json.JSONArray(); for (i in 0 until arr.length()) if (arr.getJSONObject(i).optString("pkg") != pkg) keep.put(arr.getJSONObject(i))
    p.edit().putString("report", keep.toString()).apply()
}

fun pendingUpdateReport(context: Context): List<UpdateReportItem> = runCatching {
    val arr = org.json.JSONArray(prefs(context).getString("report", "[]"))
    (0 until arr.length()).map { arr.getJSONObject(it) }.map { o ->
        val n = o.optJSONArray("notes")
        UpdateReportItem(o.getString("name"), o.getString("pkg"), o.optString("from").ifBlank { null }, o.getString("to"),
            if (n == null) emptyList() else (0 until n.length()).map { n.getJSONObject(it).let { x -> x.getString("v") to x.optString("n") } })
    }
}.getOrDefault(emptyList())

fun clearUpdateReport(context: Context) { prefs(context).edit().remove("report").apply() }

/** Short plain text of what changed, for a notification. */
private fun reportLine(r: UpdateReportItem): String {
    val bullets = r.notes.flatMap { it.second.lines() }.map { it.trim().removePrefix("•").removePrefix("-").trim() }.filter { it.isNotBlank() && !it.startsWith("#") }.take(2)
    return "${r.name} ${r.from ?: "new"} to ${r.to}" + (if (bullets.isEmpty()) "" else ": " + bullets.joinToString("; "))
}

/** Background updates: downloads and installs each one in turn (silently where Android allows), then one summary notification. */
fun autoUpdate(context: Context, updates: List<Pair<SuiteEntry, SuiteRelease>>, force: Boolean = false) {
    val cm = context.getSystemService(android.net.ConnectivityManager::class.java)
    val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    if (force) {
        nm.createNotificationChannel(NotificationChannel(CHANNEL_ID, "App updates", NotificationManager.IMPORTANCE_DEFAULT))
        runCatching { nm.notify(3, android.app.Notification.Builder(context, CHANNEL_ID).setSmallIcon(android.R.drawable.stat_sys_download).setContentTitle("Updating Wonderdesk apps")
            .setContentText(updates.joinToString(", ") { it.first.name }).setProgress(0, 0, true).setOngoing(true).build()) }
    }
    if (!force && cm?.isActiveNetworkMetered == true && !context.getSharedPreferences(UPDATE_PREFS, Context.MODE_PRIVATE).getBoolean("meteredOk", false)) { notifyUpdatesAvailable(context, updates.map { it.first.name }); return }
    val done = mutableListOf<String>(); val failed = mutableListOf<String>()
    // Apphub last, so updating itself doesn't stop the others.
    updates.sortedBy { it.first.pkg == context.packageName }.forEach { (e, rel) ->
        runCatching {
            val file = downloadApk(context, e, rel)
            // Written before the install, because updating Apphub itself ends this process before the result comes back.
            recordUpdate(context, e, rel, installedVersion(context, e.pkg))
            val latch = java.util.concurrent.CountDownLatch(1); var ok = false
            SilentInstall.install(context, file, e.name, foreground = false) { r -> ok = r.ok; latch.countDown() }
            latch.await(3, java.util.concurrent.TimeUnit.MINUTES)
            if (ok) done += e.name else { failed += e.name; forgetUpdate(context, e.pkg) }
        }.onFailure { failed += e.name; forgetUpdate(context, e.pkg) }
    }
    val mgr = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    mgr.createNotificationChannel(NotificationChannel(CHANNEL_ID, "App updates", NotificationManager.IMPORTANCE_DEFAULT))
    if (done.isNotEmpty()) runCatching {
        val lines = pendingUpdateReport(context).filter { r -> done.contains(r.name) }.map { reportLine(it) }
        val open = context.packageManager.getLaunchIntentForPackage(context.packageName)?.let { PendingIntent.getActivity(context, 2, it.putExtra("tab", "updated"), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE) }
        mgr.notify(2, android.app.Notification.Builder(context, CHANNEL_ID).setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle(if (done.size == 1) "${done[0]} was updated" else "${done.size} Wonderdesk apps were updated").setContentText(lines.firstOrNull() ?: done.joinToString(", "))
            .setStyle(android.app.Notification.BigTextStyle().bigText(lines.joinToString("\n").ifBlank { done.joinToString(", ") })).setContentIntent(open).setAutoCancel(true).build()) }
    if (force) nm.cancel(3)
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
        .addAction(android.app.Notification.Action.Builder(null, "Update now", PendingIntent.getBroadcast(context, 1,
            Intent(context, UpdateCheckReceiver::class.java).setAction(UPDATE_NOW_ACTION), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)).build())
        .build()
    mgr.notify(1, note)
}

/** Tells you about each release once: the hourly check calls this, so a version you already heard about doesn't ping again. */
fun notifyNewUpdates(context: Context, updates: List<Pair<SuiteEntry, SuiteRelease>>) {
    val p = prefs(context)
    val told = p.getStringSet("notified", emptySet()).orEmpty()
    val fresh = updates.filter { (e, r) -> "${e.pkg}:${r.version}" !in told }
    if (fresh.isEmpty()) return
    p.edit().putStringSet("notified", told + fresh.map { (e, r) -> "${e.pkg}:${r.version}" }).apply()
    notifyUpdatesAvailable(context, updates.map { it.first.name })
}
