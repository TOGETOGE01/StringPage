package com.stringpage.app

import androidx.test.platform.app.InstrumentationRegistry
import com.stringpage.app.rse.NativeSampleTree
import com.stringpage.app.rse.SampleCoordinate
import org.junit.Assert.*
import org.junit.Test
import java.io.DataInputStream
import java.util.zip.GZIPInputStream

class NativeSampleTreeTest {
    @Test fun fullNativeLookupCorpusMatchesAll350ObservedTreeVectorOrders() {
        // .spt keeps AAPT from transparently expanding/renaming a .gz asset.
        val asset = InstrumentationRegistry.getInstrumentation().context.assets.open("native-sample-tree.spt")
        var total = 0
        DataInputStream(GZIPInputStream(asset).buffered()).use { input ->
            val magic = ByteArray(8); input.readFully(magic); assertEquals("NSLT0001",String(magic,Charsets.US_ASCII))
            val banks=input.readInt();assertEquals(350,banks)
            repeat(banks) {
                val nameBytes=ByteArray(input.readInt());input.readFully(nameBytes);val name=String(nameBytes,Charsets.UTF_8)
                val pitched=input.readInt()==1;val default=input.readInt()
                fun coordinate()=SampleCoordinate(input.readInt(),input.readInt(),input.readInt(),input.readInt(),input.readInt())
                val coordinates=List(input.readInt()) { coordinate() }
                data class RawNode(val id:Int,val samples:List<Int>,val children:List<Int>)
                val raw=List(input.readInt()) {
                    RawNode(input.readInt(),List(input.readInt()){input.readInt()},List(input.readInt()){input.readInt()})
                }
                val built=arrayOfNulls<NativeSampleTree.Node>(raw.size)
                fun node(index:Int):NativeSampleTree.Node = built[index] ?: raw[index].let {
                    NativeSampleTree.Node(it.id,it.samples,it.children.map(::node)).also { built[index]=it }
                }
                val root=node(0)
                val tree=NativeSampleTree(root,coordinates,pitched,if(default<0)null else node(default))
                repeat(input.readInt()) { case ->
                    val art=NativeSampleTree.Articulation(input.readInt(),input.readInt(),input.readInt())
                    val request=coordinate();val expected=input.readInt().takeIf { it>=0 }
                    val actual=tree.find(art,request)
                    if(expected!=actual) fail("$name case $case $art $request: expected $expected, got $actual")
                    total++
                }
            }
            assertEquals(-1,input.read())
        }
        assertEquals(923240,total)
    }
}
