package com.snapclean.app

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import java.util.Locale

/**
 * Experimental accessibility workflow. Exact text matching, one action per state,
 * timeout, and post-action verification. Never uses absolute screen coordinates.
 * Snapchat's UI may change without notice.
 */
class CleanupService : AccessibilityService() {
    private enum class Step { PROFILE, MENU, MANAGE, REMOVE, CONFIRM, VERIFY }
    private val handler = Handler(Looper.getMainLooper())
    private val prefs by lazy { getSharedPreferences("cleanup_batch", MODE_PRIVATE) }
    private var names = emptyList<String>()
    private var index = 0
    private var running = false
    private var step = Step.PROFILE
    private var lastAction = 0L
    private var startedAt = 0L
    private var session = 0
    private var verifiedProfile = false
    private val sessionListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == "running" && prefs.getBoolean("running", false) && !running) reload()
        else if (key == "running" && !prefs.getBoolean("running", false)) running = false
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        serviceInfo = serviceInfo.apply {
            eventTypes = AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED or AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
            feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            flags = flags or AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
            notificationTimeout = 250
        }
        prefs.registerOnSharedPreferenceChangeListener(sessionListener)
        reload()
    }

    private fun reload() {
        names = prefs.getString("names", "").orEmpty().split("\n").filter { it.isNotBlank() }.take(200)
        index = prefs.getInt("index", 0).coerceIn(0, names.size)
        running = prefs.getBoolean("running", false)
        if (running && index < names.size) openNext()
    }

    private fun persist() {
        prefs.edit().putInt("index", index).putBoolean("running", running).apply()
    }

    private fun stop(reason: String) {
        running = false
        session++
        persist()
        prefs.edit().putString("last_result", reason).apply()
    }

    private fun openNext() {
        if (!running || !prefs.getBoolean("running", false)) { stop("Durduruldu"); return }
        if (index >= names.size) { stop("Parti tamamlandı"); return }
        step = Step.PROFILE
        verifiedProfile = false
        startedAt = android.os.SystemClock.uptimeMillis()
        lastAction = startedAt
        val generation = ++session
        handler.postDelayed({
            if (running && session == generation && step != Step.VERIFY &&
                android.os.SystemClock.uptimeMillis() - startedAt >= 18000L) stop("Ekran zaman aşımı: " + names[index])
        }, 19000L)
        val uri = Uri.parse("https://www.snapchat.com/add/" + Uri.encode(names[index]))
        try {
            startActivity(Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: Exception) { stop("Profil açılamadı: " + names[index]) }
    }

    private fun collect(root: AccessibilityNodeInfo): List<AccessibilityNodeInfo> {
        val out = mutableListOf<AccessibilityNodeInfo>()
        fun visit(node: AccessibilityNodeInfo?, depth: Int) {
            if (node == null || depth > 24 || out.size >= 600) return
            out.add(node)
            for (i in 0 until node.childCount) visit(node.getChild(i), depth + 1)
        }
        visit(root, 0)
        return out
    }

    private fun label(node: AccessibilityNodeInfo): String =
        (node.text?.toString() ?: node.contentDescription?.toString() ?: "").trim()

    private fun click(node: AccessibilityNodeInfo): Boolean {
        var target: AccessibilityNodeInfo? = node
        while (target != null) {
            if (target.isClickable && target.isEnabled) return target.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            target = target.parent
        }
        return false
    }

    private fun tap(nodes: List<AccessibilityNodeInfo>, vararg labels: String): Boolean {
        val matching = nodes.filter { node ->
            val value = label(node)
            labels.any { it.equals(value, ignoreCase = true) }
        }
        if (matching.size != 1) return false
        return click(matching.single())
    }

    private fun advance(next: Step) {
        step = next
        lastAction = android.os.SystemClock.uptimeMillis()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (!running || !prefs.getBoolean("running", false)) { running = false; return }
        if (event?.packageName?.toString() != "com.snapchat.android") return
        val now = android.os.SystemClock.uptimeMillis()
        if (now - lastAction < 850L) return
        if (now - startedAt > 24000L) { stop("Ekran zaman aşımı"); return }
        val root = rootInActiveWindow ?: return
        val nodes = collect(root)
        val texts = nodes.map(::label).filter { it.isNotBlank() }
        val username = names.getOrNull(index) ?: run { stop("Liste tamamlandı"); return }
        val usernameFound = texts.any { it.equals(username, ignoreCase = true) || it.equals("@$username", ignoreCase = true) }
        when (step) {
            Step.PROFILE -> {
                if (!usernameFound) return
                verifiedProfile = true
                if (texts.any { it.equals("Ekle", true) || it.equals("Add", true) }) {
                    index++; persist(); openNext(); return
                }
                // Menu icon is often exposed as "More"/"More options".
                if (tap(nodes, "Diğer seçenekler", "Daha fazla", "More options", "More")) advance(Step.MENU)
                else stop("Üç nokta düğmesi tanınmadı: $username")
            }
            Step.MENU -> {
                if (!verifiedProfile) { stop("Profil doğrulanamadı"); return }
                if (tap(nodes, "Arkadaşlığı Yönet", "Manage Friendship")) advance(Step.MANAGE)
            }
            Step.MANAGE -> {
                if (tap(nodes, "Arkadaşı Sil", "Remove Friend")) advance(Step.REMOVE)
            }
            Step.REMOVE -> {
                if (texts.any { it.contains(username, true) } && tap(nodes, "Kaldır", "Remove")) advance(Step.VERIFY)
                else stop("Kaldırma onayı doğrulanamadı: $username")
            }
            Step.CONFIRM -> Unit
            Step.VERIFY -> {
                if (usernameFound && texts.any { it.equals("Ekle", true) || it.equals("Add", true) }) {
                    RequestStore(this).use { it.status(username, "done") }
                    index++; persist()
                    handler.postDelayed({ openNext() }, 1200L)
                } else if (now - lastAction > 9000L) stop("Ekle doğrulaması başarısız: $username")
            }
        }
    }

    override fun onInterrupt() { stop("Erişilebilirlik kesildi") }
    override fun onDestroy() { prefs.unregisterOnSharedPreferenceChangeListener(sessionListener); session++; super.onDestroy() }
}
