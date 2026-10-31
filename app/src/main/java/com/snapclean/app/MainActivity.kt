package com.snapclean.app

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.jsoup.Jsoup
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

data class RequestEntry(val username: String, val display: String, val date: String)
private val accent = Color(0xFF67E8D0)
private val background = Color(0xFF0B1220)
private val surface = Color(0xFF162235)
private val usernamePattern = Regex("^[A-Za-z][A-Za-z0-9._-]{2,29}$")

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefs = getSharedPreferences("snapclean_v2", Context.MODE_PRIVATE)
        setContent {
            val deviceLanguage = LocalConfiguration.current.locales[0].language
            var language by remember { mutableStateOf(prefs.getString("language", if (deviceLanguage == "tr") "tr" else "en") ?: "en") }
            fun t(en: String, tr: String) = if (language == "tr") tr else en
            var entries by remember { mutableStateOf(loadEntries(prefs.getString("entries", "[]") ?: "[]")) }
            var statuses by remember { mutableStateOf(loadStatuses(prefs.getString("statuses", "{}") ?: "{}")) }
            var query by remember { mutableStateOf("") }
            var filter by remember { mutableStateOf("all") }
            var message by remember { mutableStateOf("") }
            var confirmClear by remember { mutableStateOf(false) }

            fun saveEntries(newEntries: List<RequestEntry>) {
                entries = newEntries
                val array = JSONArray()
                newEntries.forEach { array.put(JSONObject().put("username", it.username).put("display", it.display).put("date", it.date)) }
                prefs.edit().putString("entries", array.toString()).apply()
            }
            fun setStatus(username: String, status: String) {
                statuses = statuses.toMutableMap().apply { put(username, status) }
                prefs.edit().putString("statuses", JSONObject(statuses).toString()).apply()
            }
            fun copyUsername(username: String) {
                (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
                    .setPrimaryClip(ClipData.newPlainText("SnapClean username", username))
                message = t("Username copied", "Kullanıcı adı kopyalandı")
            }
            fun openProfile(username: String) {
                val url = "https://www.snapchat.com/add/" + Uri.encode(username)
                try {
                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                    message = t("Verify the profile in Snapchat before taking action.", "İşlem yapmadan önce Snapchat profilini doğrula.")
                } catch (e: Exception) {
                    copyUsername(username)
                    message = t("Could not open profile. Username copied.", "Profil açılamadı. Kullanıcı adı kopyalandı.")
                }
            }

            val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
                if (uri != null) {
                    try {
                        val html = contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() } ?: ""
                        val parsed = parseSnapchatExport(html)
                        if (parsed.isEmpty()) {
                            message = t("No sent requests found. Check your Snapchat export format.", "Gönderilen istek bulunamadı. Snapchat dışa aktarım biçimini kontrol et.")
                        } else {
                            val merged = (entries + parsed).distinctBy { it.username.lowercase(Locale.ROOT) }
                            saveEntries(merged)
                            message = t("Imported ${parsed.size} records; ${merged.size} unique total.", "${parsed.size} kayıt aktarıldı; toplam ${merged.size} benzersiz kayıt.")
                        }
                    } catch (e: Exception) {
                        message = t("Could not read this file.", "Dosya okunamadı.")
                    }
                }
            }

            val completed = entries.count { statuses[it.username] == "done" }
            val skipped = entries.count { statuses[it.username] == "skipped" }
            val visible = entries.filter { entry ->
                (entry.username.contains(query, true) || entry.display.contains(query, true)) &&
                    (filter == "all" || (filter == "pending" && statuses[entry.username].isNullOrEmpty()) ||
                        statuses[entry.username] == filter)
            }
            MaterialTheme(
                colorScheme = darkColorScheme(
                    primary = accent, onPrimary = background, background = background,
                    surface = surface, onSurface = Color(0xFFF1F5F9), onBackground = Color(0xFFF1F5F9)
                )
            ) {
                Column(Modifier.fillMaxSize().padding(16.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Column {
                            Text("SnapClean", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
                            Text(t("Your friend request workspace", "Arkadaşlık isteği yönetim alanın"), style = MaterialTheme.typography.bodySmall)
                        }
                        TextButton(onClick = {
                            language = if (language == "tr") "en" else "tr"
                            prefs.edit().putString("language", language).apply()
                        }) { Text(if (language == "tr") "EN" else "TR") }
                    }
                    Spacer(Modifier.height(16.dp))
                    Card(colors = CardDefaults.cardColors(containerColor = surface), shape = RoundedCornerShape(18.dp)) {
                        Column(Modifier.fillMaxWidth().padding(16.dp)) {
                            Text(t("Imported records", "Aktarılan kayıtlar"), style = MaterialTheme.typography.labelMedium)
                            Text("${entries.size}", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                            Text(t("Reviewed: $completed  •  Skipped: $skipped", "İncelenen: $completed  •  Atlanan: $skipped"))
                            Spacer(Modifier.height(8.dp))
                            LinearProgressIndicator(
                                progress = { if (entries.isEmpty()) 0f else completed.toFloat() / entries.size },
                                modifier = Modifier.fillMaxWidth(), color = accent
                            )
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                    Button(onClick = { picker.launch("*/*") }, modifier = Modifier.fillMaxWidth()) {
                        Text(t("Import Snapchat friends.html", "Snapchat friends.html dosyasını içe aktar"))
                    }
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = query, onValueChange = { query = it },
                        label = { Text(t("Search username", "Kullanıcı adı ara")) },
                        singleLine = true, modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf(
                            "all" to t("All", "Tümü"),
                            "pending" to t("Pending", "Bekleyen"),
                            "done" to t("Reviewed", "İncelenen"),
                            "skipped" to t("Skipped", "Atlanan")
                        ).forEach { (key, label) ->
                            FilterChip(selected = filter == key, onClick = { filter = key }, label = { Text(label) })
                        }
                    }
                    if (message.isNotBlank()) {
                        Text(message, style = MaterialTheme.typography.bodySmall, color = accent)
                        Spacer(Modifier.height(6.dp))
                    }
                    Text(t("Showing ${visible.size} records", "${visible.size} kayıt gösteriliyor"), style = MaterialTheme.typography.labelSmall)
                    LazyColumn(modifier = Modifier.weight(1f)) {
                        items(visible, key = { it.username }) { entry ->
                            Column(Modifier.fillMaxWidth().clickable { openProfile(entry.username) }.padding(vertical = 12.dp)) {
                                Text(entry.display.ifBlank { entry.username }, fontWeight = FontWeight.SemiBold)
                                Text("@${entry.username}", color = accent, style = MaterialTheme.typography.bodyMedium)
                                if (entry.date.isNotBlank()) Text(entry.date, style = MaterialTheme.typography.bodySmall)
                                Text(
                                    when (statuses[entry.username]) {
                                        "done" -> t("Reviewed • manually confirmed", "İncelendi • manuel onaylandı")
                                        "skipped" -> t("Skipped", "Atlandı")
                                        else -> t("Not reviewed • historical record", "İncelenmedi • geçmiş kayıt")
                                    }, style = MaterialTheme.typography.labelSmall
                                )
                                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                    TextButton(onClick = { openProfile(entry.username) }) { Text(t("Open profile", "Profili aç")) }
                                    TextButton(onClick = { copyUsername(entry.username) }) { Text(t("Copy", "Kopyala")) }
                                    TextButton(onClick = { setStatus(entry.username, if (statuses[entry.username] == "done") "" else "done") }) {
                                        Text(if (statuses[entry.username] == "done") t("Undo", "Geri al") else t("Reviewed", "İncelendi"))
                                    }
                                    TextButton(onClick = { setStatus(entry.username, "skipped") }) { Text(t("Skip", "Atla")) }
                                }
                            }
                            HorizontalDivider(color = Color(0xFF27374A))
                        }
                    }
                    Text(
                        t("Offline records. Historical requests are not necessarily pending. SnapClean is not affiliated with Snapchat.",
                            "Çevrimdışı kayıtlar. Geçmiş istekler hâlâ bekliyor olmayabilir. SnapClean, Snapchat ile bağlantılı değildir."),
                        style = MaterialTheme.typography.labelSmall
                    )
                    TextButton(onClick = { confirmClear = true }) { Text(t("Clear imported data", "Aktarılan verileri temizle")) }
                }
                if (confirmClear) AlertDialog(
                    onDismissRequest = { confirmClear = false },
                    title = { Text(t("Delete local data?", "Yerel veriler silinsin mi?")) },
                    text = { Text(t("All imported records and review statuses will be deleted from this device.", "Aktarılan kayıtlar ve inceleme durumları bu cihazdan silinecek.")) },
                    confirmButton = { TextButton(onClick = {
                        saveEntries(emptyList())
                        statuses = emptyMap()
                        prefs.edit().remove("statuses").apply()
                        confirmClear = false
                    }) { Text(t("Delete", "Sil")) } },
                    dismissButton = { TextButton(onClick = { confirmClear = false }) { Text(t("Cancel", "Vazgeç")) } }
                )
            }
        }
    }
}

private fun loadEntries(raw: String): List<RequestEntry> = try {
    val arr = JSONArray(raw)
    (0 until arr.length()).map { i ->
        val obj = arr.getJSONObject(i)
        RequestEntry(obj.optString("username"), obj.optString("display"), obj.optString("date"))
    }.filter { usernamePattern.matches(it.username) }
} catch (e: Exception) { emptyList() }

private fun loadStatuses(raw: String): Map<String, String> = try {
    val obj = JSONObject(raw)
    obj.keys().asSequence().associateWith { obj.optString(it) }
} catch (e: Exception) { emptyMap() }

private fun parseSnapchatExport(html: String): List<RequestEntry> {
    val doc = Jsoup.parse(html)
    val headings = doc.select("h1,h2,h3,h4,h5")
    val titleKeywords = listOf(
        "gönderilen arkadaşlık istekleri", "sent friend requests",
        "friend requests sent", "outgoing friend requests",
        "demandes d’amis envoyées", "demandes d'amis envoyées",
        "gesendete freundschaftsanfragen", "solicitudes de amistad enviadas"
    )
    val heading = headings.firstOrNull { element ->
        val title = element.text().trim().lowercase(Locale.ROOT)
        titleKeywords.any { title.contains(it) }
    } ?: return emptyList()

    // Snapchat exports can place explanatory text between a heading and its table.
    // Find the first table before the next section heading, rather than assuming
    // that the table is the heading's immediate sibling.
    var sibling = heading.nextElementSibling()
    var table: org.jsoup.nodes.Element? = null
    while (sibling != null) {
        if (sibling.tagName().matches(Regex("h[1-5]"))) break
        table = if (sibling.tagName() == "table") sibling else sibling.selectFirst("table")
        if (table != null) break
        sibling = sibling.nextElementSibling()
    }
    val rows = table?.select("tr") ?: return emptyList()
    return rows.mapNotNull { row ->
        val cells = row.select("td")
        if (cells.size < 2) null else {
            val username = cells[0].text().trim().removePrefix("@")
            // Avoid strict Snapchat username regex here: historical export data
            // may include usernames that no longer satisfy current rules.
            if (username.isBlank() || username.length > 80) null
            else RequestEntry(username, cells[1].text(), cells.getOrNull(2)?.text() ?: "")
        }
    }.distinctBy { it.username.lowercase(Locale.ROOT) }
}
