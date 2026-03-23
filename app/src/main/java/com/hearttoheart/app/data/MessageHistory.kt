package com.hearttoheart.app.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import org.json.JSONArray
import org.json.JSONObject

private val Context.messageHistoryDataStore: DataStore<Preferences> by preferencesDataStore(name = "message_history")

/**
 * Represents a stored message (sent or received).
 */
data class StoredMessage(
    val category: MessageCategory,
    val note: String,
    val timestamp: Long,
    val isSent: Boolean  // true = sent by me, false = received
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("category", category.name)
        put("note", note)
        put("timestamp", timestamp)
        put("isSent", isSent)
    }
    
    companion object {
        fun fromJson(json: JSONObject): StoredMessage? {
            return try {
                StoredMessage(
                    category = MessageCategory.valueOf(json.getString("category")),
                    note = json.optString("note", ""),
                    timestamp = json.getLong("timestamp"),
                    isSent = json.getBoolean("isSent")
                )
            } catch (e: Exception) {
                null
            }
        }
    }
}

/**
 * Local storage for message history using DataStore.
 * Stores messages as JSON array in preferences.
 */
class MessageHistory(private val context: Context) {
    
    companion object {
        private val MESSAGES_KEY = stringPreferencesKey("messages")
        private const val MAX_MESSAGES = 50  // Keep last 50 messages
    }
    
    /**
     * Save a message to history.
     */
    suspend fun saveMessage(message: StoredMessage) {
        context.messageHistoryDataStore.edit { prefs ->
            val existing = prefs[MESSAGES_KEY]?.let { parseMessages(it) } ?: emptyList()
            val updated = (existing + message).takeLast(MAX_MESSAGES)
            prefs[MESSAGES_KEY] = serializeMessages(updated)
        }
    }
    
    /**
     * Get all messages as a Flow.
     */
    fun getMessages(): Flow<List<StoredMessage>> {
        return context.messageHistoryDataStore.data.map { prefs ->
            prefs[MESSAGES_KEY]?.let { parseMessages(it) } ?: emptyList()
        }
    }
    
    /**
     * Get the most recent received message.
     */
    fun getLastReceivedMessage(): Flow<StoredMessage?> {
        return getMessages().map { messages ->
            messages.filter { !it.isSent }.maxByOrNull { it.timestamp }
        }
    }
    
    /**
     * Get the most recent sent message.
     */
    fun getLastSentMessage(): Flow<StoredMessage?> {
        return getMessages().map { messages ->
            messages.filter { it.isSent }.maxByOrNull { it.timestamp }
        }
    }
    
    /**
     * Clear all message history.
     */
    suspend fun clearHistory() {
        context.messageHistoryDataStore.edit { prefs ->
            prefs.remove(MESSAGES_KEY)
        }
    }
    
    private fun parseMessages(json: String): List<StoredMessage> {
        return try {
            val array = JSONArray(json)
            (0 until array.length()).mapNotNull { i ->
                StoredMessage.fromJson(array.getJSONObject(i))
            }
        } catch (e: Exception) {
            emptyList()
        }
    }
    
    private fun serializeMessages(messages: List<StoredMessage>): String {
        val array = JSONArray()
        messages.forEach { array.put(it.toJson()) }
        return array.toString()
    }
}
