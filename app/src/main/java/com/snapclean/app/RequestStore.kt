package com.snapclean.app

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import org.json.JSONArray
import org.json.JSONObject

class RequestStore(context: Context) : SQLiteOpenHelper(context, "snapclean.db", null, 1) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE requests (username TEXT PRIMARY KEY COLLATE NOCASE, display TEXT NOT NULL, date TEXT NOT NULL, status TEXT NOT NULL DEFAULT '')")
        db.execSQL("CREATE INDEX requests_date ON requests(date)")
    }
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {}

    fun all(): List<RequestEntry> {
        val result = mutableListOf<RequestEntry>()
        readableDatabase.rawQuery("SELECT username, display, date FROM requests ORDER BY rowid", null).use { c ->
            while (c.moveToNext()) result.add(RequestEntry(c.getString(0), c.getString(1), c.getString(2)))
        }
        return result
    }
    fun statuses(): Map<String, String> {
        val result = mutableMapOf<String, String>()
        readableDatabase.rawQuery("SELECT username, status FROM requests WHERE status != ''", null).use { c ->
            while (c.moveToNext()) result[c.getString(0)] = c.getString(1)
        }
        return result
    }
    fun upsert(rows: List<RequestEntry>) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            for (entry in rows) {
                val v = ContentValues().apply {
                    put("username", entry.username)
                    put("display", entry.display)
                    put("date", entry.date)
                }
                db.insertWithOnConflict("requests", null, v, SQLiteDatabase.CONFLICT_IGNORE)
            }
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }
    fun status(username: String, status: String) {
        val v = ContentValues().apply { put("status", status) }
        writableDatabase.update("requests", v, "username = ? COLLATE NOCASE", arrayOf(username))
    }
    fun bulkStatus(usernames: List<String>, status: String) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            val stmt = db.compileStatement("UPDATE requests SET status = ? WHERE username = ? COLLATE NOCASE")
            for (username in usernames) {
                stmt.clearBindings()
                stmt.bindString(1, status)
                stmt.bindString(2, username)
                stmt.executeUpdateDelete()
            }
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }
    fun clear() { writableDatabase.delete("requests", null, null) }

    fun backup(): String {
        val statuses = statuses()
        val rows = JSONArray()
        for (entry in all()) rows.put(JSONObject()
            .put("username", entry.username).put("display", entry.display)
            .put("date", entry.date).put("status", statuses[entry.username] ?: ""))
        return JSONObject().put("format", "snapclean-backup").put("version", 1).put("entries", rows).toString(2)
    }
    fun restore(json: String): Int {
        val obj = JSONObject(json)
        require(obj.optString("format") == "snapclean-backup" && obj.optInt("version") == 1) { "Unsupported backup format" }
        val array = obj.getJSONArray("entries")
        require(array.length() <= 200000) { "Backup too large" }
        val db = writableDatabase
        db.beginTransaction()
        try {
            for (i in 0 until array.length()) {
                val item = array.getJSONObject(i)
                val username = item.getString("username")
                require(Regex("^[A-Za-z][A-Za-z0-9._-]{2,29}$").matches(username)) { "Invalid username" }
                val status = item.optString("status")
                require(status in listOf("", "done", "skipped")) { "Invalid status" }
                val v = ContentValues().apply {
                    put("username", username)
                    put("display", item.optString("display").take(500))
                    put("date", item.optString("date").take(100))
                    put("status", status)
                }
                db.insertWithOnConflict("requests", null, v, SQLiteDatabase.CONFLICT_REPLACE)
            }
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
        return array.length()
    }
}
