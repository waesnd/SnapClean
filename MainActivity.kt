package com.snapclean.app

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import org.jsoup.Jsoup

private data class FriendRequest(val username: String, val displayName: String, val date: String, val status: String)

private class Store(context: Context) : SQLiteOpenHelper(context, "snapclean.db", null, 1) {
    override fun onCreate(db: SQLiteDatabase) { db.execSQL("CREATE TABLE requests(username TEXT PRIMARY KEY, display TEXT NOT NULL, created TEXT NOT NULL, status TEXT NOT NULL DEFAULT 'Kontrol edilmedi')") }
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {}
    fun importHtml(html: String): Int {
        val doc = Jsoup.parse(html)
        val heading = doc.select("h3").firstOrNull { it.text().trim() == "Gönderilen Arkadaşlık İstekleri" } ?: error("Gönderilen istekler bölümü bulunamadı")
        val table = heading.nextElementSibling()?.takeIf { it.tagName() == "table" } ?: error("İstek tablosu bulunamadı")
        val db = writableDatabase
        var count = 0
        db.beginTransaction()
        try {
            table.select("tr").drop(1).forEach { row ->
                val cells = row.select("td")
                if (cells.size >= 3) {
                    val username = cells[0].text().trim()
                    if (username.isNotEmpty()) {
                        val stmt = db.compileStatement("INSERT OR IGNORE INTO requests(username,display,created,status) VALUES(?,?,?,'Kontrol edilmedi')")
                        stmt.bindString(1, username); stmt.bindString(2, cells[1].text()); stmt.bindString(3, cells[2].text())
                        if (stmt.executeInsert() != -1L) count++
                        stmt.close()
                    }
                }
            }
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
        return count
    }
    fun search(query: String, filter: String): List<FriendRequest> {
        val where = mutableListOf<String>(); val args = mutableListOf<String>()
        if (query.isNotBlank()) { where += "(username LIKE ? OR display LIKE ?)"; args += "%$query%"; args += "%$query%" }
        if (filter != "Tümü") { where += "status=?"; args += filter }
        val sql = "SELECT username,display,created,status FROM requests" + (if (where.isEmpty()) "" else " WHERE " + where.joinToString(" AND ")) + " ORDER BY created DESC LIMIT 10000"
        return readableDatabase.rawQuery(sql, args.toTypedArray()).use { c ->
            buildList { while (c.moveToNext()) add(FriendRequest(c.getString(0),c.getString(1),c.getString(2),c.getString(3))) }
        }
    }
    fun count(): Int = readableDatabase.rawQuery("SELECT COUNT(*) FROM requests", null).use { it.moveToFirst(); it.getInt(0) }
    fun setStatus(username: String, status: String) { writableDatabase.execSQL("UPDATE requests SET status=? WHERE username=?", arrayOf(status,username)) }
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val store = Store(this)
        setContent {
            val context = LocalContext.current
            var query by remember { mutableStateOf("") }
            var filter by remember { mutableStateOf("Tümü") }
            var refresh by remember { mutableIntStateOf(0) }
            var message by remember { mutableStateOf("HTML dosyanı içe aktar") }
            val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
                if (uri != null) {
                    try {
                        val html = context.contentResolver.openInputStream(uri)!!.bufferedReader(Charsets.UTF_8).use { it.readText() }
                        val count = store.importHtml(html)
                        message = "$count yeni kayıt eklendi"; refresh++
                    } catch (e: Exception) { message = "İçe aktarma hatası: ${e.message}" }
                }
            }
            val rows = remember(query, filter, refresh) { store.search(query, filter) }
            MaterialTheme(colorScheme = darkColorScheme()) {
                Scaffold(topBar = {}) { pad ->
                    Column(Modifier.padding(pad).fillMaxSize().padding(16.dp)) {
                        Text("SnapClean", style = MaterialTheme.typography.headlineMedium)
                        Spacer(Modifier.height(8.dp))
                        Text("${store.count()} kayıt • Tamamen cihazında")
                        Spacer(Modifier.height(12.dp))
                        Button(onClick = { picker.launch(arrayOf("text/html", "text/*", "*/*")) }) { Text("friends.html içe aktar") }
                        Text(message, style = MaterialTheme.typography.bodySmall)
                        Spacer(Modifier.height(12.dp))
                        OutlinedTextField(value = query, onValueChange = { query = it }, label = { Text("Kullanıcı adı veya görünen ad") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                        Spacer(Modifier.height(8.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            listOf("Tümü", "Kontrol edilmedi", "İptal edildi").forEach { option ->
                                FilterChip(selected = filter == option, onClick = { filter = option }, label = { Text(option, style = MaterialTheme.typography.labelSmall) })
                            }
                        }
                        Text("${rows.size} sonuç", style = MaterialTheme.typography.labelSmall)
                        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            items(rows, key = { it.username }) { request ->
                                Card(Modifier.fillMaxWidth()) {
                                    Column(Modifier.padding(12.dp)) {
                                        Text(request.displayName.ifBlank { request.username }, style = MaterialTheme.typography.titleMedium)
                                        Text("@${request.username} • ${request.date}", style = MaterialTheme.typography.bodySmall)
                                        Text("Durum: ${request.status}", style = MaterialTheme.typography.bodySmall)
                                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                            TextButton(onClick = {
                                                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                                clipboard.setPrimaryClip(ClipData.newPlainText("Snapchat kullanıcı adı", request.username))
                                                message = "@${request.username} kopyalandı"
                                            }) { Text("Kopyala") }
                                            TextButton(onClick = { store.setStatus(request.username,"İptal edildi"); refresh++ }) { Text("İptal edildi") }
                                            TextButton(onClick = { store.setStatus(request.username,"Kontrol edilmedi"); refresh++ }) { Text("Geri al") }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
