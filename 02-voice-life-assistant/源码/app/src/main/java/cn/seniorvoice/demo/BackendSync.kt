package cn.seniorvoice.demo

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import org.json.JSONObject
import cn.seniorvoice.demo.assist.RecordApiClient
import java.time.Instant
import java.util.UUID
import java.util.concurrent.Executors

data class BackendSettings(val endpoint: String, val token: String, val deviceId: String)

/** A persisted event as seen by the local dashboard. The payload is kept intact
 * so the UI can render useful detail without coupling the database schema to it. */
data class StoredBackendEvent(
    val id: String,
    val payload: JSONObject,
    val createdMs: Long,
    val synced: Boolean
)

/** Durable local history/outbox. Network failures never block phone actions. */
class BackendSync(
    context: Context,
    private val settings: () -> BackendSettings,
    private val onStatus: (String) -> Unit,
    private val onChanged: () -> Unit = {}
) {
    private val worker = Executors.newSingleThreadExecutor()
    private val api = RecordApiClient()
    private val db = object : SQLiteOpenHelper(context.applicationContext, "events.db", null, 1) {
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL("CREATE TABLE events (id TEXT PRIMARY KEY, payload TEXT NOT NULL, created_ms INTEGER NOT NULL, synced INTEGER NOT NULL DEFAULT 0)")
        }
        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
    }

    fun record(type: String, eventId: String, source: String, text: String = "", result: String = "", data: JSONObject = JSONObject()) {
        val configuration = settings()
        val id = UUID.randomUUID().toString()
        val payload = JSONObject()
            .put("v", 1).put("record_id", id).put("event_id", eventId)
            .put("device_id", configuration.deviceId).put("source", source)
            .put("type", type).put("text", text).put("result", result)
            .put("occurred_at", Instant.now().toString()).put("data", data).toString()
        worker.execute {
            runCatching {
                db.writableDatabase.insertOrThrow("events", null, ContentValues().apply {
                    put("id", id); put("payload", payload); put("created_ms", System.currentTimeMillis())
                })
                onChanged()
                flush()
            }.onFailure { onStatus("本地记录失败：${it.message}") }
        }
    }

    fun retry() { worker.execute { runCatching { flush() }.onFailure { onStatus("同步失败：${it.message}") } } }

    /** Read recent local history without blocking the UI thread. */
    fun recent(limit: Int = 40, callback: (List<StoredBackendEvent>) -> Unit) {
        val safeLimit = limit.coerceIn(1, 100)
        worker.execute {
            val result = mutableListOf<StoredBackendEvent>()
            runCatching {
                db.readableDatabase.rawQuery(
                    "SELECT id,payload,created_ms,synced FROM events ORDER BY created_ms DESC LIMIT ?",
                    arrayOf(safeLimit.toString())
                ).use { cursor ->
                    while (cursor.moveToNext()) {
                        val payload = runCatching { JSONObject(cursor.getString(1)) }.getOrNull() ?: continue
                        result += StoredBackendEvent(
                            id = cursor.getString(0),
                            payload = payload,
                            createdMs = cursor.getLong(2),
                            synced = cursor.getInt(3) != 0
                        )
                    }
                }
            }.onFailure { onStatus("读取本地记录失败：${it.message}") }
            callback(result)
        }
    }

    private fun pendingCount(): Long = db.readableDatabase.rawQuery("SELECT COUNT(*) FROM events WHERE synced=0", null).use {
        it.moveToFirst(); it.getLong(0)
    }

    private fun flush() {
        val config = settings()
        if (config.endpoint.isBlank()) {
            onStatus("记录已保存在本机；${pendingCount()} 条等待配置后端")
            return
        }
        val records = mutableListOf<Pair<String, String>>()
        db.readableDatabase.rawQuery("SELECT id,payload FROM events WHERE synced=0 ORDER BY created_ms LIMIT 20", null).use { cursor ->
            while (cursor.moveToNext()) records.add(cursor.getString(0) to cursor.getString(1))
        }
        for ((id, payload) in records) {
            try {
                api.post(config, id, payload)
                db.writableDatabase.execSQL("UPDATE events SET synced=1 WHERE id=?", arrayOf(id))
                onChanged()
            } catch (error: Exception) {
                onStatus("${pendingCount()} 条记录待上传：${error.message}")
                return
            }
        }
        val remaining = pendingCount()
        onStatus(if (remaining == 0L) "记录已同步到后端" else "已同步一批，剩余 $remaining 条，下次继续")
    }

    fun close() {
        worker.execute { db.close() }
        worker.shutdown()
    }
}
