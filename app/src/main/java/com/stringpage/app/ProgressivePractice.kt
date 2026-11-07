package com.stringpage.app

/** Increase relative tempo after complete loop iterations, clamping at the target. */
class ProgressivePractice {
    data class Configuration(val start: Int, val target: Int, val step: Int, val repeats: Int) {
        init {
            require(start in 25..200 && target in start..200) { "起始与目标速度应为 25–200%，且目标不低于起始。" }
            require(step in 1..100 && repeats in 1..99) { "增幅应为 1–100%，重复次数应为 1–99。" }
        }
    }
    var configuration: Configuration? = null; private set
    var current = 100; private set
    var remaining = 0; private set
    val enabled get() = configuration != null
    fun start(value: Configuration): Double {
        configuration = value; current = value.start; remaining = value.repeats
        return current / 100.0
    }
    fun onLoopFinished(): Double? {
        val value = configuration ?: return null
        remaining--
        if (remaining > 0) return null
        remaining = value.repeats
        current = minOf(value.target, current + value.step)
        return current / 100.0
    }
    fun stop() { configuration = null; remaining = 0 }
}
