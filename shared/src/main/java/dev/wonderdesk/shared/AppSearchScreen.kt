package dev.wonderdesk.shared

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.toBitmap
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext

data class LaunchableApp(val component: ComponentName, val title: String, val icon: Bitmap?, val version: String, val aliases: List<String> = emptyList()) {
    val key: String get() = component.flattenToString()
    fun matches(query: String): Boolean {
        val term = query.trim()
        return title.contains(term, true) || component.packageName.contains(term, true) || aliases.any { it.contains(term, true) }
    }
}

@Suppress("DEPRECATION")
fun loadLaunchableApps(context: Context): List<LaunchableApp> {
    val pm = context.packageManager
    val aliases = SharedCatalog.aliases(context)
    return pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), PackageManager.MATCH_ALL).map { resolve ->
        val info = resolve.activityInfo
        LaunchableApp(ComponentName(info.packageName, info.name), resolve.loadLabel(pm).toString(), runCatching { resolve.loadIcon(pm).toBitmap(96, 96) }.getOrNull(), runCatching { pm.getPackageInfo(info.packageName, 0).versionName ?: "unknown" }.getOrDefault("unknown"), if (info.packageName.startsWith("dev.wonderdesk.")) aliases[info.packageName.substringAfterLast('.')].orEmpty() else emptyList())
    }.distinctBy { it.key }.sortedWith(compareBy<LaunchableApp> { it.title.lowercase() }.thenBy { it.key })
}

fun Context.launchApp(app: LaunchableApp, report: (String) -> Unit) = launchSafely(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setComponent(app.component).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED), report)

/** Suite filter. Includes the installed BlissPlayer music app variants. Pure for testing. */
fun isSuiteApp(app: LaunchableApp): Boolean =
    app.component.packageName.startsWith("dev.wonderdesk.") ||
        app.component.packageName == "com.blissplayer.music" ||
        app.component.packageName == "com.blissplayer.music.debug"

fun filterManageApps(apps: List<LaunchableApp>, query: String, suiteOnly: Boolean): List<LaunchableApp> =
    apps.filter { it.matches(query) && (!suiteOnly || isSuiteApp(it)) }

private enum class RowKind { UpToDate, UpdateAvailable, NotInstalled, Plain }

@Composable
fun AppSearchScreen(manage: Boolean = false) {
    val context = LocalContext.current
    val activity = context as ComponentActivity
    val op = rememberOperation()
    var apps by remember { mutableStateOf(listOf<LaunchableApp>()) }
    var query by remember { mutableStateOf("") }
    var selected by remember { mutableStateOf<LaunchableApp?>(null) }
    var releases by remember { mutableStateOf(mapOf<String, SuiteRelease>()) }
    var releaseError by remember { mutableStateOf("") }
    var daily by remember { mutableStateOf(dailyCheckEnabled(context)) }
    val installedPkgs = remember(apps) { apps.map { it.component.packageName }.toSet() }
    val matches = remember(apps, query) { apps.filter { it.matches(query) } }
    // Wonderdesk apps that are not on the phone yet, so they can be installed from here.
    val rows = remember(matches, query, releases) {
        if (!manage) matches
        else filterManageApps(matches, query, true) + missingSuiteEntries(installedPkgs, query)
            .sortedBy { it.name }
            .map { LaunchableApp(ComponentName(it.pkg, ""), it.name, null, "unknown") }
    }
    fun updateStatus(app: LaunchableApp): String? {
        val installed = app.component.packageName in installedPkgs
        val rel = releases[app.component.packageName]
        if (!installed && app.component.packageName in SUITE_PKG_SET) {
            return if (rel != null) "Not installed · ${rel.version} available" else "Not installed"
        }
        if (rel == null) return null
        return when {
            runCatching { compareVersions(rel.version, app.version) > 0 }.getOrDefault(false) -> "Update available: ${app.version} → ${rel.version}"
            else -> "Up to date (${app.version})"
        }
    }
    fun rowKind(app: LaunchableApp): RowKind {
        val installed = app.component.packageName in installedPkgs
        if (!installed && app.component.packageName in SUITE_PKG_SET) return RowKind.NotInstalled
        val rel = releases[app.component.packageName] ?: return RowKind.Plain
        return if (runCatching { compareVersions(rel.version, app.version) > 0 }.getOrDefault(false)) RowKind.UpdateAvailable else RowKind.UpToDate
    }
    fun checkUpdates() {
        op.run("Checking for updates", {
            runBlocking {
                val found = mutableMapOf<String, SuiteRelease>()
                var failures = 0
                withContext(Dispatchers.IO) {
                    coroutineScope {
                        SUITE_ENTRIES.map { e ->
                            async {
                                try { found[e.pkg] = fetchLatestRelease(e.repo); cacheRelease(context, e.pkg, found[e.pkg]!!) } catch (_: Exception) { failures++ }
                            }
                        }.awaitAll()
                    }
                }
                Pair(found, failures)
            }
        }) { (found, failures) ->
            releases = found
            releaseError = if (failures > 0) "$failures of ${SUITE_ENTRIES.size} could not be checked. Pull to retry." else ""
            val names = found.mapNotNull { (pkg, rel) ->
                val installed = installedVersion(context, pkg) ?: return@mapNotNull null
                if (runCatching { compareVersions(rel.version, installed) > 0 }.getOrDefault(false)) SUITE_ENTRIES.find { it.pkg == pkg }?.name else null
            }
            if (names.isNotEmpty()) notifyUpdatesAvailable(context, names)
            op.notice(if (found.isEmpty()) "No releases found." else "Checked ${found.size} apps" + if (failures > 0) " ($failures failed)." else if (names.isEmpty()) ", all up to date." else ".")
        }
    }
    fun fetchAndInstall(app: LaunchableApp) {
        val rel = releases[app.component.packageName] ?: return
        op.run("Downloading ${rel.apkName}", { downloadApk(context, rel) }) { file ->
            op.notice("Downloaded. Android will ask you to confirm the install.")
            try { installApk(context, file) } catch (e: Exception) { op.notice("Could not open the installer: ${e.localizedMessage}") }
            selected = null
        }
    }
    fun installMissing(app: LaunchableApp) {
        val entry = SUITE_ENTRIES.find { it.pkg == app.component.packageName } ?: return
        op.run("Getting ${entry.name} release", { fetchLatestRelease(entry.repo) }) { rel ->
            releases = releases + (entry.pkg to rel)
            cacheRelease(context, entry.pkg, rel)
            op.notice("Downloading ${rel.apkName}")
            op.run("Downloading ${rel.apkName}", { downloadApk(context, rel) }) { file ->
                op.notice("Downloaded. Android will ask you to confirm the install.")
                try { installApk(context, file) } catch (e: Exception) { op.notice("Could not open the installer: ${e.localizedMessage}") }
            }
        }
    }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    fun refresh() { op.run("Reading launchable installed apps", { loadLaunchableApps(context) }) { apps = it } }
    DisposableEffect(activity) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) refresh() }
        activity.lifecycle.addObserver(observer)
        refresh()
        onDispose { activity.lifecycle.removeObserver(observer) }
    }
    if (manage) {
        LaunchedEffect(Unit) {
            if (dailyCheckEnabled(context)) scheduleDailyCheck(context)
            if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
            // Show what we already know immediately, then refresh in the background.
            val cached = SUITE_ENTRIES.mapNotNull { e -> cachedRelease(context, e.pkg)?.let { e.pkg to it } }.toMap()
            if (cached.isNotEmpty()) releases = cached
            var waits = 0
            while (op.busy && waits < 40) { kotlinx.coroutines.delay(500); waits++ }
            checkUpdates()
        }
    }
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(query, { query = it.take(512) }, Modifier.fillMaxWidth(), label = { Text(if (manage) "Search Wonderdesk apps" else "Search apps, aliases or calculate") }, singleLine = true,
            trailingIcon = { if (query.isNotEmpty()) TextButton(onClick = { query = "" }) { Text("Clear") } })
        if (!manage) {
            val calculation = remember(query) { runCatching { Expression.format(Expression.evaluate(query)) }.getOrNull() }
            if (calculation != null) Card(Modifier.fillMaxWidth()) {
                Row(Modifier.padding(12.dp).fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Text("= $calculation", Modifier.weight(1f), style = MaterialTheme.typography.headlineSmall); TextButton(onClick = { context.copyText("Calculation result", calculation) }) { Text("Copy") } }
            }
            if (query.isNotBlank()) TextButton(onClick = { context.launchSafely(Intent(Intent.ACTION_VIEW, Uri.parse("https://duckduckgo.com/?q=${Uri.encode(query)}")), op::notice) }) { Text("Search web in browser (sends query)") }
            Text("Only launchable apps and local calculations. Files, private notes and media are not indexed.", style = MaterialTheme.typography.bodySmall)
        } else Text("Red: not installed (tap to install). Yellow: update ready. Green: current. Android always asks before installing.", style = MaterialTheme.typography.bodySmall)
        if (manage) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = { checkUpdates() }) { Text("Check for updates") }
                Row(Modifier.weight(1f), horizontalArrangement = Arrangement.End) {
                    Text("Daily check", Modifier.padding(end = 4.dp), style = MaterialTheme.typography.bodySmall)
                    Switch(daily, { daily = it; setDailyCheckEnabled(context, it); if (it) scheduleDailyCheck(context) else cancelDailyCheck(context) })
                }
            }
            if (releaseError.isNotEmpty()) Text(releaseError, style = MaterialTheme.typography.bodySmall)
        }
        OperationStatus(op)
        if (query.isNotBlank() && !op.busy) Text("${(if (manage) rows else matches).size} matching apps", style = MaterialTheme.typography.bodySmall)
        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            items(if (manage) rows else matches, key = { it.key }) { app ->
                val status = if (manage) updateStatus(app) else null
                if (!manage) {
                    Card(onClick = { context.launchApp(app, op::notice) }, modifier = Modifier.fillMaxWidth()) {
                        Row(Modifier.padding(12.dp).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            app.icon?.let { Image(it.asImageBitmap(), null, Modifier.size(48.dp)) }
                            Column(Modifier.weight(1f)) { Text(app.title); Text(app.component.packageName, style = MaterialTheme.typography.bodySmall) }
                        }
                    }
                } else {
                    val colors = when (rowKind(app)) {
                        RowKind.UpToDate -> CardDefaults.cardColors(containerColor = Color(0xFFD3F3D9), contentColor = Color(0xFF12391C))
                        RowKind.UpdateAvailable -> CardDefaults.cardColors(containerColor = Color(0xFFFFE082), contentColor = Color(0xFF4A3200))
                        RowKind.NotInstalled -> CardDefaults.cardColors(containerColor = Color(0xFFF6C6C6), contentColor = Color(0xFF5A1212))
                        RowKind.Plain -> CardDefaults.cardColors()
                    }
                    val installed = app.component.packageName in installedPkgs
                    Card(onClick = { if (installed) selected = app else installMissing(app) }, modifier = Modifier.fillMaxWidth(), colors = colors) {
                        Row(Modifier.padding(12.dp).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            app.icon?.let { Image(it.asImageBitmap(), null, Modifier.size(48.dp)) }
                            Column(Modifier.weight(1f)) {
                                Text(app.title)
                                Text(if (installed) "${app.version} · ${app.component.packageName}" else app.component.packageName, style = MaterialTheme.typography.bodySmall)
                                if (status != null) Text(status, style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
            }
        }
    }
    selected?.let { app -> AlertDialog(onDismissRequest = { selected = null }, title = { Text(app.title) }, text = {
        Column {
            val isInstalled = app.component.packageName in installedPkgs
            Text(if (isInstalled) "${app.component.packageName}\nVersion ${app.version}" else "${app.component.packageName}\nNot installed on this phone")
            if (isInstalled) TextButton(onClick = { context.launchApp(app, op::notice); selected = null }) { Text("Open") }
            if (isInstalled) TextButton(onClick = { context.launchSafely(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${app.component.packageName}")), op::notice); selected = null }) { Text("Android app details / permissions") }
            val rel = releases[app.component.packageName]
            if (rel != null && (!isInstalled || runCatching { compareVersions(rel.version, app.version) > 0 }.getOrDefault(false))) {
                TextButton(onClick = { fetchAndInstall(app) }) { Text(if (isInstalled) "Update to ${rel.version}…" else "Install ${rel.version}…") }
            }
            if (isInstalled && app.component.packageName != context.packageName) TextButton(onClick = { context.launchSafely(Intent(Intent.ACTION_DELETE, Uri.parse("package:${app.component.packageName}")), op::notice); selected = null }) { Text("Request uninstall…") }
        }
    }, confirmButton = { TextButton(onClick = { selected = null }) { Text("Close") } }) }
}
