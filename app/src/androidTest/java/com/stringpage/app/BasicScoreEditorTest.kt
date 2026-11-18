@file:OptIn(kotlin.contracts.ExperimentalContracts::class, ExperimentalUnsignedTypes::class)
package com.stringpage.app

import alphaTab.Settings
import alphaTab.core.ecmaScript.Uint8Array
import alphaTab.importer.ScoreLoader
import alphaTab.model.Duration
import alphaTab.model.Automation
import alphaTab.model.Chord
import alphaTab.model.Score
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.stringpage.app.rse.GpRseProperties
import com.stringpage.app.rse.ScorePerformance
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BasicScoreEditorTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private fun demo() = ScoreLoader.loadScoreFromBytes(Uint8Array(context.assets.open("morning.gp").use { it.readBytes() }.asUByteArray()), Settings())
    private val address = BasicScoreEditor.NoteAddress(0, 0, 0, 0, 0, 0)
    private fun first(score: Score) = score.tracks[0].staves[0].bars[0].voices[0].beats[0].notes[0]

    @Test fun editsOnePianoChordNoteWithoutChangingTheOtherStaffOrChordTone() {
        var score = ScoreStructure.newScore("钢琴触屏编辑", ScoreStructure.Instrument.PIANO, 100, Settings())
        val firstBar = BasicScoreEditor.BeatAddress(0, 0, 0, 0, 0)
        val editor = BasicScoreEditor(context, { score }, { next, _, _ -> score = next })
        assertTrue(editor.putMidiChord(firstBar, listOf(MidiAttack(60, 79), MidiAttack(64, 79))))
        assertTrue(editor.putMidiChord(firstBar.copy(staff = 1), listOf(MidiAttack(48, 79))))
        val selected = address.copy(note = 1)
        assertTrue(editor.updatePitch(selected, 65))
        assertTrue(editor.toggleTechnique(selected, BasicScoreEditor.Technique.Staccato))
        assertFalse(first(score).isStaccato)
        val output = ScoreLoader.loadScoreFromBytes(Uint8Array(ScoreExport.guitarProBytes(score).asUByteArray()), Settings())
        val notes = output.tracks[0].staves[0].bars[0].voices[0].beats[0].notes
        assertEquals(listOf(60.0, 65.0), notes.toList().map { it.realValue })
        assertTrue(notes[1].isStaccato)
        assertEquals(48.0, output.tracks[0].staves[1].bars[0].voices[0].beats[0].notes[0].realValue, 0.0)
        assertTrue(editor.undo())
        assertFalse(score.tracks[0].staves[0].bars[0].voices[0].beats[0].notes[1].isStaccato)
    }

    @Test fun composeTwoTracksAndReopenTheGp() {
        var score = ScoreStructure.newScore("练习小曲", ScoreStructure.Instrument.STEEL, 112, Settings())
        fun reopen() = ScoreLoader.loadScoreFromBytes(Uint8Array(ScoreExport.guitarProBytes(score).asUByteArray()), Settings())
        val source = ScoreExport.guitarProBytes(score)
        score = ScoreLoader.loadScoreFromBytes(Uint8Array(source.asUByteArray()), Settings())
        val originalProperties = GpRseProperties.read(source)
        GpRseProperties.bind(score, originalProperties)
        assertEquals(112.0, score.tempo, 0.0)
        assertTrue(score.tracks[0].staves[0].bars[0].voices[0].beats[0].isRest)
        val editor = BasicScoreEditor(context, { score }, { changed, _, _ -> score = changed })
        assertTrue(editor.addTrack(ScoreStructure.Instrument.BASS))
        assertTrue(editor.appendBar())
        assertTrue(editor.setBarTempo(1, 90))
        assertTrue(editor.setTuning(0, 0, listOf(64, 59, 55, 50, 45, 38), 2))
        assertTrue(editor.putFret(BasicScoreEditor.BeatAddress(0, 0, 0, 0, 0), 1, 2))
        assertTrue(editor.putFret(BasicScoreEditor.BeatAddress(1, 0, 1, 0, 0), 1, 5))
        assertTrue(editor.undo())
        assertTrue(score.tracks[1].staves[0].bars[1].voices[0].beats[0].isRest)
        assertTrue(editor.redo())

        assertEquals(originalProperties.tracks.single(), GpRseProperties.get(score)?.track(0))
        assertEquals(2, GpRseProperties.read(ScoreExport.guitarProBytes(score)).tracks.size)
        val restored = reopen()
        assertEquals("练习小曲", restored.title)
        assertEquals(2, restored.tracks.length.toInt())
        assertEquals(2, restored.masterBars.length.toInt())
        assertEquals(90.0, restored.masterBars[1].tempoAutomation?.value ?: 0.0, 0.0)
        assertEquals(2.0, restored.tracks[0].staves[0].capo, 0.0)
        assertEquals(38.0, restored.tracks[0].staves[0].tuning[5], 0.0)
        assertEquals(42.0, first(restored).realValue, 0.0)
        val bass = restored.tracks[1]
        assertEquals(33.0, bass.playbackInfo.program, 0.0)
        assertEquals(4, bass.staves[0].tuning.length.toInt())
        assertEquals(33.0, bass.staves[0].bars[1].voices[0].beats[0].notes[0].realValue, 0.0)
        assertNotEquals(restored.tracks[0].playbackInfo.primaryChannel, bass.playbackInfo.primaryChannel)
        assertTrue(ScoreExport.midiBytes(restored).isNotEmpty())
    }

    @Test fun appendedBarsKeepTheMeterAndEmbeddedRecording() {
        var score = demo()
        val editor = BasicScoreEditor(context, { score }, { changed, _, _ -> score = changed })
        val audio = score.backingTrack?.rawAudioFile
        val syncCount = score.masterBars.sumOf { it.syncPoints?.length?.toInt() ?: 0 }
        val barCount = score.masterBars.length.toInt()
        score.masterBars[barCount - 1].timeSignatureNumerator = 7.0
        score.masterBars[barCount - 1].timeSignatureDenominator = 8.0
        assertTrue(editor.appendBar())
        assertEquals(barCount + 1, score.masterBars.length.toInt())
        assertEquals(7.0, score.masterBars[barCount].timeSignatureNumerator, 0.0)
        assertEquals(8.0, score.masterBars[barCount].timeSignatureDenominator, 0.0)
        for (track in score.tracks) for (staff in track.staves) {
            assertEquals(listOf(Duration.Half, Duration.Quarter, Duration.Eighth),
                staff.bars[barCount].voices[0].beats.toList().map { it.duration })
        }
        assertSame(audio, score.backingTrack?.rawAudioFile)
        assertEquals(syncCount, score.masterBars.sumOf { it.syncPoints?.length?.toInt() ?: 0 })
        assertTrue(editor.undo())
        assertEquals(barCount, score.masterBars.length.toInt())
    }

    @Test fun insertingAndDeletingBarsKeepsAuthoredNotesAndRecordingAnchors() {
        val bytes = context.assets.open("morning.gp").use { it.readBytes() }
        var score = ScoreLoader.loadScoreFromBytes(Uint8Array(bytes.asUByteArray()), Settings())
        GpRseProperties.bind(score, GpRseProperties.read(bytes))
        val audio = score.backingTrack!!.rawAudioFile
        fun anchors() = score.masterBars.toList().map { bar ->
            bar.syncPoints?.toList()?.map { point ->
                Triple(point.ratioPosition, point.syncPointValue?.barOccurence, point.syncPointValue?.millisecondOffset)
            }.orEmpty()
        }
        fun pitches() = score.tracks[0].staves[0].bars.toList().map { bar ->
            bar.voices[0].beats.toList().flatMap { beat -> beat.notes.toList().map { it.realValue } }
        }
        val originalAnchors = anchors()
        val originalPitches = pitches()
        val target = originalAnchors.indices.first { it > 0 && originalAnchors[it].isNotEmpty() }
        val edits = mutableListOf<ScoreStructure.BarEdit>()
        val editor = BasicScoreEditor(context, { score }, { changed, _, bars ->
            score = changed
            if (bars is ScoreStructure.BarEdit) edits.add(bars)
        })
        assertTrue(editor.insertBar(target))
        assertEquals(originalAnchors.toMutableList().apply { add(target, emptyList()) }, anchors())
        assertEquals(originalPitches.toMutableList().apply { add(target, emptyList()) }, pitches())
        assertSame(audio, score.backingTrack!!.rawAudioFile)

        assertTrue(editor.deleteBar(target + 1))
        assertEquals(originalAnchors.toMutableList().apply { this[target] = emptyList() }, anchors())
        assertEquals(originalPitches.toMutableList().apply { this[target] = emptyList() }, pitches())
        assertSame(audio, score.backingTrack!!.rawAudioFile)
        val saved = ScoreExport.guitarProBytes(score)
        val editedAnchors = anchors()
        val editedPitches = pitches()
        assertTrue(editor.undo())
        assertTrue(editor.undo())
        assertEquals(originalAnchors, anchors())
        assertEquals(originalPitches, pitches())
        assertTrue(editor.redo())
        assertTrue(editor.redo())
        assertEquals(editedAnchors, anchors())
        assertEquals(listOf(true, false, true, false, true, false), edits.map { it.inserted })
        score = ScoreLoader.loadScoreFromBytes(Uint8Array(saved.asUByteArray()), Settings())
        assertEquals(editedAnchors, anchors())
        assertEquals(editedPitches, pitches())
        assertEquals(audio!!.length, score.backingTrack!!.rawAudioFile!!.length, 0.0)
    }

    @Test fun deletingFirstBarRetainsTempoAndDetachesAContinuation() {
        var score = ScoreStructure.newScore("续音", ScoreStructure.Instrument.STEEL, 114, Settings())
        val editor = BasicScoreEditor(context, { score }, { changed, _, _ -> score = changed })
        assertTrue(editor.appendBar())
        assertTrue(editor.putFret(BasicScoreEditor.BeatAddress(0, 0, 0, 0, 0), 1, 4))
        assertTrue(editor.putFret(BasicScoreEditor.BeatAddress(0, 0, 1, 0, 0), 1, 4))
        fun secondNote() = score.tracks[0].staves[0].bars[1].voices[0].beats[0].notes[0]
        secondNote().isTieDestination = true
        score.masterBars[0].tempoAutomations.push(Automation.buildTempoAutomation(false, 0.5, 126.0, 2.0))
        assertTrue(editor.deleteBar(0))
        assertEquals(126.0, score.tempo, 0.0)
        assertEquals(4.0, first(score).fret, 0.0)
        assertFalse(first(score).isTieDestination)
        assertNull(first(score).tieOrigin)
        assertTrue(editor.undo())
        assertSame(first(score), secondNote().tieOrigin)
        assertTrue(editor.redo())
        assertEquals(1, score.masterBars.length.toInt())
        assertTrue(runCatching { editor.deleteBar(0) }.exceptionOrNull() is IllegalArgumentException)
    }

    @Test fun repeatSignsAndAlternateEndingsChangePlaybackAndSurviveGpExport() {
        var score = ScoreStructure.newScore("反复", ScoreStructure.Instrument.STEEL, 120, Settings())
        val editor = BasicScoreEditor(context, { score }, { changed, _, _ -> score = changed })
        assertTrue(editor.appendBar())
        assertTrue(editor.appendBar())
        for (bar in 0..2) assertTrue(editor.putFret(BasicScoreEditor.BeatAddress(0, 0, bar, 0, 0), 1, bar))
        assertTrue(editor.setRepeatMarks(0, ScoreStructure.RepeatMarks(true, 0, 0)))
        assertTrue(editor.setRepeatMarks(1, ScoreStructure.RepeatMarks(false, 2, 1)))
        assertTrue(editor.setRepeatMarks(2, ScoreStructure.RepeatMarks(false, 0, 2)))
        fun occurrenceOrder(doc: Score) = ScorePerformance.compile(doc).bars.map { it.index }
        assertEquals(listOf(0, 1, 0, 2), occurrenceOrder(score))
        assertTrue(editor.undo())
        assertTrue(editor.redo())
        val saved = ScoreExport.guitarProBytes(score)
        val restored = ScoreLoader.loadScoreFromBytes(Uint8Array(saved.asUByteArray()), Settings())
        assertEquals(listOf(0, 1, 0, 2), occurrenceOrder(restored))
        assertEquals(listOf(40, 41, 40, 42), ScorePerformance.compile(restored).notes.map { it.midi })
        assertTrue(editor.deleteBar(0))
        assertTrue(score.masterBars[0].isRepeatStart)
        assertEquals(listOf(0, 1), occurrenceOrder(score))
    }

    @Test fun deletingAnImportedTrackKeepsRemainingPlaybackAndPreferenceSlots() {
        var score = ScoreStructure.newScore("三轨", ScoreStructure.Instrument.STEEL, 105, Settings())
        val editor = BasicScoreEditor(context, { score }, { changed, _, _ -> score = changed })
        assertTrue(editor.addTrack(ScoreStructure.Instrument.NYLON))
        assertTrue(editor.addTrack(ScoreStructure.Instrument.ELECTRIC))
        for (index in 0..2) assertTrue(editor.putFret(BasicScoreEditor.BeatAddress(index, 0, 0, 0, 0), 1, index + 2))
        score.tracks[1].playbackInfo.volume = 5.0
        score.tracks[2].playbackInfo.balance = 12.0
        val source = ScoreExport.guitarProBytes(score)
        score = ScoreLoader.loadScoreFromBytes(Uint8Array(source.asUByteArray()), Settings())
        val properties = GpRseProperties.read(source)
        GpRseProperties.bind(score, properties)
        editor.reset(score)
        fun slots() = (0 until score.tracks.length.toInt()).map(editor::trackSlot)
        val names = score.tracks.toList().map { it.name }
        val channels = score.tracks.toList().map { it.playbackInfo.primaryChannel }
        val document = "test:tracks-${System.nanoTime()}"
        val controls = PlaybackControls(context, { error("This test has no player") }, { true }, {},
            documentKey = { document }, reloadTone = { true }, trackSlot = editor::trackSlot)
        controls.reset(score)
        assertTrue(controls.setTone(0, GuitarTone.Distortion))
        assertTrue(controls.setTone(1, GuitarTone.Overdrive))
        val noteId = GpRseProperties.note(score.tracks[1].staves[0].bars[0].voices[0].beats[0].notes[0])?.sourceId
        assertNotNull(noteId)

        assertTrue(editor.deleteTrack(0))
        controls.reset(score, true)
        assertEquals(listOf(1, 2), slots())
        assertEquals(listOf(GuitarTone.Overdrive, GuitarTone.Original), controls.snapshotTones())
        assertEquals(names.drop(1), score.tracks.toList().map { it.name })
        assertEquals(channels.drop(1), score.tracks.toList().map { it.playbackInfo.primaryChannel })
        assertEquals(5.0, score.tracks[0].playbackInfo.volume, 0.0)
        assertEquals(12.0, score.tracks[1].playbackInfo.balance, 0.0)
        assertEquals(properties.tracks.drop(1), GpRseProperties.get(score)?.tracks)
        assertEquals(noteId, GpRseProperties.note(first(score))?.sourceId)
        assertTrue(editor.undo())
        controls.reset(score, true)
        assertEquals(listOf(0, 1, 2), slots())
        assertEquals(listOf(GuitarTone.Distortion, GuitarTone.Overdrive, GuitarTone.Original), controls.snapshotTones())
        assertTrue(editor.redo())
        assertTrue(editor.addTrack(ScoreStructure.Instrument.STEEL))
        assertTrue(editor.trackSlot(2) < 0)
        val bytes = ScoreExport.guitarProBytes(score)
        assertEquals(3, GpRseProperties.read(bytes).tracks.size)
        val restored = ScoreLoader.loadScoreFromBytes(Uint8Array(bytes.asUByteArray()), Settings())
        assertEquals(3.0, first(restored).fret, 0.0)
        assertEquals(3, restored.tracks.length.toInt())
    }

    @Test fun independentVoicesPlayTogetherAndRoundTripAsSeparateParts() {
        var score = ScoreStructure.newScore("复调", ScoreStructure.Instrument.STEEL, 120, Settings())
        val editor = BasicScoreEditor(context, { score }, { changed, _, _ -> score = changed })
        assertTrue(editor.appendBar())
        val firstVoice = BasicScoreEditor.BeatAddress(0, 0, 0, 0, 0)
        val secondVoice = firstVoice.copy(voice = 1)
        assertTrue(editor.putFret(firstVoice, 1, 4))
        assertTrue(editor.updateDuration(firstVoice, Duration.Half))
        assertTrue(editor.enableVoice(0, 0, 0, 1))
        assertTrue(editor.updateDuration(secondVoice, Duration.Quarter))
        assertTrue(editor.putFret(secondVoice.copy(beat = 1), 6, 3))
        val nextBarVoice = secondVoice.copy(bar = 1)
        assertTrue(score.tracks[0].staves[0].bars[1].voices[1].isEmpty)
        assertTrue(editor.putFret(nextBarVoice, 6, 7))
        assertTrue(editor.undo())
        assertTrue(score.tracks[0].staves[0].bars[1].voices[1].isEmpty)
        assertTrue(editor.redo())
        val fourth = nextBarVoice.copy(voice = 3)
        assertTrue(editor.enableVoice(0, 0, 1, 3))
        assertTrue(editor.updateDuration(fourth, Duration.Quarter))
        assertTrue(editor.putFret(fourth.copy(beat = 1), 3, 5))
        val restored = ScoreLoader.loadScoreFromBytes(Uint8Array(ScoreExport.guitarProBytes(score).asUByteArray()), Settings())
        val bars = restored.tracks[0].staves[0].bars
        assertEquals(4.0, bars[0].voices[0].beats[0].notes[0].fret, 0.0)
        assertEquals(3.0, bars[0].voices[1].beats[1].notes[0].fret, 0.0)
        assertEquals(7.0, bars[1].voices[1].beats[0].notes[0].fret, 0.0)
        assertTrue(bars[1].voices[2].isEmpty)
        assertEquals(5.0, bars[1].voices[3].beats[1].notes[0].fret, 0.0)
        val notes = ScorePerformance.compile(restored).notes
        assertEquals(listOf(44, 67, 71, 55), notes.map { it.midi })
        assertEquals(listOf(0.0, 960.0, 3840.0, 4800.0), notes.map { it.startTick })
    }

    @Test fun enablingVoicesAndAddingTracksKeepsTheWrittenPickupLength() {
        var score = ScoreStructure.newScore("弱起", ScoreStructure.Instrument.STEEL, 120, Settings())
        val editor = BasicScoreEditor(context, { score }, { changed, _, _ -> score = changed })
        val start = BasicScoreEditor.BeatAddress(0, 0, 0, 0, 0)
        assertTrue(editor.putFret(start, 1, 2))
        val beat = first(score).beat
        beat.duration = Duration.Eighth
        score.masterBars[0].isAnacrusis = true
        assertTrue(editor.addTrack(ScoreStructure.Instrument.ELECTRIC))
        assertEquals(480.0, score.masterBars[0].calculateDuration(), 0.0)
        assertTrue(editor.enableVoice(0, 0, 0, 1))
        assertEquals(480.0, score.masterBars[0].calculateDuration(), 0.0)
        assertEquals(Duration.Eighth, score.tracks[0].staves[0].bars[0].voices[1].beats[0].duration)
    }

    @Test fun aCopiedChordDiagramStillBelongsToItsStaffAfterTrackDeletion() {
        var score = ScoreStructure.newScore("和弦", ScoreStructure.Instrument.STEEL, 120, Settings())
        val editor = BasicScoreEditor(context, { score }, { changed, _, _ -> score = changed })
        assertTrue(editor.addTrack(ScoreStructure.Instrument.NYLON))
        assertTrue(editor.appendBar())
        for (track in 0..1) {
            val address = BasicScoreEditor.BeatAddress(track, 0, 0, 0, 0)
            assertTrue(editor.putFret(address, 1, 3 + track))
            val staff = score.tracks[track].staves[0]
            val chord = "test-$track"
            staff.addChord(chord, Chord().apply { name = chord })
            staff.bars[0].voices[0].beats[0].chordId = chord
        }
        val source = BasicScoreEditor.BeatAddress(1, 0, 0, 0, 0)
        val destination = source.copy(track = 0, bar = 1)
        editor.copyBars(source, 1)
        assertTrue(editor.deleteTrack(0))
        assertTrue(editor.pasteBars(destination))
        assertEquals("test-1", score.tracks[0].staves[0].bars[1].voices[0].beats[0].chord?.name)
        assertTrue(editor.undo())
        assertTrue(editor.undo())
        editor.copyBars(source.copy(track = 0), 1)
        assertTrue(editor.deleteTrack(0))
        assertTrue(runCatching { editor.pasteBars(destination) }.exceptionOrNull() is IllegalArgumentException)
    }

    @Test fun editingAChordAndShorteningItsBeatPreservesOriginalSyncAndCanBeExported() {
        var score = demo()
        val editor = BasicScoreEditor(context, { score }, { changed, _, _ -> score = changed })
        val address = BasicScoreEditor.BeatAddress(0, 0, 0, 0, 0)
        val originalAudio = score.backingTrack!!.rawAudioFile!!
        val original = first(score).beat
        val originalDuration = original.duration
        val shorter = BasicScoreEditor.EDITABLE_DURATIONS[BasicScoreEditor.EDITABLE_DURATIONS.indexOf(originalDuration) + 1]
        val openString = (1..original.voice.bar.staff.tuning.length.toInt()).first { string ->
            original.notes.none { it.string.toInt() == string }
        }
        val noteCount = original.notes.length.toInt()
        assertTrue(editor.putFret(address, openString, 5))
        assertEquals(noteCount + 1, first(score).beat.notes.length.toInt())
        assertTrue(editor.toggleTechnique(address, openString, BasicScoreEditor.Technique.WholeBend))
        assertTrue(editor.updateDuration(address, shorter))
        assertTrue(first(score).beat.voice.beats[1].isRest)
        assertEquals(shorter, first(score).beat.duration)
        assertTrue(editor.updateDuration(address, originalDuration))
        assertTrue(editor.undo())
        assertEquals(shorter, first(score).beat.duration)
        assertSame(originalAudio, score.backingTrack!!.rawAudioFile)
        assertEquals(16, score.masterBars.sumOf { it.syncPoints?.length?.toInt() ?: 0 })
        val restored = ScoreLoader.loadScoreFromBytes(Uint8Array(ScoreExport.guitarProBytes(score).asUByteArray()), Settings())
        assertEquals(5.0, first(restored).beat.notes.first { it.string.toInt() == openString }.fret, 0.0)
        assertTrue(first(restored).beat.notes.first { it.string.toInt() == openString }.hasBend)
        assertEquals(shorter, first(restored).beat.duration)
        assertEquals(originalAudio.length, restored.backingTrack!!.rawAudioFile!!.length, 0.0)
        assertTrue(editor.clearBeat(address, openString))
        assertEquals(noteCount, first(score).beat.notes.length.toInt())
    }

    @Test fun fretEditUndoRedoPreservesAudioSyncAndRhythm() {
        var score = demo()
        val original = score
        val editor = BasicScoreEditor(context, { score }, { changed, _, _ -> score = changed })
        editor.reset(score)
        val fret = first(score).fret
        val pitch = first(score).realValue
        val audio = score.backingTrack!!.rawAudioFile!!
        val duration = first(score).beat.duration
        assertTrue(editor.updateFret(address, fret.toInt() + 2))
        assertNotSame(original, score)
        assertEquals(pitch + 2, first(score).realValue, 0.0)
        assertEquals(duration, first(score).beat.duration)
        assertSame(audio, score.backingTrack!!.rawAudioFile)
        assertEquals(16, score.masterBars.sumOf { it.syncPoints?.length?.toInt() ?: 0 })
        assertTrue(editor.undo()); assertEquals(fret, first(score).fret, 0.0)
        assertTrue(editor.redo()); assertEquals(fret + 2, first(score).fret, 0.0)
        val reloaded = ScoreLoader.loadScoreFromBytes(Uint8Array(ScoreExport.guitarProBytes(score).asUByteArray()), Settings())
        assertEquals(first(score).realValue, first(reloaded).realValue, 0.0)
        assertEquals(audio.length, reloaded.backingTrack!!.rawAudioFile!!.length, 0.0)
    }

    @Test fun redoBranchesNoopsAndHistoryLimit() {
        var score = demo()
        val editor = BasicScoreEditor(context, { score }, { changed, _, _ -> score = changed })
        editor.reset(score)
        assertFalse(editor.updateMetadata(score.title, score.artist))
        assertFalse(editor.canUndo)
        repeat(25) { editor.updateMetadata("Title $it", "Artist") }
        assertEquals(20, editor.undoCount)
        assertTrue(editor.undo()); assertTrue(editor.canRedo)
        editor.updateMetadata("New branch", "Artist")
        assertFalse(editor.canRedo)
        val before = score
        try { editor.updateFret(address, 99); fail("Invalid fret accepted") } catch (_: IllegalArgumentException) {}
        assertSame(before, score)
        editor.reset(demo()); assertFalse(editor.canUndo)
    }

    @Test fun externalMixIsUndoableWithoutReplacingLiveScore() {
        var score = demo()
        val editor = BasicScoreEditor(context, { score }, { changed, _, _ -> score = changed })
        editor.reset(score)
        val previousVolume = score.tracks[0].playbackInfo.volume
        editor.beginExternalChange()
        score.tracks[0].playbackInfo.volume = 4.0
        editor.beginExternalChange() // Entire gesture/dialog is one undo item.
        score.tracks[0].playbackInfo.volume = 7.0
        editor.commitExternalChange("Mix")
        assertEquals(1, editor.undoCount)
        assertTrue(editor.undo()); assertEquals(previousVolume, score.tracks[0].playbackInfo.volume, 0.0)
        assertTrue(editor.redo()); assertEquals(7.0, score.tracks[0].playbackInfo.volume, 0.0)
    }

    @Test fun draftWritesReplaceAndReloadAtomically() {
        // Separate fixture context directory to avoid changing the user's app recovery file.
        val directory = java.io.File(context.cacheDir, "draft-test").apply { mkdirs() }
        val wrapper = object : android.content.ContextWrapper(context) { override fun getFilesDir() = directory }
        val store = DraftStore(wrapper)
        try {
            val score = demo()
            store.write(ScoreExport.guitarProBytes(score))
            score.title = "Recovered changes"
            store.write(ScoreExport.guitarProBytes(score))
            assertTrue(store.exists())
            val restored = ScoreLoader.loadScoreFromBytes(Uint8Array(store.read().asUByteArray()), Settings())
            assertEquals("Recovered changes", restored.title)
            assertEquals(score.backingTrack!!.rawAudioFile!!.length, restored.backingTrack!!.rawAudioFile!!.length, 0.0)
        } finally { store.delete(); directory.delete() }
    }

    @Test fun progressiveTempoRepeatsClampsAndResets() {
        val trainer = ProgressivePractice()
        assertEquals(0.6, trainer.start(ProgressivePractice.Configuration(60, 72, 5, 2)), 0.0)
        assertNull(trainer.onLoopFinished())
        assertEquals(0.65, trainer.onLoopFinished()!!, 0.0)
        assertNull(trainer.onLoopFinished()); assertEquals(0.70, trainer.onLoopFinished()!!, 0.0)
        assertNull(trainer.onLoopFinished()); assertEquals(0.72, trainer.onLoopFinished()!!, 0.0)
        trainer.stop(); assertNull(trainer.onLoopFinished()); assertFalse(trainer.enabled)
        try { ProgressivePractice.Configuration(100, 50, 5, 2); fail("Invalid range accepted") } catch (_: IllegalArgumentException) {}
    }
}
