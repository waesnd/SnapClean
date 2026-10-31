package com.snapclean.app

/**
 * Android-bağımsız (saf Kotlin) otomasyon mantığı. CleanupService yalnızca ekranı okuyup
 * bu dosyadaki fonksiyonlara verir; böylece karar mantığı birim testleriyle doğrulanabilir.
 */

/** Erişilebilirlik ağacındaki bir düğümün Android'den bağımsız kopyası. */
data class UiNode(
    val index: Int,
    val text: String,
    val desc: String,
    val viewId: String,
    val className: String,
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
    val clickable: Boolean,
    val enabled: Boolean,
    val visible: Boolean,
    val childCount: Int,
    val depth: Int
) {
    val width: Int get() = right - left
    val height: Int get() = bottom - top
    val centerX: Int get() = (left + right) / 2
    val centerY: Int get() = (top + bottom) / 2
    val area: Long get() = width.toLong().coerceAtLeast(0) * height.toLong().coerceAtLeast(0)
    val rawLabels: List<String> get() = listOf(text, desc).map { it.trim() }.filter { it.isNotEmpty() }
    val usable: Boolean get() = visible && width > 0 && height > 0

    fun contains(other: UiNode): Boolean =
        left <= other.left && top <= other.top && right >= other.right && bottom >= other.bottom
}

/** Türkçe noktalı/noktasız I farkını ve büyük/küçük harfi yok sayan normalleştirme. */
fun fold(s: String): String {
    val sb = StringBuilder(s.length)
    var lastSpace = true
    for (c in s.trim()) {
        val m = when (c) {
            'İ', 'I', 'ı' -> 'i'
            else -> c.lowercaseChar()
        }
        if (m.isWhitespace()) {
            if (!lastSpace) sb.append(' ')
            lastSpace = true
        } else {
            sb.append(m)
            lastSpace = false
        }
    }
    return sb.toString().trim()
}

sealed class Match {
    object None : Match()
    data class One(val node: UiNode) : Match()
    data class Many(val nodes: List<UiNode>) : Match()
}

/**
 * Etiketi (text veya contentDescription) tam olarak eşleşen, ekranda görünen düğümleri bulur.
 * Ebeveyn/çocuk gibi iç içe geçmiş ve aynı etiketi taşıyan düğümler TEK hedef sayılır
 * (eski sürümde bunlar "birden fazla eşleşme" sayılıp işlem sessizce iptal ediliyordu).
 */
fun findExact(nodes: List<UiNode>, labels: List<String>): Match {
    if (labels.isEmpty()) return Match.None
    val wanted = labels.map(::fold).filter { it.isNotEmpty() }.toSet()
    val hits = nodes.filter { n -> n.usable && n.rawLabels.any { fold(it) in wanted } }
        .sortedBy { it.area }
    val reps = mutableListOf<UiNode>()
    for (h in hits) {
        if (reps.none { it.contains(h) || h.contains(it) }) reps.add(h)
    }
    return when (reps.size) {
        0 -> Match.None
        1 -> Match.One(reps[0])
        else -> Match.Many(reps)
    }
}

fun isPresent(nodes: List<UiNode>, labels: List<String>): Boolean = findExact(nodes, labels) !is Match.None

/** Kullanıcı adını bir jeton olarak arar: "anna" ile "annabel" eşleşmez, "@anna" eşleşir. */
fun usernameVisible(nodes: List<UiNode>, username: String): Boolean {
    val u = fold(username).trim('.', '_', '-')
    if (u.isEmpty()) return false
    val splitter = Regex("[^a-z0-9._-]+")
    return nodes.any { n ->
        n.usable && n.rawLabels.any { raw ->
            splitter.split(fold(raw)).any { tok -> tok.trim('.', '_', '-') == u }
        }
    }
}

/** Etiketi verilen parçalardan birini İÇEREN görünür düğüm var mı? (ör. "... tarihinden beri arkadaş") */
fun containsAny(nodes: List<UiNode>, fragments: List<String>): Boolean {
    val wanted = fragments.map(::fold).filter { it.isNotEmpty() }
    if (wanted.isEmpty()) return false
    return nodes.any { n -> n.usable && n.rawLabels.any { raw -> val f = fold(raw); wanted.any { f.contains(it) } } }
}

/** Görünür düğümleri view-id'sine göre bulur (id'nin son parçası, büyük/küçük harf duyarsız). */
fun findByViewId(nodes: List<UiNode>, ids: List<String>): Match {
    val wanted = ids.map { it.trim().lowercase() }.filter { it.isNotEmpty() }.toSet()
    if (wanted.isEmpty()) return Match.None
    val hits = nodes.filter { n -> n.usable && n.viewId.substringAfterLast('/').lowercase() in wanted }.sortedBy { it.area }
    val reps = mutableListOf<UiNode>()
    for (h in hits) if (reps.none { it.contains(h) || h.contains(it) }) reps.add(h)
    return when (reps.size) { 0 -> Match.None; 1 -> Match.One(reps[0]); else -> Match.Many(reps) }
}

const val PROFILE_USERNAME_ID = "profile_identity_section_username"

/**
 * Profilin kimlik satırı varsa (Snapchat: profile_identity_section_username) YALNIZCA ona bakılır;
 * böylece "arkadaş önerileri" listesindeki başka bir hesabın adı yanlış doğrulama üretmez.
 * Bu satır ağaçta yoksa genel jeton aramasına düşülür.
 */
fun profileUsernameVisible(nodes: List<UiNode>, username: String): Boolean {
    val idNodes = nodes.filter { it.usable && it.viewId.substringAfterLast('/') == PROFILE_USERNAME_ID }
    return if (idNodes.isNotEmpty()) usernameVisible(idNodes, username) else usernameVisible(nodes, username)
}

/**
 * SIKI profil doğrulaması: yalnızca profilin kimlik satırı (profile_identity_section_username)
 * hedef kullanıcı adını taşıyorsa true. Arama kutusuna yazılan metin, arama sonucu satırı veya
 * öneri kartı bu kimlik satırı olmadığı için profil sayılmaz (genel jeton aramasına DÜŞÜLMEZ).
 */
fun onTargetProfile(nodes: List<UiNode>, username: String): Boolean {
    val idNodes = nodes.filter { it.usable && it.viewId.substringAfterLast('/') == PROFILE_USERNAME_ID }
    return idNodes.isNotEmpty() && usernameVisible(idNodes, username)
}

enum class ProfileVerdict { USERNAME_MISSING, MUTUAL_FRIEND, NOT_FRIEND_ADD, PENDING_OK, UNKNOWN_STATE }

/**
 * Güvenlik kapısı. Yalnızca kullanıcı adı doğrulanmış VE profilde açıkça "bekleyen/gönderilmiş
 * istek" işareti varsa silmeye izin verilir. "Ekle" görünüyorsa zaten arkadaş değildir (atla).
 * İkisi de yoksa karşılıklı arkadaş olabilir; dokunulmaz.
 */
fun classifyProfile(nodes: List<UiNode>, username: String, labels: Map<String, List<String>>): ProfileVerdict {
    if (!profileUsernameVisible(nodes, username)) return ProfileVerdict.USERNAME_MISSING
    // Karşılıklı arkadaşlık yazısı varsa kesinlikle dokunma (örn. "... tarihinden beri arkadaş").
    if (containsAny(nodes, labels["friendsince"].orEmpty())) return ProfileVerdict.MUTUAL_FRIEND
    // Suggested-friends cards can contain "Add"; only trust the target profile action.
    val targetActions = nodes.filter { n ->
        n.usable && n.viewId.substringAfterLast('/').contains("non_friend_action_button")
    }
    val addLabels = labels["add"].orEmpty().map(::fold).toSet()
    if (targetActions.any { n -> n.rawLabels.any { fold(it) in addLabels } }) return ProfileVerdict.NOT_FRIEND_ADD
    if (isPresent(nodes, labels["pending"].orEmpty())) return ProfileVerdict.PENDING_OK
    return ProfileVerdict.UNKNOWN_STATE
}

/**
 * Etiketsiz "..." düğmesi için aday sıralaması: sağ üst bölgedeki, simge boyutunda yaprak veya
 * tıklanabilir düğümler; tıklanabilir olanlar ve en sağdakiler önde. Tek aday şartı YOK; çağıran
 * her adaydan sonra bir sonraki ekranın gerçekten açıldığını doğrular.
 */
fun overflowCandidates(nodes: List<UiNode>, screenLeft: Int, screenTop: Int, screenRight: Int, screenBottom: Int): List<UiNode> {
    val w = screenRight - screenLeft
    val h = screenBottom - screenTop
    if (w <= 0 || h <= 0) return emptyList()
    val minSide = (w * 0.03).toInt().coerceAtLeast(20)
    val maxSide = (w * 0.22).toInt()
    val picked = nodes.filter { n ->
        n.usable && n.enabled &&
            n.width in minSide..maxSide && n.height in minSide..maxSide &&
            n.centerX > screenLeft + w * 0.62 &&
            n.centerY < screenTop + h * 0.20 &&
            (n.clickable || n.childCount == 0)
    }.sortedWith(compareByDescending<UiNode> { it.clickable }.thenByDescending { it.centerX }.thenBy { it.centerY })
    val reps = mutableListOf<UiNode>()
    for (n in picked) {
        if (reps.none { it.contains(n) || n.contains(it) }) reps.add(n)
    }
    return reps.take(4)
}

/** Ayarlanabilir etiket sözlüğü. Satır biçimi: anahtar=etiket1,etiket2 */
object Labels {
    val defaults: LinkedHashMap<String, List<String>> = linkedMapOf(
        "overflowid" to listOf("unified_profile_icon_button_action_menu"),
        "overflow" to listOf("More options", "More", "Diğer seçenekler", "Daha fazla", "Menü", "Menu"),
        "manage" to listOf("Manage Friendship", "Arkadaşlığı Yönet"),
        "removefriend" to listOf("Remove Friend", "Arkadaşı Sil", "Arkadaşı Kaldır"),
        "confirm" to listOf("Remove", "Kaldır"),
        "add" to listOf("Add", "Add Friend", "Ekle", "Arkadaş Ekle"),
        "pending" to listOf("Pending", "Beklemede", "Request Sent", "İstek Gönderildi"),
        "friendsince" to listOf("tarihinden beri arkadaş", "beri arkadaş", "friends since")
    )

    fun defaultText(): String = defaults.entries.joinToString("\n") { (k, v) -> k + "=" + v.joinToString(",") }

    /** Boş veya bozuk satırlar varsayılana düşer; verilen anahtar varsayılanı tamamen değiştirir. */
    fun parse(text: String?): Map<String, List<String>> {
        val result = LinkedHashMap<String, List<String>>(defaults)
        if (text.isNullOrBlank()) return result
        for (line in text.lines()) {
            val eq = line.indexOf('=')
            if (eq <= 0) continue
            val key = line.substring(0, eq).trim().lowercase()
            if (key !in defaults) continue
            val values = line.substring(eq + 1).split(',').map { it.trim() }.filter { it.isNotEmpty() }
            if (values.isNotEmpty()) result[key] = values
        }
        return result
    }
}
