package me.anno.zauber.ast.reverse

import me.anno.utils.CollectionUtils.sortedByTopology
import me.anno.zauber.ast.rich.expression.constants.NumberExpression
import me.anno.zauber.ast.simple.SimpleBlock
import me.anno.zauber.ast.simple.SimpleGraph
import me.anno.zauber.ast.simple.constants.SimpleNumber
import me.anno.zauber.ast.simple.fields.LocalField
import me.anno.zauber.ast.simple.fields.SimpleSetLocalField
import me.anno.zauber.types.Types
import java.util.*
import kotlin.math.min

object LinearTreeSimplification {

    /**
     * Check if there is two nodes A, B, where all of A ends up in B, and between, there is no loops (sortable),
     * if so, it can be easily transformed into an if-else-tree.
     * */
    fun simplifyTree(graph: SimpleGraph): Boolean {

        graph.validateBlocks()

        val region = findLinearRegion(graph) ?: return false
        val nodesInRegion = collectRegionNodes(region.start, region.end)

        val ordered = nodesInRegion.sortedByTopology { block ->
            if (block == region.start) emptyList() else block.inputBlocks
        } ?: error("Content must be sortable")

        check(ordered.first() === region.start)
        check(ordered.last() === region.end)
        convertTreeToIfs(graph, ordered)

        // println("Graph after Tree-ification: $graph")

        return true
    }

    private fun findCommonParent(a: SimpleBlock, b: SimpleBlock, depths: IntArray): SimpleBlock {
        var a = a
        var b = b
        while (a !== b) {
            val da = depths[a.id]
            val db = depths[b.id]
            check(da >= 0)
            check(db >= 0)
            val targetDepth = if (da == db) da - 1 else min(da, db)
            a = getParentAtDepth(a, depths, targetDepth)
            b = getParentAtDepth(b, depths, targetDepth)
        }
        return a
    }

    private fun getParentAtDepth(
        node: SimpleBlock,
        depths: IntArray,
        targetDepth: Int,
    ): SimpleBlock {
        var node = node
        var depth = depths[node.id]
        check(depth >= 0)
        check(targetDepth >= 0) { "Target-depth cannot be negative" }
        while (depth > targetDepth) {
            if (node.inputBlocks.isEmpty()) {
                error("How can ${node.idStr()} be unreachable?")
            }
            node = node.inputBlocks.minBy {
                val depth = depths[it.id]
                check(depth >= 0)
                depth
            }
            depth = depths[node.id]
            check(depth >= 0)
        }
        return node
    }

    private fun findDominatorParent(node: SimpleBlock, depths: IntArray): SimpleBlock {
        return node.inputBlocks
            .reduce { a, b -> findCommonParent(a, b, depths) }
    }

    private fun convertTreeToIfs(graph: SimpleGraph, nodes: List<SimpleBlock>) {

        // println("Tree before conversion: $graph")

        val depth = computeDepth(graph, nodes)
        val scope = graph.method.scope
        val origin = graph.method.origin
        val state = createField(graph)

        // create assignments for states
        for (i in 0 until nodes.lastIndex) { // end node doesn't need an assignment
            val node = nodes[i]
            val list = node.instructions
            val value = graph.field(Types.Int).use()
            val instr = if (node.isBranch) {
                SimpleConditionalInt(
                    value, node.branchCondition!!,
                    node.ifBranch!!.id,
                    node.elseBranch!!.id,
                    scope, origin
                )
            } else {
                SimpleNumber(value, NumberExpression(i.toString(), scope, origin))
            }
            list.add(instr)
            list.add(SimpleSetLocalField(state, value, scope, origin))
        }

        // fill in nodes
        for (i in 1 until nodes.lastIndex) {
            val node = nodes[i]
            val dst = findDominatorParent(node, depth).instructions
            val enterNode = graph.field(Types.Boolean)
            dst.add(SimpleLocalFieldEqualsInt(enterNode, state, node.id, scope, origin))
            dst.add(SimpleBranch(enterNode.use(), node, null))
        }

        // first links to last
        val node0 = nodes.first()
        node0.ifBranch = nodes.last()
        node0.elseBranch = null
        node0.branchCondition = null

        // nothing else is linked; end remains unchanged
        for (i in 1 until nodes.lastIndex) {
            val node = nodes[i]
            node.ifBranch = null
            node.elseBranch = null
            node.branchCondition = null
        }
    }

    private fun computeDepth(graph: SimpleGraph, nodes: List<SimpleBlock>): IntArray {
        val depths = IntArray(nodes.maxOf { it.id } + 1)
        depths.fill(-1)
        depths[nodes.first().id] = 0 // start at depth 0

        val end = nodes.last()
        for (i in 1 until nodes.size) {
            val node = nodes[i]
            if (node.inputBlocks.isEmpty()) {
                println(graph)
                error("Node ${node.idStr()} has no inputs")
            }
            val depth = node.inputBlocks.maxOf { input ->
                val depth = depths[input.id]
                check(depth >= 0 || node === end) {
                    "Depth cannot be negative, because nodes are sortable, " +
                            "${nodes.map { it.headerStr() }}, ${node.idStr()} by ${node.inputBlocks.map { it.idStr() }}"
                }
                depth
            } + 1
            depths[node.id] = depth
        }
        return depths
    }


    private fun createField(graph: SimpleGraph): LocalField {
        return graph.createLocalField(null, "treeState", Types.Int, true)
    }

    private data class Region(
        val start: SimpleBlock,
        val end: SimpleBlock,
        val nodes: Set<SimpleBlock>
    )

    private fun findLinearRegion(graph: SimpleGraph): Region? {
        for (start in graph.blocks) {
            val end = findUniqueExit(start)
            if (end != null && end != start) {
                val nodes = collectRegionNodes(start, end)
                if (nodes.size < 3) continue
                return Region(start, end, nodes)
            }
        }
        return null
    }

    private enum class State {
        VISITING,
        DONE
    }

    private fun collectRegionNodes(start: SimpleBlock, end: SimpleBlock): Set<SimpleBlock> {
        val visited = IdentityHashMap<SimpleBlock, Unit>()
        fun dfs(node: SimpleBlock) {
            if (visited.put(node, Unit) != null) return
            if (node === end) return

            node.ifBranch?.let(::dfs)
            node.elseBranch?.let(::dfs)
        }

        dfs(start)
        validateRegionNodes(start, end, visited.keys)
        return visited.keys
    }

    private fun validateRegionNodes(start: SimpleBlock, end: SimpleBlock, visited: Set<SimpleBlock>) {
        check(end in visited)
        for (node in visited) {
            if (node === start || node === end) continue
            for (input in node.inputBlocks) {
                check(input in visited) {
                    println(start.graph)
                    "Expected ${input.headerStr()} to be in ${start.headerStr()} .. ${end.headerStr()}"
                }
            }
        }
    }

    private fun findUniqueExit(start: SimpleBlock): SimpleBlock? {

        val state = IdentityHashMap<SimpleBlock, State>()
        val memo = IdentityHashMap<SimpleBlock, SimpleBlock?>()

        fun dfs(node: SimpleBlock): SimpleBlock? {

            val result1 = memo[node]
            if (result1 != null) return result1

            when (state[node]) {
                State.VISITING -> return null // cycle detected
                State.DONE -> return memo[node]
                null -> {}
            }

            state[node] = State.VISITING

            val result = when {
                node.isBranch -> {
                    val exitT = dfs(node.ifBranch!!)
                    val exitF = dfs(node.elseBranch!!)
                    if (exitT == null || exitF == null) null
                    else if (exitT == exitF) exitT else null
                }
                node.nextBranch == null -> node  // terminal node
                else -> dfs(node.nextBranch!!)
            }

            state[node] = State.DONE
            memo[node] = result

            return result
        }

        return dfs(start)
    }
}