package com.snapclean.app

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.content.ComponentName
import android.view.accessibility.AccessibilityManager
import android.accessibilityservice.AccessibilityServiceInfo
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
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
import java.text.Normalizer
import java.util.zip.ZipInputStream
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream

data class RequestEntry(val username: String, val display: String, val date: String)
private val accent = Color(0xFF67E8D0)
private val background = Color(0xFF0B1220)
private val surface = Color(0xFF162235)
private val usernamePattern = Regex("^[A-Za-z][A-Za-z0-9._-]{2,29}$")

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefs = getSharedPreferences("snapclean_v2", Context.MODE_PRIVATE)
        val store = RequestStore(this)
        if (!prefs.getBoolean("sqlite_migrated", false)) {
            store.upsert(loadEntries(prefs.getString("entries", "[]") ?: "[]"))
            for ((username, status) in loadStatuses(prefs.getString("statuses", "{}") ?: "{}")) store.status(username, status)
            prefs.edit().putBoolean("sqlite_migrated", true).remove("entries").remove("statuses").apply()
        }
        setContent {
            val deviceLanguage = LocalConfiguration.current.locales[0].language
            var language by remember { mutableStateOf(prefs.getString("language", if (deviceLanguage == "tr") "tr" else "en") ?: "en") }
            fun t(en: String, tr: String) = if (language == "tr") tr else en
            var entries by remember { mutableStateOf(store.all()) }
            var statuses by remember { mutableStateOf(store.statuses()) }
            var query by remember { mutableStateOf("") }
            var filter by remember { mutableStateOf("all") }
            var message by remember { mutableStateOf("") }
            var confirmClear by remember { mutableStateOf(false) }
            var importing by remember { mutableStateOf(false) }
            var sort by remember { mutableStateOf("newest") }
            var selected by remember { mutableStateOf(setOf<String>()) }
            var batchSize by remember { mutableIntStateOf(5) }
            var customBatch by remember { mutableStateOf("5") }

            fun refresh() {
                entries = store.all()
                statuses = store.statuses()
            }
            fun saveEntries(newEntries: List<RequestEntry>) {
                store.upsert(newEntries)
                refresh()
            }
            fun setStatus(username: String, status: String) {
                store.status(username, status)
                refresh()
            }
            fun bulkStatus(status: String) {
                store.bulkStatus(selected.toList(), status)
                selected = emptySet()
                refresh()
            }
            val exportBackup = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
                if (uri != null) {
                    try {
                        contentResolver.openOutputStream(uri)?.use { it.write(store.backup().toByteArray(Charsets.UTF_8)) }
                            ?: error("Unable to write backup")
                        message = t("Backup saved", "Yedek kaydedildi")
                    } catch (e: Exception) { message = t("Backup failed: ${e.message}", "Yedekleme hatası: ${e.message}") }
                }
            }
            val importBackup = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
                if (uri != null) {
                    try {
                        val raw = contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
                            ?: error("Unable to read backup")
                        val count = store.restore(raw)
                        refresh()
                        message = t("Restored $count records", "$count kayıt geri yüklendi")
                    } catch (e: Exception) { message = t("Restore failed: ${e.message}", "Geri yükleme hatası: ${e.message}") }
                }
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
                if (uri != null && !importing) {
                    importing = true
                    message = t("Reading file…", "Dosya okunuyor…")
                    Thread {
                        try {
                            val html = contentResolver.openInputStream(uri)?.use { stream ->
                                val input = BufferedInputStream(stream)
                                input.mark(8)
                                val first = input.read()
                                val second = input.read()
                                input.reset()
                                if (first == 0x50 && second == 0x4B) {
                                    var found: String? = null
                                    ZipInputStream(input).use { zip ->
                                        var entry = zip.nextEntry
                                        while (entry != null) {
                                            if (!entry.isDirectory && entry.name.substringAfterLast('/').equals("friends.html", true)) {
                                                val bytes = ByteArrayOutputStream()
                                                val buffer = ByteArray(8192)
                                                var total = 0
                                                while (true) {
                                                    val count = zip.read(buffer)
                                                    if (count < 0) break
                                                    total += count
                                                    if (total > 20_000_000) error("HTML too large")
                                                    bytes.write(buffer, 0, count)
                                                }
                                                found = bytes.toString("UTF-8")
                                                break
                                            }
                                            entry = zip.nextEntry
                                        }
                                    }
                                    found ?: error("friends.html missing from ZIP")
                                } else {
                                    input.bufferedReader(Charsets.UTF_8).use { it.readText() }
                                }
                            } ?: error("Cannot open file")
                            val parsed = parseSnapchatExport(html)
                            val diagnostic = diagnoseSnapchatExport(html)
                            runOnUiThread {
                                if (parsed.isEmpty()) {
                                    message = t("No records found. $diagnostic", "Kayıt bulunamadı. $diagnostic")
                                } else {
                                    saveEntries(parsed)
                                    val merged = entries
                                    message = t(
                                        "Imported ${parsed.size} records; ${merged.size} unique total.",
                                        "${parsed.size} kayıt aktarıldı; toplam ${merged.size} benzersiz kayıt."
                                    )
                                }
                                importing = false
                            }
                        } catch (e: Exception) {
                            runOnUiThread {
                                message = t("Import failed: ${e.message ?: "unknown error"}", "İçe aktarma başarısız: ${e.message ?: "bilinmeyen hata"}")
                                importing = false
                            }
                        }
                    }.start()
                }
            }

            val completed = entries.count { statuses[it.username] == "done" }
            val skipped = entries.count { statuses[it.username] == "skipped" }
            val visible = entries.filter { entry ->
                (entry.username.contains(query, true) || entry.display.contains(query, true)) &&
                    (filter == "all" || (filter == "pending" && statuses[entry.username].isNullOrEmpty()) ||
                        statuses[entry.username] == filter)
            }
            val sortedVisible = when (sort) {
                "oldest" -> visible.sortedBy { it.date }
                "username" -> visible.sortedBy { it.username.lowercase(Locale.ROOT) }
                else -> visible.sortedByDescending { it.date }
            }
            MaterialTheme(
                colorScheme = darkColorScheme(
                    primary = accent, onPrimary = background, background = background,
                    surface = surface, onSurface = Color(0xFFF1F5F9), onBackground = Color(0xFFF1F5F9)
                )
            ) {
                Surface(color = background, modifier = Modifier.fillMaxSize()) {
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
                    Button(onClick = { picker.launch("*/*") }, enabled = !importing, modifier = Modifier.fillMaxWidth()) {
                        Text(if (importing) t("Importing…", "İçe aktarılıyor…") else t("Import Snapchat HTML / ZIP", "Snapchat HTML / ZIP içe aktar"))
                    }
                    Spacer(Modifier.height(8.dp))
                    Row(modifier = Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf(1, 5, 10, 25, 50, 100, 200).forEach { count ->
                            FilterChip(selected = batchSize == count, onClick = { batchSize = count; customBatch = count.toString() }, label = { Text("$count") })
                        }
                    }
                    OutlinedTextField(
                        value = customBatch,
                        onValueChange = { value ->
                            if (value.length <= 3 && value.all { it.isDigit() }) {
                                customBatch = value
                                batchSize = value.toIntOrNull() ?: 0
                            }
                        },
                        label = { Text(t("Custom account limit (1–200)", "Özel hesap limiti (1–200)")) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Text("${selected.size} " + t("accounts selected", "hesap seçildi"), style = MaterialTheme.typography.labelSmall)
                    Button(
                        onClick = {
                            val batch = sortedVisible.filter { it.username in selected && statuses[it.username].isNullOrEmpty() }.take(batchSize)
                            if (batch.isEmpty()) {
                                message = t("Select unreviewed accounts using the checkboxes first.", "Önce kutucuklardan incelenmemiş hesapları seç.")
                            } else {
                                getSharedPreferences("cleanup_batch", Context.MODE_PRIVATE).edit()
                                    .putString("names", batch.joinToString("\n") { it.username })
                                    .putInt("index", 0)
                                    .putBoolean("running", true)
                                    .putLong("session_token", System.nanoTime())
                                    .apply()
                                message = t(
                                    "Experimental batch of ${batch.size} prepared. Enable SnapClean accessibility access if needed.",
                                    "${batch.size} hesaplık deneysel parti hazır. Gerekirse SnapClean erişilebilirlik iznini aç."
                                )
                                val manager = getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
                                val enabled = manager.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
                                    .any { it.resolveInfo.serviceInfo.packageName == packageName &&
                                        it.resolveInfo.serviceInfo.name == CleanupService::class.java.name }
                                if (!enabled) {
                                    message = t("Enable SnapClean accessibility service once, then return and tap the test button.", "SnapClean erişilebilirlik servisini bir kez aç, geri dönüp test düğmesine tekrar bas.")
                                    startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                                } else {
                                    message = t("Accessibility active. Starting ${batch.size}-account test.", "Erişilebilirlik aktif. ${batch.size} hesaplık test başlatılıyor.")
                                }
                            }
                        },
                        enabled = selected.isNotEmpty() && batchSize in 1..200,
                        modifier = Modifier.fillMaxWidth()
                    ) { Text(if (language == "tr") "Seçilen hesapları başlat" else "Start selected batch") }
                    TextButton(onClick = {
                        getSharedPreferences("cleanup_batch", Context.MODE_PRIVATE).edit()
                            .putBoolean("running", false).apply()
                        message = t("Automation stopped", "Otomasyon durduruldu")
                    }) { Text(t("Stop automation", "Otomasyonu durdur")) }
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = query, onValueChange = { query = it },
                        label = { Text(t("Search username", "Kullanıcı adı ara")) },
                        singleLine = true, modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(8.dp))
                    Row(modifier = Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf(
                            "all" to t("All", "Tümü"),
                            "pending" to t("Unreviewed", "İncelenmemiş"),
                            "done" to t("Reviewed", "İncelenen"),
                            "skipped" to t("Skipped", "Atlanan")
                        ).forEach { (key, label) ->
                            FilterChip(selected = filter == key, onClick = { filter = key }, label = { Text(label) })
                        }
                    }
                    Row(modifier = Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = { sort = "newest" }) { Text(t("Newest", "En yeni")) }
                        TextButton(onClick = { sort = "oldest" }) { Text(t("Oldest", "En eski")) }
                        TextButton(onClick = { sort = "username" }) { Text(t("Username A-Z", "Kullanıcı A-Z")) }
                    }
                    Row(modifier = Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = { exportBackup.launch("snapclean-backup.json") }) { Text(t("Backup", "Yedekle")) }
                        TextButton(onClick = { importBackup.launch("application/json") }) { Text(t("Restore", "Geri yükle")) }
                        TextButton(onClick = { selected = sortedVisible.map { it.username }.toSet() }) { Text(t("Select visible", "Görünenleri seç")) }
                    }
                    if (selected.isNotEmpty()) {
                        Row(modifier = Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(t("${selected.size} selected", "${selected.size} seçili"))
                            TextButton(onClick = { bulkStatus("done") }) { Text(t("Review selected", "Seçileni incele")) }
                            TextButton(onClick = { bulkStatus("skipped") }) { Text(t("Skip selected", "Seçileni atla")) }
                            TextButton(onClick = { selected = emptySet() }) { Text(t("Cancel", "İptal")) }
                        }
                    }
                    if (message.isNotBlank()) {
                        Text(message, style = MaterialTheme.typography.bodySmall, color = accent)
                        Spacer(Modifier.height(6.dp))
                    }
                    Text(t("Showing ${visible.size} records", "${visible.size} kayıt gösteriliyor"), style = MaterialTheme.typography.labelSmall)
                    LazyColumn(modifier = Modifier.weight(1f)) {
                        items(sortedVisible, key = { it.username }) { entry ->
                            Column(Modifier.fillMaxWidth().padding(vertical = 12.dp)) {
                                Row {
                                    Checkbox(checked = entry.username in selected, onCheckedChange = { checked ->
                                        selected = if (checked) selected + entry.username else selected - entry.username
                                    })
                                    Column(Modifier.clickable { openProfile(entry.username) }) {
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
                                }
                                }
                                Row(modifier = Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
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
                }
                if (confirmClear) AlertDialog(
                    onDismissRequest = { confirmClear = false },
                    title = { Text(t("Delete local data?", "Yerel veriler silinsin mi?")) },
                    text = { Text(t("All imported records and review statuses will be deleted from this device.", "Aktarılan kayıtlar ve inceleme durumları bu cihazdan silinecek.")) },
                    confirmButton = { TextButton(onClick = {
                        store.clear()
                        refresh()
                        selected = emptySet()
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

private fun normalizeHeading(value: String): String {
    val turkishAware = value.trim().lowercase(Locale.forLanguageTag("tr-TR"))
    return Normalizer.normalize(turkishAware, Normalizer.Form.NFD)
        .replace(Regex("\\p{M}+"), "")
        .replace(Regex("\\s+"), " ")
}

private fun diagnoseSnapchatExport(html: String): String {
    val doc = Jsoup.parse(html)
    val headings = doc.select("h1,h2,h3,h4,h5").map { it.text().take(55) }
    return "HTML chars=${html.length}; tables=${doc.select("table").size}; headings=${headings.take(6).joinToString(" / ")}"
}

internal fun parseSnapchatExport(html: String): List<RequestEntry> {
    val doc = Jsoup.parse(html)
    val keywords = listOf(
        "gönderilen arkadaşlık istekleri", "sent friend requests",
        "friend requests sent", "outgoing friend requests",
        "demandes d’amis envoyées", "demandes d'amis envoyées",
        "gesendete freundschaftsanfragen", "solicitudes de amistad enviadas"
    )
    val heading = doc.select("h1,h2,h3,h4,h5").firstOrNull { element ->
        val normalized = normalizeHeading(element.text())
        keywords.any { normalized.contains(normalizeHeading(it)) }
    }
    val table = if (heading != null) {
        // The actual Snapchat export places the target table immediately after
        // its h3 heading. Skip any non-heading siblings if layout changes.
        var sibling = heading.nextElementSibling()
        var found: org.jsoup.nodes.Element? = null
        while (sibling != null) {
            if (sibling.tagName().matches(Regex("h[1-5]"))) break
            found = if (sibling.tagName() == "table") sibling else sibling.selectFirst("table")
            if (found != null) break
            sibling = sibling.nextElementSibling()
        }
        found
    } else null

    // Some exports may use a different heading tag or translated title.
    // Match table captions/preceding headings but never blindly import all
    // tables, which could mix blocked and removed users into sent requests.
    val selected = table ?: doc.select("table").firstOrNull { candidate ->
        val previous = candidate.previousElementSibling()
        val context = normalizeHeading(previous?.text() ?: "")
        keywords.any { context.contains(normalizeHeading(it)) }
    } ?: return emptyList()

    return selected.select("tr").mapNotNull { row ->
        val cells = row.select("td")
        if (cells.size < 2) null else {
            val username = cells[0].text().trim().removePrefix("@")
            if (username.isBlank() || username.length > 80) null
            else RequestEntry(username, cells[1].text(), cells.getOrNull(2)?.text() ?: "")
        }
    }.distinctBy { it.username.lowercase(Locale.ROOT) }
}
