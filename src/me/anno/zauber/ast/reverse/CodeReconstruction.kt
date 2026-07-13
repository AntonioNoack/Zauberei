package me.anno.zauber.ast.reverse

import me.anno.zauber.ast.reverse.GraphToClass.convertGraphToClass
import me.anno.zauber.ast.reverse.LinearTreeSimplification.simplifyTree
import me.anno.zauber.ast.simple.SimpleBlock
import me.anno.zauber.ast.simple.SimpleGraph
import me.anno.zauber.logging.LogManager

/**
 * Converts an arbitrary graph back into if-else and while-blocks.
 * */
object CodeReconstruction {

    private val LOGGER = LogManager.getLogger(CodeReconstruction::class)

    fun createCodeFromGraph(graph: SimpleGraph, onlyCheapSimplifications: Boolean = false) {

        if (isSolved(graph)) return

        // println("code before reconstruction: $graph")

        var fine = true
        while (!isSolved(graph)) {
            // cheap simplifications
            if (simplifySequence(graph)) continue
            if (simplifyBranch(graph)) continue
            if (simplifyLoop(graph)) continue

            if (onlyCheapSimplifications) {
                // early exit
                return
            }

            // more expensive ones
            if (simplifyTree(graph)) continue

            if (fine) {
                /*
                * println("Graph needs tail-calls")
                println(graph)
                println()
                * */
                fine = false
            }

            if (breakAtStrongestKnot(graph)) continue

            // last option
            return convertGraphToClass(graph)
        }

        if (LOGGER.isInfoEnabled) LOGGER.info("code after reconstruction: $graph")
    }

    private fun simplifySequence(graph: SimpleGraph): Boolean {
        var changed = false
        for (i in graph.blocks.indices) {
            val curr = graph.blocks[i]
            val next = curr.nextBranch
            if (!curr.isBranch &&
                next != null && next.isOnlyInput(curr)
            ) {

                if (curr === next) {
                    if (LOGGER.isInfoEnabled) LOGGER.info("Simple loop in ${curr.idStr()}")
                    // simple infinite loop
                    val newNode = graph.addBlock()
                    newNode.instructions.addAll(curr.instructions)
                    curr.instructions.clear()
                    curr.add(SimpleLoop(newNode))
                    curr.ifBranch = null
                    curr.elseBranch = null
                } else {
                    if (LOGGER.isInfoEnabled) LOGGER.info("Concat ${curr.idStr()} and ${next.idStr()}")
                    // concat-join
                    curr.instructions.addAll(next.instructions)
                    curr.ifBranch = next.ifBranch
                    curr.elseBranch = next.elseBranch
                    curr.branchCondition = next.branchCondition
                    next.clear()
                }
                changed = true
            }
        }
        return changed
    }

    private fun simplifyBranch(graph: SimpleGraph): Boolean {
        var changed = false

        graph.validateBlocks()

        for (i in graph.blocks.indices) {
            val curr = graph.blocks[i]
            val nextT = curr.ifBranch
            val nextF = curr.elseBranch
            val after = nextT?.nextBranch
            if (curr.isBranch &&
                nextT != null && nextT.isOnlyInput(curr) &&
                nextF != null && nextF.isOnlyInput(curr) &&
                nextT.isOnlyOutput(after) &&
                nextF.isOnlyOutput(after) &&
                after?.isEntryPoint != true
            ) {

                if (LOGGER.isInfoEnabled) LOGGER.info("Diamond in ${curr.idStr()} -> ${nextT.idStr()} | ${nextF.idStr()} -> ${after?.idStr()}")

                curr.instructions.add(SimpleBranch(curr.branchCondition!!, nextT, nextF))
                nextT.removeLinks()
                nextF.removeLinks()
                curr.removeLinks()
                curr.nextBranch = after
                changed = true

                graph.validateBlocks()

            }
        }
        return changed
    }

    private fun simplifyLoop(graph: SimpleGraph): Boolean {

        // positive loop
        for (i in graph.blocks.indices) {
            val curr = graph.blocks[i]
            val body = curr.ifBranch
            val after = curr.elseBranch
            if (curr.isBranch &&
                body != null &&
                body.nextBranch != null &&
                body.isOnlyInput(curr) &&
                body !== after &&
                body.isOnlyOutput(curr)
            ) {
                createSimplifyLoop(graph, curr, body, after, false)
                return true
            }
        }

        // negative loop
        for (i in graph.blocks.indices) {
            val curr = graph.blocks[i]
            val body = curr.elseBranch
            val after = curr.ifBranch
            if (curr.isBranch &&
                body != null &&
                body.nextBranch != null &&
                body.isOnlyInput(curr) &&
                body !== after &&
                body.isOnlyOutput(curr)
            ) {
                createSimplifyLoop(graph, curr, body, after, true)
                return true
            }
        }

        // infinite loop
        for (i in graph.blocks.indices) {
            val curr = graph.blocks[i]
            val body = curr.ifBranch
            if (!curr.isBranch && curr != body &&
                body != null && !body.isBranch &&
                body.nextBranch == body &&
                body.inputBlocks.size == 2 // body and curr
            ) {
                createSimplifyLoop(graph, curr, body, null, false)
                return true
            }
        }
        return false
    }

    fun createSimplifyLoop(
        graph: SimpleGraph,
        curr: SimpleBlock,
        body: SimpleBlock, after: SimpleBlock?,
        negate: Boolean
    ) {
        val condition = curr.branchCondition
        if (LOGGER.isInfoEnabled) LOGGER.info("Loop in ${curr.idStr()} -> while $condition != $negate { ${body.idStr()} } -> ${after?.idStr()}")

        val loop = if (condition != null) {
            val conditionBlock = graph.addBlock()
            conditionBlock.instructions.addAll(curr.instructions)
            curr.instructions.clear()
            SimpleLoop(conditionBlock, condition, negate, body)
        } else {
            check(!negate)
            SimpleLoop(body)
        }

        curr.instructions.add(loop)
        curr.elseBranch = null
        curr.ifBranch = after
        curr.branchCondition = null

        body.ifBranch = null
        body.elseBranch = null
        body.branchCondition = null
    }

    /**
     * breaks the graph on the block with the most inputs in hope of making more branches possible
     * */
    private fun breakAtStrongestKnot(graph: SimpleGraph): Boolean {
        val bestBlock = graph.blocks
            //.filter { !it.isEntryPoint }
            .filter { it.inputBlocks.size > 1 }
            .maxByOrNull { it.inputBlocks.size }
            ?: return false

        bestBlock.isEntryPoint = true
        val inputNodes = ArrayList(bestBlock.inputBlocks)
        for (inputNode in inputNodes) {
            if (inputNode.isBranch) {
                if (inputNode.ifBranch == bestBlock) {
                    if (LOGGER.isInfoEnabled) LOGGER.info("Breaking branch/if in ${inputNode.idStr()}")
                    inputNode.ifBranch = graph.createTailCall(bestBlock)
                } else {
                    if (LOGGER.isInfoEnabled) LOGGER.info("Breaking branch/else in ${inputNode.idStr()}")
                    check(inputNode.elseBranch == bestBlock) { "Expected elseBranch to be bestNode" }
                    inputNode.elseBranch = graph.createTailCall(bestBlock)
                }
            } else {
                if (LOGGER.isInfoEnabled) LOGGER.info("Breaking sequence in ${inputNode.idStr()}")
                check(inputNode.nextBranch == bestBlock)
                inputNode.ifBranch = null
                inputNode.elseBranch = null
                inputNode.branchCondition = null
                inputNode.instructions.add(SimpleTailCall(bestBlock))
            }
        }
        return true
    }

    private fun SimpleGraph.createTailCall(target: SimpleBlock): SimpleBlock {
        val node = addBlock()
        node.instructions.add(SimpleTailCall(target))
        return node
    }

    private fun isSolved(graph: SimpleGraph): Boolean {
        return graph.blocks.all {
            it.branchCondition == null &&
                    it.ifBranch == null &&
                    it.elseBranch == null
        }
    }
}