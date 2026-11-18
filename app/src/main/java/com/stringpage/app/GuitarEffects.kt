package com.stringpage.app

internal enum class GuitarWah(val label: String) {
    Off("旁通"), Manual("固定哇音"), Auto("自动哇音")
}

internal enum class GuitarModulation(val label: String) {
    Off("旁通"), Chorus("立体声合唱"), Phaser("立体声移相")
}

internal enum class ModulationPosition(val label: String) {
    AfterCabinet("箱体之后"), BeforeAmp("音箱之前")
}

internal enum class SpacePosition(val label: String) {
    AfterCabinet("箱体之后"), AmpLoop("音箱效果环路")
}

internal enum class GuitarDelay(val label: String, val beats: Double = 0.0) {
    Off("旁通"), Fixed("固定毫秒"), Quarter("四分音符", 1.0),
    DottedEighth("附点八分音符", 0.75), Eighth("八分音符", 0.5),
    TripletEighth("八分三连音", 1.0 / 3), Sixteenth("十六分音符", 0.25)
}

internal enum class GuitarReverb(val label: String, val size: Double = 0.0) {
    Off("旁通"), Room("房间", 0.72), Plate("板式", 0.96), Hall("大厅", 1.24)
}

internal data class GuitarEffects(
    val compression: Int = 0,
    val wah: GuitarWah = GuitarWah.Off,
    val wahPosition: Int = 50,
    val modulation: GuitarModulation = GuitarModulation.Off,
    val modulationDepth: Int = 40,
    val modulationRate: Int = 85,
    val modulationPosition: ModulationPosition = ModulationPosition.AfterCabinet,
    val delay: GuitarDelay = GuitarDelay.Off,
    val delayMs: Int = 320,
    val delayFeedback: Int = 28,
    val delayMix: Int = 22,
    val delayPosition: SpacePosition = SpacePosition.AfterCabinet,
    val reverb: GuitarReverb = GuitarReverb.Off,
    val reverbDecay: Int = 45,
    val reverbMix: Int = 18,
    val reverbPosition: SpacePosition = SpacePosition.AfterCabinet
)
