package com.stringpage.app

internal object BuiltInGuitarPresets {
    data class Group(val label: String, val presets: List<Pair<String, GuitarAmpSettings>>)

    val groups = listOf(
        Group("清音预设", listOf(
            "清音" to GuitarAmpSettings(),
            "暖调爵士" to GuitarAmpSettings(AmpPreset.Warm, gain = 38, bass = 1, mids = 2, treble = -3,
                effects = GuitarEffects(reverb = GuitarReverb.Room, reverbDecay = 22, reverbMix = 10)),
            "放克切音" to GuitarAmpSettings(AmpPreset.Bright, gain = 42, bass = -3, mids = 2,
                effects = GuitarEffects(compression = 47, reverb = GuitarReverb.Room, reverbMix = 8)),
            "合唱清音" to GuitarAmpSettings(effects = GuitarEffects(compression = 24,
                modulation = GuitarModulation.Chorus, modulationDepth = 54,
                reverb = GuitarReverb.Plate, reverbDecay = 36, reverbMix = 16)),
            "清音分解" to GuitarAmpSettings(AmpPreset.Bright, gain = 36, treble = 2,
                effects = GuitarEffects(compression = 18, delay = GuitarDelay.Eighth, delayMix = 15,
                    delayFeedback = 21, reverb = GuitarReverb.Plate, reverbMix = 12)),
            "冲浪弹簧" to GuitarAmpSettings(AmpPreset.Bright, gain = 45, mids = -2, treble = 2,
                effects = GuitarEffects(reverb = GuitarReverb.Spring, reverbDecay = 58, reverbMix = 34))
        )),
        Group("过载与失真", listOf(
            "轻过载" to GuitarAmpSettings(AmpPreset.Crunch),
            "英式失真" to GuitarAmpSettings(AmpPreset.British),
            "蓝调轻破" to GuitarAmpSettings(AmpPreset.Crunch, gain = 34, mids = 3, treble = -1,
                effects = GuitarEffects(reverb = GuitarReverb.Spring, reverbMix = 14, reverbDecay = 32)),
            "过载主音" to GuitarAmpSettings(AmpPreset.British, gain = 57, mids = 2,
                pedal = GuitarDrive.Overdrive, pedalDrive = 29, effects = GuitarEffects(compression = 16,
                    delay = GuitarDelay.DottedEighth, delayMix = 18, delayFeedback = 24,
                    reverb = GuitarReverb.Room, reverbMix = 12)),
            "复古法兹" to GuitarAmpSettings(AmpPreset.Warm, gain = 42, mids = 1, treble = -2,
                pedal = GuitarDrive.Fuzz, pedalDrive = 62,
                effects = GuitarEffects(reverb = GuitarReverb.Room, reverbMix = 10)),
            "朋克节奏" to GuitarAmpSettings(AmpPreset.British, gain = 63, bass = -3, mids = 3, treble = 1,
                pedal = GuitarDrive.Boost, pedalDrive = 22)
        )),
        Group("高增益", listOf(
            "现代高增益" to GuitarAmpSettings(AmpPreset.Modern, gateDb = -62),
            "紧实重节奏" to GuitarAmpSettings(AmpPreset.Modern, gain = 58, gateDb = -58, bass = -4, mids = -1,
                pedal = GuitarDrive.Overdrive, pedalDrive = 19),
            "高增益主音" to GuitarAmpSettings(AmpPreset.Modern, gain = 54, gateDb = -67, mids = 3, treble = -2,
                effects = GuitarEffects(delay = GuitarDelay.DottedEighth, delayVoice = GuitarDelayVoice.Tape,
                    delayMix = 21, delayFeedback = 32, reverb = GuitarReverb.Plate, reverbMix = 15))
        )),
        Group("调制与空间", listOf(
            "弹簧颤音" to GuitarAmpSettings(AmpPreset.Warm, gain = 40,
                effects = GuitarEffects(modulation = GuitarModulation.Tremolo, modulationDepth = 60, modulationRate = 370,
                    reverb = GuitarReverb.Spring, reverbDecay = 38, reverbMix = 23)),
            "移相律动" to GuitarAmpSettings(AmpPreset.Bright, gain = 42,
                effects = GuitarEffects(modulation = GuitarModulation.Phaser, modulationDepth = 68, modulationRate = 180,
                    modulationPosition = ModulationPosition.BeforeAmp, compression = 27)),
            "镶边摇滚" to GuitarAmpSettings(AmpPreset.British, gain = 51, mids = 2,
                effects = GuitarEffects(modulation = GuitarModulation.Flanger, modulationDepth = 60, modulationRate = 63,
                    modulationPosition = ModulationPosition.AmpLoop, reverb = GuitarReverb.Room, reverbMix = 11)),
            "左右交替延迟" to GuitarAmpSettings(AmpPreset.Bright, gain = 35,
                effects = GuitarEffects(delay = GuitarDelay.DottedEighth, delayVoice = GuitarDelayVoice.PingPong,
                    delayMix = 36, delayFeedback = 41)),
            "氛围长尾" to GuitarAmpSettings(gain = 38, cabinet = GuitarCabinet.Twin, treble = -2,
                effects = GuitarEffects(modulation = GuitarModulation.Chorus, modulationDepth = 44, modulationRate = 38,
                    delay = GuitarDelay.Quarter, delayVoice = GuitarDelayVoice.Tape, delayMix = 27, delayFeedback = 47,
                    reverb = GuitarReverb.Hall, reverbDecay = 88, reverbMix = 36))
        ))
    )
}
