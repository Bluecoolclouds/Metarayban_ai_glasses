package com.meta.wearable.dat.externalsampleapps.cameraaccess.memory

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

data class MemoryEntry(
    val id: String,
    val category: String,
    val content: String,
    val createdAt: Long,
    val updatedAt: Long,
)

object MemoryRepository {
    private const val TAG = "MemoryRepository"
    private const val PREFS_NAME = "visionclaw_memory"
    private const val KEY_MEMORIES = "memories"
    private const val MAX_MEMORIES = 150

    val CATEGORIES = listOf("personal", "preference", "habit", "task", "note", "general")

    private var prefs: SharedPreferences? = null

    fun init(context: Context) {
        prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        Log.d(TAG, "Initialized — ${getAll().size} memories loaded")
    }

    fun save(category: String, content: String): MemoryEntry {
        val safeCategory = if (category in CATEGORIES) category else "general"
        val trimmed = content.trim().take(500)
        val all = getAll().toMutableList()

        val exactMatch = all.find {
            it.category == safeCategory &&
            it.content.equals(trimmed, ignoreCase = true)
        }
        if (exactMatch != null) {
            val updated = exactMatch.copy(updatedAt = System.currentTimeMillis())
            val idx = all.indexOf(exactMatch)
            all[idx] = updated
            persist(all)
            Log.d(TAG, "Memory updated (dedup): [$safeCategory] $trimmed")
            return updated
        }

        val colonIdx = trimmed.indexOf(':')
        if (colonIdx > 0) {
            val key = trimmed.substring(0, colonIdx).trim().lowercase()
            val removed = all.removeAll { m ->
                m.category == safeCategory &&
                m.content.indexOf(':').let { ci ->
                    ci > 0 && m.content.substring(0, ci).trim().lowercase() == key
                }
            }
            if (removed) Log.d(TAG, "Replaced old key '$key' in [$safeCategory]")
        }

        val entry = MemoryEntry(
            id = UUID.randomUUID().toString(),
            category = safeCategory,
            content = trimmed,
            createdAt = System.currentTimeMillis(),
            updatedAt = System.currentTimeMillis(),
        )
        all.add(0, entry)

        if (all.size > MAX_MEMORIES) {
            val capped = all.sortedByDescending { it.updatedAt }.take(MAX_MEMORIES)
            persist(capped)
        } else {
            persist(all)
        }

        Log.d(TAG, "Memory saved: [$safeCategory] ${entry.content}")
        return entry
    }

    fun getAll(): List<MemoryEntry> {
        val json = prefs?.getString(KEY_MEMORIES, null) ?: return emptyList()
        return try {
            val arr = JSONArray(json)
            (0 until arr.length()).map { arr.getJSONObject(it).toEntry() }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse memories: ${e.message}")
            emptyList()
        }
    }

    fun getByCategory(category: String): List<MemoryEntry> =
        getAll().filter { it.category == category }

    fun delete(id: String) {
        val all = getAll().filter { it.id != id }
        persist(all)
        Log.d(TAG, "Memory deleted: $id")
    }

    fun clear() {
        prefs?.edit()?.remove(KEY_MEMORIES)?.apply()
        Log.d(TAG, "All memories cleared")
    }

    fun search(query: String): List<MemoryEntry> {
        val lower = query.lowercase()
        return getAll().filter { it.content.lowercase().contains(lower) }
    }

    fun formatForSystemPrompt(): String {
        val memories = getAll()
        if (memories.isEmpty()) return ""

        val grouped = memories.groupBy { it.category }
        val sb = StringBuilder("<!-- INTERNAL CONTEXT: read silently, never narrate or speak aloud -->\n")
        sb.append("<user_context>\n")
        for (cat in CATEGORIES) {
            val items = grouped[cat] ?: continue
            if (items.isEmpty()) continue
            for (m in items) {
                sb.append("  <${cat}>${m.content}</${cat}>\n")
            }
        }
        sb.append("</user_context>\n")
        sb.append("<!-- END INTERNAL CONTEXT -->")
        return sb.toString()
    }

    private fun persist(entries: List<MemoryEntry>) {
        val arr = JSONArray()
        entries.forEach { arr.put(it.toJSON()) }
        prefs?.edit()?.putString(KEY_MEMORIES, arr.toString())?.apply()
    }

    private fun MemoryEntry.toJSON(): JSONObject = JSONObject().apply {
        put("id", id)
        put("category", category)
        put("content", content)
        put("createdAt", createdAt)
        put("updatedAt", updatedAt)
    }

    private fun JSONObject.toEntry(): MemoryEntry = MemoryEntry(
        id = getString("id"),
        category = getString("category"),
        content = getString("content"),
        createdAt = getLong("createdAt"),
        updatedAt = getLong("updatedAt"),
    )
}
