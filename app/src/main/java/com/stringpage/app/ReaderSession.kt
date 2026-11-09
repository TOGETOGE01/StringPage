package com.stringpage.app

import android.os.Bundle

/** Small reading/practice state only. Scores and audio never enter the activity Bundle. */
internal data class ReaderSession(
    val document: String,
    val track: Int,
    val source: String,
    val tick: Double,
    val speed: Double,
    val scale: Double,
    val notation: String,
    val fullScreen: Boolean,
    val masterVolume: Double,
    val loop: Loop?
) {
    data class Loop(val start: Double,val end: Double,val enabled: Boolean,val fromBar: Int?,val toBar: Int?)

    fun toBundle()=Bundle().apply {
        putInt("version",1);putString("document",document);putInt("track",track);putString("source",source)
        putDouble("tick",tick);putDouble("speed",speed);putDouble("scale",scale);putString("notation",notation)
        putBoolean("fullScreen",fullScreen);putDouble("masterVolume",masterVolume)
        loop?.let {
            putDouble("loopStart",it.start);putDouble("loopEnd",it.end);putBoolean("loopEnabled",it.enabled)
            it.fromBar?.let { bar -> putInt("fromBar",bar) };it.toBar?.let { bar -> putInt("toBar",bar) }
        }
    }
    companion object {
        const val DEMO="asset:morning.gp"
        fun fromBundle(bundle: Bundle?): ReaderSession? {
            if(bundle==null || bundle.getInt("version")!=1) return null
            val document=bundle.getString("document")?.takeIf { it.isNotBlank() } ?: return null
            fun number(key: String,default: Double,min: Double,max: Double): Double {
                val value=bundle.getDouble(key,default)
                return if(value.isFinite()) value.coerceIn(min,max) else default
            }
            val start=bundle.getDouble("loopStart",Double.NaN);val end=bundle.getDouble("loopEnd",Double.NaN)
            val loop=if(start.isFinite() && end.isFinite() && start>=0 && end>start)
                Loop(start,end,bundle.getBoolean("loopEnabled"),
                    bundle.getInt("fromBar").takeIf { it>0 },bundle.getInt("toBar").takeIf { it>0 }) else null
            return ReaderSession(document,bundle.getInt("track").coerceAtLeast(0),
                bundle.getString("source")?.takeIf { it in setOf("embedded","synth","prepared") } ?: "synth",
                number("tick",0.0,0.0,Double.MAX_VALUE),number("speed",1.0,.1,4.0),
                number("scale",.85,.25,4.0),bundle.getString("notation") ?: "ScoreTab",
                bundle.getBoolean("fullScreen"),number("masterVolume",1.0,0.0,1.0),loop)
        }
    }
}
