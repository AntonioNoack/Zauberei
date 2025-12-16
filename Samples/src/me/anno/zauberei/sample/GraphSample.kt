package me.anno.zauberei.sample

import zauberKt.types.Self
import zauberKt.types.TreeLink
import zauberKt.types.TreeType

@TreeType
class Graph<NodeI : Graph.Node> {

    open class Node(
        // todo this could be a separate heap, or embedded...
        @property:TreeLink
        val links: ArrayList<Self>
    )

    data class Link<NodeI>(
        @property:TreeLink
        val from: NodeI,
        @property:TreeLink
        val to: NodeI
    )

    val nodes = ArrayList<NodeI>()
    val links = ArrayList<Link<NodeI>>()

    fun add(node: NodeI) {
        nodes.add(node)
    }

    fun link(a: NodeI, b: NodeI) {
        links.add(Link(a, b))
        a.links.add(b as Self)
    }

}