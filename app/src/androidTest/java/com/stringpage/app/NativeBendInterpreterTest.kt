package com.stringpage.app

import androidx.test.platform.app.InstrumentationRegistry
import com.stringpage.app.rse.NativeBendInterpreter as Bend
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class NativeBendInterpreterTest {
    private fun asset(name: String)=InstrumentationRegistry.getInstrumentation().context.assets.open(name)
        .bufferedReader().use { JSONObject(it.readText()) }
    private fun curve(input: JSONObject): Bend.Curve {
        val values=input.getJSONArray("values");val offsets=input.getJSONArray("offsets")
        return Bend.Bend(input.getInt("type"),values.getDouble(0),values.getDouble(1),values.getDouble(2),
            offsets.getDouble(0).toFloat(),offsets.getDouble(1).toFloat(),offsets.getDouble(2).toFloat(),offsets.getDouble(3).toFloat()).curve()
    }
    private fun points(expected: JSONArray,actual: Bend.Curve) {
        assertEquals(expected.length(),actual.points.size)
        for(i in 0 until expected.length()) {
            assertEquals(expected.getJSONArray(i).getDouble(0).toFloat().toRawBits(),actual.points[i].position.toRawBits())
            assertEquals(expected.getJSONArray(i).getDouble(1).toFloat().toRawBits(),actual.points[i].value.toRawBits())
        }
    }
    @Test fun bendControlPointsMatch90ActualNativeCalls() {
        val cases=asset("native-bend-points.json").getJSONArray("rows");assertEquals(90,cases.length())
        for(i in 0 until cases.length()) { val row=cases.getJSONObject(i);points(row.getJSONArray("points"),curve(row)) }
    }
    @Test fun interpolationMatches1080ActualNativeValuesBitForBit() {
        val cases=asset("native-bend-interpolation.json").getJSONArray("cases");var count=0
        for(i in 0 until cases.length()) {
            val row=cases.getJSONObject(i);val curve=curve(row.getJSONObject("input"));val samples=row.getJSONArray("samples")
            for(j in 0 until samples.length()) {
                val sample=samples.getJSONObject(j)
                assertEquals("case $i tick ${sample.getInt("at")} total ${sample.getInt("total")}",
                    sample.getLong("bits").toInt(),curve.value(sample.getInt("at"),sample.getInt("total")).toRawBits());count++
            }
        }
        assertEquals(1080,count)
    }
    @Test fun actualTiedBendGraphsAndPitchValuesMatchTheOriginalInterpreter() {
        val cases=asset("native-bend-ties.json").getJSONArray("cases")
        for(i in 0 until cases.length()) {
            val row=cases.getJSONObject(i);val states=row.getJSONArray("notes")
            val notes=(0 until states.length()).map { at ->
                val n=states.getJSONObject(at)
                val data=if(!n.getBoolean("isBendValid")) null else Bend.Bend(n.getInt("bendType"),
                    n.getDouble("bendOriginValue")/50.0,n.getDouble("bendMiddleValue")/50.0,n.getDouble("bendDestinationValue")/50.0,
                    n.getDouble("bendOriginOffset").toFloat()/100f,n.getDouble("bendDestinationOffset").toFloat()/100f,
                    n.getDouble("bendMiddleOffset1").toFloat()/100f,n.getDouble("bendMiddleOffset2").toFloat()/100f)
                Bend.Note(480,data,if(n.getBoolean("haveBendStopper")) n.getDouble("bendDestinationValue").toFloat()/50f else null)
            }
            val plan=Bend.plan(notes);val graph=row.getJSONObject("graph");val regions=graph.getJSONArray("regions")
            assertEquals(row.getString("name"),regions.length(),plan.regions.size)
            for(j in 0 until regions.length()) {
                val expected=regions.getJSONObject(j);val actual=plan.regions[j]
                assertEquals(expected.getInt("start"),actual.start);assertEquals(expected.getInt("end"),actual.end)
                points(expected.getJSONArray("points"),actual.curve)
            }
            val samples=graph.getJSONArray("samples")
            for(j in 0 until samples.length()) { val s=samples.getJSONObject(j)
                assertEquals(row.getString("name")+" tick "+s.getInt("tick"),s.getLong("bits").toInt(),plan.value(s.getInt("tick")).toRawBits()) }
        }
    }
}
