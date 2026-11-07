@file:OptIn(kotlin.contracts.ExperimentalContracts::class, ExperimentalUnsignedTypes::class)

package com.stringpage.app.rse

import alphaTab.model.AccentuationType

/** Uses the real RSE parser policy and native-verified tree search. Authored order
 * resolves equal-distance ties; native heap-address order is not portable. */
class FactorySampleSelector(private val bank: RseSoundBank) {
    data class Selection(val sample: RseSample, val wave: RseWave, val substitutedArticulation: Boolean,
        val nativeMidi: Int, val pitchCorrection: Float, val amplitudeCorrection: Float, val dynamicCorrection: Float)
    private val metadata = RseBankMetadata(bank)

    fun select(event: ScorePerformance.NoteEvent): Selection {
        val adapted = FactoryNoteAdapter.adapt(event, metadata.pitched)
        val note = event.sourceNote
        val source = note?.let(GpRseProperties::note)
        val flags = source?.accentFlags ?: when (note?.accentuated) {
            AccentuationType.Heavy -> 4
            AccentuationType.Normal -> 8
            else -> 0
        }
        val antiAccent = source?.antiAccent ?: if (note?.isGhost == true) 2 else 0
        val request = adapted.request.copy(coordinate = adapted.request.coordinate.copy(dynamic =
            NativeSampleGain.adjustedDynamic(adapted.request.coordinate.dynamic,flags,antiAccent)))
        val index = metadata.tree.find(request.articulation, request.coordinate)
            ?: throw IllegalArgumentException("找不到对应的 RSE 采样。")
        val chosen = metadata.samples[index]
        val wave = chosen.waves.firstOrNull { it.pickup == "none" } ?: chosen.waves.first()
        val unresolved = adapted.incompleteSource || chosen.articulation != request.articulation ||
            event.sourceNote?.isDead == true || event.sourceNote?.isPalmMute == true
        return Selection(bank.samples[chosen.sourceIndex], wave, unresolved, chosen.coordinate.midi,
            chosen.pitchCorrection, chosen.amplitudeCorrection,
            NativeSampleGain.dynamicCorrection(request.coordinate.dynamic-chosen.coordinate.dynamic))
    }

    companion object { fun dynamicIndex(text: String): Int = RseBankMetadata.dynamicValue(text) }
}
