package com.snapclean.app

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Path
import android.graphics.Rect
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.random.Random

/**
 * Kullanıcının seçtiği hesaplar için kontrollü Snapchat otomasyonu.
 *
 * Tasarım ilkeleri:
 *  1. Bir adım ANCAK bir sonraki ekranın işareti (ör. "Arkadaşlığı Yönet" yazısı) gerçekten
 *     göründüğünde ilerler. Tıklamanın "kabul edilmesi" başarı sayılmaz.
 *  2. Her adımın kendi zaman aşımı ve yeniden deneme bütçesi vardır (ACTION_CLICK -> hareket).
 *  3. Silme yalnızca: kullanıcı adı doğrulandı + profilde "bekleyen istek" işareti var +
 *     kuru çalıştırma kapalı + onay düğmesi tek ve belirgin ise yapılır.
 *  4. "done" durumu yalnızca silme sonrası profilde "Ekle" görülünce yazılır.
 *  5. Her hata ekran ağacı dökümü ve gerekçeyle kaydedilir; ilk hatada güvenle durur.
 */
class CleanupService : AccessibilityService() {

    private companion object {
        const val SNAP = "com.snapchat.android"
        const val TICK_MS = 700L
        const val RETRY_AFTER_MS = 1800L
        const val FRESH_START_MS = 90_000L
        const val VERIFIED_TTL_MS = 120_000L
        const val MAX_DUMPS = 3
        const val MAX_DUMP_LINES = 250
        const val HINT_BACKGROUND = " | Snapchat öne gelmedi: arka plandan uygulama açma engelli olabilir " +
            "(HyperOS: Uygulama bilgisi > Diğer izinler > 'Arka planda açılan pencereleri göster' ve pil kısıtlamasını kapat)"
    }

    private enum class Step(val label: String, val timeoutMs: Long, val maxAttempts: Int) {
        PROFILE("profil", 22_000L, 0),
        OPEN_MENU("üç nokta menüsü", 16_000L, 4),
        MANAGE("Arkadaşlığı Yönet", 10_000L, 3),
        REMOVE_FRIEND("Arkadaşı Sil", 10_000L, 3),
        CONFIRM("onay", 10_000L, 2),
        VERIFY("doğrulama", 26_000L, 0)
    }

    private class Screen(
        val nodes: List<UiNode>,
        val refs: List<AccessibilityNodeInfo>,
        val windows: Int
    )

    private val handler = Handler(Looper.getMainLooper())
    private val prefs: SharedPreferences by lazy { getSharedPreferences("cleanup_batch", MODE_PRIVATE) }

    private var names: List<String> = emptyList()
    private var index = 0
    private var running = false
    private var step = Step.PROFILE
    private var stepStartedAt = 0L
    private var lastActionAt = 0L
    private var attempts = 0
    private var epoch = 0
    private var transitioning = false
    private var tickScheduled = false
    private var profileVerified = false
    private var profileVerifiedAt = 0L
    private var unknownSince = 0L
    private var profileDumped = false
    private var verifyReopened = false
    private var launchRetried = false
    private var consecutiveReview = 0
    private var processed = 0
    private var skippedCount = 0
    private var reviewCount = 0
    private var dryCount = 0
    private var gestureBusyUntil = 0L
    private var lastHeartbeat = 0L
    private var lastPublish = 0L
    private var lastReadIssue = ""
    private var lastStatus = ""
    private var labelsRaw: String? = "\u0000"
    private var labels: Map<String, List<String>> = Labels.parse(null)

    private val sessionListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == "capture_request") {
            startCapture()
        } else if (key == "session_token" && prefs.getBoolean("running", false)) {
            reload()
        } else if (key == "running" && !prefs.getBoolean("running", false) && running) {
            running = false
            epoch++
            cancelAll()
        }
    }

    private val tickRunnable = Runnable {
        tickScheduled = false
        if (running) {
            val mine = epoch
            try {
                tick()
            } catch (e: Exception) {
                failCurrent("iç hata: " + e.javaClass.simpleName + ": " + e.message)
            }
            if (running && mine == epoch && !tickScheduled) scheduleTick(TICK_MS)
        }
    }

    // ---------------------------------------------------------------- elle ekran kaydı
    // Otomasyon çalışmadan, kullanıcı Snapchat'te gezerken ekranları kaydeder (hiçbir yere dokunmaz).

    private val captureHandler = Handler(Looper.getMainLooper())
    private var captureLeft = 0
    private var lastCaptureSig = 0

    private val captureRunnable = object : Runnable {
        override fun run() {
            if (captureLeft <= 0) return
            captureLeft--
            val s = readScreen()
            if (s != null) {
                val sig = s.nodes.filter { it.usable }.flatMap { it.rawLabels }.joinToString("|").hashCode()
                if (sig != lastCaptureSig) {
                    lastCaptureSig = sig
                    dumpScreen("MANUEL", s, "manual_dumps", 8)
                }
            }
            prefs.edit().putString("last_result", "Ekran kaydı: kalan ${captureLeft * 4} sn").apply()
            if (captureLeft > 0) captureHandler.postDelayed(this, 4000L)
            else prefs.edit().putString("last_result", "Ekran kaydı bitti").apply()
        }
    }

    private fun startCapture() {
        captureHandler.removeCallbacksAndMessages(null)
        prefs.edit().remove("manual_dumps").apply()
        lastCaptureSig = 0
        captureLeft = 13
        captureHandler.postDelayed(captureRunnable, 5000L)
    }

    // ---------------------------------------------------------------- yaşam döngüsü

    override fun onServiceConnected() {
        super.onServiceConnected()
        val info = serviceInfo
        if (info != null) {
            info.eventTypes = AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED or
                AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or AccessibilityEvent.TYPE_WINDOWS_CHANGED
            info.feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            // INCLUDE_NOT_IMPORTANT_VIEWS: Snapchat birçok görünümü "erişilebilirlik için önemsiz" işaretler;
            // bu bayrak olmadan düğmeler ağaçta hiç görünmez.
            info.flags = info.flags or AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or
                AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or
                AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS
            info.notificationTimeout = 100
            serviceInfo = info
        }
        prefs.registerOnSharedPreferenceChangeListener(sessionListener)
        prefs.edit().putBoolean("svc_connected", true).putString("svc_build", buildTag()).apply()
        // Güvenlik: eski/yarım kalmış bir oturum servis yeniden bağlanınca KENDİLİĞİNDEN başlamaz.
        val startedAt = prefs.getLong("session_started_at", 0L)
        val fresh = System.currentTimeMillis() - startedAt in 0..FRESH_START_MS
        if (prefs.getBoolean("running", false) && !fresh) {
            prefs.edit().putBoolean("running", false)
                .putString("last_result", "Eski oturum otomatik devam ettirilmedi (güvenlik). Yeniden başlat.").apply()
        } else {
            reload()
        }
    }

    override fun onUnbind(intent: Intent?): Boolean {
        prefs.edit().putBoolean("svc_connected", false).apply()
        return super.onUnbind(intent)
    }

    override fun onInterrupt() {
        if (running) stop("Erişilebilirlik servisi sistem tarafından kesildi")
    }

    override fun onDestroy() {
        prefs.unregisterOnSharedPreferenceChangeListener(sessionListener)
        prefs.edit().putBoolean("svc_connected", false).apply()
        epoch++
        cancelAll()
        captureHandler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val pkg = event?.packageName?.toString() ?: return
        if (pkg != SNAP) return
        val now = SystemClock.uptimeMillis()
        if (now - lastHeartbeat > 1000L) {
            lastHeartbeat = now
            prefs.edit().putLong("svc_last_event", System.currentTimeMillis()).apply()
        }
        if (running && !transitioning) scheduleTick(250L)
    }

    // ---------------------------------------------------------------- oturum / zamanlama

    private fun cancelAll() {
        handler.removeCallbacksAndMessages(null)
        tickScheduled = false
        transitioning = false
    }

    private fun scheduleTick(delay: Long) {
        handler.removeCallbacks(tickRunnable)
        tickScheduled = true
        handler.postDelayed(tickRunnable, delay)
    }

    private fun reload() {
        cancelAll()
        epoch++
        names = prefs.getString("names", "").orEmpty().split("\n").map { it.trim() }
            .filter { it.isNotEmpty() }.distinct().take(200)
        index = prefs.getInt("index", 0).coerceIn(0, names.size)
        running = prefs.getBoolean("running", false)
        consecutiveReview = 0
        processed = 0; skippedCount = 0; reviewCount = 0; dryCount = 0
        lastStatus = ""
        prefs.edit().remove("failed").remove("dumps").remove("log").apply()
        if (!running) return
        if (index >= names.size) { finish(); return }
        appendLog("Oturum başladı: ${names.size} hesap, kuru çalıştırma=${prefs.getBoolean("dry_run", true)}, sürüm=${buildTag()}")
        openNext()
    }

    private fun openNext() {
        transitioning = false
        if (!running) return
        if (index >= names.size) { finish(); return }
        val username = names[index]
        step = Step.PROFILE
        attempts = 0
        profileVerified = false
        verifyReopened = false
        launchRetried = false
        unknownSince = 0L
        profileDumped = false
        stepStartedAt = SystemClock.uptimeMillis()
        lastActionAt = 0L
        status("Profil açılıyor: $username (${index + 1}/${names.size})")
        if (!launchProfile(username)) { failCurrent("profil bağlantısı açılamadı"); return }
        scheduleTick(1500L)
    }

    private fun launchProfile(username: String): Boolean {
        val uri = Uri.parse("https://www.snapchat.com/add/" + Uri.encode(username))
        // Prefer Snapchat's own deep-link handler over a browser or chooser.
        val direct = Intent(Intent.ACTION_VIEW, uri)
            .setPackage(SNAP)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        return try {
            startActivity(direct)
            true
        } catch (_: Exception) {
            // Some Snapchat releases do not expose an ACTION_VIEW handler.
            // Never fall back to an unqualified URL: it can open the browser.
            val launch = packageManager.getLaunchIntentForPackage(SNAP) ?: return false
            try {
                startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                status("Snapchat açıldı; profil bağlantısı desteklenmiyor: $username")
                true
            } catch (_: Exception) {
                false
            }
        }
    }

    private fun advanceAfter(minMs: Long, maxMs: Long) {
        transitioning = true
        index++
        prefs.edit().putInt("index", index).apply()
        val mine = epoch
        handler.postDelayed({
            if (running && mine == epoch) openNext()
        }, Random.nextLong(minMs, maxMs))
    }

    private fun persistStop(reason: String) {
        prefs.edit().putBoolean("running", false).putInt("index", index).putString("last_result", reason).apply()
    }

    private fun stop(reason: String) {
        running = false
        epoch++
        cancelAll()
        appendLog("DURDU: $reason")
        persistStop(reason)
    }

    private fun finish() {
        stop("Parti bitti: $processed silindi+doğrulandı, $skippedCount atlandı, $reviewCount belirsiz (dokunulmadı), $dryCount kuru çalıştırma")
    }

    // ---------------------------------------------------------------- ana döngü

    private fun tick() {
        if (!prefs.getBoolean("running", false)) { stop("Durduruldu"); return }
        if (transitioning) return
        val username = names.getOrNull(index) ?: run { finish(); return }
        val now = SystemClock.uptimeMillis()
        refreshLabels()
        val screen = readScreen()
        if (screen == null) {
            val notFront = lastReadIssue.startsWith("Snapchat ekranda değil")
            if (step == Step.PROFILE && notFront && !launchRetried && now - stepStartedAt > 6000L) {
                launchRetried = true
                status("Snapchat öne gelmedi; bağlantı bir kez daha gönderiliyor: $username")
                if (!launchProfile(username)) {
                    failCurrent("Snapchat uygulaması başlatılamadı: $username")
                    return
                }
                return
            }
            stepWait(username, now, null, if (notFront) lastReadIssue + HINT_BACKGROUND else lastReadIssue)
            return
        }
        publishRead(screen, now)
        val nodes = screen.nodes

        when (step) {
            Step.PROFILE -> onProfile(username, now, screen)

            Step.OPEN_MENU -> {
                if (!requireVerified(username, screen)) return
                if (isPresent(nodes, lbl("manage"))) { enter(Step.MANAGE, now); return }
                if (timedOut(username, now, screen, "üç nokta menüsü açılamadı")) return
                if (waitingForRetry(now)) return
                if (attempts >= step.maxAttempts) { failStep(username, "üç nokta menüsü $attempts denemede açılmadı", screen); return }
                if (attempts == 0) dumpScreen("MENU-ONCESI", screen)
                // Önceki deneme yanlış bir yere dokunduysa (paylaşım sayfası vb.) geri dön.
                if (attempts > 0 && !profileUsernameVisible(nodes, username)) {
                    performGlobalAction(GLOBAL_ACTION_BACK)
                    lastActionAt = now
                    status("Beklenmeyen ekran, geri dönülüyor")
                    return
                }
                val byId = findByViewId(nodes, lbl("overflowid"))
                val byLabel = findExact(nodes, lbl("overflow"))
                val dm = resources.displayMetrics
                val targets: List<UiNode> = when {
                    byId is Match.One -> listOf(byId.node)
                    byLabel is Match.One -> listOf(byLabel.node)
                    else -> overflowCandidates(nodes, 0, 0, dm.widthPixels, dm.heightPixels)
                }
                if (targets.isEmpty()) {
                    attempts++
                    lastActionAt = now
                    status("Üç nokta düğmesi bulunamadı (etiket yok, aday yok) deneme $attempts/${step.maxAttempts}")
                    return
                }
                val target = targets[(attempts / 2).coerceAtMost(targets.size - 1)]
                val viaGesture = attempts % 2 == 1
                val ok = tapNode(screen, target, viaGesture)
                attempts++
                lastActionAt = now
                status("Menü denemesi $attempts/${step.maxAttempts}: ${describe(target)} ${if (viaGesture) "hareket" else "tıklama"} ${if (ok) "gönderildi" else "reddedildi"}")
            }

            Step.MANAGE -> {
                if (!requireVerified(username, screen)) return
                if (isPresent(nodes, lbl("removefriend"))) { enter(Step.REMOVE_FRIEND, now); return }
                actOnLabel(username, now, screen, "manage", "Arkadaşlığı Yönet")
            }

            Step.REMOVE_FRIEND -> {
                if (!requireVerified(username, screen)) return
                if (isPresent(nodes, lbl("confirm"))) { enter(Step.CONFIRM, now); return }
                actOnLabel(username, now, screen, "removefriend", "Arkadaşı Sil")
            }

            Step.CONFIRM -> onConfirm(username, now, screen)

            Step.VERIFY -> {
                if (verifySuccess(nodes, username)) { complete(username); return }
                val waited = now - stepStartedAt
                if (!verifyReopened && waited > 7000L) {
                    verifyReopened = true
                    status("Doğrulama için profil yeniden açılıyor: $username")
                    launchProfile(username)
                    return
                }
                if (waited > step.timeoutMs) {
                    failStep(username, "'Kaldır'a basıldı ama profilde 'Ekle' doğrulanamadı. Silinmiş OLABİLİR; elle kontrol et (kayıt 'done' yapılmadı)", screen)
                } else status("Silme doğrulanıyor: $username (${waited / 1000} sn)")
            }
        }
    }

    private fun onProfile(username: String, now: Long, screen: Screen) {
        when (classifyProfile(screen.nodes, username, labels)) {
            ProfileVerdict.USERNAME_MISSING -> {
                unknownSince = 0L
                if (!profileDumped && now - stepStartedAt > 6000L) {
                    profileDumped = true
                    dumpScreen("PROFIL-OKUNAMADI", screen)
                }
                stepWait(username, now, screen, "kullanıcı adı ağaçta görünmüyor; görülen: " + sample(screen.nodes))
            }
            ProfileVerdict.MUTUAL_FRIEND -> {
                markStatus(username, "skipped")
                skippedCount++
                consecutiveReview = 0
                status("Karşılıklı arkadaş, DOKUNULMADI: $username")
                advanceAfter(1200L, 2200L)
            }
            ProfileVerdict.NOT_FRIEND_ADD -> {
                markStatus(username, "skipped")
                skippedCount++
                consecutiveReview = 0
                status("Atlandı ('Ekle' görünüyor, arkadaş değil): $username")
                advanceAfter(1200L, 2200L)
            }
            ProfileVerdict.UNKNOWN_STATE -> {
                if (unknownSince == 0L) unknownSince = now
                if (now - unknownSince >= 4000L) {
                    dumpScreen("DURUM-BELIRSIZ", screen)
                    addFailed(username, "profil durumu doğrulanamadı ('bekleyen' işareti yok); karşılıklı arkadaş olabilir, dokunulmadı")
                    reviewCount++
                    consecutiveReview++
                    if (consecutiveReview >= 3) {
                        stop("Art arda 3 hesapta profil durumu doğrulanamadı. 'Gelişmiş etiketler' içindeki pending= satırını profil dökümüne göre düzelt.")
                    } else {
                        status("Belirsiz, dokunulmadı: $username")
                        advanceAfter(1200L, 2200L)
                    }
                } else status("Profil durumu okunuyor: $username")
            }
            ProfileVerdict.PENDING_OK -> {
                profileVerified = true
                profileVerifiedAt = now
                consecutiveReview = 0
                dumpScreen("PROFIL-DOGRULANDI", screen)
                enter(Step.OPEN_MENU, now)
            }
        }
    }

    private fun onConfirm(username: String, now: Long, screen: Screen) {
        if (!requireVerified(username, screen)) return
        val nodes = screen.nodes
        if (verifySuccess(nodes, username)) { complete(username); return }
        when (val m = findExact(nodes, lbl("confirm"))) {
            is Match.None -> {
                if (attempts > 0) enter(Step.VERIFY, now) // iletişim kutusu kapandı; sonucu doğrula
                else stepWait(username, now, screen, "onay iletişim kutusu bekleniyor")
            }
            is Match.Many -> failStep(username, "onay düğmesi için ${m.nodes.size} belirsiz eşleşme; dokunulmadı", screen)
            is Match.One -> {
                if (prefs.getBoolean("dry_run", true)) { dryRunDone(username, screen); return }
                if (now - profileVerifiedAt > VERIFIED_TTL_MS) {
                    failStep(username, "profil doğrulaması 2 dakikadan eski; güvenlik için iptal", screen)
                    return
                }
                if (waitingForRetry(now)) return
                if (attempts >= step.maxAttempts) { failStep(username, "onay düğmesine basıldı ama ekran değişmedi", screen); return }
                if (attempts == 0) dumpScreen("ONAY-ONCESI", screen)
                val viaGesture = attempts % 2 == 1
                val ok = tapNode(screen, m.node, viaGesture)
                attempts++
                lastActionAt = now
                status("ONAY basıldı ($attempts/${step.maxAttempts}) ${if (viaGesture) "hareket" else "tıklama"} ${if (ok) "gönderildi" else "reddedildi"}: $username")
            }
        }
    }

    // ---------------------------------------------------------------- adım yardımcıları

    private fun enter(next: Step, now: Long) {
        step = next
        attempts = 0
        stepStartedAt = now
        lastActionAt = 0L
        status("→ ${next.label}")
    }

    private fun lbl(key: String): List<String> = labels[key].orEmpty()

    private fun refreshLabels() {
        val raw = prefs.getString("labels", null)
        if (raw != labelsRaw) {
            labelsRaw = raw
            labels = Labels.parse(raw)
        }
    }

    private fun waitingForRetry(now: Long) = lastActionAt != 0L && now - lastActionAt < RETRY_AFTER_MS

    private fun timedOut(username: String, now: Long, screen: Screen?, why: String): Boolean {
        if (now - stepStartedAt > step.timeoutMs) { failStep(username, why, screen); return true }
        return false
    }

    private fun stepWait(username: String, now: Long, screen: Screen?, why: String) {
        if (now - stepStartedAt > step.timeoutMs) {
            failStep(username, "zaman aşımı: $why", screen)
        } else status("${step.label} bekleniyor: $why".take(260))
    }

    private fun requireVerified(username: String, screen: Screen?): Boolean {
        if (profileVerified && names.getOrNull(index) == username) return true
        failStep(username, "profil doğrulanmadan işlem denenmeye çalışıldı (iç güvenlik)", screen)
        return false
    }

    private fun verifySuccess(nodes: List<UiNode>, username: String) =
        profileUsernameVisible(nodes, username) &&
            !containsAny(nodes, lbl("friendsince")) &&
            isPresent(nodes, lbl("add"))

    /** Etiketle bulunan tek bir düğmeye basar; belirsizse (birden fazla) güvenle durur. */
    private fun actOnLabel(username: String, now: Long, screen: Screen, key: String, what: String) {
        if (timedOut(username, now, screen, "$what bulunamadı/açılmadı")) return
        if (waitingForRetry(now)) return
        when (val m = findExact(screen.nodes, lbl(key))) {
            is Match.None -> status("$what bekleniyor")
            is Match.Many -> failStep(username, "$what için ${m.nodes.size} belirsiz eşleşme; dokunulmadı", screen)
            is Match.One -> {
                if (attempts >= step.maxAttempts) { failStep(username, "$what $attempts denemede ekranı değiştirmedi", screen); return }
                if (attempts == 0) dumpScreen("${step.name}-ONCESI", screen)
                val viaGesture = attempts % 2 == 1
                val ok = tapNode(screen, m.node, viaGesture)
                attempts++
                lastActionAt = now
                status("$what denemesi $attempts/${step.maxAttempts}: ${if (viaGesture) "hareket" else "tıklama"} ${if (ok) "gönderildi" else "reddedildi"}")
            }
        }
    }

    private fun dryRunDone(username: String, screen: Screen) {
        dumpScreen("KURU-ONAY-EKRANI", screen)
        dryCount++
        performGlobalAction(GLOBAL_ACTION_BACK)
        status("KURU ÇALIŞTIRMA tamam: $username için akış onay ekranına kadar çalıştı, 'Kaldır'a BASILMADI")
        advanceAfter(1800L, 3000L)
    }

    private fun complete(username: String) {
        markStatus(username, "done")
        processed++
        consecutiveReview = 0
        status("Silindi ve DOĞRULANDI ('Ekle' görüldü): $username")
        advanceAfter(2500L, 4500L)
    }

    private fun failStep(username: String, reason: String, screen: Screen?) {
        dumpScreen("HATA@${step.name}", screen)
        addFailed(username, "[${step.label}] $reason")
        stop("Durdu — $username: $reason")
    }

    private fun failCurrent(reason: String) {
        val username = names.getOrNull(index) ?: "?"
        addFailed(username, reason)
        stop("Durdu — $username: $reason")
    }

    // ---------------------------------------------------------------- ekranı okuma ve dokunma

    private fun readScreen(): Screen? {
        val roots = ArrayList<AccessibilityNodeInfo>()
        val active: AccessibilityNodeInfo? = rootInActiveWindow
        if (active != null) roots.add(active)
        var windowCount = 0
        try {
            val all = windows
            windowCount = all.size
            for (w in all) {
                val r = w.root ?: continue
                if (roots.none { it.windowId == r.windowId }) roots.add(r)
            }
        } catch (e: Exception) {
            // pencere listesi alınamadı; yalnızca aktif pencere kullanılır
        }
        if (roots.isEmpty()) {
            lastReadIssue = "ağaç boş: rootInActiveWindow=null, pencere sayısı=$windowCount"
            return null
        }
        // A cached/background Snapchat window is not proof it is foreground.
        val activePackage = active?.packageName?.toString()
        if (activePackage != SNAP) {
            lastReadIssue = "Snapchat ekranda değil; aktif paket: " + (activePackage ?: "?")
            return null
        }
        val snap = roots.filter { it.packageName?.toString() == SNAP }
        if (snap.isEmpty()) {
            lastReadIssue = "Snapchat ekranda değil; görünen paketler: " + roots.joinToString(",") { it.packageName?.toString() ?: "?" }
            return null
        }
        val nodes = ArrayList<UiNode>()
        val refs = ArrayList<AccessibilityNodeInfo>()
        for (r in snap) flatten(r, nodes, refs, 0)
        lastReadIssue = ""
        return Screen(nodes, refs, windowCount)
    }

    private fun flatten(node: AccessibilityNodeInfo?, nodes: MutableList<UiNode>, refs: MutableList<AccessibilityNodeInfo>, depth: Int) {
        if (node == null || depth > 60 || nodes.size >= 2500) return
        val r = Rect()
        node.getBoundsInScreen(r)
        nodes.add(
            UiNode(
                index = nodes.size,
                text = node.text?.toString().orEmpty(),
                desc = node.contentDescription?.toString().orEmpty(),
                viewId = node.viewIdResourceName.orEmpty(),
                className = node.className?.toString().orEmpty(),
                left = r.left, top = r.top, right = r.right, bottom = r.bottom,
                clickable = node.isClickable,
                enabled = node.isEnabled,
                visible = node.isVisibleToUser,
                childCount = node.childCount,
                depth = depth
            )
        )
        refs.add(node)
        for (i in 0 until node.childCount) flatten(node.getChild(i), nodes, refs, depth + 1)
    }

    /** gesture=false: en yakın tıklanabilir atayı ACTION_CLICK ile tıklar; yoksa hareketle dokunur. */
    private fun tapNode(screen: Screen, n: UiNode, gesture: Boolean): Boolean {
        if (!gesture) {
            var cur: AccessibilityNodeInfo? = screen.refs.getOrNull(n.index)
            var hops = 0
            while (cur != null && hops < 7) {
                if (cur.isClickable && cur.isEnabled) return cur.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                cur = cur.parent
                hops++
            }
        }
        return gestureTapAt(n.centerX, n.centerY)
    }

    private fun gestureTapAt(x: Int, y: Int): Boolean {
        val now = SystemClock.uptimeMillis()
        if (now < gestureBusyUntil) return false
        val dm = resources.displayMetrics
        val px = x.coerceIn(1, dm.widthPixels - 1).toFloat()
        val py = y.coerceIn(1, dm.heightPixels - 1).toFloat()
        val path = Path().apply { moveTo(px, py) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0L, 70L)).build()
        gestureBusyUntil = now + 1500L
        val accepted = dispatchGesture(gesture, object : AccessibilityService.GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                gestureBusyUntil = 0L
                appendLog("dokunma hareketi tamamlandı ($x,$y)")
            }
            override fun onCancelled(gestureDescription: GestureDescription?) {
                gestureBusyUntil = 0L
                appendLog("dokunma hareketi Android tarafından İPTAL edildi ($x,$y)")
            }
        }, handler)
        if (!accepted) {
            gestureBusyUntil = 0L
            appendLog("dispatchGesture reddedildi (canPerformGestures kapalı olabilir)")
        }
        return accepted
    }

    // ---------------------------------------------------------------- kayıt, döküm, durum

    private fun markStatus(username: String, status: String) {
        val store = RequestStore(this)
        try { store.status(username, status) } finally { store.close() }
    }

    private fun status(msg: String) {
        prefs.edit().putString("last_result", msg).apply()
        if (msg != lastStatus) {
            lastStatus = msg
            appendLog(msg)
        }
    }

    private fun stamp(): String = SimpleDateFormat("HH:mm:ss", Locale.ROOT).format(Date())

    private fun appendLog(msg: String) {
        val old = prefs.getString("log", "").orEmpty()
        val merged = (old + "\n" + stamp() + " [" + step.name + "] " + msg).takeLast(7000)
        prefs.edit().putString("log", merged).apply()
    }

    private fun addFailed(username: String, reason: String) {
        val old = prefs.getString("failed", "").orEmpty()
        prefs.edit().putString("failed", (old + "\n" + username + " | " + reason).trim()).apply()
    }

    private fun buildTag(): String = try {
        packageManager.getPackageInfo(packageName, 0).versionName ?: "?"
    } catch (e: Exception) {
        "?"
    }

    private fun describe(n: UiNode): String {
        val label = n.rawLabels.firstOrNull().orEmpty().take(24)
        return n.className.substringAfterLast('.') + "[" + n.left + "," + n.top + "," + n.right + "," + n.bottom + "]" +
            (if (label.isNotEmpty()) " '$label'" else "")
    }

    private fun sample(nodes: List<UiNode>): String =
        nodes.filter { it.usable }.flatMap { it.rawLabels }.distinct().take(8).joinToString(" / ").take(160)

    private fun publishRead(screen: Screen, now: Long) {
        if (now - lastPublish < 1500L) return
        lastPublish = now
        val labeled = screen.nodes.count { it.rawLabels.isNotEmpty() }
        val clickable = screen.nodes.count { it.clickable }
        prefs.edit().putString(
            "svc_last_read",
            "okunan düğüm=${screen.nodes.size}, etiketli=$labeled, tıklanabilir=$clickable, pencere=${screen.windows}"
        ).apply()
    }

    private fun dumpScreen(tag: String, screen: Screen?, key: String = "dumps", keep: Int = MAX_DUMPS) {
        val sb = StringBuilder()
        sb.append("=== ").append(tag).append(" | ").append(stamp()).append(" | sürüm ").append(buildTag())
            .append(" | ").append(Build.MANUFACTURER).append(' ').append(Build.MODEL)
            .append(" Android ").append(Build.VERSION.RELEASE).append(" (API ").append(Build.VERSION.SDK_INT).append(")\n")
        sb.append("hesap=").append(names.getOrNull(index)).append(" adım=").append(step.name)
            .append(" deneme=").append(attempts).append(" okumaSorunu=").append(lastReadIssue).append('\n')
        if (screen == null) {
            sb.append("(ekran okunamadı)\n")
        } else {
            val n = screen.nodes
            sb.append("toplam=").append(n.size).append(" etiketli=").append(n.count { it.rawLabels.isNotEmpty() })
                .append(" tıklanabilir=").append(n.count { it.clickable })
                .append(" görünür=").append(n.count { it.visible }).append(" pencere=").append(screen.windows).append('\n')
            sb.append("derinlik|sınıf|id|text|desc|bayrak(C=tıklanabilir,x=pasif,h=gizli)|sol,üst,sağ,alt\n")
            var lines = 0
            for (x in n) {
                if (lines >= MAX_DUMP_LINES) { sb.append("... (kırpıldı)\n"); break }
                if (x.rawLabels.isEmpty() && !x.clickable && x.viewId.isEmpty()) continue
                lines++
                sb.append(x.depth).append('|').append(x.className.substringAfterLast('.')).append('|')
                    .append(x.viewId.substringAfterLast('/')).append("|t='").append(x.text.take(60).replace('\n', ' '))
                    .append("'|d='").append(x.desc.take(60).replace('\n', ' ')).append("'|")
                    .append(if (x.clickable) "C" else "").append(if (!x.enabled) "x" else "").append(if (!x.visible) "h" else "")
                    .append('|').append(x.left).append(',').append(x.top).append(',').append(x.right).append(',').append(x.bottom).append('\n')
            }
        }
        val old = prefs.getString(key, "").orEmpty()
        val parts = (if (old.isEmpty()) emptyList() else old.split("\u0001")) + sb.toString()
        prefs.edit().putString(key, parts.takeLast(keep).joinToString("\u0001")).apply()
    }
}
