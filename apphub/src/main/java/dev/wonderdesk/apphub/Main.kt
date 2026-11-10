package dev.wonderdesk.apphub

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.text.format.DateUtils
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.imageResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.toBitmap
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.compose.ui.platform.LocalLifecycleOwner
import dev.wonderdesk.shared.*
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : WonderActivity() {
    @Composable override fun Content() = AppHub(intent?.getStringExtra("tab"))
}

private const val ABOUT = "Wonderdesk Apphub installs and updates every Wonderdesk app straight from its official GitHub releases. " +
    "Each download is checked to be the right app before Android asks you to confirm the install. It can also check once a day and notify you."

/** What App Hub is doing for one app. */
sealed class Job {
    data class Downloading(val done: Long, val total: Long) : Job()
    data object Waiting : Job()
    data object Installing : Job()
}

/** Survives recomposition and rotation; one per process. */
object Hub {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    var releases by mutableStateOf<Map<String, SuiteRelease>>(emptyMap())
    var installed by mutableStateOf<Map<String, String>>(emptyMap())   // pkg -> versionName
    var checking by mutableStateOf(false)
    var checkedCount by mutableIntStateOf(0)
    var failures by mutableStateOf<Map<String, String>>(emptyMap())
    var notice by mutableStateOf<String?>(null)
    val jobs = mutableStateMapOf<String, Job>()
    /** Downloaded APKs waiting for the user to confirm, installed one at a time on resume. */
    val queue = ArrayDeque<Pair<SuiteEntry, File>>()
    var lastChecked by mutableLongStateOf(0L)
    var started = false

    fun refreshInstalled(context: Context) {
        installed = SUITE_ENTRIES.mapNotNull { e -> installedVersion(context, e.pkg)?.let { e.pkg to it } }.toMap()
        // Anything that finished installing drops out of the job list.
        jobs.keys.filter { pkg -> jobs[pkg] == Job.Installing && releases[pkg]?.let { r -> installed[pkg]?.let { compareVersions(r.version, it) <= 0 } } == true }.forEach { jobs.remove(it) }
    }

    fun status(e: SuiteEntry): Status {
        val have = installed[e.pkg]
        val rel = releases[e.pkg]
        return when {
            have == null -> Status.NotInstalled
            rel != null && compareVersions(rel.version, have) > 0 -> Status.Update
            else -> Status.Current
        }
    }

    fun check(context: Context, force: Boolean, toast: (String) -> Unit = {}) {
        if (checking) return
        checking = true; checkedCount = 0
        scope.launch {
            val outcome = withContext(Dispatchers.IO) {
                checkReleases(context.applicationContext, force) { _, _ -> scope.launch { checkedCount++ } }
            }
            releases = outcome.releases
            failures = outcome.failures
            lastChecked = lastCheckTime(context)
            checking = false
            refreshInstalled(context)
            val updates = SUITE_ENTRIES.count { status(it) == Status.Update }
            when {
                outcome.rateLimitMessage != null -> toast(outcome.rateLimitMessage!!)
                outcome.failures.size == SUITE_ENTRIES.size -> toast(outcome.failures.values.first())
                force -> toast(if (updates == 0) "Everything is up to date" else "$updates update${if (updates == 1) "" else "s"} available")
            }
        }
    }

    fun install(context: Context, entries: List<SuiteEntry>, toast: (String) -> Unit) {
        if (!canInstallPackages(context)) {
            notice = "install-permission"
            return
        }
        val todo = entries.filter { jobs[it.pkg] == null }
        todo.forEach { jobs[it.pkg] = Job.Waiting }
        scope.launch {
            for (e in todo) {
                try {
                    val rel = releases[e.pkg] ?: withContext(Dispatchers.IO) { fetchLatestRelease(e.repo).also { cacheRelease(context, e.pkg, it) } }
                    releases = releases + (e.pkg to rel)
                    jobs[e.pkg] = Job.Downloading(0, 0)
                    val file = withContext(Dispatchers.IO) {
                        downloadApk(context, e, rel) { done, total -> scope.launch { jobs[e.pkg] = Job.Downloading(done, total) } }
                    }
                    jobs[e.pkg] = Job.Installing
                    queue.addLast(e to file)
                    if (queue.size == 1) openNext(context, toast)
                } catch (ex: SignatureMismatchException) {
                    jobs.remove(e.pkg); notice = "sig:${e.pkg}"
                } catch (ex: Exception) {
                    jobs.remove(e.pkg)
                    toast("${e.name}: ${ex.message ?: "download failed"}")
                    if (ex is RateLimitedException) { todo.forEach { if (jobs[it.pkg] == Job.Waiting) jobs.remove(it.pkg) }; break }
                }
            }
        }
    }

    /** Shows Android's install prompt for the next downloaded APK. */
    fun openNext(context: Context, toast: (String) -> Unit) {
        val (e, file) = queue.firstOrNull() ?: return
        try { installApk(context, file) } catch (ex: Exception) { toast("Couldn't open the installer for ${e.name}"); queue.removeFirst(); jobs.remove(e.pkg) }
    }

    /** Called on resume: the previous prompt is finished (installed or cancelled), move on. */
    fun onResume(context: Context, toast: (String) -> Unit) {
        refreshInstalled(context)
        val head = queue.firstOrNull() ?: return
        queue.removeFirst()
        val (e, _) = head
        val done = releases[e.pkg]?.let { r -> installed[e.pkg]?.let { compareVersions(r.version, it) <= 0 } } == true
        if (!done) { jobs.remove(e.pkg); toast("${e.name} was not installed") } else { jobs.remove(e.pkg); toast("${e.name} installed") }
        if (queue.isNotEmpty()) openNext(context, toast)
    }
}

enum class Status { NotInstalled, Update, Current }

@Composable
fun suiteIcon(e: SuiteEntry): ImageBitmap {
    val context = LocalContext.current
    val installedIcon = remember(e.pkg, Hub.installed[e.pkg]) { runCatching { context.packageManager.getApplicationIcon(e.pkg).toBitmap(144, 144).asImageBitmap() }.getOrNull() }
    val res = remember(e.id) { context.resources.getIdentifier("suite_${e.id}", "drawable", context.packageName) }
    return installedIcon ?: ImageBitmap.imageResource(if (res != 0) res else R.drawable.suite_apphub)
}

@Composable
fun AppHub(startTab: String?) {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    var tab by rememberSaveable { mutableStateOf(startTab ?: "suite") }
    var detail by rememberSaveable { mutableStateOf<String?>(null) }
    var daily by remember { mutableStateOf(dailyCheckEnabled(context)) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    var toastRef by remember { mutableStateOf<(String) -> Unit>({}) }
    DisposableEffect(owner) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) Hub.onResume(context) { toastRef(it) } }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(Unit) {
        if (!Hub.started) {
            Hub.started = true
            Hub.releases = SUITE_ENTRIES.mapNotNull { e -> cachedRelease(context, e.pkg)?.let { e.pkg to it } }.toMap()
            Hub.lastChecked = lastCheckTime(context)
            Hub.refreshInstalled(context)
            Hub.check(context, force = false) { toastRef(it) }
            if (dailyCheckEnabled(context)) scheduleDailyCheck(context)
        }
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED && daily) {
            permission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
    val updates = SUITE_ENTRIES.filter { Hub.status(it) == Status.Update }
    val entry = detail?.let { d -> SUITE_ENTRIES.find { it.pkg == d } }
    WonderApp(
        appName = "Wonderdesk Apphub", about = ABOUT,
        tabs = if (entry != null) emptyList() else listOf(
            WonderTab("suite", "Wonderdesk", WonderIcons.Storefront),
            WonderTab("updates", if (updates.isEmpty()) "Updates" else "Updates (${updates.size})", WonderIcons.SystemUpdate),
        ),
        selectedTab = tab, onTab = { tab = it },
        title = entry?.name ?: when (tab) { "updates" -> "Updates"; else -> "Wonderdesk Apphub" },
        onBack = if (entry != null) ({ detail = null }) else null,
        actions = {
            if (entry == null) ToolbarButton(WonderIcons.Refresh, "Check for updates", { Hub.check(context, force = true) { toastRef(it) } }, enabled = !Hub.checking)
        },
        settings = {
            Panel(title = "Updates") {
                SwitchRow("Check once a day", daily, {
                    daily = it; setDailyCheckEnabled(context, it); if (it) scheduleDailyCheck(context) else cancelDailyCheck(context)
                }, subtitle = "Sends a notification when updates are ready", icon = WonderIcons.Notifications, tint = Color(0xFFE5483C))
                PanelDivider()
                ListRow("Allow installing apps", subtitle = if (canInstallPackages(context)) "Allowed" else "Needed to install and update", icon = WonderIcons.Security, tint = Color(0xFF2E9E44), chevron = true,
                    onClick = { openInstallPermissionSettings(context) })
            }
        }
    ) {
        toastRef = LocalToast.current
        if (entry != null) DetailPage(entry) { toastRef(it) }
        else when (tab) {
            "updates" -> UpdatesTab(updates) { detail = it.pkg }
            else -> SuiteTab { detail = it.pkg }
        }
    }
    when (val n = Hub.notice) {
        null -> {}
        "install-permission" -> WonderDialog(onDismissRequest = { Hub.notice = null }, title = { Text("Allow App Hub to install apps") }, text = {
            Text("Android asks you once to let App Hub install apps. Turn on \"Allow from this source\" on the next screen, then come back and tap Install again.")
        }, confirmButton = { TextButton(onClick = { Hub.notice = null; openInstallPermissionSettings(context) }) { Text("Open settings") } },
            dismissButton = { TextButton(onClick = { Hub.notice = null }) { Text("Not now") } })
        else -> if (n.startsWith("sig:")) {
            val e = SUITE_ENTRIES.find { it.pkg == n.removePrefix("sig:") }
            WonderDialog(onDismissRequest = { Hub.notice = null }, title = { Text("Reinstall ${e?.name} to update") }, text = {
                Text("The copy of ${e?.name} on this phone was installed from a different build, so Android can't update it in place. Uninstall it, then install it again here. Its saved data will be removed.")
            }, confirmButton = { TextButton(onClick = { Hub.notice = null; e?.let { context.launchSafely(Intent(Intent.ACTION_DELETE, Uri.parse("package:${it.pkg}"))) {} } }) { Text("Uninstall") } },
                dismissButton = { TextButton(onClick = { Hub.notice = null }) { Text("Cancel") } })
        } else Hub.notice = null
    }
}

@Composable
private fun StatusLine() {
    val p = wonderPalette()
    val text = when {
        Hub.checking -> "Checking… ${Hub.checkedCount} of ${SUITE_ENTRIES.size}"
        Hub.lastChecked == 0L -> "Not checked yet"
        else -> "Checked " + relativeTime(Hub.lastChecked).replaceFirstChar { it.lowercase() }
    }
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 6.dp)) {
        if (Hub.checking) CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp, color = p.accent)
        else Icon(WonderIcons.CheckCircle, null, Modifier.size(16.dp), tint = if (Hub.failures.isEmpty()) p.success else p.warning)
        Spacer(Modifier.width(6.dp))
        Text(text + if (!Hub.checking && Hub.failures.isNotEmpty()) " · ${Hub.failures.size} couldn't be checked" else "", style = MaterialTheme.typography.bodySmall, color = p.secondaryText)
    }
}

@Composable
private fun ActionButton(e: SuiteEntry, toast: (String) -> Unit) {
    val context = LocalContext.current
    val p = wonderPalette()
    when (val job = Hub.jobs[e.pkg]) {
        is Job.Downloading -> Column(Modifier.width(96.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            AquaProgress(if (job.total > 0) job.done.toFloat() / job.total else null, Modifier.fillMaxWidth())
            Text(if (job.total > 0) "${job.done * 100 / job.total}%" else formatBytes(job.done), style = MaterialTheme.typography.labelSmall, color = p.secondaryText)
        }
        Job.Waiting -> Text("Waiting…", style = MaterialTheme.typography.labelMedium, color = p.secondaryText)
        Job.Installing -> Text("Installing…", style = MaterialTheme.typography.labelMedium, color = p.secondaryText)
        null -> when (Hub.status(e)) {
            Status.NotInstalled -> PillButton("Install", true) { Hub.install(context, listOf(e), toast) }
            Status.Update -> PillButton("Update", true) { Hub.install(context, listOf(e), toast) }
            Status.Current -> Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(WonderIcons.CheckCircle, "Up to date", tint = Color(0xFF2E9E44), modifier = Modifier.size(22.dp))
                Spacer(Modifier.width(6.dp))
                PillButton("Open", false, enabled = e.pkg != context.packageName) {
                    context.packageManager.getLaunchIntentForPackage(e.pkg)?.let { context.launchSafely(it, toast) }
                }
            }
        }
    }
}

/** Compact App-Store-style capsule button. */
@Composable
private fun PillButton(text: String, primary: Boolean, enabled: Boolean = true, onClick: () -> Unit) {
    val p = wonderPalette()
    val shape = RoundedCornerShape(50)
    Box(
        Modifier.height(32.dp).widthIn(min = 76.dp).clip(shape)
            .background(if (primary) p.accent else p.window)
            .border(1.dp, if (primary) p.accent.darken(.15f) else p.panelBorder, shape)
            .clickable(enabled = enabled, onClick = onClick).padding(horizontal = 14.dp),
        contentAlignment = Alignment.Center
    ) { Text(text, color = if (primary) p.onAccent else if (enabled) p.accent else p.secondaryText, style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold)) }
}

@Composable
private fun SuiteRow(e: SuiteEntry, modifier: Modifier, onOpen: () -> Unit) {
    val p = wonderPalette()
    val toast = LocalToast.current
    val have = Hub.installed[e.pkg]
    val rel = Hub.releases[e.pkg]
    Row(modifier.clickable(onClick = onOpen).padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Image(suiteIcon(e), null, Modifier.size(52.dp))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(e.name, style = MaterialTheme.typography.titleMedium)
            Text(e.tagline, style = MaterialTheme.typography.bodySmall, color = p.secondaryText, maxLines = 2, overflow = TextOverflow.Ellipsis)
            val v = when (Hub.status(e)) {
                Status.Update -> "$have → ${rel?.version}"
                Status.Current -> "Up to date · $have"
                Status.NotInstalled -> rel?.let { "Version ${it.version}" } ?: ""
            }
            if (v.isNotEmpty()) Text(v, style = MaterialTheme.typography.labelSmall, color = when (Hub.status(e)) { Status.Update -> p.accent; Status.Current -> Color(0xFF2E9E44); else -> p.secondaryText })
        }
        Spacer(Modifier.width(8.dp))
        ActionButton(e, toast)
    }
}

@Composable
private fun SuiteTab(open: (SuiteEntry) -> Unit) {
    val context = LocalContext.current
    val toast = LocalToast.current
    val missing = SUITE_ENTRIES.filter { Hub.status(it) == Status.NotInstalled }
    val have = SUITE_ENTRIES.filter { Hub.status(it) != Status.NotInstalled }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp)) {
        item {
            Banner(missing.size, have.size) { Hub.install(context, missing, toast) }
            Spacer(Modifier.height(10.dp))
            StatusLine()
            Spacer(Modifier.height(14.dp))
        }
        if (missing.isNotEmpty()) {
            item { SectionLabel("Get more Wonderdesk apps", Modifier.padding(start = 6.dp, bottom = 6.dp)) }
            itemsIndexed(missing, key = { _, e -> "m" + e.pkg }) { i, e -> SuiteRow(e, Modifier.panelItem(i, missing.size)) { open(e) } }
            item { Spacer(Modifier.height(18.dp)) }
        }
        if (have.isNotEmpty()) {
            item { SectionLabel("On this phone", Modifier.padding(start = 6.dp, bottom = 6.dp)) }
            itemsIndexed(have, key = { _, e -> "h" + e.pkg }) { i, e -> SuiteRow(e, Modifier.panelItem(i, have.size)) { open(e) } }
        }
    }
}

@Composable
private fun Banner(missing: Int, have: Int, installAll: () -> Unit) {
    val p = wonderPalette()
    val shape = RoundedCornerShape(p.panelRadius + 4.dp)
    Box(Modifier.fillMaxWidth().clip(shape).background(androidx.compose.ui.graphics.Brush.linearGradient(listOf(Color(0xFF1B3B8F), Color(0xFF3A1F7A), Color(0xFF7A2A8F)))).padding(18.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("The Wonderdesk suite", color = Color.White, style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.SemiBold))
                Text(if (missing == 0) "All ${have} apps are on this phone." else "$have of ${have + missing} apps installed. Free, private, no accounts.",
                    color = Color.White.copy(alpha = .85f), style = MaterialTheme.typography.bodyMedium)
            }
            if (missing > 1) {
                Spacer(Modifier.width(10.dp))
                Box(Modifier.clip(RoundedCornerShape(50)).background(Color.White).clickable(onClick = installAll).padding(horizontal = 14.dp, vertical = 8.dp)) {
                    Text("Get all", color = Color(0xFF1B3B8F), style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold))
                }
            }
        }
    }
}

@Composable
private fun UpdatesTab(updates: List<SuiteEntry>, open: (SuiteEntry) -> Unit) {
    val context = LocalContext.current
    val toast = LocalToast.current
    val p = wonderPalette()
    val current = SUITE_ENTRIES.filter { Hub.status(it) == Status.Current }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp)) {
        item {
            StatusLine()
            Spacer(Modifier.height(12.dp))
        }
        if (updates.isEmpty()) item {
            Panel {
                EmptyState(
                    if (Hub.checking) "Checking for updates…" else "You're up to date",
                    if (Hub.checking) "Looking at each app's latest release." else "Every installed Wonderdesk app is on its newest version.",
                    icon = WonderIcons.CheckCircle
                )
            }
        } else {
            item {
                Row(Modifier.fillMaxWidth().padding(bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    SectionLabel("${updates.size} update${if (updates.size == 1) "" else "s"} available", Modifier.weight(1f).padding(start = 6.dp))
                    WButton("Update all", { Hub.install(context, updates, toast) }, icon = WonderIcons.Download, kind = ButtonKind.Primary,
                        enabled = updates.any { Hub.jobs[it.pkg] == null })
                }
            }
            itemsIndexed(updates, key = { _, e -> "u" + e.pkg }) { i, e ->
                Column(Modifier.panelItem(i, updates.size)) {
                    SuiteRow(e, Modifier) { open(e) }
                    val notes = Hub.releases[e.pkg]?.notesSince(Hub.installed[e.pkg]).orEmpty()
                    if (notes.isNotEmpty()) {
                        var expanded by rememberSaveable(e.pkg) { mutableStateOf(false) }
                        Column(Modifier.fillMaxWidth().clickable { expanded = !expanded }.padding(start = 76.dp, end = 14.dp, bottom = 12.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("What's new", style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold), color = p.accent)
                                Icon(if (expanded) WonderIcons.ExpandLess else WonderIcons.ExpandMore, null, tint = p.accent, modifier = Modifier.size(18.dp))
                            }
                            ReleaseNotes(notes, maxLines = if (expanded) Int.MAX_VALUE else 3, showVersions = expanded && notes.size > 1)
                        }
                    }
                }
            }
            item { Text("Android asks you to confirm each install. App Hub waits for you, then opens the next one.", Modifier.padding(6.dp, 8.dp),
                style = MaterialTheme.typography.bodySmall, color = p.secondaryText) }
        }
        if (Hub.failures.isNotEmpty() && !Hub.checking) {
            item { Spacer(Modifier.height(14.dp)); SectionLabel("Couldn't check", Modifier.padding(start = 6.dp, bottom = 6.dp)) }
            val list = Hub.failures.entries.toList()
            itemsIndexed(list) { i, (pkg, msg) ->
                val e = SUITE_ENTRIES.first { it.pkg == pkg }
                ListRow(e.name, Modifier.panelItem(i, list.size), subtitle = msg, leading = { Image(suiteIcon(e), null, Modifier.size(32.dp)) })
            }
        }
        if (current.isNotEmpty()) {
            item { Spacer(Modifier.height(18.dp)); SectionLabel("Up to date", Modifier.padding(start = 6.dp, bottom = 6.dp)) }
            itemsIndexed(current, key = { _, e -> "c" + e.pkg }) { i, e ->
                ListRow(e.name, Modifier.panelItem(i, current.size), subtitle = "Version ${Hub.installed[e.pkg]}", leading = { Image(suiteIcon(e), null, Modifier.size(32.dp)) },
                    trailing = { Icon(WonderIcons.CheckCircle, null, tint = p.success, modifier = Modifier.size(20.dp)) }, onClick = { open(e) })
            }
        }
    }
}

@Composable
private fun DetailPage(e: SuiteEntry, toast: (String) -> Unit) {
    val context = LocalContext.current
    val p = wonderPalette()
    val have = Hub.installed[e.pkg]
    val rel = Hub.releases[e.pkg]
    Page {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Image(suiteIcon(e), null, Modifier.size(96.dp))
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(e.name, style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.SemiBold))
                Text("Wonderdesk", color = p.secondaryText, style = MaterialTheme.typography.bodyMedium)
                ActionButton(e, toast)
            }
        }
        Text(e.tagline, style = MaterialTheme.typography.bodyLarge)
        rel?.let { r ->
            val newer = r.notesSince(have)
            if (have != null && newer.isNotEmpty()) Panel(title = "What's new since $have") {
                Box(Modifier.padding(14.dp)) { ReleaseNotes(newer, showVersions = newer.size > 1) }
            } else if (r.notes.isNotBlank()) Panel(title = "What's new in ${r.version}") {
                Box(Modifier.padding(14.dp)) { ReleaseNotes(listOf(r.version to r.notes), showVersions = false) }
            }
            val older = r.history.filter { it.second.isNotBlank() && (have == null || compareVersions(it.first, have) <= 0) }
            if (older.isNotEmpty()) {
                var open by rememberSaveable(e.pkg) { mutableStateOf(false) }
                Panel(title = "Version history") {
                    ListRow(if (open) "Hide earlier versions" else "Show earlier versions", subtitle = "${older.size} earlier release${if (older.size == 1) "" else "s"}",
                        icon = WonderIcons.History, tint = Color(0xFF7A7F87), onClick = { open = !open })
                    if (open) { PanelDivider(0.dp); Box(Modifier.padding(14.dp)) { ReleaseNotes(older, showVersions = true) } }
                }
            }
        }
        Panel(title = "Information") {
            ListRow("Installed", trailing = { Text(have ?: "Not installed", color = p.secondaryText) })
            PanelDivider(12.dp)
            ListRow("Latest release", trailing = { Text(rel?.version ?: if (Hub.checking) "Checking…" else "Unknown", color = p.secondaryText) })
            PanelDivider(12.dp)
            ListRow("Package", trailing = { Text(e.pkg, color = p.secondaryText, style = MaterialTheme.typography.bodySmall) })
            Hub.failures[e.pkg]?.let { PanelDivider(12.dp); ListRow("Last check", subtitle = it) }
        }
        Panel {
            ListRow("Release notes on GitHub", icon = WonderIcons.OpenInNew, tint = Color(0xFF24292F), chevron = true, onClick = {
                context.launchSafely(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/$SUITE_GITHUB_OWNER/${e.repo}/releases")), toast)
            })
            if (have != null) {
                PanelDivider()
                ListRow("App info and permissions", icon = WonderIcons.Info, tint = Color(0xFF7A7F87), chevron = true, onClick = {
                    context.launchSafely(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${e.pkg}")), toast)
                })
                if (e.pkg != context.packageName) {
                    PanelDivider()
                    ListRow("Uninstall", icon = WonderIcons.Delete, danger = true, onClick = {
                        context.launchSafely(Intent(Intent.ACTION_DELETE, Uri.parse("package:${e.pkg}")), toast)
                    })
                }
            }
        }
    }
}


/** Release notes as "# heading" and "• bullet" lines; [showVersions] puts a version header over each block. */
@Composable
private fun ReleaseNotes(blocks: List<Pair<String, String>>, maxLines: Int = Int.MAX_VALUE, showVersions: Boolean) {
    val p = wonderPalette()
    var used = 0
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        for ((version, text) in blocks) {
            if (used >= maxLines) break
            if (showVersions) Text("Version $version", Modifier.padding(top = 6.dp), style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold))
            for (line in text.lines()) {
                if (used >= maxLines) break
                used++
                when {
                    line.startsWith("# ") -> Text(line.removePrefix("# "), Modifier.padding(top = 4.dp), style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold), color = p.text)
                    line.startsWith("• ") -> Row {
                        Text("•", Modifier.width(14.dp), color = p.accent, style = MaterialTheme.typography.bodyMedium)
                        Text(line.removePrefix("• "), style = MaterialTheme.typography.bodyMedium, color = p.text)
                    }
                    else -> Text(line, style = MaterialTheme.typography.bodyMedium, color = p.secondaryText)
                }
            }
        }
        if (used >= maxLines && maxLines != Int.MAX_VALUE) Text("…", color = p.secondaryText)
    }
}
