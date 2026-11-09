package com.stringpage.app.rse

/** GPIF's authored bend fields, before Core's tied-note validity correction.
 * Values retain GP units (50 per semitone, offsets in percent).
 */
data class GpBendData(val enabled: Boolean=false,val origin: Float=0f,val middle: Float=-1f,val destination: Float=-1f,
    val originOffset: Float=0f,val destinationOffset: Float=100f,
    val middleOffset1: Float=50f,val middleOffset2: Float=50f) {
    companion object {
        val FIELDS=setOf("BendOriginValue","BendMiddleValue","BendDestinationValue","BendOriginOffset",
            "BendDestinationOffset","BendMiddleOffset1","BendMiddleOffset2")
        fun from(enabled: Boolean,fields: Map<String,Float>)=GpBendData(enabled,
            fields["BendOriginValue"] ?: 0f,fields["BendMiddleValue"] ?: -1f,
            fields["BendDestinationValue"] ?: -1f,fields["BendOriginOffset"] ?: 0f,
            fields["BendDestinationOffset"] ?: 100f,fields["BendMiddleOffset1"] ?: 50f,fields["BendMiddleOffset2"] ?: 50f)
    }
}
