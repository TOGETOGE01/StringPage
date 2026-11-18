package com.stringpage.app

import android.content.SharedPreferences

internal class GuitarPresetLibrary(private val store: SharedPreferences) {
    fun names(): List<String> = store.all.keys.sortedWith(String.CASE_INSENSITIVE_ORDER)

    fun load(name: String): GuitarAmpSettings? = store.getString(name, null)?.let(GuitarAmpPreferences::decode)

    fun save(name: String, settings: GuitarAmpSettings) {
        require(name.isNotBlank() && name.length <= MAX_NAME_LENGTH)
        val preset = settings.copy(enabled = true, volume = 75, pan = 0)
        store.edit().putString(name, GuitarAmpPreferences.encode(preset)).apply()
    }

    fun remove(name: String) { store.edit().remove(name).apply() }

    companion object { const val MAX_NAME_LENGTH = 40 }
}
