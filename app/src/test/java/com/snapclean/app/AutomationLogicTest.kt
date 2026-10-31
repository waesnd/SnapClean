package com.snapclean.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AutomationLogicTest {
    private fun node(
        text: String = "", desc: String = "",
        l: Int = 0, t: Int = 0, r: Int = 100, b: Int = 100,
        clickable: Boolean = false, enabled: Boolean = true, visible: Boolean = true, children: Int = 0, index: Int = 0
    ) = UiNode(index, text, desc, "", "android.view.View", l, t, r, b, clickable, enabled, visible, children, 0)

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
        val add = node(text = "Ekle", index = 1)
        val pending = node(text = "Beklemede", index = 2)
        assertEquals(ProfileVerdict.USERNAME_MISSING, classifyProfile(listOf(add), "sample_user", labels))
        assertEquals(ProfileVerdict.NOT_FRIEND_ADD, classifyProfile(listOf(user, add), "sample_user", labels))
        assertEquals(ProfileVerdict.PENDING_OK, classifyProfile(listOf(user, pending), "sample_user", labels))
        // İşaret yoksa (karşılıklı arkadaş olabilir) ASLA silmeye izin verilmez.
        assertEquals(ProfileVerdict.UNKNOWN_STATE, classifyProfile(listOf(user, node(text = "Sohbet")), "sample_user", labels))
        // Hem Ekle hem bekleyen görünüyorsa güvenli taraf: Ekle (atla).
        assertEquals(ProfileVerdict.NOT_FRIEND_ADD, classifyProfile(listOf(user, add, pending), "sample_user", labels))
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
}
