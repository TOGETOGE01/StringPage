package com.stringpage.app.rse

/** Value-level pitch functors recovered from the original bend interpreter.
 * Positions and interpolation use float32 in the observed evaluation order.
 * Timing inputs are native ticks (480 per quarter), not sample frames.
 */
object NativeBendInterpreter {
    data class Point(val position: Float,val value: Float)
    data class Curve(val points: List<Point>) {
        fun value(tick: Int,totalTicks: Int): Float {
            if(points.isEmpty()) return 0f
            if(points.size==1) return points[0].value
            if(totalTicks.toUInt()<2u) return (points.first().value+points.last().value)*.5f
            if(tick<0) return points[0].value
            if(tick>=totalTicks) return points.last().value
            val position=unit(tick.toFloat()/(totalTicks-1).toFloat())
            if(points.size==2) return (1f-position)*points[0].value+position*points[1].value
            var right=1
            while(right<points.lastIndex && !(position<points[right].position)) right++
            val a=points[right-1];val b=points[right]
            if(a.position==b.position) return (a.value+b.value)*.5f
            if(a.value==b.value) return a.value
            val ratio=unit((position-a.position)/(b.position-a.position))
            return (1f-ratio)*a.value+ratio*b.value
        }
    }
    data class Bend(val type: Int,val origin: Double,val middle: Double,val destination: Double,
        val originOffset: Float=0f,val destinationOffset: Float=1f,
        val middleOffset1: Float=.5f,val middleOffset2: Float=.5f) {
        fun curve(): Curve {
            val from=origin.toFloat();val mid=middle.toFloat();val to=destination.toFloat()
            return Curve(when(type) {
                1,4,6,7 -> listOf(Point(0f,from),Point(originOffset,from),Point(destinationOffset,to),Point(1f,to))
                2,3 -> listOf(Point(0f,mid))
                5 -> listOf(Point(0f,from),Point(originOffset,from),Point(middleOffset1,mid),
                    Point(middleOffset2,mid),Point(destinationOffset,to),Point(1f,to))
                else -> emptyList()
            })
        }
    }
    data class Note(val durationTicks: Int,val bend: Bend?=null,val stopperSemitones: Float?=null)
    data class Region(val start: Int,val end: Int,val curve: Curve)
    data class Plan(val regions: List<Region>) {
        fun rawValue(tick: Int): Float {
            // The original composite falls back to its last region when none
            // contains the tick. Its full operator suppresses negative offsets.
            val selected=regions.firstOrNull { tick>=it.start && tick<it.end } ?: regions.lastOrNull() ?: return 0f
            val relative=tick-selected.start
            if(relative<0) return 0f
            return selected.curve.value(relative,selected.end-selected.start)
        }
        /** SampleAccessor.computePitchModulation's independently clamped query.
         * Audio rendering adds rawValue to other pitch sources before clamping.
         */
        fun value(tick: Int): Float {
            // computePitchModulation adds the modifier to an initial +0f;
            // this also turns a constant -0f into the observed +0f result.
            val value=rawValue(tick)+0f
            return if(value < -36f) -36f else if(value<=36f) value else 36f
        }
    }
    fun plan(chain: List<Note>): Plan {
        require(chain.size<=200000)
        val regions=ArrayList<Region>();var at=0;var time=0
        fun advance(duration: Int): Int {
            require(duration>=0 && time.toLong()+duration<=Int.MAX_VALUE) { "Bend duration exceeds the native tick range" }
            return time+duration
        }
        while(at<chain.size) {
            var start=time
            var curve=chain[at].bend?.curve()?.takeIf { it.points.isNotEmpty() }
            time=advance(chain[at++].durationTicks)
            while(at<chain.size && chain[at].bend==null) {
                chain[at].stopperSemitones?.let { stopped ->
                    curve?.let { regions.add(Region(start,time,it)) }
                    curve=Curve(listOf(Point(0f,stopped)));start=time
                }
                time=advance(chain[at++].durationTicks)
            }
            curve?.let { regions.add(Region(start,time,it)) }
        }
        return Plan(regions)
    }
    private fun unit(value: Float): Float = if(value>=0f) { if(value<=1f) value else 1f } else 0f
}
