package dev.wonderdesk.shared

import android.content.Context
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.messaging.FirebaseMessaging
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import org.json.JSONObject

private const val PUSH_TOPIC = "releases"
/** Firebase console values, bundled once the project exists. Until then push stays off. */
private const val PUSH_ASSET = "google-services.json"

/**
 * Instant release alerts over Google push: one shared system connection wakes
 * the phone the moment a release lands, with no polling and no extra battery
 * use. Without the bundled Firebase file this stays off and the hourly check
 * is the fallback. Respects the background check switch.
 */
@Synchronized
fun ensurePush(context: Context): Boolean {
    if (!dailyCheckEnabled(context)) return false
    return runCatching {
        val app = context.applicationContext
        if (FirebaseApp.getApps(app).isEmpty()) {
            val raw = app.assets.open(PUSH_ASSET).bufferedReader().readText()
            val root = JSONObject(raw)
            val info = root.getJSONObject("project_info")
            val client = root.getJSONArray("client").getJSONObject(0)
            val ci = client.getJSONObject("client_info")
            val key = client.getJSONArray("api_key").getJSONObject(0).getString("current_key")
            val opts = FirebaseOptions.Builder()
                .setProjectId(info.getString("project_id"))
                .setApplicationId(ci.getString("mobilesdk_app_id"))
                .setApiKey(key)
                .setGcmSenderId(info.optString("project_number", info.getString("project_id")))
                .build()
            FirebaseApp.initializeApp(app, opts)
        }
        FirebaseMessaging.getInstance().subscribeToTopic(PUSH_TOPIC)
        true
    }.getOrDefault(false)
}

/** True once the Firebase file is bundled and push initialised on this phone. */
fun pushActive(context: Context): Boolean = runCatching {
    FirebaseApp.getApps(context.applicationContext).isNotEmpty()
}.getOrDefault(false)

/** Wakes on every published release and checks at once, even with the app closed. */
class ReleasePushService : FirebaseMessagingService() {
    override fun onMessageReceived(msg: RemoteMessage) {
        val app = applicationContext
        if (!dailyCheckEnabled(app)) return
        runCatching {
            val updates = checkAllForUpdates(app, 0L)
            if (updates.isNotEmpty()) {
                if (autoUpdateEnabled(app)) autoUpdate(app, updates) else notifyNewUpdates(app, updates)
            }
        }
    }

    override fun onNewToken(token: String) {
        runCatching { FirebaseMessaging.getInstance().subscribeToTopic(PUSH_TOPIC) }
    }
}
