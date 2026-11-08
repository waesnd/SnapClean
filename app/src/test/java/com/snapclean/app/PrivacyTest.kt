package com.snapclean.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PrivacyTest {
    private val known = knownLabelSet(Labels.parse(null))

    @Test fun knownButtonLabelIsKept() {
        assertEquals("Arkadaşlığı Yönet", redactLabel("Arkadaşlığı Yönet", true, known, "deniztest42"))
    }

    @Test fun targetUsernameInsideKnownDialogTitleIsMasked() {
        val out = redactLabel("deniztest42 adlı kullanıcıyı arkadaşlıktan çıkarmak istediğinden emin misin?", false, known, "deniztest42")
        assertFalse(out.contains("deniztest42"))
        assertTrue(out.contains("arkadaşlıktan çıkarmak"))
    }

    @Test fun freeChatTextIsReducedToLength() {
        val msg = "bugün akşam buluşalım mı?"
        assertEquals("‹${msg.length} krk›", redactLabel(msg, false, known, "deniztest42"))
    }

    @Test fun anotherUsernameLookingLabelIsMaskedEvenIfClickable() {
        assertEquals("‹12 krk›", redactLabel("baskasininad", true, known, "deniztest42"))
        assertTrue(redactLabel("some_user.9", true, known, "deniztest42").startsWith("‹"))
    }

    @Test fun shortClickableWordsWithSpacesAreKept() {
        assertEquals("Şikayet Et", redactLabel("Şikayet Et", true, known, "deniztest42"))
    }

    @Test fun targetUsernameAsClickableTextIsMasked() {
        assertTrue(redactLabel("deniztest42", true, known, "deniztest42").startsWith("‹"))
    }

    @Test fun shortKnownWordMustMatchExactlyNotAsSubstring() {
        // "add" bilinen etiket; bir sohbet cümlesinin içinde geçmesi korunma sebebi olmamalı.
        val msg = "can you add me later"
        assertTrue(redactLabel(msg, false, known, null).startsWith("‹"))
    }

    @Test fun maskUsernamesIsCaseInsensitiveAndLongestFirst() {
        val out = maskUsernames("Abcd ve abc ve ABC", listOf("abc", "abcd")) { "U$it" }
        assertEquals("U1 ve U0 ve U0", out)
    }

    @Test fun maskUsernamesIgnoresTooShortNames() {
        assertEquals("ab ab", maskUsernames("ab ab", listOf("ab")))
    }

    @Test fun maskUsernamesEscapesRegexCharacters() {
        assertEquals("<kullanıcı> x", maskUsernames("a.b_c x", listOf("a.b_c")))
        assertEquals("axb", maskUsernames("axb", listOf("a.b")))
    }
}
