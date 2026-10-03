package dev.gray.companion.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import dev.gray.companion.core.ModelUsageRecord
import dev.gray.companion.core.UsageStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Accounting survives a failed/cancelled workflow; it is not part of the session transaction. */
class SqliteUsageRepository(context: Context) {
    private val helper = Db(context.applicationContext)

    suspend fun append(record: ModelUsageRecord): Unit = withContext(Dispatchers.IO) {
        val values = ContentValues().apply {
            put("id", record.id); put("session_id", record.sessionId); put("profile_id", record.profileId); put("model", record.model)
            put("started_at", record.startedAt); put("finished_at", record.finishedAt); put("status", record.status.name)
            put("prompt_tokens", record.promptTokens); put("completion_tokens", record.completionTokens); put("total_tokens", record.totalTokens)
        }
        check(helper.writableDatabase.insertOrThrow("usage", null, values) != -1L) { "Usage write failed" }
    }

    suspend fun query(from: Long, through: Long): List<ModelUsageRecord> = withContext(Dispatchers.IO) {
        helper.readableDatabase.rawQuery("SELECT id,session_id,profile_id,model,started_at,finished_at,status,prompt_tokens,completion_tokens,total_tokens FROM usage WHERE started_at>=? AND started_at<=? ORDER BY started_at DESC", arrayOf(from.toString(), through.toString())).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    fun nullableLong(index: Int): Long? = if (cursor.isNull(index)) null else cursor.getLong(index)
                    add(ModelUsageRecord(cursor.getString(0), cursor.getString(1), cursor.getString(2), cursor.getString(3), cursor.getLong(4), cursor.getLong(5), UsageStatus.valueOf(cursor.getString(6)), nullableLong(7), nullableLong(8), nullableLong(9)))
                }
            }
        }
    }

    fun close() = helper.close()

    private class Db(context: Context) : SQLiteOpenHelper(context, "companion-usage.db", null, 1) {
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL("CREATE TABLE usage(id TEXT PRIMARY KEY NOT NULL,session_id TEXT NOT NULL,profile_id TEXT NOT NULL,model TEXT NOT NULL,started_at INTEGER NOT NULL,finished_at INTEGER NOT NULL,status TEXT NOT NULL,prompt_tokens INTEGER,completion_tokens INTEGER,total_tokens INTEGER)")
            db.execSQL("CREATE INDEX usage_time ON usage(started_at)")
        }
        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = error("Unsupported usage database $oldVersion")
    }
}
