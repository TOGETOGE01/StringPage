package com.stringpage.app.rse

import java.util.IdentityHashMap
import kotlin.math.abs

/** Structural lookup shared by observed AMSoundFont and real GPRSE trees,
 * independently ported from native code and runtime results. Coordinates and child/sample vector order
 * are explicit inputs. Native MSVC sample vectors are sorted by object address;
 * this module preserves the supplied order and does not invent a portable heap
 * address ordering or random round-robin behavior for equal-distance samples.
 */
class NativeSampleTree(
    val root: Node,
    coordinates: List<SampleCoordinate>,
    private val pitched: Boolean,
    private val defaultRoot: Node? = root
) {
    class Node(val id: Int, samples: List<Int> = emptyList(), children: List<Node> = emptyList()) {
        val samples = samples.toList()
        val children = children.toList()
    }
    data class Articulation(val attack: Int, val technique: Int, val variation: Int)
    private val coordinates = coordinates.toList()
    private val parents = IdentityHashMap<Node, Node?>()

    init {
        require(this.coordinates.size <= 65536)
        val assigned = HashSet<Int>()
        fun inspect(node: Node, parent: Node?, depth: Int) {
            require(depth <= 64 && parents.size < 4096 && !parents.containsKey(node)) { "Invalid sample tree" }
            parents[node] = parent
            for (sample in node.samples) require(sample in this.coordinates.indices && assigned.add(sample))
            for (child in node.children) inspect(child, node, depth + 1)
        }
        inspect(root, null, 0)
        require(defaultRoot == null || parents.containsKey(defaultRoot))
    }

    /** Returns the original coordinate/sample-list index, or null on a native miss. */
    fun find(articulation: Articulation, coordinate: SampleCoordinate): Int? {
        val candidates = if (articulation.attack == 1) (2..4).mapNotNull {
            nodeFind(root, intArrayOf(1, articulation.technique, it), coordinate)
        } else listOfNotNull(nodeFind(root, intArrayOf(articulation.attack, articulation.technique, articulation.variation), coordinate))
        var selected = nearest(candidates, coordinate)
        if (selected == null && pitched && articulation.attack != 2) {
            // This alternate branch uses exact child IDs, not findNode's longest prefix.
            val harmonic = child(root, 2)?.let { child(it, 1) }
            if (harmonic != null) {
                var pitchDistance = 0; var stringDistance = 0
                fun inspect(node: Node) {
                    for (sample in node.samples) {
                        // Native int32 subtraction/abs retain their signed overflow behavior.
                        val pitch = abs(coordinate.midi - coordinates[sample].midi)
                        val string = abs(coordinate.stringIndex - coordinates[sample].stringIndex)
                        if (selected == null || pitch < pitchDistance || pitch == pitchDistance && string < stringDistance) {
                            selected = sample; pitchDistance = pitch; stringDistance = string
                        }
                    }
                    node.children.forEach(::inspect)
                }
                inspect(harmonic)
            }
        }
        if (selected == null && defaultRoot != null) {
            selected = nodeFind(defaultRoot, intArrayOf(articulation.attack, articulation.technique, articulation.variation), coordinate)
        }
        return selected
    }

    private fun child(node: Node, id: Int) = node.children.firstOrNull { it.id == id }

    private fun findNode(node: Node, path: IntArray, offset: Int = 0): Node? {
        if (offset >= path.size) return null
        val selected = child(node, path[offset]) ?: return null
        return if (offset + 1 < path.size) findNode(selected, path, offset + 1) ?: selected else selected
    }

    private fun nearest(indices: List<Int>, coordinate: SampleCoordinate): Int? {
        var selected: Int? = null; var distance = 0f
        for (sample in indices) {
            val next = coordinate.distance(coordinates[sample])
            if (selected == null || next < distance) { selected = sample; distance = next }
        }
        return selected
    }

    private fun nodeFind(root: Node, path: IntArray, coordinate: SampleCoordinate): Int? {
        var selected = findNode(root, path) ?: return null
        if (selected.samples.isEmpty()) selected = fallback(selected, path.size) ?: return null
        return nearest(selected.samples, coordinate)
    }

    private fun fallback(start: Node, pathLength: Int): Node? {
        val visited = java.util.Collections.newSetFromMap(IdentityHashMap<Node, Boolean>())
        visited.add(start)
        fun descendant(node: Node): Node? {
            // Native searches non-visited children before returning a nonempty parent.
            for (child in node.children) {
                if (child in visited) continue
                descendant(child)?.let { return it }
                visited.add(child)
            }
            return node.takeIf { it.samples.isNotEmpty() }
        }
        var node: Node? = start
        while (node != null) {
            descendant(node)?.let { return it }
            if (pathLength <= 1) return null
            // Observed native callback retains the original path during parent recursion.
            node = parents[node]
        }
        return null
    }
}
