package com.snapclean.app

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * Experimental, user-initiated batch runner. It never clicks an unverified
 * coordinate or guesses which control means cancellation.
 */
class CleanupService : AccessibilityService() {
    private val handler = Handler(Looper.getMainLooper())
    private var names = emptyList<String>()
    private var index = 0
    private var running = false
    private var awaitingProfile = false

    override fun onServiceConnected() {
        super.onServiceConnected()
        serviceInfo = serviceInfo.apply {
            eventTypes = AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED or
                AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
            feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            flags = flags or AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
        }
        loadSession()
    }

    private fun loadSession() {
        val p = getSharedPreferences("cleanup_batch", MODE_PRIVATE)
        running = p.getBoolean("running", false)
        names = p.getString("names", "")!!.split("\n").filter { it.isNotBlank() }.take(200)
        index = p.getInt("index", 0).coerceIn(0, names.size)
        if (running && index < names.size) openNext()
    }

    private fun save() {
        getSharedPreferences("cleanup_batch", MODE_PRIVATE).edit()
            .putInt("index", index).putBoolean("running", running).apply()
    }

    private fun stop() { running = false; awaitingProfile = false; save() }

    private fun openNext() {
        if (!running || index >= names.size) { stop(); return }
        awaitingProfile = true
        val uri = Uri.parse("https://www.snapchat.com/add/" + Uri.encode(names[index]))
        val intent = Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try { startActivity(intent) } catch (_: Exception) { stop() }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (!running || !awaitingProfile || event?.packageName != "com.snapchat.android") return
        val root = rootInActiveWindow ?: return
        // Fail closed: only inspect the UI. Snapchat does not expose a stable,
        // documented accessibility contract for the cancellation action.
        // Never click arbitrary "Add", "Remove" or screen coordinates.
        val text = mutableListOf<String>()
        fun scan(node: AccessibilityNodeInfo?, depth: Int) {
            if (node == null || depth > 20 || text.size > 250) return
            node.text?.toString()?.let { text.add(it) }
            node.contentDescription?.toString()?.let { text.add(it) }
            for (i in 0 until node.childCount) scan(node.getChild(i), depth + 1)
        }
        scan(root, 0)
        // Unverified screen: pause rather than risk deleting an existing friend.
        if (text.isNotEmpty()) {
            awaitingProfile = false
            stop()
        }
    }

    override fun onInterrupt() { stop() }
}
