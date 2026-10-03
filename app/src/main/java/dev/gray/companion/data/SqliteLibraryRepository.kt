package dev.gray.companion.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import dev.gray.companion.core.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer

/** Resource records stay indexed by type and stable ID; sessions are independent records. */
class SqliteLibraryRepository(context: Context) : LibraryRepository {
    private val helper = Db(context.applicationContext)
    private val json = LibraryFormat.json
    private data class EncodedRecord(val value: Any, val payload: String)
    private var persistedRecords: Map<Pair<String, String>, Pair<Int, String>>? = null
    private var encodedRecords = emptyMap<Pair<String, String>, EncodedRecord>()

    override suspend fun load(): Library = withContext(Dispatchers.IO) {
        val db = helper.readableDatabase
        val rows = mutableMapOf<String, MutableList<String>>()
        val persisted = mutableMapOf<Pair<String, String>, Pair<Int, String>>()
        db.rawQuery("SELECT kind,payload,id,ordinal FROM records ORDER BY ordinal,rowid", null).use { cursor ->
            while (cursor.moveToNext()) {
                rows.getOrPut(cursor.getString(0)) { mutableListOf() }.add(cursor.getString(1))
                persisted[cursor.getString(0) to cursor.getString(2)] = cursor.getInt(3) to cursor.getString(1)
            }
        }
        val meta = mutableMapOf<String, String>()
        db.rawQuery("SELECT key,value FROM metadata", null).use { cursor ->
            while (cursor.moveToNext()) meta[cursor.getString(0)] = cursor.getString(1)
        }
        val schema = meta["schema"]?.toIntOrNull() ?: if (rows.isEmpty()) 1 else error("Library metadata is damaged")
        val known = setOf("character", "main_call", "package", "group", "profile", "session", "connection", "chat_folder", "saved_chat", "persona", "prompt_tag", "config_edit")
        require(rows.keys.all { it in known }) { "Unknown resource record type" }
        val decoded = Library(
            schemaVersion = schema,
            characters = decode(rows["character"], CharacterCard.serializer()),
            mainCalls = decode(rows["main_call"], MainCall.serializer()),
            packages = decode(rows["package"], PromptPackage.serializer()),
            groups = decode(rows["group"], CallGroup.serializer()),
            profiles = decode(rows["profile"], ModelProfile.serializer()),
            connections = decode(rows["connection"], ApiConnection.serializer()),
            sessions = decode(rows["session"], Session.serializer()),
            selectedSessionId = meta["selected_session"],
            selectedProfileId = meta["selected_profile"],
            chatFolders = decode(rows["chat_folder"], ChatFolder.serializer()),
            savedChats = decode(rows["saved_chat"], SavedChat.serializer()),
            personas = decode(rows["persona"], UserPersona.serializer()),
            promptTags = decode(rows["prompt_tag"], PromptTag.serializer()),
            selectedPersonaId = meta["selected_persona"],
            reasoningParsing = meta["reasoning_parsing"]?.let { json.decodeFromString(ReasoningParsingConfig.serializer(), it) } ?: ReasoningParsingConfig(),
            configurationEdits = decode(rows["config_edit"], ConfigurationEditReceipt.serializer()),
            user = meta["user"]?.let { json.decodeFromString(UserIdentity.serializer(), it) } ?: UserIdentity(),
            titleConfig = meta["title_config"]?.let { json.decodeFromString(ChatTitleConfig.serializer(), it) } ?: ChatTitleConfig()
        )
        val library = LibraryFormat.upgrade(decoded)
        val encoded = mutableMapOf<Pair<String, String>, EncodedRecord>()
        fun <T : Any> remember(kind: String, records: List<T>, id: (T) -> String) {
            records.forEach { record ->
                val key = kind to id(record)
                encoded[key] = EncodedRecord(record, persisted.getValue(key).second)
            }
        }
        // Cache the decoded objects: a migrated record must be encoded once on save.
        remember("character", decoded.characters) { it.id }
        remember("main_call", decoded.mainCalls) { it.id }
        remember("package", decoded.packages) { it.id }
        remember("group", decoded.groups) { it.id }
        remember("profile", decoded.profiles) { it.id }
        remember("connection", decoded.connections) { it.id }
        remember("session", decoded.sessions) { it.id }
        remember("chat_folder", decoded.chatFolders) { it.id }
        remember("saved_chat", decoded.savedChats) { it.id }
        remember("persona", decoded.personas) { it.id }
        remember("prompt_tag", decoded.promptTags) { it.id }
        remember("config_edit", decoded.configurationEdits) { it.id }
        persistedRecords = persisted
        encodedRecords = encoded
        library
    }

    private fun <T> decode(rows: List<String>?, serializer: KSerializer<T>): List<T> =
        rows.orEmpty().map { json.decodeFromString(serializer, it) }

    override suspend fun save(library: Library): Unit = withContext(Dispatchers.IO) {
        require(library.schemaVersion == LibraryFormat.CURRENT_VERSION) { "Unsupported library schema" }
        val db = helper.writableDatabase
        val nextEncoded = mutableMapOf<Pair<String, String>, EncodedRecord>()
        val wanted = mutableMapOf<Pair<String, String>, Pair<Int, String>>()
        db.beginTransaction()
        try {
            val previous = persistedRecords ?: buildMap {
                db.rawQuery("SELECT kind,id,ordinal,payload FROM records", null).use { cursor ->
                    while (cursor.moveToNext()) put(cursor.getString(0) to cursor.getString(1), cursor.getInt(2) to cursor.getString(3))
                }
            }
            fun <T : Any> add(kind: String, values: List<T>, serializer: KSerializer<T>, id: (T) -> String) {
                values.forEachIndexed { index, value ->
                    val key = kind to id(value)
                    require(key.second.isNotBlank() && key !in wanted) { "Duplicate or empty resource ID" }
                    val cached = encodedRecords[key]
                    val payload = if (cached?.value === value) cached.payload else json.encodeToString(serializer, value)
                    nextEncoded[key] = EncodedRecord(value, payload)
                    wanted[key] = index to payload
                }
            }
            add("character", library.characters, CharacterCard.serializer()) { it.id }
            add("main_call", library.mainCalls, MainCall.serializer()) { it.id }
            add("package", library.packages, PromptPackage.serializer()) { it.id }
            add("group", library.groups, CallGroup.serializer()) { it.id }
            add("profile", library.profiles, ModelProfile.serializer()) { it.id }
            add("connection", library.connections, ApiConnection.serializer()) { it.id }
            add("session", library.sessions, Session.serializer()) { it.id }
            add("chat_folder", library.chatFolders, ChatFolder.serializer()) { it.id }
            add("saved_chat", library.savedChats, SavedChat.serializer()) { it.id }
            add("persona", library.personas, UserPersona.serializer()) { it.id }
            add("prompt_tag", library.promptTags, PromptTag.serializer()) { it.id }
            add("config_edit", library.configurationEdits, ConfigurationEditReceipt.serializer()) { it.id }
            for ((key, value) in wanted) if (previous[key] != value) {
                if (previous[key]?.second == value.second) {
                    check(db.update("records", ContentValues().apply { put("ordinal", value.first) },
                        "kind=? AND id=?", arrayOf(key.first, key.second)) == 1) { "Record order update failed" }
                    continue
                }
                val cv = ContentValues().apply {
                    put("kind", key.first); put("id", key.second); put("ordinal", value.first); put("payload", value.second)
                }
                check(db.insertWithOnConflict("records", null, cv, SQLiteDatabase.CONFLICT_REPLACE) != -1L) { "Record write failed" }
            }
            for (key in previous.keys - wanted.keys) db.delete("records", "kind=? AND id=?", arrayOf(key.first, key.second))
            putMeta(db, "schema", library.schemaVersion.toString())
            putMeta(db, "selected_session", library.selectedSessionId)
            putMeta(db, "selected_profile", library.selectedProfileId)
            putMeta(db, "selected_persona", library.selectedPersonaId)
            putMeta(db, "user", json.encodeToString(UserIdentity.serializer(), library.user))
            putMeta(db, "title_config", json.encodeToString(ChatTitleConfig.serializer(), library.titleConfig))
            putMeta(db, "reasoning_parsing", json.encodeToString(ReasoningParsingConfig.serializer(), library.reasoningParsing))
            db.setTransactionSuccessful()
        } catch (failure: Exception) {
            persistedRecords = null
            encodedRecords = emptyMap()
            throw failure
        } finally {
            try { db.endTransaction() }
            catch (failure: Exception) {
                persistedRecords = null
                encodedRecords = emptyMap()
                throw failure
            }
        }
        persistedRecords = wanted
        encodedRecords = nextEncoded
    }

    private fun putMeta(db: SQLiteDatabase, key: String, value: String?) {
        if (value == null) db.delete("metadata", "key=?", arrayOf(key))
        else check(db.insertWithOnConflict("metadata", null, ContentValues().apply {
            put("key", key); put("value", value)
        }, SQLiteDatabase.CONFLICT_REPLACE) != -1L) { "Metadata write failed" }
    }

    private class Db(context: Context) : SQLiteOpenHelper(context, "companion-library.db", null, 1) {
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL("CREATE TABLE records(kind TEXT NOT NULL,id TEXT NOT NULL,ordinal INTEGER NOT NULL,payload TEXT NOT NULL,PRIMARY KEY(kind,id))")
            db.execSQL("CREATE INDEX records_kind_order ON records(kind,ordinal)")
            db.execSQL("CREATE TABLE metadata(key TEXT PRIMARY KEY NOT NULL,value TEXT NOT NULL)")
        }
        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int): Unit =
            error("Unsupported database schema $oldVersion")
        override fun onDowngrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int): Unit =
            error("Unsupported database schema $oldVersion")
    }
}
