package me.anno.zauber.ast.simple

import me.anno.generation.cpp.CppSourceGenerator.Companion.nativeCppTypes
import me.anno.utils.StringStyles
import me.anno.utils.StringStyles.GREEN
import me.anno.utils.StringStyles.style
import me.anno.zauber.SpecialFieldNames.OUTER_FIELD_NAME
import me.anno.zauber.ast.rich.controlflow.ReturnExpression
import me.anno.zauber.ast.rich.expression.Expression
import me.anno.zauber.ast.rich.expression.resolved.ResolvedCallExpression
import me.anno.zauber.ast.rich.expression.resolved.ResolvedGetFieldExpression
import me.anno.zauber.ast.rich.member.Field
import me.anno.zauber.ast.simple.fields.*
import me.anno.zauber.logging.LogManager
import me.anno.zauber.scope.Scope
import me.anno.zauber.scope.ScopeInitType
import me.anno.zauber.typeresolution.Inheritance.isSubTypeOf
import me.anno.zauber.types.Specialization
import me.anno.zauber.types.Type
import me.anno.zauber.types.impl.ClassType
import me.anno.zauber.types.impl.GenericType
import me.anno.zauber.types.impl.arithmetic.AndType
import me.anno.zauber.types.impl.arithmetic.NullType
import me.anno.zauber.types.impl.arithmetic.UnionType
import me.anno.zauber.types.impl.unresolved.UnresolvedType

class SimpleBlock(val graph: SimpleGraph) {

    var isEntryPoint = false

    var branchCondition: SimpleField? = null
        set(value) {
            check(field == null || value == null)
            field = value
        }

    var ifBranch: SimpleBlock? = null
        set(value) {
            unlinkTo(field)
            field = value
            linkTo(value)
        }

    var elseBranch: SimpleBlock? = null
        set(value) {
            unlinkTo(field)
            field = value
            linkTo(value)
        }

    val inputBlocks = ArrayList<SimpleBlock>(4)

    var nextBranch: SimpleBlock?
        get() = ifBranch
        set(value) {
            ifBranch = value
        }

    val isBranch get() = branchCondition != null && ifBranch != elseBranch

    private fun linkTo(value: SimpleBlock?) {
        value ?: return
        value.inputBlocks.add(this)
    }

    private fun unlinkTo(value: SimpleBlock?) {
        value ?: return
        value.inputBlocks.remove(this)
    }


    fun clear() {
        check(!isEntryPoint)
        removeLinks()
        instructions.clear()
    }

    fun removeLinks() {
        ifBranch = null
        elseBranch = null
        branchCondition = null
    }

    fun isOnlyInput(input: SimpleBlock): Boolean {
        return !isEntryPoint && inputBlocks.all { it == input }
    }

    fun isOnlyOutput(output: SimpleBlock?): Boolean {
        val ifBranch = ifBranch
        val elseBranch = elseBranch
        return (ifBranch == output || ifBranch == null) && (elseBranch == output || elseBranch == null)
    }

    val id: Int =
        if (graph.blocks.isNotEmpty()) graph.blocks.last().id + 1
        else 0

    val instructions = ArrayList<SimpleInstruction>()

    fun add(expr: SimpleInstruction) {
        instructions.add(expr)
    }

    fun add0(expr: SimpleInstruction) {
        instructions.add(0, expr)
    }

    fun add0(expr: List<SimpleInstruction>) {
        instructions.addAll(0, expr)
    }

    fun field(type: Type, constantRef: Expression? = null): SimpleField =
        graph.field(type, constantRef)

    fun thisField(
        type: Type, thisScope: Scope, scope: Scope, origin: Long,
        specialization: Specialization,
        contextExpr: Expression?
    ): SimpleField {
        thisScope[ScopeInitType.AFTER_DISCOVERY]
        if (thisScope.isObjectLike()) {
            // are objects comptime? yes
            val dst = field(thisScope.typeWithArgs2)
            add(SimpleGetObject(dst, thisScope, scope, origin))
            return dst
        } else {
            val isAmbiguous = graph.selfField != null
            val isExplicitSelf = if (isAmbiguous) {
                val selfType = graph.selfField!!.type
                val thisType = graph.thisField!!.type
                if (isSubTypeOf(type, selfType)) {
                    println("$selfType is a $type -> explicitSelf = true")
                    true
                } else if (isSubTypeOf(type, thisType)) {
                    println("$thisType is a $type -> explicitSelf = false")
                    false
                } else when (contextExpr) {
                    is ReturnExpression -> true
                    is ResolvedCallExpression -> {
                        val methodOrField = contextExpr.callable.resolved
                        val methodOwner = (methodOrField as? Field)?.ownerScope ?: (methodOrField.scope.parent!!)
                        !methodOwner.isInsideExpression() && !methodOwner.isMethodLike()
                    }
                    is ResolvedGetFieldExpression -> {
                        val methodOrField = contextExpr.field.resolved
                        val fieldOwner = methodOrField.ownerScope
                        !fieldOwner.isInsideExpression() && !fieldOwner.isMethodLike()
                    }
                    else -> TODO("$thisScope is ambiguous, what does $contextExpr ${contextExpr?.javaClass?.simpleName} indicate?")
                }/*.apply {
                    println("isExplicitSelf? $contextExpr -> $this")
                }*/
            } else false

            if (thisScope.isClassLike()) {
                val ownerScope = graph.method.ownerScope
                if (ownerScope.inheritsFrom(thisScope)) {
                    return thisField(type, ownerScope, scope, origin, specialization, contextExpr)
                }

                if (ownerScope.isInnerClassOf(thisScope)) {
                    return createOuterFieldChain(ownerScope, thisScope, scope, origin, specialization, contextExpr)
                }
            }

            // println("Creating simple-this: $thisScope, $isExplicitSelf, type: $type")
            val localField = if (isExplicitSelf) graph.selfField else graph.thisField
            if (localField == null) {
                error("Missing localField $isExplicitSelf in ${graph.method}")
            }

            val dst = field(localField.type)

            // todo 'this' from the Lambda gets incorrectly passed here...
            //  it should be defined in the ResolutionContext somehow...
            //  -> if it is inlined, this should be impossible, because the fields should be local fields
            //  -> else, we should be in our own method-body, and it should be defined well

            if (!isSubTypeOf(dst.type, localField.type)) {
                LOGGER.warn(
                    "Cannot assign $localField to $dst, ${(dst.type as? ClassType)?.clazz?.scopeType}\n" +
                            "  isAmbiguous: $isAmbiguous\n" +
                            "  isExplicitSelf: $isExplicitSelf\n" +
                            "  ThisScope: $thisScope\n" +
                            "  this: ${graph.thisField}\n" +
                            "  self: ${graph.selfField}\n" +
                            "  type: $type"
                )
            }

            add(SimpleGetLocalField(dst, localField, scope, origin))
            dst.fromLocalField = localField
            return dst
        }
    }

    private fun createOuterFieldChain(
        innerScope: Scope, outerScope: Scope,
        scope: Scope, origin: Long,
        specialization: Specialization,
        contextExpr: Expression?
    ): SimpleField {

        check(innerScope.isInnerClassOf(outerScope))

        var currField = thisField(innerScope.typeWithArgs, innerScope, scope, origin, specialization, contextExpr)

        var innerScopeI = innerScope
        while (innerScopeI != outerScope) {
            val outerScopeI = innerScopeI.parent!!

            val dst = field(outerScopeI.typeWithArgs2) // todo specialize
            val field1 = innerScopeI.fields.first { it.name == OUTER_FIELD_NAME }

            println("Step: ${innerScopeI}.$field1 -> $dst, calling on $currField")

            val spec = Specialization(field1.fieldScope, specialization.typeParameters)
            add(SimpleGetClassField(dst, currField, field1, spec, scope, origin))
            currField = dst
            innerScopeI = outerScopeI
        }
        return currField
    }

    // todo allow A<B: C|D>: B()? could be nice to use...

    private fun Scope.inheritsFrom(superScope: Scope): Boolean {
        if (this == superScope) return false
        for (superCall in superCalls) {
            if (superCall.type.clazz == superScope) return true
            val isGrandChild = superCall.type.clazz.inheritsFrom(superScope)
            if (isGrandChild) return true
        }
        return false
    }

    override fun toString(): String {
        val builder = headerStr()
        /*val or = onReturn
        if (or != null) builder.append('r').append(or.blockId)
        val ot = onThrow
        if (ot != null) builder.append('t').append(ot.handler.blockId)*/
        builder.append(':')
        for (instr in instructions) {
            builder.append("\n  ").append(instr)
        }
        return builder.toString()
    }

    fun idStr() = style("b$id", GREEN)

    fun headerStr(): StringBuilder {
        val builder = StringBuilder()
        builder.append(idStr()).append('[')

        if (isEntryPoint) builder.append("->|")

        if (nextBranch == null) {
            builder.append(StringStyles.style("end", StringStyles.RED))
        } else if (branchCondition != null) {
            builder.append(branchCondition).append(" ? ")
                .append(style("b${ifBranch!!.id}", GREEN)).append(" : ")
                .append(style("b${elseBranch!!.id}", GREEN))
        } else {
            builder.append(style("b${nextBranch!!.id}", GREEN))
        }
        builder.append(']')
        return builder
    }

    fun isEmpty(): Boolean {
        return !isBranch && nextBranch == null &&
                instructions.isEmpty()
    }

    fun nextOrSelfIfEmpty(): SimpleBlock {
        if (isEmpty()) return this
        val next = graph.addBlock()
        nextBranch = next
        return next
    }

    companion object {
        private val LOGGER = LogManager.getLogger(SimpleBlock::class)
    }
}