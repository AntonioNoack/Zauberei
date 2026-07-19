package me.anno.zauber.ast.simple

import me.anno.utils.FullMap
import me.anno.utils.ResetThreadLocal.Companion.threadLocal
import me.anno.utils.StringStyles.bold
import me.anno.utils.assertEquals
import me.anno.zauber.SpecialFieldNames.OUTER_FIELD_NAME
import me.anno.zauber.ast.rich.TokenListIndex.resolveOrigin
import me.anno.zauber.ast.rich.expression.Expression
import me.anno.zauber.ast.rich.expression.resolved.ResolvedGetFieldExpression
import me.anno.zauber.ast.rich.expression.resolved.ResolvedSetFieldExpression
import me.anno.zauber.ast.rich.expression.resolved.ThisExpression
import me.anno.zauber.ast.rich.member.Constructor
import me.anno.zauber.ast.rich.member.Field
import me.anno.zauber.ast.rich.member.FieldGetterSetter.finishGetter
import me.anno.zauber.ast.rich.member.Method
import me.anno.zauber.ast.rich.member.MethodLike
import me.anno.zauber.ast.rich.parameter.NamedParameter
import me.anno.zauber.ast.rich.parameter.Parameter
import me.anno.zauber.ast.simple.controlflow.Flow
import me.anno.zauber.ast.simple.controlflow.FlowResult
import me.anno.zauber.ast.simple.controlflow.SimpleReturn
import me.anno.zauber.ast.simple.controlflow.SimpleThrow
import me.anno.zauber.ast.simple.expression.SimpleAllocateInstance
import me.anno.zauber.ast.simple.expression.SimpleCallable
import me.anno.zauber.ast.simple.expression.SimpleConstructorCall
import me.anno.zauber.ast.simple.expression.SimpleMethodCall
import me.anno.zauber.ast.simple.fields.*
import me.anno.zauber.expansion.MethodOverrides.isJavaClass
import me.anno.zauber.interpreting.ZClass.Companion.needsToBeStored
import me.anno.zauber.logging.LogManager
import me.anno.zauber.scope.Scope
import me.anno.zauber.scope.ScopeInitType
import me.anno.zauber.typeresolution.CallWithNames.resolveNamedParameters
import me.anno.zauber.typeresolution.Inheritance.isSubTypeOf
import me.anno.zauber.typeresolution.ParameterList.Companion.emptyParameterList
import me.anno.zauber.typeresolution.ResolutionContext
import me.anno.zauber.typeresolution.members.MatchScore
import me.anno.zauber.typeresolution.members.ResolvedField
import me.anno.zauber.typeresolution.members.ResolvedMember
import me.anno.zauber.typeresolution.members.ResolvedMethod
import me.anno.zauber.types.Specialization
import me.anno.zauber.types.Type
import me.anno.zauber.types.Types
import me.anno.zauber.types.impl.ClassType

object ASTSimplifier {

    private val LOGGER = LogManager.getLogger(ASTSimplifier::class)

    val voidResult = FlowResult(null, null, null)

    private val cache by threadLocal { HashMap</*Method*/Specialization, SimpleGraph>() }

    val nativeInts by threadLocal {
        Types.run {
            listOf(
                Types.Byte, Types.UByte,
                Types.UShort, Types.Short, Types.Char,
                Types.UInt, Types.Int,
                Types.Long, Types.ULong,
            )
        }
    }

    val nativeFloats by threadLocal {
        Types.run { listOf(Types.Half, Types.Float, Types.Double) }
    }

    val nativeNumbers by threadLocal {
        nativeInts + nativeFloats
    }

    fun unitInstance(graph: SimpleGraph, scope: Scope, origin: Long): SimpleField {
        var field = graph.unitField
        if (field != null) return field
        val block = graph.startBlock
        val type = Types.Unit
        field = block.field(type)
        block.add0(SimpleGetObject(field, type.clazz, scope, origin))
        graph.unitField = field
        return field.use()
    }

    fun unitInstance(graph: SimpleGraph, expr: Expression): SimpleField {
        return unitInstance(graph, expr.scope, expr.origin)
    }

    // todo inline functions
    // todo calculate what errors a function throws,
    //  and handle all possibilities after each call

    fun simplify(method0: Specialization, readOnly: Boolean = false): SimpleGraph {
        check(method0.isMethodLike())
        val graph = cache.getOrPut(method0) {
            method0.use { /* use scope */ simplifyImpl(method0) }
        }
        return if (readOnly) graph else graph.clone()
    }

    private fun simplifyImpl(method0: Specialization): SimpleGraph {
        val context = ResolutionContext(method0.scope!!, null, method0, true, null)

        if (LOGGER.isInfoEnabled) LOGGER.info(
            "${bold("Simplifying")} ${method0.scope}, ${method0.method}" +
                    "\n  ${method0.method.body}"
        )

        val expr = method0.method.getSpecializedBody(method0)
            ?: error("Specialized body is null? For $method0")

        val graph = SimpleGraph(method0)
        graph.initializeSpecialFields(context)

        val flow0 = FlowResult(Flow(unitInstance(graph, expr), graph.startBlock), null, null)
        val flow1 = expr.simplify(context, graph.startBlock, flow0, false)
        finishFlows(flow1, method0, expr)

        if (LOGGER.isInfoEnabled) LOGGER.info("\n${bold("Simplified")} $method0:\n  $flow1\n  to $graph\n")
        return graph
    }

    private fun finishFlows(flow1: FlowResult, method0: Specialization, expr: Expression) {

        val flow1v = flow1.value
        if (flow1v != null) {
            check(flow1v.block.nextBranch == null) {
                "Last block must not continue flow: ${flow1v.block}"
            }
            // missing return -> we do it ourselves
            // validate method returns Unit
            val methodReturnType = method0.method.returnType
            assertEquals(Types.Unit, methodReturnType) {
                "Expected $method0 to return Unit, because it's missing an explicit return"
            }
            // push object & return it
            val unitType = Types.Unit
            val instance = flow1v.block.field(unitType)
            flow1v.block.add(SimpleGetObject(instance, unitType.clazz, expr.scope, expr.origin))
            addSimpleReturn(flow1v.block.graph, method0, instance, expr, flow1v.block)
        }

        val flow1r = flow1.returned
        if (flow1r != null) {
            addSimpleReturn(flow1r.block.graph, method0, flow1r.value, expr, flow1r.block)
        }

        val flow1t = flow1.thrown
        flow1t?.block?.add(SimpleThrow(flow1t.value.use(), expr.scope, expr.origin))
    }

    fun addSimpleReturn(
        graph: SimpleGraph, method0: Specialization,
        value: SimpleField, expr: Expression, block: SimpleBlock
    ) {

        val expectedReturnType = graph.expectedReturnType
        check(
            isSubTypeOf(expectedReturnType, value.type) ||
                    // Java needs some exceptions..., e.g. java.lang.Class.getClassLoader() only has spotty types for local fields
                    isJavaClass(graph.method.ownerScope)
        ) {

            println(graph)

            "Expected return value in ${method0.method} " +
                    "to match $expectedReturnType, got ${value.type}"
        }

        block.add(SimpleReturn(value.use(), expr.scope, expr.origin))
    }

    fun needsFieldByParameter(parameter: Any?): Boolean {
        if (parameter == null) return true
        if (parameter !is Parameter) return false
        return parameter.isVal || parameter.isVar
    }

    fun simplifyJump(expr: Expression, flow0: FlowResult, target: SimpleBlock?): FlowResult {
        val block0 = flow0.value ?: return flow0
        check(target != null) { "Failed to resolve jump target to $expr" }
        block0.block.nextBranch = target
        return flow0.withoutValue() // our flow ends here, nothing can come after
    }

    private fun fieldHasSensibleType(context: ResolutionContext, field: Field): Boolean {
        field.ownerScope[ScopeInitType.AFTER_RESOLVE_TYPES]

        // println("Resolving type of $field in $context")
        val type0 = field.resolveValueType(context)
        return type0 !is ClassType || !type0.clazz.isObjectLike()
    }

    private fun isLocalField(graph: SimpleGraph, field: Field): Boolean {
        var fieldScope = field.scope
        while (true) {
            if (fieldScope.isMethodLike() || fieldScope.isClassLike()) break
            fieldScope = fieldScope.parent!!
        }

        return graph.method.memberScope == fieldScope
    }

    fun simplifyGetField(
        context: ResolutionContext,
        expr: ResolvedGetFieldExpression,
        block0: SimpleBlock,
        flow0: FlowResult
    ): FlowResult {

        var expr = expr
        var canUseGetter = true
        if (expr.field.isBackingField) {
            canUseGetter = false
            // probably could be smaller and easier...
            val backed = expr.field.resolved.getBackedField()!!
            val realField = backedToRealField(backed, expr.field, expr.context)
            expr = ResolvedGetFieldExpression(
                ThisExpression(backed.ownerScope, expr.scope, expr.origin),
                realField, expr.scope, expr.origin
            )
        }

        val field = expr.field.resolved
        if (field.isObjectInstance()) {
            val dst = block0.field(field.ownerScope.typeWithArgs2)
            val value = SimpleGetObject(dst, field.ownerScope, expr.scope, expr.origin)
            block0.add(value)
            return flow0.withValue(dst)
        }

        val selfType = expr.self.resolveValueType(context.withTargetType(/*field.valueType*/ null))
        val contextI = context.withSelfType(selfType)
        if (!fieldHasSensibleType(contextI, field)) {
            LOGGER.info("Skipping non-sense getter for ${field.ownerScope}.${field.name} at ${resolveOrigin(expr.origin)}")
            return flow0
        }

        val valueType = expr.resolveValueType(contextI)
        // println("valueType for $expr: $valueType")

        if (isLocalField(block0.graph, field)) {
            val localField = block0.graph.getOrPutLocalField(field, contextI)
            val dst = block0.field(valueType)
            block0.add(SimpleGetLocalField(dst, localField, expr.scope, expr.origin))
            dst.fromLocalField = localField
            return flow0.withValue(dst)
        }

        var self0 = expr.self
        if (field.ownerScope.isCompanionObject() &&
            (self0 !is ThisExpression || self0.label != field.ownerScope)
        ) {
            self0 = ThisExpression(field.ownerScope, expr.scope, expr.origin)
        }

        val block1 = self0.simplify(contextI, block0, flow0, true, expr)
        val block1v = block1.value ?: return block1
        val self = block1v.value

        val useGetter = canUseGetter && (
                expr.field.resolved.isOpen() || (
                        !expr.field.isBackingField && (
                                field.hasCustomGetter ||
                                        field.isLateinit() ||
                                        !field.needsToBeStored()
                                )
                        )
                )

        val selfMethod = getMethod(self.type)
        val valueMethod = block0.graph.method

        // todo if we call in an inner method, immediately AST-simplify it, so we know all captured fields

        if (needsCapture(selfMethod, valueMethod, field)) {
            val dst = block0.graph.readCapturedField(valueMethod, field, valueType)
            return block1.withValue(dst, block1v.block)
        }

        val dst = block1v.block.field(valueType)
        if (useGetter) {

            if (field.getter == null) finishGetter(field.ownerScope, field)

            val ownerTypes = (self.type as? ClassType)?.typeParameters ?: emptyParameterList()
            val getter = field.getter ?: error("Missing getter for $field")
            val newContext = contextI.withSpec(Specialization(getter.scope, ownerTypes))
            val resolvedGetter = ResolvedMethod(getter, newContext, expr.scope, MatchScore.zero)
            val dst = block0.field(resolvedGetter.resolveValueType())
            return simplifyCall(
                dst, block1v.block, block1, self, true, null,
                emptyList(), resolvedGetter,
                false, expr.scope, expr.origin
            )
        } else {

            // println("Creating SimpleGetField for $field, self: ${expr.self}")

            var self = self

            // add extra getters for inner classes
            if (self.type is ClassType && self.type.clazz.isInnerClassOf(field.ownerScope)) {
                val clazz = self.type.clazz
                val outerField = clazz.fields.firstOrNull { it.name == OUTER_FIELD_NAME }
                    ?: error("Missing $OUTER_FIELD_NAME field in $clazz for $field")
                val selfDst = block1v.block.field(outerField.valueType!!.specialize(contextI))
                // println("Adding self = self.$field for $clazz -> ${outerField.valueType}")
                val spec = Specialization(outerField.fieldScope, expr.field.specialization.typeParameters)
                val getter = SimpleGetClassField(
                    selfDst, self.use(), outerField,
                    spec, expr.scope, expr.origin
                )
                block1v.block.add(getter)
                self = selfDst
            }

            val getter = SimpleGetClassField(
                dst, self.use(), field,
                expr.field.specialization, expr.scope, expr.origin
            )
            block1v.block.add(getter)
            return block1.withValue(dst, block1v.block)
        }
    }

    fun simplifySetField(
        context: ResolutionContext,
        expr: ResolvedSetFieldExpression,
        block0: SimpleBlock,
        flow0: FlowResult
    ): FlowResult {

        var expr = expr
        var canUseSetter = true
        if (expr.field.isBackingField) {
            canUseSetter = false
            // probably could be smaller and easier...
            val backed = expr.field.resolved.getBackedField()!!
            val realField = backedToRealField(backed, expr.field, expr.context)
            expr = ResolvedSetFieldExpression(
                ThisExpression(backed.ownerScope, expr.scope, expr.origin),
                realField, expr.value, expr.scope, expr.origin
            )
        }

        val field = expr.field.resolved
        val graph = block0.graph

        if (isLocalField(graph, field)) {
            val localField = graph.getOrPutLocalField(field, context)
            val contextI = context.withTargetType(localField.type)
            val block2 = expr.value.simplify(contextI, block0, flow0, true)
            val block2v = block2.value ?: return block2
            val value = block2v.value

            block2v.block.add(SimpleSetLocalField(localField, value.use(), expr.scope, expr.origin))
            return block2.withValue(unitInstance(graph, expr))
        }

        val block1 = expr.self.simplify(context, block0, flow0, true)
        val block1v = block1.value ?: return block1
        val self = block1v.value

        val expectedValueType = field.resolveValueType(context)
        val contextI = context.withTargetType(expectedValueType)
        val block2 = expr.value.simplify(contextI, block1v.block, block1, true)
        val block2v = block2.value ?: return block2
        val value = block2v.value

        val actualValueType = value.type
        var expectedValueTypeI = expectedValueType
        if (field.isLateinit()) expectedValueTypeI = expectedValueTypeI.orNull()

        // check that assignments use the correct type
        check(isSubTypeOf(expectedValueTypeI, actualValueType)) {
            "Expected value for $field-setter to be $expectedValueTypeI, but got $actualValueType"
        }

        // println("block for self: ${block1v.block}")
        // println("block for value: ${block2v.block}")

        if (!fieldHasSensibleType(context, field)) {
            LOGGER.info("Skipping non-sense setter for ${field.ownerScope}.${field.name} at ${resolveOrigin(expr.origin)}")
            return block2
        }

        val useSetter = canUseSetter && (
                expr.field.resolved.isOpen() || (
                        !expr.field.isBackingField &&
                                (field.hasCustomSetter || !field.needsToBeStored())
                        )
                )

        val selfMethod = getMethod(self.type)
        val valueMethod = block0.graph.method

        // todo if we call in an inner method, immediately AST-simplify it, so we know all captured fields

        if (needsCapture(selfMethod, valueMethod, field)) {
            block0.graph.onCapturedField(field)

            TODO("Set captured field $field somehow...")
        }

        if (useSetter) {
            val ownerTypes = (self.type as? ClassType)?.typeParameters ?: emptyParameterList()
            val setter = field.setter ?: error("Missing setter for $field")
            val newContext = context.withSpec(Specialization(setter.scope, ownerTypes))
            val resolvedSetter = ResolvedMethod(setter, newContext, expr.scope, MatchScore.zero)
            val dst = block0.field(Types.Unit)
            return simplifyCall(
                dst, block2v.block, block2, self, true, null,
                listOf(value), resolvedSetter,
                false, expr.scope, expr.origin
            )
        } else {
            block2v.block.add(
                SimpleSetClassField(
                    self.use(), field, value.use(),
                    expr.field.specialization,
                    expr.scope, expr.origin
                )
            )
            return block2.withValue(unitInstance(graph, expr), block2v.block)
        }
    }

    fun backedToRealField(backed: Field, backing: ResolvedField, context: ResolutionContext): ResolvedField {
        val realSpec = Specialization(
            backed.fieldScope,
            context.specialization.typeParameters
        )
        return ResolvedField(
            backed, context.withSpec(realSpec),
            backing.codeScope, backing.matchScore
        )
    }

    fun getMethod(type: Type): MethodLike? {
        val type = type.resolvedName.resolve()
        if (type !is ClassType) return null

        var scope = type.clazz
        while (true) {
            val method = scope.selfAsMethod
            if (method != null) return method

            scope = scope.parentIfSameFile ?: return null
        }
    }

    private fun needsCapture(selfMethod: MethodLike?, valueMethod: MethodLike, field: Field): Boolean {
        return selfMethod != null && selfMethod != valueMethod &&
                valueMethod.scope.parent?.isObjectLike() != true &&
                !field.ownerScope.isObjectLike()
    }

    fun simplifyCall(
        dst: SimpleField,
        block0: SimpleBlock,
        flow0: FlowResult,

        selfField: SimpleField?,
        needsResolution: Boolean,

        thisField: SimpleField?,

        valueParameters: List<SimpleField>,

        method0: ResolvedMember<*>,
        allocateNewInstance: Boolean,

        scope: Scope,
        origin: Long
    ): FlowResult {
        for (param in valueParameters) param.use()
        return when (val method = method0.resolved) {
            is Method -> {
                // then execute it
                val specialization = method0.specialization
                val selfField = selfField!!.use()
                assertEquals(method.hasExplicitSelfType, thisField != null) {
                    "Explicit this mismatch, $method vs $thisField"
                }
                val call = if (!needsResolution) {
                    // super.xzy()
                    val methodMap = FullMap<ClassType, MethodLike>(method)
                    SimpleMethodCall(
                        dst, method, methodMap, selfField, null,
                        specialization, valueParameters, scope, origin
                    )
                } else {
                    if (thisField != null) {
                        thisField.use()
                        SimpleMethodCall(
                            dst, method, thisField, selfField,
                            specialization, valueParameters, scope, origin
                        )
                    } else {
                        SimpleMethodCall(
                            dst, method, selfField, null,
                            specialization, valueParameters, scope, origin
                        )
                    }
                }
                handleThrown(block0, flow0, dst, call, method.getThrownType(specialization))
            }
            is Constructor -> createConstructorInvocation(
                dst, block0, flow0, selfField, method, valueParameters,
                method0, allocateNewInstance, scope, origin
            )
            else -> throw NotImplementedError("Simplify call $method at ${resolveOrigin(origin)}")
        }
    }

    private fun createConstructorInvocation(
        dst: SimpleField,
        block0: SimpleBlock,
        flow0: FlowResult,

        selfExpr: SimpleField?,

        method: Constructor,
        valueParameters: List<SimpleField>,

        method0: ResolvedMember<*>,
        allocateNewInstance: Boolean,

        scope: Scope,
        origin: Long
    ): FlowResult {
        val graph = block0.graph
        // check that super calls still work, especially this-constructor calls(...) -> we added a unit test :)
        // println("constructor $method inside ${graph.method}, new instance? $allocateNewInstance")
        return if (!allocateNewInstance) {

            val self = selfExpr ?: run {
                val selfScope = (graph.method as Constructor).classScope // we must be inside a constructor
                val selfType = selfScope.typeWithArgs.specialize(method0.specialization)
                block0.thisField(selfType, selfScope, scope, origin, method0.specialization, null)
            }

            val constructor = SimpleConstructorCall(
                dst, false, self.use(),
                method0.specialization, valueParameters, scope, origin
            )
            handleThrown(
                block0, flow0, dst, constructor,
                method.getThrownType(method0.specialization)
            )
        } else {
            var selfType = method.selfTypeI.specialize(method0.specialization)
            if (selfType.typeParameters == null) {
                selfType = selfType.clazz.typeWithArgs2.specialize(method0.specialization)
            }
            // todo allocation could fail, too...
            block0.add(
                SimpleAllocateInstance(
                    dst, selfType, valueParameters,
                    method0.specialization.withScope(selfType.clazz),
                    scope, origin
                )
            )
            val specialization = method0.specialization
            val call = SimpleConstructorCall(
                dst, true, dst.use(),
                specialization, valueParameters, scope, origin
            )
            LOGGER.info("Finding throw type for $method0")
            handleThrown(block0, flow0, dst, call, method.getThrownType(specialization))
        }
    }

    fun handleThrown(
        block0: SimpleBlock, flow0: FlowResult,
        result: SimpleField, callable: SimpleCallable, thrownType: Type
    ): FlowResult {

        block0.add(callable)

        val flow1 = flow0.withValue(result, block0)
        if (thrownType == Types.Nothing) return flow1

        val throwBlock = block0.graph.addBlock()
        val throwField = throwBlock.field(thrownType)
        val throwFlow = Flow(throwField, throwBlock)
        callable.onThrown = throwFlow

        return flow1
            .joinReturnAndThrown(throwFlow)
            .withValue(result, block0)
    }

    fun reorderResolveParameters(
        context: ResolutionContext,
        src: List<NamedParameter>, targetParams: List<Parameter>,
        scope: Scope, origin: Long
    ): List<Expression> {
        return reorderParameters(src, targetParams, scope, origin).mapIndexed { index, param ->
            param.resolve(context.withTargetType(targetParams[index].type))
        }
    }

    fun reorderParameters(
        src: List<NamedParameter>, dst: List<Parameter>,
        scope: Scope, origin: Long
    ): List<Expression> {
        return resolveNamedParameters(dst, src, scope, origin)
            ?: error("Failed to fill in call parameters at ${resolveOrigin(origin)}")
    }

}