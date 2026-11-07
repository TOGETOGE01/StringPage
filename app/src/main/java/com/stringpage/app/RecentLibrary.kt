package com.stringpage.app

import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

/** URI bookmarks only. Removing an entry never opens or deletes its source document. */
class RecentLibrary(private val preferences: SharedPreferences) {
    data class Entry(val uri: String, val name: String, val fileName: String = "")

    var collapsed: Boolean
        get() = preferences.getBoolean("recentCollapsed", false)
        set(value) { preferences.edit().putBoolean("recentCollapsed", value).apply() }

    @Synchronized fun entries(): List<Entry> {
        val data = runCatching { JSONArray(preferences.getString("recent", "[]")) }.getOrElse { JSONArray() }
        val seen = HashSet<String>()
        return buildList {
            for (index in 0 until data.length()) {
                val item = data.optJSONObject(index) ?: continue
                val uri = item.optString("uri").takeIf { it.isNotBlank() } ?: continue
                if (!seen.add(uri)) continue
                add(Entry(uri, item.optString("name").ifBlank { "本地乐谱" }, item.optString("fileName")))
            }
        }
    }

    /** Keep the entire import, in selection order, followed by older distinct entries. */
    @Synchronized fun addBatch(incoming: List<Entry>) {
        if (incoming.isEmpty()) return
        val valid = incoming.filter { it.uri.isNotBlank() }.distinctBy { it.uri }
        val uris = valid.mapTo(HashSet()) { it.uri }
        write(valid + entries().filter { it.uri !in uris })
    }

    @Synchronized fun remove(uris: Set<String>) {
        val edit = preferences.edit()
        val remaining = entries().filter { it.uri !in uris }
        edit.putString("recent", json(remaining))
        if (preferences.getString("lastUri", null) in uris) edit.remove("lastUri")
        edit.apply()
    }

    @Synchronized fun clear() {
        preferences.edit().putString("recent", "[]").remove("lastUri").apply()
    }

    private fun write(entries: List<Entry>) { preferences.edit().putString("recent", json(entries)).apply() }
    private fun json(entries: List<Entry>) = JSONArray().apply {
        entries.forEach { put(JSONObject().put("uri", it.uri).put("name", it.name).put("fileName", it.fileName)) }
    }.toString()
}
