package com.snapclean.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AutomationLogicTest {
    private fun node(
        text: String = "", desc: String = "",
        l: Int = 0, t: Int = 0, r: Int = 100, b: Int = 100,
        clickable: Boolean = false, enabled: Boolean = true, visible: Boolean = true, children: Int = 0, index: Int = 0, id: String = ""
    ) = UiNode(index, text, desc, id, "android.view.View", l, t, r, b, clickable, enabled, visible, children, 0)

    private val labels = Labels.parse(null)

    @Test fun foldHandlesTurkishDottedI() {
        assertEquals(fold("KALDIR"), fold("kaldır"))
        assertEquals(fold("İstek Gönderildi"), fold("istek gönderildi"))
        assertEquals(fold("MORE OPTIONS"), fold("more options"))
    }

    @Test fun duplicateParentChildLabelsAreOneTarget() {
        val parent = node(desc = "Arkadaşlığı Yönet", l = 0, t = 0, r = 500, b = 120, clickable = true, children = 1, index = 0)
        val child = node(text = "Arkadaşlığı Yönet", l = 20, t = 10, r = 300, b = 100, index = 1)
        val m = findExact(listOf(parent, child), listOf("Manage Friendship", "Arkadaşlığı Yönet"))
        assertTrue(m is Match.One)
        assertEquals(1, (m as Match.One).node.index) // en küçük (çocuk) düğüm
    }

    @Test fun separateTargetsAreAmbiguous() {
        val a = node(text = "Kaldır", l = 0, t = 0, r = 100, b = 50)
        val b = node(text = "Kaldır", l = 0, t = 500, r = 100, b = 550)
        assertTrue(findExact(listOf(a, b), listOf("Kaldır")) is Match.Many)
    }

    @Test fun invisibleNodesAreIgnored() {
        val hidden = node(text = "Kaldır", visible = false)
        assertTrue(findExact(listOf(hidden), listOf("Kaldır")) is Match.None)
    }

    @Test fun removeFriendDoesNotMatchConfirmLabel() {
        val item = node(text = "Remove Friend")
        assertTrue(findExact(listOf(item), listOf("Remove", "Kaldır")) is Match.None)
    }

    @Test fun usernameIsTokenMatchedNotSubstring() {
        assertTrue(usernameVisible(listOf(node(text = "anna")), "Anna"))
        assertTrue(usernameVisible(listOf(node(text = "@anna.k")), "anna.k"))
        assertTrue(usernameVisible(listOf(node(desc = "Profil: anna_k, Sohbet")), "anna_k"))
        assertFalse(usernameVisible(listOf(node(text = "annabel")), "anna"))
        assertFalse(usernameVisible(listOf(node(text = "anna", visible = false)), "anna"))
    }

    @Test fun classifyProfileGate() {
        val user = node(text = "sample_user", index = 0)
        val add = node(text = "Ekle", index = 1, id = "com.snapchat.android:id/non_friend_action_button_single")
        val pending = node(text = "Beklemede", index = 2)
        assertEquals(ProfileVerdict.USERNAME_MISSING, classifyProfile(listOf(add), "sample_user", labels))
        assertEquals(ProfileVerdict.NOT_FRIEND_ADD, classifyProfile(listOf(user, add), "sample_user", labels))
        assertEquals(ProfileVerdict.PENDING_OK, classifyProfile(listOf(user, pending), "sample_user", labels))
        // İşaret yoksa (karşılıklı arkadaş olabilir) ASLA silmeye izin verilmez.
        assertEquals(ProfileVerdict.UNKNOWN_STATE, classifyProfile(listOf(user, node(text = "Sohbet")), "sample_user", labels))
        // Hem Ekle hem bekleyen görünüyorsa güvenli taraf: Ekle (atla).
        assertEquals(ProfileVerdict.NOT_FRIEND_ADD, classifyProfile(listOf(user, add, pending), "sample_user", labels))
    }

    @Test fun suggestedAddButtonDoesNotOverridePendingStatus() {
        val user = node(text = "sample_user", index = 0,
            id = "com.snapchat.android:id/profile_identity_section_username")
        val suggestionAdd = node(text = "Ekle", index = 1,
            id = "com.snapchat.android:id/suggestion_add_button")
        val pending = node(text = "Beklemede", index = 2)
        assertEquals(ProfileVerdict.PENDING_OK,
            classifyProfile(listOf(user, suggestionAdd, pending), "sample_user", labels))
        assertEquals(ProfileVerdict.UNKNOWN_STATE,
            classifyProfile(listOf(user, suggestionAdd), "sample_user", labels))
    }

    @Test fun overflowCandidatesPickTopRightIcons() {
        // 1080x2400 ekran
        val share = node(l = 880, t = 90, r = 960, b = 170, clickable = true, index = 0)
        val more = node(l = 960, t = 90, r = 1040, b = 170, clickable = true, index = 1)
        val back = node(l = 20, t = 90, r = 100, b = 170, clickable = true, index = 2)
        val bottom = node(l = 960, t = 2200, r = 1040, b = 2280, clickable = true, index = 3)
        val tiny = node(l = 1000, t = 40, r = 1010, b = 50, clickable = true, index = 4)
        val c = overflowCandidates(listOf(share, more, back, bottom, tiny), 0, 0, 1080, 2400)
        assertEquals(listOf(1, 0), c.map { it.index }) // en sağdaki önce; sol/alt/küçük elenir
    }

    @Test fun overflowCandidatesEmptyWhenNothingMatches() {
        assertTrue(overflowCandidates(listOf(node(l = 0, t = 1000, r = 1080, b = 1200)), 0, 0, 1080, 2400).isEmpty())
        assertTrue(overflowCandidates(emptyList(), 0, 0, 0, 0).isEmpty())
    }

    @Test fun labelsParseOverridesOnlyKnownKeys() {
        val parsed = Labels.parse("pending=Bekliyor, Gönderildi\nbilinmeyen=x\nbozuk satir\nadd=")
        assertEquals(listOf("Bekliyor", "Gönderildi"), parsed["pending"])
        assertEquals(Labels.defaults["add"], parsed["add"]) // boş değer varsayılanı bozmaz
        assertEquals(null, parsed["bilinmeyen"])
        assertEquals(Labels.defaults["manage"], parsed["manage"])
    }

    @Test fun defaultTextRoundTrips() {
        assertEquals(Labels.defaults.toMap(), Labels.parse(Labels.defaultText()).toMap())
    }

    // Gerçek cihaz dökümünden (Xiaomi, Android 16) uyarlanmış karşılıklı arkadaş profili.
    private fun realFriendProfile() = listOf(
        node(id = "com.snapchat.android:id/unified_profile_share_button", l = 941, t = 137, r = 1055, b = 251, index = 0),
        node(id = "com.snapchat.android:id/unified_profile_icon_button_action_menu", l = 1079, t = 137, r = 1193, b = 251, index = 1),
        node(text = "Nilgün Oruç", desc = "Nilgün Oruç", id = "x:id/profile_identity_section_display_name", l = 321, t = 594, r = 625, b = 668, index = 2),
        node(text = "nilgunoruc25", desc = "nilgunoruc25", id = "x:id/profile_identity_section_username", l = 321, t = 668, r = 545, b = 717, index = 3),
        node(text = "eminee.bayir", l = 134, t = 1521, r = 353, b = 1570, index = 4),
        node(text = "add_button", l = 118, t = 1594, r = 369, b = 1690, index = 5),
        node(text = "Nilgün Oruç adlı kişiyle 4 Ekim 2026 tarihinden beri arkadaş", l = 87, t = 1987, r = 1133, b = 2047, clickable = true, index = 6)
    )

    @Test fun realMutualFriendProfileIsNeverTouched() {
        assertEquals(ProfileVerdict.MUTUAL_FRIEND, classifyProfile(realFriendProfile(), "nilgunoruc25", labels))
    }

    @Test fun suggestionCardUsernameDoesNotVerifyAnotherAccount() {
        // Ekranda başka bir profil açık; aranan hesap yalnızca öneri kartında görünüyor.
        assertFalse(profileUsernameVisible(realFriendProfile(), "eminee.bayir"))
        assertEquals(ProfileVerdict.USERNAME_MISSING, classifyProfile(realFriendProfile(), "eminee.bayir", labels))
    }

    @Test fun overflowFoundByViewIdEvenWhenNotClickable() {
        val m = findByViewId(realFriendProfile(), Labels.defaults["overflowid"].orEmpty())
        assertTrue(m is Match.One)
        assertEquals(1, (m as Match.One).node.index)
        assertEquals(1136, m.node.centerX)
    }

    @Test fun overflowGeometryAlsoRanksRealMenuFirst() {
        val c = overflowCandidates(realFriendProfile(), 0, 0, 1220, 2712)
        assertEquals(1, c.first().index)
    }

    @Test fun containsAnyMatchesFriendsSince() {
        assertTrue(containsAny(listOf(node(text = "Ali since 4 Oct - Friends since 2026")), listOf("friends since")))
        assertFalse(containsAny(listOf(node(text = "Ekle")), listOf("beri arkadaş")))
    }

    @Test fun searchFieldTextIsNotAProfile() {
        // Arama kutusuna kullanıcı adı yazılmış; ekranda kimlik satırı YOK -> profil sayılmamalı.
        val field = node(text = "sample_user", id = "com.snapchat.android:id/search_input", index = 0)
        val result = node(text = "sample_user", id = "com.snapchat.android:id/result_username", index = 1)
        assertFalse(onTargetProfile(listOf(field, result), "sample_user"))
    }

    @Test fun onlyIdentityRowCountsAsTargetProfile() {
        val id = "com.snapchat.android:id/profile_identity_section_username"
        assertTrue(onTargetProfile(listOf(node(text = "sample_user", id = id)), "sample_user"))
        assertTrue(onTargetProfile(listOf(node(text = "@Sample_User", id = id)), "sample_user"))
        assertFalse(onTargetProfile(listOf(node(text = "other_user", id = id)), "sample_user"))
        // Başka profil açık, aranan hesap yalnızca öneri kartında
        assertFalse(onTargetProfile(realFriendProfile(), "eminee.bayir"))
        assertTrue(onTargetProfile(realFriendProfile(), "nilgunoruc25"))
        // Gizli kimlik satırı sayılmaz
        assertFalse(onTargetProfile(listOf(node(text = "sample_user", id = id, visible = false)), "sample_user"))
    }

    // Videodaki sohbet ekranı (1220x2712): geri oku, avatar, kişi adı, sağda arkadaş-ekle simgesi, altta bant ve mesaj kutusu.
    private fun chatScreen() = listOf(
        node(l = 20, t = 100, r = 100, b = 190, clickable = true, index = 0),                       // geri oku
        node(l = 120, t = 90, r = 210, b = 200, index = 1, clickable = true),                       // avatar (etiketsiz)
        node(text = "Laz kızı", l = 240, t = 120, r = 480, b = 180, index = 2),                     // başlık
        node(l = 1060, t = 100, r = 1180, b = 190, clickable = true, index = 3),                    // arkadaş-ekle simgesi
        node(text = "@Laz kızı seni henüz arkadaş olarak eklemedi.", l = 40, t = 2380, r = 1180, b = 2460, index = 4),
        node(text = "Mesaj Gönder", l = 120, t = 2580, r = 700, b = 2660, index = 5)
    )

    @Test fun chatScreenIsRecognised() {
        assertTrue(looksLikeChat(chatScreen(), labels["chat"].orEmpty()))
        assertFalse(looksLikeChat(realFriendProfile(), labels["chat"].orEmpty()))
    }

    @Test fun chatHeaderCandidatesPreferNameAndSkipBackAndRightIcon() {
        val c = chatHeaderCandidates(chatScreen(), 0, 0, 1220, 2712)
        assertEquals(2, c[0].index)                       // etiketli kişi adı önce
        assertTrue(c.none { it.index == 0 })              // geri oku elenir
        assertTrue(c.none { it.index == 3 })              // sağ üst simge elenir
        assertTrue(c.none { it.index == 4 || it.index == 5 }) // alttaki bant ve mesaj kutusu elenir
    }

    @Test fun chatHeaderCandidatesEmptyOnEmptyScreen() {
        assertTrue(chatHeaderCandidates(emptyList(), 0, 0, 0, 0).isEmpty())
        assertTrue(chatHeaderCandidates(realFriendProfile().take(1), 0, 0, 1220, 2712).isEmpty())
    }

    @Test fun chatScreenIsNeverATargetProfile() {
        // Sohbet ekranında kimlik satırı yok: kullanıcı adı başlıkta bile geçse profil sayılmaz.
        val withName = chatScreen() + node(text = "halenurbasar61", index = 6)
        assertFalse(onTargetProfile(withName, "halenurbasar61"))
    }

    @Test fun hugeContainerIsNotAPlausibleClickTarget() {
        val screen = 1220L * 2712L
        // Üç nokta simgesi 114x114; ekranı kaplayan RecyclerView (1220x2570) asla hedef olmamalı
        assertFalse(ancestorIsPlausibleTarget(114L * 114L, 1220L * 2570L, screen))
        // Simgeyi saran 150x150'lik düğme makul
        assertTrue(ancestorIsPlausibleTarget(114L * 114L, 150L * 150L, screen))
        // Geçersiz boyutlar güvenli tarafta
        assertFalse(ancestorIsPlausibleTarget(0L, 100L, screen))
        assertFalse(ancestorIsPlausibleTarget(100L, 100L, 0L))
    }

    // Videodaki arama sonucu satırı (1220 genişlik): avatar solda, adın yanında küçük durum simgesi, altında kullanıcı adı.
    private fun resultRow() = listOf(
        node(l = 70, t = 100, r = 230, b = 262, index = 0),                                   // avatar 160x162
        node(text = "Laz kızı", l = 270, t = 110, r = 520, b = 165, index = 1),               // görünen ad
        node(l = 270, t = 205, r = 312, b = 247, index = 2),                                  // durum kutucuğu 42x42
        node(text = "halenurbasar61", l = 330, t = 200, r = 700, b = 255, index = 3)          // kullanıcı adı (çapa)
    )

    @Test fun avatarIsFoundLeftOfUsernameAndStatusIconIsSkipped() {
        val nodes = resultRow()
        val c = resultAvatarCandidates(nodes, nodes[3], 1220)
        assertEquals(0, c.first().index)
        assertTrue(c.none { it.index == 2 })   // küçük durum simgesi elenir
        assertTrue(c.none { it.index == 1 })   // geniş metin düğümü (kare değil) elenir
    }

    @Test fun avatarCandidatesNeverPickNodesToTheRightOrFarAbove() {
        val anchor = node(text = "u", l = 330, t = 200, r = 700, b = 255, index = 9)
        val right = node(l = 800, t = 100, r = 960, b = 262, index = 1)
        val farAbove = node(l = 70, t = -400, r = 230, b = -238, index = 2)
        assertTrue(resultAvatarCandidates(listOf(anchor, right, farAbove), anchor, 1220).isEmpty())
        assertTrue(resultAvatarCandidates(emptyList(), anchor, 0).isEmpty())
    }

    @Test fun screenSignatureDetectsChangeButIgnoresOrderAndHiddenNodes() {
        val a = listOf(node(text = "A", index = 0), node(text = "B", index = 1))
        val reordered = listOf(node(text = "B", index = 0), node(text = "A", index = 1))
        assertEquals(screenSignature(a), screenSignature(reordered))
        assertFalse(screenSignature(a) == screenSignature(a + node(text = "C", index = 2)))
        assertEquals(screenSignature(a), screenSignature(a + node(text = "gizli", visible = false, index = 2)))
    }

    // Ekran görüntüsünden (900 genişlik ölçeği): "Arkadaşlığı Yönet" ikinci alt sayfası.
    private fun manageSheet() = listOf(
        node(text = "Arkadaşlığı Yönet", l = 58, t = 1170, r = 300, b = 1205, index = 0),   // sayfa başlığı
        node(text = "Daha Fazla Bilgi", l = 580, t = 1170, r = 790, b = 1205, index = 1),
        node(text = "Engelle", l = 58, t = 1275, r = 180, b = 1315, index = 2),
        node(text = "Şikayet Et", l = 58, t = 1405, r = 216, b = 1445, index = 3),
        node(text = "Arkadaşı Sil", l = 58, t = 1535, r = 244, b = 1575, index = 4),
        node(text = "Adı Düzenle", l = 58, t = 1665, r = 256, b = 1705, index = 5),
        node(text = "Tamamlandı", l = 350, t = 1805, r = 550, b = 1845, index = 6)
    )

    // Onay penceresi (ekran görüntüsü 4).
    private fun confirmDialog() = listOf(
        node(text = "nilgunoruc25 adlı kullanıcıyı arkadaşlıktan çıkarmak istediğinden emin misin?", l = 190, t = 770, r = 710, b = 930, index = 0),
        node(text = "Kaldır", l = 190, t = 977, r = 708, b = 1088, index = 1, clickable = true),
        node(text = "İptal et", l = 380, t = 1140, r = 520, b = 1190, index = 2)
    )

    @Test fun realManageSheetLabelsResolveToSingleTargets() {
        val rf = Labels.defaults["removefriend"].orEmpty()
        val m = findExact(manageSheet(), rf)
        assertTrue(m is Match.One)
        assertEquals(4, (m as Match.One).node.index)                 // "Engelle"/"Şikayet Et" ile karışmaz
        assertTrue(findExact(manageSheet(), Labels.defaults["sheetdone"].orEmpty()) is Match.One)
    }

    @Test fun manageLabelAppearsTwiceDuringSheetTransitionAndIsAmbiguous() {
        // İlk sayfadaki satır + ikinci sayfadaki başlık aynı anda ağaçtaysa Many döner (servis kısa süre bekler).
        val firstSheetRow = node(text = "Arkadaşlığı Yönet", l = 58, t = 905, r = 340, b = 945, index = 9)
        val both = manageSheet() + firstSheetRow
        assertTrue(findExact(both, Labels.defaults["manage"].orEmpty()) is Match.Many)
    }

    @Test fun confirmDialogButtonsAreUniqueAndDialogNamesTheUser() {
        val d = confirmDialog()
        assertEquals(1, (findExact(d, Labels.defaults["confirm"].orEmpty()) as Match.One).node.index)
        assertEquals(2, (findExact(d, Labels.defaults["cancel"].orEmpty()) as Match.One).node.index)
        assertTrue(dialogNamesUser(d, "nilgunoruc25"))
        assertFalse(dialogNamesUser(d, "nilgunoruc2"))      // önek eşleşmez
        assertFalse(dialogNamesUser(d, "baska_kullanici"))
    }

    @Test fun profileIdentityRowAloneDoesNotCountAsDialogConfirmation() {
        val identity = node(text = "nilgunoruc25", id = "x:id/profile_identity_section_username", index = 0)
        val noName = listOf(node(text = "Kaldır", index = 1), identity)
        assertFalse(dialogNamesUser(noName, "nilgunoruc25"))
    }

    private fun oneWayChat() = listOf(
        node(text = "Sema🎀", index = 0),
        node(text = "Sema🎀 seni henüz arkadaş olarak eklemedi. Arkadaş olarak ekleyene kadar senin mesajlarını göremez.", index = 1)
    )
    private fun mutualChat() = listOf(
        node(text = "Kazi", index = 0),
        node(text = "Mesaj Gönder", index = 1)
    )

    @Test fun oneWayBannerSeenOnlyInOneWayChat() {
        assertTrue(oneWayBannerVisible(oneWayChat(), labels))
        assertFalse(oneWayBannerVisible(mutualChat(), labels))
    }

    @Test fun friendSinceTextAloneIsNeverProofOfOneWay() {
        val p = realFriendProfile()
        assertEquals(ProfileVerdict.MUTUAL_FRIEND, classifyProfile(p, "nilgunoruc25", labels))
    }

    @Test fun friendSinceWithChatProofIsAllowedToProceed() {
        val p = realFriendProfile()
        assertEquals(ProfileVerdict.PENDING_OK, classifyProfile(p, "nilgunoruc25", labels, oneWayProven = true))
    }

    @Test fun proofNeverOverridesWrongProfile() {
        val p = realFriendProfile()
        assertEquals(ProfileVerdict.USERNAME_MISSING, classifyProfile(p, "baskakisi", labels, oneWayProven = true))
    }

    @Test fun profileOrChatScreenDetection() {
        assertTrue(isProfileOrChatScreen(realFriendProfile(), Labels.defaults["chat"].orEmpty()))
        assertTrue(isProfileOrChatScreen(oneWayChat(), Labels.defaults["chat"].orEmpty()))
        assertTrue(isProfileOrChatScreen(mutualChat(), Labels.defaults["chat"].orEmpty()))
        assertFalse(isProfileOrChatScreen(listOf(node(text = "Arkadaşlar")), Labels.defaults["chat"].orEmpty()))
    }

    // Gerçek e256002 dökümü (2.8-beta): onay penceresinde "Kaldır" yazısı YOK, yazısız tıklanabilir View var.
    private fun realUnlabeledConfirm() = listOf(
        node(index = 0, l = 145, t = 963, r = 1075, b = 1727, clickable = true, id = "x:id/0_resource_name_obfuscated"),
        node(index = 1, l = 241, t = 1035, r = 979, b = 1655, id = "x:id/dialog_content"),
        node(text = "e256002 adlı kullanıcıyı arkadaşlıktan çıkarmak istediğinden", index = 2, l = 241, t = 1035, r = 979, b = 1274, id = "x:id/alert_dialog_title"),
        node(index = 3, l = 241, t = 1322, r = 979, b = 1481, clickable = true),
        node(text = "İptal et", index = 4, l = 241, t = 1505, r = 979, b = 1655, clickable = true, id = "x:id/cancel_button")
    )

    @Test fun removeDialogRecognizedByTitle() {
        assertTrue(removeDialogVisible(realUnlabeledConfirm(), labels))
        assertFalse(removeDialogVisible(oneWayChat(), labels))
    }

    @Test fun unlabeledConfirmButtonFoundGeometrically() {
        val m = findConfirmButton(realUnlabeledConfirm(), labels)
        assertTrue(m is Match.One)
        assertEquals(3, (m as Match.One).node.index)
    }

    @Test fun cancelButtonIsNeverTheConfirmTarget() {
        val onlyCancel = realUnlabeledConfirm().filter { it.index != 3 }
        assertTrue(findConfirmButton(onlyCancel, labels) is Match.None)
    }

    @Test fun twoClickablesInDialogAreAmbiguous() {
        val two = realUnlabeledConfirm() + node(index = 5, l = 300, t = 1330, r = 900, b = 1470, clickable = true)
        assertTrue(findConfirmButton(two, labels) is Match.Many)
    }

    @Test fun labeledKaldirStillWins() {
        val withLabel = realUnlabeledConfirm() + node(text = "Kaldır", index = 6, l = 300, t = 1340, r = 900, b = 1460)
        val m = findConfirmButton(withLabel, labels)
        assertTrue(m is Match.One)
        assertEquals(6, (m as Match.One).node.index)
    }

    @Test fun dialogNamesUserWorksOnRealTitle() {
        assertTrue(dialogNamesUser(realUnlabeledConfirm(), "e256002"))
        assertFalse(dialogNamesUser(realUnlabeledConfirm(), "baskakisi"))
    }

    private fun staleSearchScreen() = listOf(
        node(text = "gulsumcanayaz24", index = 0, l = 100, t = 130, r = 900, b = 260),
        node(text = "İptal Et", index = 1, l = 950, t = 130, r = 1190, b = 260),
        node(text = "Arkadaşlar ve Gruplar", index = 2, l = 40, t = 300, r = 900, b = 360),
        node(text = "Gülsüm Canayaz", index = 3, l = 300, t = 400, r = 900, b = 470)
    )

    @Test fun staleSearchScreenCancelButtonFound() {
        val m = searchCancelButton(staleSearchScreen(), labels, true)
        assertTrue(m is Match.One)
        assertEquals(1, (m as Match.One).node.index)
    }

    @Test fun searchCancelIgnoredWithoutEditableOrInRemoveDialog() {
        assertTrue(searchCancelButton(staleSearchScreen(), labels, false) is Match.None)
        assertTrue(searchCancelButton(realUnlabeledConfirm(), labels, true) is Match.None)
    }

    private fun profileWith(actionDesc: String, extra: List<UiNode> = emptyList()) = listOf(
        node(text = "e256002", index = 0, id = "x:id/profile_identity_section_username", l = 321, t = 668, r = 474, b = 717),
        node(desc = actionDesc, index = 1, id = "x:id/non_friend_action_button_single", l = 48, t = 895, r = 1171, b = 1027)
    ) + extra

    @Test fun removalVerifiedWhenSinceLineGoneAndButtonChanged() {
        assertTrue(removalVerified(profileWith("Ekle"), "e256002", labels, "Snap"))
        assertTrue(removalVerified(profileWith("Arkadaş olarak ekle"), "e256002", labels, "Snap"))
    }

    @Test fun removalNotVerifiedWhileStillFriend() {
        val since = node(text = "E adlı kişiyle 26 Ağustos 2026 tarihinden beri arkadaşsın.", index = 2)
        assertFalse(removalVerified(profileWith("Ekle", listOf(since)), "e256002", labels, "Snap"))
        assertFalse(removalVerified(profileWith("Snap"), "e256002", labels, "Snap"))
        assertFalse(removalVerified(profileWith("Snap"), "baskakisi", labels, "Snap"))
    }

    @Test fun targetActionLabelReadsRealButton() {
        assertEquals("Snap", targetActionLabel(profileWith("Snap")))
        assertEquals("", targetActionLabel(listOf(node(text = "x"))))
    }
}
