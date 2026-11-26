package com.stringpage.app.pdf

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest

internal data class PdfReviewDraft(
    val page: Int,
    val whole: Boolean,
    val range: IntRange?,
    val bpm: Int?,
    val meter: PdfMeter?,
    val tracks: Set<Int>?,
    val tunings: Map<Int, List<Int>>,
    val corrections: Map<String, String>,
    val confirmed: Set<String>
)

/** Keep corrections separate from GP recovery and from different revisions of the same PDF. */
internal class PdfReviewDrafts(private val context: Context) {
    private fun prefs(id: String) = context.getSharedPreferences("pdf-review-$id", Context.MODE_PRIVATE)

    fun load(id: String): PdfReviewDraft? {
        val stored = prefs(id).getString("draft", null) ?: return null
        return try {
            val json = JSONObject(stored)
            val tunings = json.getJSONObject("tunings")
            val corrections = json.getJSONObject("corrections")
            PdfReviewDraft(
                json.getInt("page"), json.getBoolean("whole"),
                json.optJSONArray("range")?.let { it.getInt(0)..it.getInt(1) },
                json.optInt("bpm", -1).takeIf { it in 20..400 },
                json.optJSONArray("meter")?.let { PdfMeter(it.getInt(0), it.getInt(1)) },
                json.optJSONArray("tracks")?.let { it.ints().toSet() },
                tunings.keys().asSequence().associate { it.toInt() to tunings.getJSONArray(it).ints() },
                corrections.keys().asSequence().associateWith { corrections.getString(it) },
                json.getJSONArray("confirmed").let { list -> (0 until list.length()).map(list::getString).toSet() }
            )
        } catch (_: org.json.JSONException) { null }
    }

    fun save(id: String, state: PdfReviewDraft) {
        val json = JSONObject().apply {
            put("page", state.page)
            put("whole", state.whole)
            state.range?.let { put("range", JSONArray(listOf(it.first, it.last))) }
            state.bpm?.let { put("bpm", it) }
            state.meter?.let { put("meter", JSONArray(listOf(it.beats, it.unit))) }
            state.tracks?.let { put("tracks", JSONArray(it.toList())) }
            put("tunings", JSONObject().apply {
                for ((track, notes) in state.tunings) put(track.toString(), JSONArray(notes))
            })
            put("corrections", JSONObject(state.corrections))
            put("confirmed", JSONArray(state.confirmed.toList()))
        }
        prefs(id).edit().putString("draft", json.toString()).apply()
    }

    private fun JSONArray.ints() = (0 until length()).map(::getInt)

    companion object {
        fun id(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it) }
    }
}
