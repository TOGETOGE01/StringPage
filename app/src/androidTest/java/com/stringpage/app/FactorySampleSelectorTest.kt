@file:OptIn(kotlin.contracts.ExperimentalContracts::class, ExperimentalUnsignedTypes::class)

package com.stringpage.app

import alphaTab.Settings
import alphaTab.importer.AlphaTexImporter
import alphaTab.model.DynamicValue
import alphaTab.model.HarmonicType
import com.stringpage.app.rse.*
import org.junit.Assert.*
import org.junit.Test

class FactorySampleSelectorTest {
    private fun score() = AlphaTexImporter().run {
        initFromString(". 0.6.4 0.5.4 r.4 r.4", Settings()); readScore()
    }
    private fun wave(name: String, pickup: String = "none", repitch: Double = 0.0, ratio: Double = 1.0) =
        RseWave(pickup,name,repitch,0.0,BankEntry(name,0,0),ratio)
    private fun sample(name: String, dynamic: String = "mf", path: List<String> = listOf("pick","sustain","down"), midi: Int = 40) =
        RseSample(path,midi,1,dynamic,"none",listOf(wave(name)))
    private fun bank(vararg samples: RseSample) = RseSoundBank("test","","steelGuitar","",listOf(40,45,50,55,59,64),samples.toList())
    private fun bind(score: alphaTab.model.Score, style: String?) {
        GpRseProperties.bind(score,GpRseProperties.Properties(GpRseProperties.Container.Zip,
            listOf(GpRseProperties.TrackInfo("0",null,null,style,style?.let(GpRseProperties::playingStyleCore),emptyList())),true))
    }

    @Test fun actualPlaybackSelectorUsesRseMfAndFInsteadOfPreviewDynamicZero() {
        val score=score();val selector=FactorySampleSelector(bank(sample("mf"),sample("f","f")))
        val beat=score.tracks[0].staves[0].bars[0].voices[0].beats[0]
        beat.dynamics=DynamicValue.MF
        assertEquals("mf",selector.select(ScorePerformance.compile(score).notes.first()).wave.file)
        beat.dynamics=DynamicValue.F
        assertEquals("f",selector.select(ScorePerformance.compile(score).notes.first()).wave.file)
        assertEquals(0,FactorySampleSelector.dynamicIndex("sfz"))
    }

    @Test fun importedPlayingStyleSelectsFingerThumbAndExplicitPickOverridesIt() {
        val score=score();bind(score,"stringedfinger")
        val selector=FactorySampleSelector(bank(sample("pick"),sample("finger",path=listOf("finger","sustain","thumb"))))
        var event=ScorePerformance.compile(score).notes.first()
        assertEquals("finger",selector.select(event).wave.file)
        assertFalse(selector.select(event).substitutedArticulation)
        event.sourceNote!!.beat.pickStroke=alphaTab.model.PickStroke.Down
        event=ScorePerformance.compile(score).notes.first()
        assertEquals("pick",selector.select(event).wave.file)
    }

    @Test fun scoreBridgePreservesLowToHighStringsCapoAndChannelTranspose() {
        val score=score();val staff=score.tracks[0].staves[0];staff.capo=2.0
        val events=ScorePerformance.compile(score).notes
        val first=FactoryNoteAdapter.adapt(events[0].copy(midi=events[0].midi+3,channelTransposition=3),true).request.coordinate
        val second=FactoryNoteAdapter.adapt(events[1],true).request.coordinate
        assertEquals(0,first.stringIndex);assertEquals(2,first.fret);assertEquals(45,first.midi)
        assertEquals(1,second.stringIndex);assertEquals(2,second.fret);assertEquals(47,second.midi)
    }

    @Test fun harmonicUsesRecoveredSampleMidiAndLastAuthoredWaveCorrections() {
        val score=score();val note=score.tracks[0].staves[0].bars[0].voices[0].beats[0].notes[0]
        note.harmonicType=HarmonicType.Natural;note.harmonicValue=12.0
        val source=sample("old",path=listOf("harmonic","natural","12th"),midi=999).copy(waves=listOf(
            wave("old","bridge",5.0,1.0),wave("replacement","bridge",30.0,2.0),wave("last","neck",50.0,3.0)))
        val event=ScorePerformance.compile(score).notes.first()
        val selected=FactorySampleSelector(bank(source)).select(event)
        assertEquals(52,selected.nativeMidi);assertEquals("replacement",selected.wave.file)
        assertEquals(.5f,selected.pitchCorrection,0f);assertEquals(3f,selected.amplitudeCorrection,0f)
        val request=FactoryNoteAdapter.adapt(event,true).request
        assertEquals(NativeSampleTree.Articulation(2,2,8),request.articulation)
        assertEquals(9,request.coordinate.dynamic)
    }

    @Test fun failedSourcePropertiesCannotSilentlyEnterFactoryPlayback() {
        val score=score();GpRseProperties.bindFailure(score,"broken GPIF")
        try { FactorySampleSelector(bank(sample("a"))).select(ScorePerformance.compile(score).notes.first());fail("Missing native source accepted") }
        catch(_:IllegalArgumentException) { }
    }

    @Test fun accentAndGhostAffectSelectionBeforeNativeDynamicDeltaCompensation() {
        val score=score();val note=score.tracks[0].staves[0].bars[0].voices[0].beats[0].notes[0]
        note.beat.dynamics=DynamicValue.MF
        val selector=FactorySampleSelector(bank(sample("mf"),sample("f","f")))
        fun select()=selector.select(ScorePerformance.compile(score).notes.first())
        assertEquals("mf",select().wave.file);assertEquals(1f,select().dynamicCorrection,0f)
        note.accentuated=alphaTab.model.AccentuationType.Normal
        assertEquals("f",select().wave.file);assertEquals(1f,select().dynamicCorrection,0f)
        note.accentuated=alphaTab.model.AccentuationType.Heavy
        assertEquals("f",select().wave.file);assertEquals(2.15f,select().dynamicCorrection,0f)
        note.accentuated=alphaTab.model.AccentuationType.None;note.isGhost=true
        assertEquals("mf",select().wave.file);assertEquals(1f/2.15f,select().dynamicCorrection,.0000001f)
    }
}
