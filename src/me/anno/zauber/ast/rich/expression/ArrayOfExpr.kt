package me.anno.zauber.ast.rich.expression

import me.anno.zauber.ast.rich.expression.constants.NumberExpression
import me.anno.zauber.ast.simple.ASTSimplifier.unitInstance
import me.anno.zauber.ast.simple.SimpleBlock
import me.anno.zauber.ast.simple.constants.SimpleNumber
import me.anno.zauber.ast.simple.controlflow.FlowResult
import me.anno.zauber.ast.simple.expression.SimpleAllocateInstance
import me.anno.zauber.ast.simple.expression.SimpleConstructorCall
import me.anno.zauber.ast.simple.expression.SimpleMethodCall
import me.anno.zauber.ast.simple.fields.SimpleField
import me.anno.zauber.scope.Scope
import me.anno.zauber.scope.ScopeInitType
import me.anno.zauber.typeresolution.ParameterList
import me.anno.zauber.typeresolution.ResolutionContext
import me.anno.zauber.types.Specialization
import me.anno.zauber.types.Type
import me.anno.zauber.types.Types
import me.anno.zauber.types.impl.ClassType

class ArrayOfExpr(val values: List<Expression>, val type: Type, scope: Scope, origin: Long) :
    Expression(scope, origin) {

    override fun toStringImpl(depth: Int): String {
        return "arrayOf(${values.joinToString(",") { "\n  " + it.toString(depth) }})"
    }

    override fun resolveValueType(context: ResolutionContext): Type {
        return Types.Array.withTypeParameter(type.specialize(context))
    }

    override fun clone(scope: Scope) = ArrayOfExpr(values.map { it.clone(scope) }, type, scope, origin)
    override fun hasLambdaOrUnknownGenericsType(context: ResolutionContext): Boolean = false
    override fun splitsScope(): Boolean = false

    override fun needsBackingField(methodScope: Scope): Boolean {
        return values.any { it.needsBackingField(methodScope) }
    }

    override fun isResolved(): Boolean = values.all { it.isResolved() }

    override fun resolveImpl(context: ResolutionContext): Expression {
        val elementType = type.specialize(context)
        val subContext = context
            .withAllowTypeless(false)
            .withTargetType(elementType)
        return ArrayOfExpr(values.map { it.resolve(subContext) }, elementType, scope, origin)
    }

    override fun forEachExpression(callback: (Expression) -> Unit) {
        for (entry in values) callback(entry)
    }

    override fun simplify(
        context: ResolutionContext,
        block0: SimpleBlock,
        flow0: FlowResult,
        needsValue: Boolean,
        contextExpr: Expression?
    ): FlowResult {
        var flowI = flow0
        var blockI = block0

        val arrayType = resolveValueType(context)
        val instanceType = (arrayType as ClassType).typeParameters!![0]

        val subContext = context
            .withAllowTypeless(false)
            .withTargetType(instanceType)

        val values: List<SimpleField> = values.map { value ->
            flowI = value.simplify(subContext, blockI, flowI, true)
            val blockIv = flowI.value ?: return flowI
            blockI = blockIv.block
            blockIv.value
        }

        val array = blockI.field(arrayType)
        val size = blockI.field(Types.Int)
        blockI.add(SimpleNumber(size, NumberExpression("${values.size}", scope, origin)))
        val allocateParams = listOf(size.use())
        blockI.add(
            SimpleAllocateInstance(
                array, arrayType, allocateParams,
                Specialization(arrayType), scope, origin
            )
        )
        // handle error?

        // find constructor method
        val unit = unitInstance(blockI.graph, scope, origin)

        val constrScope = Types.Array.clazz[ScopeInitType.AFTER_OVERRIDES].children
            .firstOrNull { child ->
                val constr = child.selfAsConstructor
                constr != null && constr.valueParameters.size == 1 && constr.valueParameters[0].type == Types.Int
            } ?: error("Missing Array(size: Int) constructor")

        val specParams = ParameterList(Types.Array.clazz.typeParameters, listOf(instanceType))
        val specialization = Specialization(constrScope, specParams)
        val constr = SimpleConstructorCall(
            unit, true, array.use(),
            specialization, allocateParams, scope, origin
        )
        blockI.add(constr)
        // todo handle OOM error

        if (values.isNotEmpty()) {
            // find assignment method
            val setMethod = Types.Array.clazz
                .methods.firstOrNull {
                    it.name == "set" && it.valueParameters.size == 2 &&
                            it.valueParameters[0].type == Types.Int
                }
                ?: error("Missing Array(size: Int).set(index,value) method")

            val setMethodSpec = specialization.withScope(setMethod.memberScope)

            // execute all assignments
            for (i in values.indices) {
                val indexExpr = NumberExpression("$i", scope, origin) // could be cached
                val index = blockI.field(Types.Int, indexExpr)
                blockI.add(SimpleNumber(index, indexExpr))
                val valueParameters = listOf(index.use(), values[i].use())

                blockI.add(
                    SimpleMethodCall(
                        unit, setMethod, array.use(),
                        null, setMethodSpec, valueParameters,
                        scope, origin
                    )
                )
            }
        }

        return flowI.withValue(array, blockI)
    }

}