package com.stringpage.app

import androidx.test.platform.app.InstrumentationRegistry
import com.stringpage.app.rse.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class RseNoteCoordinateTest {
    private fun base() = RseNoteCoordinate.RawInput(RseNoteCoordinate.Kind.Stringed,44,
        primaryStringedMode=true,stringIndex=0,beatDynamic=10,
        soundingMidiWithoutHarmonic=46,tuningOpenMidi=40,tuningMidiAtDerivedFret=46,
        soundingMidiWithHarmonic=64)

    @Test fun pureEnumHelpersReplayOriginalGPCoreAndGprseGetterResults() {
        val fixture=JSONObject(InstrumentationRegistry.getInstrumentation().context.assets
            .open("rse-note-coordinate-enums.json").bufferedReader().use { it.readText() })
        for(name in listOf("fretEnums","fretFromFloat")) {
            val rows=fixture.getJSONArray(name)
            for(i in 0 until rows.length()) {
                val row=rows.getJSONObject(i);val e=row.getInt("enum")
                val value=if(name=="fretEnums") row.getDouble("float") else row.getDouble("value")
                assertEquals(e,RseNoteCoordinate.harmonicFretFromFloat(value.toFloat()))
                assertEquals(row.getInt("variation"),RseNoteCoordinate.harmonicVariation(e))
            }
        }
        val types=fixture.getJSONArray("harmonicTypeTechnique")
        for(i in 0 until types.length()) { val row=types.getJSONObject(i)
            assertEquals(row.getInt("technique"),RseNoteCoordinate.harmonicTechnique(row.getInt("type"))) }
        val dynamics=fixture.getJSONArray("dynamics")
        for(i in 0 until dynamics.length()) { val row=dynamics.getJSONObject(i)
            assertEquals(row.getInt("value"),RseNoteCoordinate.dynamicFromString(row.getString("text"))) }
        val unpitched=fixture.getJSONArray("unpitched")
        for(i in 0 until unpitched.length()) { val row=unpitched.getJSONObject(i)
            assertEquals(row.getInt("value"),RseNoteCoordinate.unpitchedEnum(row.getString("text"),row.getInt("axis"))) }
        assertEquals(0,RseNoteCoordinate.rightHandFingeringFromAlphaTabValue(-2))
        assertEquals(0,RseNoteCoordinate.rightHandFingeringFromAlphaTabValue(-1))
        for(i in 0..4) assertEquals(i+1,RseNoteCoordinate.rightHandFingeringFromAlphaTabValue(i))
    }

    @Test fun stringedConstructionPreservesObservedFlagOrderCapoAndHarmonicDefaults() {
        val normal=RseNoteCoordinate.build(base())
        assertEquals(SampleCoordinate(0,46,10,0,6),normal.coordinate)
        assertEquals(NativeSampleTree.Articulation(0,0,0),normal.articulation)
        val alternate=RseNoteCoordinate.build(base().copy(playingStyle=1,beatIndex=0))
        assertEquals(1,alternate.articulation.variation)
        assertEquals(0,RseNoteCoordinate.build(base().copy(playingStyle=1,beatIndex=1)).articulation.variation)
        val harmonic=RseNoteCoordinate.build(base().copy(playingStyle=4,pickStroked=true,pickDirection=1,
            slapped=true,popped=true,harmonic=true,harmonicType=0,harmonicFretEnum=3,midiTransposition=-2))
        assertEquals(NativeSampleTree.Articulation(2,2,5),harmonic.articulation)
        assertEquals(SampleCoordinate(0,62,9,0,6),harmonic.coordinate)
        assertEquals(4,RseNoteCoordinate.build(base().copy(slapped=true,popped=true)).articulation.attack)
        assertEquals(3,RseNoteCoordinate.build(base().copy(popped=true)).articulation.attack)
        assertEquals(NativeSampleTree.Articulation(0,4,0),RseNoteCoordinate.build(base().copy(playingStyle=3)).articulation)
        assertEquals(NativeSampleTree.Articulation(1,0,2),RseNoteCoordinate.build(base().copy(playingStyle=3,stringIndex=4)).articulation)
    }

    @Test fun pitchedUnpitchedDelegateAndAlternateStringedPathsRemainDistinct() {
        val p=RseNoteCoordinate.build(RseNoteCoordinate.RawInput(RseNoteCoordinate.Kind.Pitched,60,midiTransposition=12,beatDynamic=7))
        assertEquals(SampleCoordinate(-1,72,7,0,-1),p.coordinate)
        val n=RseNoteCoordinate.build(RseNoteCoordinate.RawInput(RseNoteCoordinate.Kind.NonPitched,42,beatDynamic=10,
            nonPitchedRseSound=listOf("stick","hit","closed")))
        assertEquals(NativeSampleTree.Articulation(0,0,11),n.articulation)
        assertEquals(SampleCoordinate(-1,-1,10,0,-1),n.coordinate)
        val delegate=RseNoteCoordinate.Request(NativeSampleTree.Articulation(8,3,13),SampleCoordinate(2,77,1,5,12))
        assertEquals(delegate,RseNoteCoordinate.build(RseNoteCoordinate.RawInput(RseNoteCoordinate.Kind.NonPitched,42,delegate=delegate)))
        val overridden=RseNoteCoordinate.build(RseNoteCoordinate.RawInput(RseNoteCoordinate.Kind.Pitched,60,beatDynamic=9,delegate=delegate))
        assertEquals(delegate.articulation,overridden.articulation)
        assertEquals(delegate.coordinate.copy(midi=60,dynamic=9),overridden.coordinate)
        val alternate=RseNoteCoordinate.build(base().copy(primaryStringedMode=false,midiTransposition=1))
        assertEquals(NativeSampleTree.Articulation(7,0,10),alternate.articulation)
        assertEquals(SampleCoordinate(0,45,10,0,-1),alternate.coordinate)
        try { RseNoteCoordinate.build(base().copy(primaryStringedMode=null));fail("Unresolved mode was inferred") }
        catch(_:IllegalArgumentException) { }
    }
}
