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
fun classifyProfile(
    nodes: List<UiNode>,
    username: String,
    labels: Map<String, List<String>>,
    oneWayProven: Boolean = false
): ProfileVerdict {
    if (!profileUsernameVisible(nodes, username)) return ProfileVerdict.USERNAME_MISSING
    // Suggested-friends cards can contain "Add"; only trust the target profile action.
    val targetActions = nodes.filter { n ->
        n.usable && n.viewId.substringAfterLast('/').contains("non_friend_action_button")
    }
    val addLabels = labels["add"].orEmpty().map(::fold).toSet()
    if (targetActions.any { n -> n.rawLabels.any { fold(it) in addLabels } }) return ProfileVerdict.NOT_FRIEND_ADD
    if (isPresent(nodes, labels["pending"].orEmpty())) return ProfileVerdict.PENDING_OK
    // "… tarihinden beri arkadaşsın" yazısı TEK TARAFLI hesaplarda da çıkar (videoda doğrulandı): tek başına
    // ne karşılıklılık ne tek taraflılık kanıtıdır. Kanıt, sohbetteki "seni henüz arkadaş olarak eklemedi" bandıdır.
    if (oneWayProven) return ProfileVerdict.PENDING_OK
    if (containsAny(nodes, labels["friendsince"].orEmpty())) return ProfileVerdict.MUTUAL_FRIEND
    return ProfileVerdict.UNKNOWN_STATE
}

/**
 * Sohbet ekranında Snapchat'in "X seni henüz arkadaş olarak eklemedi" bandı var mı? Bu bant yalnızca karşı taraf
 * seni geri eklememişken görünür; gerçek ekran kaydında 4/4 hedef hesapta vardı. Karşılıklı arkadaşta çıkmaması
 * beklenir ama bunun için olumsuz kontrol kaydı henüz yok: bu yüzden yalnızca bant GÖRÜLÜRSE ilerlenir.
 */
fun oneWayBannerVisible(nodes: List<UiNode>, labels: Map<String, List<String>>): Boolean =
    containsAny(nodes, labels["oneway"].orEmpty())

/** Profil veya sohbet ekranında mıyız? (Arama simgesi yoksa geri tuşuyla ana listeye dönmek için.) */
fun isProfileOrChatScreen(nodes: List<UiNode>, chatMarkers: List<String>): Boolean =
    nodes.any { it.usable && it.viewId.substringAfterLast('/') == PROFILE_USERNAME_ID } ||
        containsAny(nodes, chatMarkers)

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

/**
 * Bir tıklanabilir ATANIN, asıl hedef düğüm yerine tıklanmasının makul olup olmadığı. Ata hedefle benzer
 * boyutta (en çok 12 kat alan) ve ekranın %35'inden küçük olmalı; aksi halde (ekranı kaplayan kapsayıcı,
 * tüm satır/liste) tıklama yanlış yere gider ve performAction yine de "true" döner.
 */
fun ancestorIsPlausibleTarget(nodeArea: Long, ancestorArea: Long, screenArea: Long): Boolean {
    if (nodeArea <= 0L || ancestorArea <= 0L || screenArea <= 0L) return false
    return ancestorArea <= nodeArea * 12 && ancestorArea * 100 <= screenArea * 35
}

/**
 * Arama sonucu satırında kullanıcı adı metninin SOLUNDAKİ avatar adayları. Satıra (veya metne) dokunmak
 * Snapchat'te SOHBET açar; profil için avatara dokunmak gerekir. Aday: ekran genişliğinin %5–25'i
 * boyutunda, yaklaşık kare, metinle aynı hizada. En yakın (metne en yakın) önce gelir.
 * Küçük durum simgeleri (kutucuk, ok) boyut sınırıyla elenir.
 */
fun resultAvatarCandidates(nodes: List<UiNode>, anchor: UiNode, screenWidth: Int): List<UiNode> {
    if (screenWidth <= 0) return emptyList()
    val minSide = (screenWidth * 0.05).toInt()
    val maxSide = (screenWidth * 0.25).toInt()
    val picked = nodes.filter { n ->
        n.usable && n.enabled && n.index != anchor.index &&
            n.centerX < anchor.left &&
            n.width in minSide..maxSide && n.height in minSide..maxSide &&
            Math.abs(n.width - n.height) <= maxOf(n.width, n.height) * 0.35 &&
            n.centerY >= anchor.top - n.height && n.centerY <= anchor.bottom + n.height
    }.sortedBy { anchor.left - it.right }
    val reps = mutableListOf<UiNode>()
    for (n in picked) if (reps.none { it.contains(n) || n.contains(it) }) reps.add(n)
    return reps.take(2)
}

/** Ekranın kararlı imzası (görünür düğümlerin kimlik+metin özeti). Dokunuş etkisini "değişti/değişmedi" diye ölçmek için. */
fun screenSignature(nodes: List<UiNode>): Int =
    nodes.filter { it.usable }
        .map { it.viewId.substringAfterLast('/') + "|" + it.text + "|" + it.desc }
        .sorted().joinToString("\n").hashCode()

/**
 * "Arkadaşlıktan çıkar" onay penceresi hedef kullanıcıyı adıyla anıyor mu? Arkasındaki profilin kimlik
 * satırı sayılmaz (o zaten ekranda kalır); yalnızca pencerenin kendi metni doğrulama sağlar.
 * Gerçek metin: "nilgunoruc25 adlı kullanıcıyı arkadaşlıktan çıkarmak istediğinden emin misin?"
 */
fun dialogNamesUser(nodes: List<UiNode>, username: String): Boolean =
    usernameVisible(nodes.filter { it.viewId.substringAfterLast('/') != PROFILE_USERNAME_ID }, username)

const val DIALOG_TITLE_ID = "alert_dialog_title"
const val DIALOG_CANCEL_ID = "cancel_button"
const val DIALOG_CONTENT_ID = "dialog_content"

private fun idOf(n: UiNode) = n.viewId.substringAfterLast('/')

/**
 * "…adlı kullanıcıyı arkadaşlıktan çıkarmak istediğinden emin misin?" penceresi açık mı? (gerçek id: alert_dialog_title)
 * Gerçek dökümde bu pencerenin "Kaldır" düğmesi YAZISIZ bir View'dur; bu yüzden pencere başlıktan tanınır.
 */
fun removeDialogVisible(nodes: List<UiNode>, labels: Map<String, List<String>>): Boolean {
    val title = nodes.firstOrNull { it.usable && idOf(it) == DIALOG_TITLE_ID } ?: return false
    return containsAny(listOf(title), labels["removetitle"].orEmpty())
}

/**
 * Onay düğmesi: önce "Kaldır" yazısı; yoksa pencerenin içindeki, başlığın altında ve "İptal et"in üstündeki
 * TEK tıklanabilir düğüm (gerçek dökümde: dialog_content içindeki yazısız View). Birden fazlaysa Many.
 */
fun findConfirmButton(nodes: List<UiNode>, labels: Map<String, List<String>>): Match {
    val byLabel = findExact(nodes, labels["confirm"].orEmpty())
    if (byLabel !is Match.None) return byLabel
    val content = nodes.firstOrNull { it.usable && idOf(it) == DIALOG_CONTENT_ID } ?: return Match.None
    val title = nodes.firstOrNull { it.usable && idOf(it) == DIALOG_TITLE_ID } ?: return Match.None
    val cancel = nodes.firstOrNull { it.usable && idOf(it) == DIALOG_CANCEL_ID }
    val cands = nodes.filter { n ->
        n.usable && n.clickable && n.enabled && idOf(n) != DIALOG_CANCEL_ID && n.index != content.index &&
            content.contains(n) && n.top >= title.bottom && (cancel == null || n.bottom <= cancel.top)
    }
    return when (cands.size) { 0 -> Match.None; 1 -> Match.One(cands[0]); else -> Match.Many(cands) }
}

/**
 * Arama ekranı (düzenlenebilir alan + "İptal Et") açık kalmışsa o düğme; onay penceresinin "İptal et"i (cancel_button) sayılmaz.
 * Gerçek döküm: GERİ tuşları profil→sohbet→ARAMA SONUÇLARI ekranına döner, orada arama simgesi yoktur.
 */
fun searchCancelButton(nodes: List<UiNode>, labels: Map<String, List<String>>, hasEditable: Boolean): Match {
    if (!hasEditable || removeDialogVisible(nodes, labels)) return Match.None
    return findExact(nodes.filter { idOf(it) != DIALOG_CANCEL_ID }, labels["cancel"].orEmpty())
}

/** Snapchat SOHBET ekranı işareti (mesaj kutusu veya "henüz arkadaş olarak eklemedi" bandı). */
fun looksLikeChat(nodes: List<UiNode>, markers: List<String>): Boolean = containsAny(nodes, markers)

/**
 * Sohbet ekranında profile geçmek için dokunulacak başlık adayları: ekranın üst %12'sindeki, geri
 * okunun (sol %10) ve sağ üstteki simgelerin (sağ %22) dışında kalan, etiketli veya tıklanabilir
 * düğümler. Etiketli başlık (kişi adı) önce, soldan sağa; ardından etiketsiz tıklanabilirler (avatar).
 * Dokunuştan sonra sonucu çağıran doğrular (profil kimlik satırı görünmeli); burada karar verilmez.
 */
fun chatHeaderCandidates(nodes: List<UiNode>, screenLeft: Int, screenTop: Int, screenRight: Int, screenBottom: Int): List<UiNode> {
    val w = screenRight - screenLeft
    val h = screenBottom - screenTop
    if (w <= 0 || h <= 0) return emptyList()
    val picked = nodes.filter { n ->
        n.usable && n.enabled &&
            n.centerY < screenTop + h * 0.12 &&
            n.centerX > screenLeft + w * 0.10 && n.centerX < screenLeft + w * 0.78 &&
            n.width <= w * 0.6 && n.height <= h * 0.08 &&
            (n.rawLabels.isNotEmpty() || n.clickable)
    }.sortedWith(compareByDescending<UiNode> { it.rawLabels.isNotEmpty() }.thenBy { it.centerX })
    val reps = mutableListOf<UiNode>()
    for (n in picked) if (reps.none { it.contains(n) || n.contains(it) }) reps.add(n)
    return reps.take(3)
}

/** Ayarlanabilir etiket sözlüğü. Satır biçimi: anahtar=etiket1,etiket2 */
object Labels {
    val defaults: LinkedHashMap<String, List<String>> = linkedMapOf(
        "overflowid" to listOf("unified_profile_icon_button_action_menu"),
        "overflow" to listOf("More options", "More", "Diğer seçenekler", "Daha fazla", "Menü", "Menu"),
        "manage" to listOf("Manage Friendship", "Arkadaşlığı Yönet"),
        "removefriend" to listOf("Remove Friend", "Arkadaşı Sil", "Arkadaşı Kaldır"),
        "confirm" to listOf("Remove", "Kaldır"),
        "cancel" to listOf("Cancel", "İptal et", "İptal"),
        "sheetdone" to listOf("Done", "Tamamlandı"),
        "add" to listOf("Add", "Add Friend", "Ekle", "Arkadaş Ekle"),
        "pending" to listOf("Pending", "Beklemede", "Request Sent", "İstek Gönderildi"),
        "friendsince" to listOf("tarihinden beri arkadaş", "beri arkadaş", "friends since"),
        "chat" to listOf("Mesaj Gönder", "Send a chat", "Send a message", "henüz arkadaş olarak eklemedi"),
        "removetitle" to listOf("arkadaşlıktan çıkarmak", "arkadaşlıktan çıkar", "unfriend", "remove as a friend", "remove as friend"),
        "oneway" to listOf("seni henüz arkadaş olarak eklemedi", "henüz arkadaş olarak eklemedi", "hasn't added you", "has not added you")
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
