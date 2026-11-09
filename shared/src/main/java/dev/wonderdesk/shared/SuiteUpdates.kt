package dev.wonderdesk.shared

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import org.json.JSONObject

const val SUITE_GITHUB_OWNER = "thoroftroy"

/** Every installable suite app and the GitHub repo that publishes its releases. */
data class SuiteEntry(val name: String, val pkg: String, val repo: String)

val SUITE_ENTRIES = listOf(
    SuiteEntry("Abacus", "dev.wonderdesk.abacus", "Abacus"),
    SuiteEntry("App Hub", "dev.wonderdesk.apphub", "App-Hub"),
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
    SuiteEntry("Shutter", "dev.wonderdesk.shutter", "Shutter"),
    SuiteEntry("Spyglass", "dev.wonderdesk.spyglass", "Spyglass"),
    SuiteEntry("Timepiece", "dev.wonderdesk.timepiece", "Timepiece"),
    SuiteEntry("Vault", "dev.wonderdesk.vault", "Vault"),
    SuiteEntry("Wayfinder", "dev.wonderdesk.wayfinder", "Wayfinder"),
)

val SUITE_PKG_SET = SUITE_ENTRIES.map { it.pkg }.toSet()

/** Suite entries not on the phone whose name matches the query. Pure for testing. */
fun missingSuiteEntries(installedPkgs: Set<String>, query: String): List<SuiteEntry> =
    SUITE_ENTRIES.filter { e -> e.pkg !in installedPkgs && e.name.contains(query.trim(), ignoreCase = true) }

/** The newest published release that carries an APK, or null when the JSON has none. Pure for testing. */
data class SuiteRelease(val tag: String, val version: String, val apkName: String, val apkUrl: String)

fun parseLatestRelease(json: String): SuiteRelease? {
    val root = JSONObject(json)
    val tag = root.optString("tag_name").trim()
    if (tag.isEmpty()) return null
    val assets = root.optJSONArray("assets") ?: return null
    for (i in 0 until assets.length()) {
        val asset = assets.optJSONObject(i) ?: continue
        val name = asset.optString("name")
        val url = asset.optString("browser_download_url")
        if (name.endsWith(".apk", ignoreCase = true) && url.isNotBlank()) {
            return SuiteRelease(tag, tag.removePrefix("v"), name, url)
        }
    }
    return null
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

private fun githubConnection(url: String, token: String): HttpURLConnection {
    val c = URL(url).openConnection() as HttpURLConnection
    c.connectTimeout = 20_000
    c.readTimeout = 30_000
    c.setRequestProperty("Accept", "application/vnd.github+json")
    c.setRequestProperty("User-Agent", "Wonderdesk-App-Hub")
    if (token.isNotBlank()) c.setRequestProperty("Authorization", "Bearer $token")
    return c
}

/** Newest release carrying an APK. Throws with a user-readable message on failure. */
fun fetchLatestRelease(repo: String, token: String): SuiteRelease {
    val url = "https://api.github.com/repos/$SUITE_GITHUB_OWNER/$repo/releases/latest"
    val c = githubConnection(url, token)
    try {
        val code = c.responseCode
        if (code == 401 || code == 403) throw IllegalStateException("GitHub refused ($code): rate-limited or bad token.")
        if (code == 404) throw IllegalStateException("No release found. The repo may be private (add a token) or have no release yet.")
        if (code !in 200..299) throw IllegalStateException("GitHub answered $code.")
        val body = c.inputStream.bufferedReader().use { it.readText() }
        return parseLatestRelease(body) ?: throw IllegalStateException("The newest release has no APK attached yet.")
    } finally {
        c.disconnect()
    }
}

fun installedVersion(context: Context, pkg: String): String? =
    runCatching { context.packageManager.getPackageInfo(pkg, 0).versionName }.getOrNull()

/** Downloads the APK into private cache. Throws with a user-readable message on failure. */
fun downloadApk(context: Context, release: SuiteRelease, token: String): File {
    val dir = File(context.cacheDir, "updates").apply { mkdirs() }
    val out = File(dir, release.apkName)
    val c = githubConnection(release.apkUrl, token)
    try {
        // Release assets redirect to a different host; follow manually so the token is not leaked.
        c.instanceFollowRedirects = false
        var code = c.responseCode
        var location = c.getHeaderField("Location")
        c.disconnect()
        val get = if (code in 300..399 && !location.isNullOrBlank()) URL(location).openConnection() as HttpURLConnection else githubConnection(release.apkUrl, token)
        try {
            get.instanceFollowRedirects = true
            get.connect()
            if (get.responseCode !in 200..299) throw IllegalStateException("Download failed (${get.responseCode}).")
            get.inputStream.use { input -> out.outputStream().use { input.copyTo(it) } }
        } finally {
            get.disconnect()
        }
    } catch (e: IllegalStateException) { throw e
    } catch (e: Exception) { throw IllegalStateException("Download failed: ${e.localizedMessage ?: e.javaClass.simpleName}") }
    if (out.length() == 0L) throw IllegalStateException("Downloaded file is empty.")
    return out
}

/** Hands the APK to Android's installer. Android always asks the user to confirm. */
fun installApk(context: Context, file: File) {
    val uri: Uri = FileProvider.getUriForFile(context, "${context.packageName}.updates", file)
    context.startActivity(Intent(Intent.ACTION_VIEW).apply {
        setDataAndType(uri, "application/vnd.android.package-archive")
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
    })
}

private const val UPDATE_PREFS = "suite-updates"
private const val TOKEN_KEY = "github_token"

fun loadGithubToken(context: Context): String =
    context.getSharedPreferences(UPDATE_PREFS, Context.MODE_PRIVATE).getString(TOKEN_KEY, "").orEmpty()

fun saveGithubToken(context: Context, token: String) {
    context.getSharedPreferences(UPDATE_PREFS, Context.MODE_PRIVATE).edit().putString(TOKEN_KEY, token.trim()).apply()
}
