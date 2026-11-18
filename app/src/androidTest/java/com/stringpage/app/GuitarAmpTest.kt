@file:OptIn(kotlin.contracts.ExperimentalContracts::class, ExperimentalUnsignedTypes::class)

package com.stringpage.app

import alphaTab.Settings
import alphaTab.model.Bar
import alphaTab.model.Beat
import alphaTab.model.Duration
import alphaTab.model.MasterBar
import alphaTab.model.Note
import alphaTab.model.Score
import alphaTab.model.Track
import alphaTab.model.Voice
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.PI
import kotlin.math.sin
import kotlin.math.sqrt

@RunWith(AndroidJUnit4::class)
class GuitarAmpTest {
    @Test fun alternatingEchoKeepsStereoEvenIfAnOlderPresetRequestsTheMonoLoop() {
        val rate = 8000
        val tap = rate / 10
        val audio = impulse(tap * 3 + 10)
        val settings = bypassedAmp().copy(effects = GuitarEffects(delay = GuitarDelay.Fixed, delayMs = 100,
            delayVoice = GuitarDelayVoice.PingPong, delayMix = 70, delayFeedback = 50,
            delayPosition = SpacePosition.AmpLoop))
        GuitarAmp(settings, rate).process(audio, audio.size / 2)
        assertEquals(0.35f, audio[tap * 2], 0.00001f)
        assertEquals(0f, audio[tap * 2 + 1], 0.00001f)
        assertEquals(0f, audio[tap * 4], 0.00001f)
        assertTrue(audio[tap * 4 + 1] > 0.14f)
        assertTrue(audio[tap * 6] > 0.07f)
        assertEquals(0f, audio[tap * 6 + 1], 0.00001f)
    }

    @Test fun newModulatorsAndSpringHaveIndependentSignalShapes() {
        val rate = 8000
        fun render(source: FloatArray, effects: GuitarEffects): FloatArray =
            source.clone().also { GuitarAmp(bypassedAmp().copy(effects = effects), rate).process(it, it.size / 2) }
        val tremolo = render(FloatArray(rate * 2) { 0.5f },
            GuitarEffects(modulation = GuitarModulation.Tremolo, modulationRate = 100, modulationDepth = 100))
        assertEquals(0.275f, tremolo[0], 0.0001f)
        assertEquals(0.05f, tremolo[1], 0.0001f)
        assertEquals(0.5f, tremolo[rate * 3 / 2], 0.0001f)

        val pulse = impulse(rate)
        val flanger = render(pulse, GuitarEffects(modulation = GuitarModulation.Flanger, modulationDepth = 100))
        assertTrue((20..50).any { flanger[it * 2] < -0.06f })
        assertTrue((20..50).any { flanger[it * 2] != flanger[it * 2 + 1] })
        assertTrue((1000 until rate).all { kotlin.math.abs(flanger[it * 2]) < 0.00001f })

        val room = render(pulse, GuitarEffects(reverb = GuitarReverb.Room, reverbMix = 65))
        val spring = render(pulse, GuitarEffects(reverb = GuitarReverb.Spring, reverbMix = 65))
        assertTrue((50..110).any { kotlin.math.abs(spring[it * 2]) > 0.01f })
        assertTrue((50..110).all { room[it * 2] == 0f })
    }

    @Test fun allFactoryPresetsRoundTripAndProduceDistinctFiniteAudio() {
        val rate = 44100
        val frames = rate / 2
        val source = FloatArray(frames * 2) { index ->
            val frame = index / 2
            if (frame < rate / 4) (0.16 * sin(2 * PI * 196 * frame / rate) +
                0.07 * sin(2 * PI * 571 * frame / rate)).toFloat() else 0f
        }
        val configs = BuiltInGuitarPresets.groups.flatMap { it.presets }
        assertEquals(configs.size, configs.map { it.first }.toSet().size)
        val outputs = configs.map { (name, settings) ->
            assertEquals(name, settings, GuitarAmpPreferences.decode(GuitarAmpPreferences.encode(settings)))
            val pcm = source.clone()
            val amp = GuitarAmp(settings, rate)
            amp.process(pcm, frames)
            assertTrue("$name should stay in range", pcm.all { it.isFinite() && it in -1f..1f })
            assertTrue("$name should produce a usable signal", pcm.any { kotlin.math.abs(it) > 0.01f })
            amp.reset()
            val replay = source.clone().also { amp.process(it, frames) }
            assertArrayEquals(name, pcm, replay, 0f)
            pcm.contentHashCode()
        }
        assertEquals(configs.size, outputs.toSet().size)
    }

    @Test fun delayFollowsUnfoldedTempoAndKeepsFixedMillisecondsAtSlowPracticeSpeeds() {
        val rate = 8000
        val tempo = SourceBeatMap(rate, listOf(SourceBeatMap.Tempo(0.0, 120.0), SourceBeatMap.Tempo(250.0, 60.0)))
        val base = bypassedAmp().copy(effects = GuitarEffects(delay = GuitarDelay.Quarter, delayFeedback = 0, delayMix = 50))
        val audio = impulse(7000)
        GuitarAmp(base, rate, tempo).process(audio, 7000, speed = 0.5)
        assertEquals(0.5f, audio[0], 0f)
        assertEquals(0.25f, audio[6000 * 2], 1e-6f)
        assertTrue((1 until 5999).all { audio[it * 2] == 0f })

        val fixed = base.copy(effects = base.effects.copy(delay = GuitarDelay.Fixed, delayMs = 320))
        for (speed in listOf(0.1, 0.5, 1.0)) {
            val frames = (rate * 0.320 * speed).toInt()
            val amp = GuitarAmp(fixed, rate, tempo)
            val head = impulse(247)
            amp.process(head, 247, speed = speed)
            val rest = FloatArray((frames + 2 - 247) * 2)
            amp.process(rest, rest.size / 2, speed = speed)
            assertEquals("fixed delay at rate $speed", 0.25f, rest[(frames - 247) * 2], 1e-6f)
        }
    }

    @Test fun modulationAndReverbProduceIndependentStereoWetSignalsAndBoundedTails() {
        val rate = 8000
        val base = bypassedAmp()
        val space = GuitarEffects(modulation = GuitarModulation.Chorus, modulationDepth = 75,
            reverb = GuitarReverb.Hall, reverbDecay = 88, reverbMix = 55)
        val audio = impulse(rate)
        val amp = GuitarAmp(base.copy(effects = space), rate)
        amp.process(audio, rate)
        assertTrue((50 until rate).any { audio[it * 2] != audio[it * 2 + 1] })
        assertTrue((rate / 5 until rate / 2).any { kotlin.math.abs(audio[it * 2]) > 0.0001f })
        assertTrue(audio.all { it.isFinite() && it in -1f..1f })
        assertTrue(amp.tailFrames(rate.toLong(), 0.1) in 1..3200)

        val compressed = FloatArray(rate * 2) { 0.6f }
        GuitarAmp(base.copy(effects = GuitarEffects(compression = 90)), rate).process(compressed, rate)
        assertTrue(compressed.last() in 0.02f..0.20f)
        val tone = FloatArray(rate * 2) { (0.08 * sin(2 * PI * 330 * (it / 2) / rate)).toFloat() }
        val wah = tone.clone()
        GuitarAmp(base.copy(effects = GuitarEffects(wah = GuitarWah.Manual, wahPosition = 0)), rate).process(wah, rate)
        assertTrue(wah.indices.any { kotlin.math.abs(wah[it] - tone[it]) > 0.1f })
    }

    @Test fun frontAndLoopPlacementRunThroughTheAmpAndResetDeterministically() {
        val rate = 44100
        val frames = 10000
        val source = FloatArray(frames * 2) { index ->
            val frame = index / 2
            if (frame < 5000) (0.45 * sin(2 * PI * 332 * frame / rate) + 0.15 * sin(2 * PI * 180 * frame / rate)).toFloat()
            else 0f
        }
        val effects = GuitarEffects(compression = 16, wah = GuitarWah.Auto, modulation = GuitarModulation.Phaser,
            delay = GuitarDelay.Fixed, delayMs = 65, delayMix = 75, reverb = GuitarReverb.Room)
        val config = GuitarAmpSettings(AmpPreset.British, pedal = GuitarDrive.Overdrive, effects = effects,
            cabinetImpulse = CabinetImpulse("test", List(220) { if (it == 0) 0.68f else (sin(it * 0.1) * 0.008).toFloat() }))
        val frontAndLoop = config.copy(effects = effects.copy(modulationPosition = ModulationPosition.BeforeAmp,
            delayPosition = SpacePosition.AmpLoop, reverbPosition = SpacePosition.AmpLoop))
        val post = source.clone().also { GuitarAmp(config, rate).process(it, frames) }
        val amp = GuitarAmp(frontAndLoop, rate)
        val moved = source.clone().also { amp.process(it, frames) }
        assertTrue(moved.indices.any { kotlin.math.abs(moved[it] - post[it]) > 0.02f })
        assertTrue((5500 until frames).any { kotlin.math.abs(moved[it * 2]) > 0.002f })
        assertTrue(moved.all { it.isFinite() && it in -1f..1f })
        amp.reset()
        val repeated = source.clone().also { amp.process(it, frames) }
        assertArrayEquals(moved, repeated, 0f)
    }

    @Test fun customPresetKeepsToneAndRouteAcrossScoresWithoutCopyingTrackLevels() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val file = "user-tone-test-${System.nanoTime()}"
        val store = context.getSharedPreferences(file, android.content.Context.MODE_PRIVATE)
        try {
            val settings = GuitarAmpSettings(AmpPreset.Modern, enabled = false, volume = 43, pan = -46,
                effects = GuitarEffects(delay = GuitarDelay.Eighth, delayPosition = SpacePosition.AmpLoop,
                    modulation = GuitarModulation.Chorus, modulationPosition = ModulationPosition.BeforeAmp))
            GuitarPresetLibrary(store).save("练习主音", settings)
            val reopened = GuitarPresetLibrary(store)
            assertEquals(listOf("练习主音"), reopened.names())
            assertEquals(settings.copy(enabled = true, volume = 75, pan = 0), reopened.load("练习主音"))
            reopened.remove("练习主音")
            assertNull(reopened.load("练习主音"))
        } finally { context.deleteSharedPreferences(file) }
    }

    @Test fun seekingPrimesTheAmpWithoutCarryingAnEchoFromBeforeTheJump() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val rate = 8000
        val file = File.createTempFile("echo-seek-", ".pcm", context.cacheDir)
        val samples = FloatArray(2400 * 2).apply { this[1400] = 0.5f; this[1401] = 0.5f }
        val part = PcmStem.Writer(file).use { it.write(samples, 2400); it.stem() }
        val settings = mapOf(0 to bypassedAmp().copy(effects = GuitarEffects(delay = GuitarDelay.Fixed,
            delayMs = 80, delayFeedback = 0, delayMix = 50)))
        AmpStems(listOf(AmpStems.Part(0, part)), rate, 0.0, headroom = 1.0).use { stems ->
            LiveAmpMixer(stems, 512).use { mixer ->
                val buffer = FloatArray(1024)
                mixer.seek(0, settings)
                repeat(2) { mixer.read(buffer, 512, settings) }
                assertTrue(mixer.tailFrames(1024L, 1.0) > 0)
                mixer.read(buffer, 512, settings)
                assertEquals(0.25f, buffer[(1340 - 1024) * 2], 1e-6f)
                mixer.seek(0, settings)
                repeat(2) { mixer.read(buffer, 512, settings) }
                val initial = settings.getValue(0)
                val wetter = mapOf(0 to initial.copy(effects = initial.effects.copy(delayMix = 90)))
                mixer.read(buffer, 512, wetter)
                assertTrue("live wet mix retains the echo", buffer[(1340 - 1024) * 2] in 0.42f..0.45f)
                mixer.seek(900, wetter)
                mixer.read(buffer, 512, wetter)
                assertTrue(buffer.all { it == 0f })
            }
        }
    }

    @Test fun audioOutputDrainsSpaceEffectsBeforeReportingTheEndOfTheScore() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val rate = 44100
        val frames = rate / 5
        val last = frames - 500
        val audio = FloatArray(frames * 2).apply { this[last * 2] = 0.5f; this[last * 2 + 1] = 0.5f }
        val file = File.createTempFile("echo-end-", ".pcm", instrumentation.targetContext.cacheDir)
        val part = PcmStem.Writer(file).use { it.write(audio, frames); it.stem() }
        val stems = AmpStems(listOf(AmpStems.Part(0, part)), rate, 0.0, headroom = 1.0)
        val settings = mapOf(0 to bypassedAmp().copy(effects = GuitarEffects(delay = GuitarDelay.Fixed,
            delayMs = 850, delayFeedback = 0, delayMix = 50)))
        val ready = CountDownLatch(1)
        val error = java.util.concurrent.atomic.AtomicReference<Exception?>()
        StemAudio(stems, settings) { error.set(it) }.use { output ->
            instrumentation.runOnMainSync { output.open { ready.countDown() } }
            assertTrue(ready.await(5, TimeUnit.SECONDS))
            instrumentation.runOnMainSync { output.play() }
            fun waitFor(condition: () -> Boolean): Boolean {
                val deadline = SystemClock.uptimeMillis() + 5000
                while (!condition() && SystemClock.uptimeMillis() < deadline && error.get() == null) SystemClock.sleep(10)
                return condition()
            }
            assertTrue(waitFor { output.currentPositionMs >= 198.0 })
            assertTrue("effects are still playing", output.currentPositionMs < output.backingTrackDuration)
            assertTrue(waitFor { output.currentPositionMs == output.backingTrackDuration })
            assertNull(error.get())
        }
    }

    private fun impulse(frames: Int) = FloatArray(frames * 2).apply { this[0] = 0.5f; this[1] = 0.5f }

    private fun bypassedAmp() = GuitarAmpSettings(ampEnabled = false, cabinetEnabled = false, eqEnabled = false)

    @Test fun customCabinetConvolutionMatchesDirectConvolutionWithoutAnExtraBlockOfLatency() {
        val impulse = DoubleArray(593) { sin(it * 0.17) / (it + 2) }.apply { this[0] = 0.63 }
        val input = DoubleArray(513) { sin(it * 0.77) * 0.13 + sin(it * 0.049) * 0.35 }
        val cabinet = CabinetConvolution(impulse)
        for (frame in 0 until input.size + impulse.size + 128) {
            val expected = (maxOf(0, frame - input.lastIndex)..minOf(frame, impulse.lastIndex))
                .sumOf { tap -> impulse[tap] * input[frame - tap] }
            assertEquals("frame $frame", expected, cabinet.process(input.getOrElse(frame) { 0.0 }), 1e-10)
        }
    }

    @Test fun readsAndPersistsWaveIrAndKeepsPreviouslySavedSettingsCompatible() {
        val samples = ShortArray(2500).apply { this[32] = 25000; this[170] = -7000; this[2499] = 20 }
        val wave = ByteBuffer.allocate(44 + samples.size * 2).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray()); putInt(capacity() - 8); put("WAVEfmt ".toByteArray()); putInt(16)
            putShort(1); putShort(1); putInt(48000); putInt(96000); putShort(2); putShort(16)
            put("data".toByteArray()); putInt(samples.size * 2); samples.forEach { putShort(it) }
        }.array()
        val ir = CabinetImpulse.readWave("test.wav", wave.inputStream())
        assertEquals(CabinetImpulse.MAX_TAPS, ir.coefficients.size)
        assertTrue(ir.coefficients.first() > 0.5f)
        assertTrue(ir.coefficients.drop(100).any { it < -0.1f })
        assertThrows(IllegalArgumentException::class.java) { CabinetImpulse.readWave("bad.wav", wave.copyOf(40).inputStream()) }

        val legacy = GuitarAmpSettings(AmpPreset.British, gain = 62, volume = 69, pan = -18,
            cabinet = GuitarCabinet.Open, bass = -2, mids = 3, treble = 1)
        assertEquals(legacy, GuitarAmpPreferences.decode("""{"preset":"British","gain":62,"volume":69,
            "pan":-18,"cabinet":"Open","bass":-2,"mids":3,"treble":1}"""))
        val new = legacy.copy(enabled = false, inputDb = 6, gateDb = -67, pedal = GuitarDrive.Overdrive,
            pedalDrive = 46, ampEnabled = false, cabinetImpulse = ir, eqEnabled = false,
            effects = GuitarEffects(compression = 40, wah = GuitarWah.Auto, modulation = GuitarModulation.Phaser,
                delay = GuitarDelay.TripletEighth, reverb = GuitarReverb.Plate))
        assertEquals(new, GuitarAmpPreferences.decode(GuitarAmpPreferences.encode(new)))
    }

    @Test fun noiseGateClosesOnQuietInputAndDrivePedalWorksWithAmpBypassed() {
        val rate = 44100
        val base = GuitarAmpSettings(ampEnabled = false, cabinetEnabled = false, eqEnabled = false)
        val quiet = FloatArray(rate * 2) { 0.0005f }
        GuitarAmp(base.copy(gateDb = -50), rate).process(quiet, rate)
        assertTrue(quiet.all { it == 0f })

        val source = FloatArray(rate * 2) { (0.22 * sin(2 * PI * 220 * (it / 2) / rate)).toFloat() }
        val bypassed = source.clone().also { GuitarAmp(base, rate).process(it, rate) }
        assertArrayEquals(source, bypassed, 1e-7f)
        val driven = source.clone().also {
            GuitarAmp(base.copy(gateDb = -60, pedal = GuitarDrive.Distortion, pedalDrive = 65), rate).process(it, rate)
        }
        val third = (rate / 2 until rate).sumOf { driven[it * 2] * sin(2 * PI * 660 * it / rate) }
        assertTrue("distortion produces a third harmonic: $third", kotlin.math.abs(third) > 100.0)
        assertTrue(driven.all { it.isFinite() && it in -1f..1f })
    }

    @Test fun amplifierRemainsContinuousAtBlockBoundariesAndProducesHarmonics() {
        val rate = 44100
        val frames = rate
        val source = FloatArray(frames * 2) { (0.09 * sin(2 * PI * 294 * (it / 2) / rate)).toFloat() }
        val config = GuitarAmpSettings(AmpPreset.Modern, cabinet = GuitarCabinet.Closed)
        val whole = source.clone().also { GuitarAmp(config, rate).process(it, frames) }
        val clean = source.clone().also { GuitarAmp(config.copy(preset = AmpPreset.Clean), rate).process(it, frames) }
        val amp = GuitarAmp(config, rate)
        val split = source.clone()
        for (start in 0 until frames step 257) {
            val end = minOf(frames, start + 257)
            val block = source.copyOfRange(start * 2, end * 2)
            amp.process(block, end - start)
            block.copyInto(split, start * 2)
        }
        assertArrayEquals(whole, split, 0f)
        assertTrue(whole.all { it.isFinite() && it in -1f..1f })
        fun harmonic(audio: FloatArray, order: Int) =
            (rate / 2 until rate).sumOf { audio[it * 2] * sin(2 * PI * 294 * order * it / rate) }
        val cleanRatio = kotlin.math.abs(harmonic(clean, 3) / harmonic(clean, 1))
        val dirtyRatio = kotlin.math.abs(harmonic(whole, 3) / harmonic(whole, 1))
        assertTrue("clean=$cleanRatio dirty=$dirtyRatio", dirtyRatio > cleanRatio * 3)
        fun rms(audio: FloatArray) = sqrt(audio.sumOf { it.toDouble() * it } / audio.size)
        assertTrue("clean/drive level ${rms(clean) / rms(whole)}", rms(clean) / rms(whole) in 0.45..2.2)
    }

    @Test fun twoGuitarMixReusesDryStemsAndChangesOnlyTheConfiguredVoice() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(context.cacheDir, "amp-test-${System.nanoTime()}").apply { mkdir() }
        val settings = Settings()
        val score = testScore(settings)
        try {
            val source = SynthAmpMix(directory) { context.assets.open("sonivox.sf2").use { it.readBytes() } }
            source.prepare(score, settings, listOf(GuitarTone.Clean, GuitarTone.Clean), 0, { false }, {}).use { stems ->
                val config = GuitarAmpSettings(AmpPreset.Clean, pan = -100)
                val before = directory.listFiles()!!.filter { it.extension == "pcm" }.associate { it.name to it.lastModified() }
                val clean = FloatArray(1024)
                val dirty = FloatArray(1024)
                val original = mapOf(0 to config)
                val changed = mapOf(0 to config.copy(preset = AmpPreset.British,
                    pedal = GuitarDrive.Overdrive, inputDb = 3, gateDb = -67))
                LiveAmpMixer(stems, 512).use { control ->
                    LiveAmpMixer(stems, 512).use { mixed ->
                        val at = 6000L
                        control.seek(at, original)
                        mixed.seek(at, original)
                        control.read(clean, 512, original)
                        mixed.read(dirty, 512, changed)
                        assertEquals(clean[0], dirty[0], 0f)
                        assertTrue(clean.indices.any { it % 2 == 0 && clean[it] != dirty[it] })
                        assertTrue(clean.indices.any { it % 2 == 1 && clean[it] != 0f })
                        assertTrue(clean.indices.all { it % 2 == 0 || clean[it] == dirty[it] })
                    }
                }
                val after = directory.listFiles()!!.filter { it.extension == "pcm" }.associate { it.name to it.lastModified() }
                assertEquals(4, before.size)
                assertEquals(before, after)
            }
        } finally {
            directory.listFiles()?.forEach { it.delete() }
            directory.delete()
        }
    }

    private fun testScore(settings: Settings) = Score().apply {
        addMasterBar(MasterBar().apply { timeSignatureNumerator = 4.0; timeSignatureDenominator = 4.0 })
        repeat(2) { index ->
            val track = Track().apply {
                name = "Guitar ${index + 1}"
                ensureStaveCount(1.0)
                playbackInfo.program = 27.0
                playbackInfo.primaryChannel = 0.0
                playbackInfo.secondaryChannel = 1.0
                playbackInfo.balance = if (index == 0) 8.0 else 16.0
            }
            addTrack(track)
            val bar = Bar()
            track.staves[0].addBar(bar)
            val voice = Voice()
            bar.addVoice(voice)
            repeat(2) { at ->
                val beat = Beat().apply { duration = Duration.Half }
                voice.addBeat(beat)
                beat.addNote(Note().apply { octave = 4.0; tone = (index * 2 + at * 4).toDouble() })
            }
        }
        finish(settings)
    }
}
