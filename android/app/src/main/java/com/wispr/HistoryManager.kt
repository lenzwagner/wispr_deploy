package com.wispr

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

data class HistoryItem(
    val id: String,
    val text: String,
    val timestamp: Long
)

class HistoryManager(context: Context) {
    private val prefs = context.getSharedPreferences("wispr_prefs", Context.MODE_PRIVATE)
    private val key = "dictation_history"

    fun saveItem(text: String) {
        val items = getHistory().toMutableList()
        val newItem = HistoryItem(
            id = UUID.randomUUID().toString(),
            text = text,
            timestamp = System.currentTimeMillis()
        )
        items.add(0, newItem) // Newest first

        // Keep only items from the last 24 hours (86400 * 1000 ms)
        val cutoff = System.currentTimeMillis() - 86400 * 1000
        val filtered = items.filter { it.timestamp > cutoff }

        saveList(filtered)
    }

    fun getHistory(): List<HistoryItem> {
        val jsonString = prefs.getString(key, null) ?: return emptyList()
        try {
            val jsonArray = JSONArray(jsonString)
            val list = mutableListOf<HistoryItem>()
            for (i in 0 until jsonArray.length()) {
                val obj = jsonArray.getJSONObject(i)
                list.add(
                    HistoryItem(
                        id = obj.getString("id"),
                        text = obj.getString("text"),
                        timestamp = obj.getLong("timestamp")
                    )
                )
            }
            // Filter out items older than 24 hours
            val cutoff = System.currentTimeMillis() - 86400 * 1000
            return list.filter { it.timestamp > cutoff }
        } catch (e: Exception) {
            e.printStackTrace()
            return emptyList()
        }
    }

    fun clearHistory() {
        prefs.edit().remove(key).apply()
    }

    private fun saveList(list: List<HistoryItem>) {
        val jsonArray = JSONArray()
        for (item in list) {
            val obj = JSONObject().apply {
                put("id", item.id)
                put("text", item.text)
                put("timestamp", item.timestamp)
            }
            jsonArray.put(obj)
        }
        prefs.edit().putString(key, jsonArray.toString()).apply()
    }
}
