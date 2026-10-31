package com.snapclean.app

import org.junit.Assert.assertEquals
import org.junit.Test

class SnapchatImportTest {
    @Test fun turkishHeadingWithDottedCapitalI() {
        val html = """<html><body><div class="rightpanel">
            <h3>Arkadaşlar</h3><table><tr><td>existing_friend</td><td>Friend</td></tr></table>
            <h3>Gönderilen Arkadaşlık İstekleri</h3>
            <table><tr><th>Kullanıcı Adı</th><th>Görünen Ad</th><th>Oluşturma Zaman Damgası</th></tr>
            <tr><td>sample_user.11</td><td>Sample</td><td>2026-10-01 UTC</td></tr>
            <tr><td>another_user</td><td>Another</td><td>2026-10-02 UTC</td></tr></table>
            <h3>Engellenen Kullanıcılar</h3><table><tr><td>blocked_user</td><td>Blocked</td></tr></table>
            </div></body></html>"""
        val rows = parseSnapchatExport(html)
        assertEquals(2, rows.size)
        assertEquals("sample_user.11", rows[0].username)
        assertEquals("2026-10-01 UTC", rows[0].date)
    }

    @Test fun englishHeading() {
        val html = """<h3>Sent Friend Requests</h3><table>
            <tr><td>sample123</td><td>Sample</td><td>today</td></tr></table>"""
        assertEquals(1, parseSnapchatExport(html).size)
    }
}
