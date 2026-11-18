package com.stringpage.app

import android.util.Base64
import org.json.JSONObject
import java.nio.ByteBuffer

internal object GuitarAmpPreferences {
    fun encode(config: GuitarAmpSettings): String = JSONObject().apply {
        put("preset", config.preset.name)
        put("gain", config.gain)
        put("volume", config.volume)
        put("pan", config.pan)
        put("cabinet", config.cabinet.name)
        put("cabinetMic", config.cabinetMic.name)
        put("bass", config.bass)
        put("mids", config.mids)
        put("treble", config.treble)
        put("enabled", config.enabled)
        put("inputDb", config.inputDb)
        put("gateDb", config.gateDb ?: JSONObject.NULL)
        put("pedal", config.pedal.name)
        put("pedalDrive", config.pedalDrive)
        put("pedalTone", config.pedalTone)
        put("pedalLevelDb", config.pedalLevelDb)
        put("secondPedal", JSONObject().apply {
            put("model", config.secondPedal.model.name)
            put("drive", config.secondPedal.drive)
            put("tone", config.secondPedal.tone)
            put("levelDb", config.secondPedal.levelDb)
        })
        put("ampEnabled", config.ampEnabled)
        put("cabinetEnabled", config.cabinetEnabled)
        put("eqEnabled", config.eqEnabled)
        put("effects", encodeEffects(config.effects))
        config.cabinetImpulse?.let { impulse ->
            val bytes = ByteBuffer.allocate(impulse.coefficients.size * 4)
            impulse.coefficients.forEach { bytes.putFloat(it) }
            put("cabinetImpulse", JSONObject().put("name", impulse.name)
                .put("samples", Base64.encodeToString(bytes.array(), Base64.NO_WRAP)))
        }
    }.toString()

    fun decode(raw: String): GuitarAmpSettings? = runCatching {
        val json = JSONObject(raw)
        val preset = AmpPreset.valueOf(json.getString("preset"))
        GuitarAmpSettings(
            preset = preset,
            gain = json.optInt("gain", 50).coerceIn(0, 100),
            volume = json.optInt("volume", 75).coerceIn(0, 100),
            pan = json.optInt("pan").coerceIn(-100, 100),
            cabinet = GuitarCabinet.entries.firstOrNull { it.name == json.optString("cabinet") } ?: preset.defaultCabinet,
            cabinetMic = CabinetMic.entries.firstOrNull { it.name == json.optString("cabinetMic") } ?: CabinetMic.Center,
            bass = json.optInt("bass").coerceIn(-12, 12),
            mids = json.optInt("mids").coerceIn(-12, 12),
            treble = json.optInt("treble").coerceIn(-12, 12),
            enabled = json.optBoolean("enabled", true),
            inputDb = json.optInt("inputDb").coerceIn(-18, 18),
            gateDb = if (json.isNull("gateDb")) null else json.optInt("gateDb", -62).coerceIn(-80, -24),
            pedal = GuitarDrive.entries.firstOrNull { it.name == json.optString("pedal") } ?: GuitarDrive.Off,
            pedalDrive = json.optInt("pedalDrive", 35).coerceIn(0, 100),
            pedalTone = json.optInt("pedalTone", 50).coerceIn(0, 100),
            pedalLevelDb = json.optInt("pedalLevelDb").coerceIn(-18, 12),
            secondPedal = json.optJSONObject("secondPedal")?.let { pedal ->
                GuitarPedalSettings(
                    model = GuitarDrive.entries.firstOrNull { it.name == pedal.optString("model") } ?: GuitarDrive.Off,
                    drive = pedal.optInt("drive", 35).coerceIn(0, 100),
                    tone = pedal.optInt("tone", 50).coerceIn(0, 100),
                    levelDb = pedal.optInt("levelDb").coerceIn(-18, 12)
                )
            } ?: GuitarPedalSettings(),
            ampEnabled = json.optBoolean("ampEnabled", true),
            cabinetEnabled = json.optBoolean("cabinetEnabled", true),
            cabinetImpulse = json.optJSONObject("cabinetImpulse")?.let(::decodeImpulse),
            eqEnabled = json.optBoolean("eqEnabled", true),
            effects = json.optJSONObject("effects")?.let(::decodeEffects) ?: GuitarEffects()
        )
    }.getOrNull()

    private fun encodeEffects(effects: GuitarEffects) = JSONObject().apply {
        put("compression", effects.compression)
        put("wah", effects.wah.name)
        put("wahPosition", effects.wahPosition)
        put("modulation", effects.modulation.name)
        put("modulationDepth", effects.modulationDepth)
        put("modulationRate", effects.modulationRate)
        put("modulationPosition", effects.modulationPosition.name)
        put("delay", effects.delay.name)
        put("delayVoice", effects.delayVoice.name)
        put("delayMs", effects.delayMs)
        put("delayFeedback", effects.delayFeedback)
        put("delayMix", effects.delayMix)
        put("delayPosition", effects.delayPosition.name)
        put("reverb", effects.reverb.name)
        put("reverbDecay", effects.reverbDecay)
        put("reverbMix", effects.reverbMix)
        put("reverbPosition", effects.reverbPosition.name)
    }

    private fun decodeEffects(json: JSONObject): GuitarEffects {
        val defaults = GuitarEffects()
        fun value(key: String, fallback: Int, range: IntRange = 0..100) = json.optInt(key, fallback).coerceIn(range)
        fun <T : Enum<T>> option(key: String, choices: List<T>, fallback: T): T {
            val name = json.optString(key)
            return choices.firstOrNull { it.name == name } ?: fallback
        }
        return GuitarEffects(
            compression = value("compression", defaults.compression),
            wah = option("wah", GuitarWah.entries, defaults.wah),
            wahPosition = value("wahPosition", defaults.wahPosition),
            modulation = option("modulation", GuitarModulation.entries, defaults.modulation),
            modulationDepth = value("modulationDepth", defaults.modulationDepth),
            modulationRate = value("modulationRate", defaults.modulationRate, 10..500),
            modulationPosition = option("modulationPosition", ModulationPosition.entries, defaults.modulationPosition),
            delay = option("delay", GuitarDelay.entries, defaults.delay),
            delayVoice = option("delayVoice", GuitarDelayVoice.entries, defaults.delayVoice),
            delayMs = value("delayMs", defaults.delayMs, 60..1200),
            delayFeedback = value("delayFeedback", defaults.delayFeedback, 0..75),
            delayMix = value("delayMix", defaults.delayMix),
            delayPosition = option("delayPosition", SpacePosition.entries, defaults.delayPosition),
            reverb = option("reverb", GuitarReverb.entries, defaults.reverb),
            reverbDecay = value("reverbDecay", defaults.reverbDecay),
            reverbMix = value("reverbMix", defaults.reverbMix),
            reverbPosition = option("reverbPosition", SpacePosition.entries, defaults.reverbPosition)
        )
    }

    private fun decodeImpulse(json: JSONObject): CabinetImpulse? = runCatching {
        val encoded = json.getString("samples")
        require(encoded.length <= (CabinetImpulse.MAX_TAPS * 4 + 2) / 3 * 4)
        val bytes = ByteBuffer.wrap(Base64.decode(encoded, Base64.NO_WRAP))
        require(bytes.remaining() in 4..CabinetImpulse.MAX_TAPS * 4 && bytes.remaining() % 4 == 0)
        val coefficients = List(bytes.remaining() / 4) { bytes.getFloat() }
        require(coefficients.all { it.isFinite() && it in -1f..1f })
        CabinetImpulse(json.optString("name", "自定义箱体 IR").take(100), coefficients)
    }.getOrNull()
}
