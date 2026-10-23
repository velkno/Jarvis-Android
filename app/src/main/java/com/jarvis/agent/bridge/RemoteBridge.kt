package com.jarvis.agent.bridge

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.jarvis.agent.agent.AgentSession
import com.jarvis.agent.agent.FastIntentParser
import com.jarvis.agent.data.KeyStore
import com.jarvis.agent.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * Device-approved remote bridge using GitHub Issues in a PRIVATE repository.
 *
 * ChatGPT's GitHub connector can create an issue in the configured private repo.
 * The phone polls while the user's Accessibility service is running, notifies the
 * user, and waits for a tap on "Approve". It never silently executes commands.
 *
 * No screen content, screenshots or secret values are sent to GitHub.
 */
data class RemoteCommand(
    val issue: Int, val task: String, val sender: String, val expiresAt: Long
)

data class BridgeSettings(val repository: String, val trustedSender: String, val token: String)

class BridgeStorage(context: Context) {
    private val key = MasterKey.Builder(context.applicationContext)
        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
    private val prefs = EncryptedSharedPreferences.create(
        context.applicationContext, "jarvis_private_bridge", key,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )

    val deviceId: String
        get() = prefs.getString("device_id", null) ?: UUID.randomUUID().toString().also {
            prefs.edit().putString("device_id", it).commit()
        }

    val enabled get() = prefs.getBoolean("enabled", false)

    fun setEnabled(value: Boolean) {
        prefs.edit().putBoolean("enabled", value).commit()
    }

    fun config(): BridgeSettings? {
        val repo = prefs.getString("repository", "").orEmpty()
        val sender = prefs.getString("sender", "").orEmpty()
        val token = prefs.getString("token", "").orEmpty()
        if (!isValidRepository(repo) || !isValidLogin(sender) || token.isBlank()) return null
        return BridgeSettings(repo, sender, token)
    }

    fun save(repository: String, trustedSender: String, token: String) {
        require(isValidRepository(repository))
        require(isValidLogin(trustedSender))
        require(token.isNotBlank() && token.length <= 500)
        prefs.edit()
            .putString("repository", repository)
            .putString("sender", trustedSender)
            .putString("token", token)
            .putBoolean("enabled", false)  // re-verify on each config change
            .remove("pending")
            .apply()
    }

    fun pending(): RemoteCommand? {
        return try {
            val raw = prefs.getString("pending", null) ?: return null
            val json = JSONObject(raw)
            RemoteCommand(
                json.getInt("issue"), json.getString("task"),
                json.getString("sender"), json.getLong("expires_at")
            )
        } catch (_: Exception) { null }
    }

    fun setPending(cmd: RemoteCommand?) {
        val edit = prefs.edit()
        if (cmd == null) edit.remove("pending")
        else edit.putString("pending", JSONObject()
            .put("issue", cmd.issue).put("task", cmd.task)
            .put("sender", cmd.sender).put("expires_at", cmd.expiresAt).toString())
        edit.commit()
    }

    fun handled(issue: Int): Boolean = handledSet().contains(issue)

    fun markHandled(issue: Int) {
        val updated = (handledSet() + issue).takeLast(300)
        prefs.edit().putString("handled", updated.joinToString(",")).commit()
    }

    private fun handledSet(): List<Int> = prefs.getString("handled", "").orEmpty()
        .split(",").mapNotNull { it.toIntOrNull() }

    companion object {
        fun isValidRepository(v: String): Boolean =
            Regex("^[A-Za-z0-9-]{1,39}/[A-Za-z0-9_.-]{1,100}$").matches(v) &&
                !v.contains("..")
        fun isValidLogin(v: String): Boolean =
            Regex("^[A-Za-z0-9](?:[A-Za-z0-9-]{0,37}[A-Za-z0-9])?$").matches(v)
    }
}

private class GitHubBridgeApi(private val config: BridgeSettings) {
    // Reuse the same HTTP connection pool across polling and result reports.
    private val client = sharedClient

    companion object {
        private val sharedClient = OkHttpClient.Builder()
            .callTimeout(15, TimeUnit.SECONDS)
            .connectTimeout(8, TimeUnit.SECONDS)
            .build()
    }
    private val base = "https://api.github.com/repos/${config.repository}"

    private fun request(path: String, body: JSONObject? = null): JSONObjectOrArray {
        val builder = Request.Builder()
            .url(base + path)
            .header("Accept", "application/vnd.github+json")
            .header("X-GitHub-Api-Version", "2022-11-28")
            .header("Authorization", "Bearer ${config.token}")
            .header("User-Agent", "Jarvis-Android-Private-Bridge")
        if (body != null) builder.post(body.toString().toRequestBody("application/json".toMediaType()))
        val response = client.newCall(builder.build()).execute()
        response.use { res ->
            val text = res.body?.string().orEmpty()
            if (!res.isSuccessful) error("GitHub HTTP ${res.code} (check private repo access/token permissions)")
            return JSONObjectOrArray(text)
        }
    }

    fun verifyPrivateRepo() {
        val repo = request("").obj()
        // Reject public repositories: commands/results must not leak into public Issues.
        check(repo.optBoolean("private", false)) { "الربط يتطلب مستودع GitHub خاص (Private)." }
        check(repo.optString("full_name").equals(config.repository, ignoreCase = true)) {
            "المستودع لا يطابق الإعدادات."
        }
        check(repo.optBoolean("has_issues", false)) { "فعّل Issues فالمستودع الخاص." }
    }

    fun newestCommands(): JSONArray =
        request("/issues?state=open&sort=created&direction=desc&per_page=30").arr()

    fun report(issue: Int, status: String, summary: String) {
        // Always private. Never upload accessibility dumps or screenshots here.
        val body = JSONObject().put(
            "body", "JARVIS_REMOTE_RESULT_V1\nstatus: $status\nresult: review the local Jarvis app log"
        )
        request("/issues/$issue/comments", body)
    }
}

/** JSON helper without depending on two separate URL clients. */
private class JSONObjectOrArray(raw: String) {
    private val text = raw
    fun obj() = JSONObject(text)
    fun arr() = JSONArray(text)
}

object RemoteBridge {
    const val ISSUE_TITLE = "[jarvis-remote-v1]"
    const val PROTOCOL = "jarvis-remote-v1"
    private const val CHANNEL_ID = "jarvis_remote_approval"
    private const val NOTIFICATION_ID = 41822
    private const val MAX_AGE_MS = 15 * 60 * 1000L
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null

    private val _pending = MutableStateFlow<RemoteCommand?>(null)
    val pending = _pending.asStateFlow()

    private val _status = MutableStateFlow("الربط غير نشط")
    val status = _status.asStateFlow()

    @Synchronized
    fun start(ctx: Context) {
        val context = ctx.applicationContext
        if (job?.isActive == true) return
        val storage = BridgeStorage(context)
        if (!storage.enabled || storage.config() == null) {
            _status.value = "الربط غير مفعل"
            return
        }
        _pending.value = storage.pending()
        val config = storage.config() ?: return
        job = scope.launch {
            val api = GitHubBridgeApi(config)
            var verified = false
            while (isActive) {
                var retryDelay = 8_000L
                try {
                    // Private-repo validation runs once for the active session,
                    // instead of two API calls every poll.
                    if (!verified) {
                        api.verifyPrivateRepo()
                        verified = true
                    }
                    poll(context, storage, api)
                } catch (e: Exception) {
                    _status.value = "تعذر الاتصال: " + (e.message?.take(100) ?: "خطأ")
                    retryDelay = 60_000L // Back off on errors/rate limits.
                }
                delay(retryDelay)
            }
        }
    }

    @Synchronized
    fun stop() {
        job?.cancel()
        job = null
        _status.value = "الربط متوقف"
    }

    fun refresh(ctx: Context) {
        stop()
        _pending.value = BridgeStorage(ctx).pending()
        start(ctx)
    }

    fun restore(ctx: Context) {
        _pending.value = BridgeStorage(ctx).pending()
    }

    suspend fun verify(repository: String, sender: String, token: String): String {
        if (!BridgeStorage.isValidRepository(repository)) return "✗ صيغة المستودع: owner/private-repo"
        if (!BridgeStorage.isValidLogin(sender)) return "✗ اسم مستخدم GitHub غير صالح"
        if (token.isBlank()) return "✗ أضف Fine-grained PAT"
        return try {
            kotlinx.coroutines.withContext(Dispatchers.IO) {
                GitHubBridgeApi(BridgeSettings(repository, sender, token)).verifyPrivateRepo()
            }
            "✓ اتصال آمن بمستودع خاص"
        } catch (e: Exception) {
            "✗ ${e.message ?: "فشل التحقق"}"
        }
    }

    private fun poll(ctx: Context, storage: BridgeStorage, api: GitHubBridgeApi) {
        if (!storage.enabled) return
        val config = storage.config() ?: return

        if (storage.pending() != null) {
            _pending.value = storage.pending()
            _status.value = "كاين أمر كيتسنى الموافقة"
            return
        }

        val issues = api.newestCommands()
        val now = System.currentTimeMillis()
        for (i in 0 until issues.length()) {
            val issue = issues.optJSONObject(i) ?: continue
            if (issue.has("pull_request") || issue.optString("title") != ISSUE_TITLE) continue
            val number = issue.optInt("number", 0)
            if (number <= 0 || storage.handled(number)) continue
            val author = issue.optJSONObject("user")?.optString("login").orEmpty()
            if (!author.equals(config.trustedSender, ignoreCase = true)) continue
            val created = try { Instant.parse(issue.optString("created_at")).toEpochMilli() }
                catch (_: Exception) { continue }
            if (now < created - 2 * 60_000L || now - created > MAX_AGE_MS) continue
            val command = try {
                val json = JSONObject(issue.optString("body", ""))
                if (json.optString("protocol") != PROTOCOL ||
                    json.optString("device_id") != storage.deviceId) continue
                val expires = Instant.parse(json.optString("expires_at")).toEpochMilli()
                if (expires <= now || expires > created + MAX_AGE_MS) continue
                val task = json.optString("task").trim()
                if (task.isBlank() || task.length > 500) continue
                if (task.any { it.code < 32 && it != '\n' && it != '\t' }) continue
                RemoteCommand(number, task, author, expires)
            } catch (_: Exception) { continue }

            storage.setPending(command) // persist before notification
            _pending.value = command
            _status.value = "توصلنا بالأمر #$number، خاص موافقتك"
            notifyApproval(ctx)
            break
        }
        if (_pending.value == null) _status.value = "✓ الرابط نشط: كيتحقق من الأوامر كل 8 ثوان"
    }

    private fun notifyApproval(ctx: Context) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) !=
                PackageManager.PERMISSION_GRANTED) return

        val manager = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "موافقة أوامر جارفيس", NotificationManager.IMPORTANCE_HIGH)
        )
        val intent = Intent(ctx, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val action = PendingIntent.getActivity(
            ctx, 41, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(ctx, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("أمر جديد لجارفيس")
            .setContentText("اضغط للمراجعة والموافقة على الهاتف")
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(action)
            .build()
        manager.notify(NOTIFICATION_ID, notification)
    }

    fun reject(ctx: Context) {
        val storage = BridgeStorage(ctx)
        val cmd = storage.pending() ?: return
        storage.markHandled(cmd.issue)
        storage.setPending(null)
        _pending.value = null
        _status.value = "تم رفض الأمر #${cmd.issue}"
        scope.launch { report(ctx, cmd.issue, "rejected", "Rejected on device") }
    }

    fun approve(ctx: Context) {
        val context = ctx.applicationContext
        val storage = BridgeStorage(context)
        val cmd = storage.pending() ?: return
        if (System.currentTimeMillis() > cmd.expiresAt) {
            reject(context)
            _status.value = "انتهت صلاحية الأمر #${cmd.issue}"
            return
        }
        if (!storage.enabled) { _status.value = "فعّل الربط أولاً"; return }
        if (KeyStore(context).activeConfig() == null &&
            FastIntentParser.parse(cmd.task) == null) {
            _status.value = "خاص تضيف مفتاح AI للمهام المعقدة أولاً"; return
        }
        if (AgentSession.busy.value) {
            _status.value = "جارفيس خدام فمهمة أخرى، وقفها أولاً"; return
        }
        // Exactly once on this device, even after process restart.
        storage.markHandled(cmd.issue)
        storage.setPending(null)
        _pending.value = null
        _status.value = "تنفيذ الأمر #${cmd.issue}"
        AgentSession.send(context, cmd.task) { success, summary ->
            _status.value = if (success) "✓ اكتمل الأمر #${cmd.issue}" else "✗ فشل الأمر #${cmd.issue}"
            scope.launch { report(context, cmd.issue, if (success) "completed" else "failed", summary) }
        }
    }

    private fun report(ctx: Context, issue: Int, result: String, summary: String) {
        try {
            val storage = BridgeStorage(ctx)
            val cfg = storage.config() ?: return
            GitHubBridgeApi(cfg).report(issue, result, summary)
        } catch (e: Exception) {
            _status.value = "نتيجة الأمر محليّة؛ تعذّر نشرها: ${e.message?.take(80)}"
        }
    }
}
