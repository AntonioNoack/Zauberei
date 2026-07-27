package me.anno.zauber.interpreting

import me.anno.utils.CollectionUtils.getOrPutRecursive
import me.anno.utils.CollectionUtils.mapArray
import me.anno.utils.ResetThreadLocal.Companion.threadLocal
import me.anno.utils.StringStyles
import me.anno.utils.StringStyles.GREEN
import me.anno.utils.StringStyles.YELLOW
import me.anno.utils.StringStyles.style
import me.anno.utils.assertTrue
import me.anno.zauber.ast.rich.TokenListIndex.resolveOrigin
import me.anno.zauber.ast.rich.member.Field
import me.anno.zauber.ast.rich.member.MethodLike
import me.anno.zauber.ast.simple.ASTSimplifier
import me.anno.zauber.ast.simple.SimpleBlock
import me.anno.zauber.ast.simple.SimpleGraph
import me.anno.zauber.ast.simple.expression.SimpleCallable
import me.anno.zauber.ast.simple.fields.SimpleField
import me.anno.zauber.interpreting.RuntimeCreate.createString
import me.anno.zauber.logging.LogManager
import me.anno.zauber.scope.Scope
import me.anno.zauber.scope.ScopeInitType
import me.anno.zauber.typeresolution.Inheritance.isSubTypeOf
import me.anno.zauber.typeresolution.InsertMode
import me.anno.zauber.typeresolution.ParameterList
import me.anno.zauber.typeresolution.ResolutionContext
import me.anno.zauber.types.Specialization
import me.anno.zauber.types.Type
import me.anno.zauber.types.Types
import me.anno.zauber.types.impl.ClassType
import me.anno.zauber.types.impl.GenericType
import me.anno.zauber.types.impl.arithmetic.NullType
import javax.lang.model.type.UnionType

class Runtime {

    companion object {
        private val LOGGER = LogManager.getLogger(Runtime::class)

        val runtime by threadLocal { Runtime() }
    }

    private var instanceCounter = 0
    fun nextInstanceId() = instanceCounter++

    private val classes = HashMap<Type, ZClass>()
    private val nullInstance = Instance(getClass(NullType), emptyArray(), nextInstanceId())

    val callStack = ArrayList<Call>()
    val externalMethods = HashMap<ExternalKey, ExternalMethod>()
    val printed = StringBuilder()

    fun getCall() = callStack.last()

    fun register(scope: Scope, name: String, types: List<Type>, method: ExternalMethod) {
        val key = ExternalKey(scope, name, types)
        externalMethods[key] = method
    }

    fun register(scope: ClassType, name: String, types: List<Type>, method: ExternalMethod) {
        register(scope.clazz, name, types, method)
    }

    operator fun get(field: SimpleField): Instance {
        return this[getCall(), field]
    }

    operator fun get(call: Call, field: SimpleField): Instance {
        val field = field.dst
        // LOGGER.info("Getting SimpleField $field")
        return call.simpleFields[field.id]
            ?: run {
                println("Broken Graph: ${call.graph}")
                error(
                    "$field is uninitialized, fields: ${
                        call.simpleFields.withIndex()
                            .filter { it.value != null }
                            .joinToString("") { (k, v) ->
                                "\n  ${style("%$k", YELLOW)}=$v"
                            }
                    }"
                )
            }
    }

    operator fun set(field: SimpleField, value: Instance) {
        // todo a field may be used for both use-cases...
        val field = field.dst

        // todo only if that field really belongs into this scope:
        //  it might belong to one of the scopes before us
        val valueHasValidType = isSubTypeOf(
            field.type,
            value.clazz.type,
            emptyList(),
            ParameterList.emptyParameterList(),
            InsertMode.READ_ONLY
        )
        if (false) check(valueHasValidType) {
            "Assignment of $value into $field invalid, type-mismatch"
        }
        // get(field) // sanity check for existence
        getCall().setSimple(field, value)
    }

    operator fun set(instance: Instance, field: Field, value: Instance) {
        val clazz = instance.clazz
        var fieldIndex = clazz.fields.indexOf(field)
        if (fieldIndex == -1) {
            // fallback for inheritance
            fieldIndex = clazz.fields.indexOfFirst { it.name == field.name }
        }
        check(fieldIndex != -1) {
            "Instance $clazz does not have field $field, " +
                    "available: ${clazz.fields.map { it.name }}, " +
                    "fields: ${(clazz.type as ClassType).clazz.fields.map { it.name }}"
        }
        instance.fields[fieldIndex] = value
    }

    fun isNull(va: Instance) = va == getNull()

    fun getBool(bool: Boolean): Instance {
        val boolCompanion = Types.Boolean.clazz[ScopeInitType.AFTER_DISCOVERY].companionObject
            ?: error("Missing definition for enum class Boolean")
        val boolInstance = getObjectInstance(boolCompanion)
        val fields = boolInstance.clazz.fields
        val name = if (bool) "TRUE" else "FALSE"
        val field = fields.firstOrNull { it.name == name }
            ?: error("Missing enum Boolean.$name")
        return boolInstance[field]
    }

    fun getNull(): Instance = nullInstance

    fun getClass(selfType: Type): ZClass {
        return classes.getOrPut(selfType) { ZClass(selfType) }
    }

    fun executeCall(
        methodOwnerInstance: Instance,
        explicitSelfInstance: Instance?,
        specialization: Specialization,
        valueParameters: List<SimpleField>,
        origin: Long = -1
    ): BlockReturn {
        val valueParameters = valueParameters.map { valueField ->
            this[valueField].cloneIfValue()
        }
        return executeCall2(methodOwnerInstance, explicitSelfInstance, specialization, valueParameters, origin)
    }

    fun executeCall2(
        methodOwnerInstance: Instance,
        explicitSelfInstance: Instance?,
        specialization: Specialization,
        valueParameters: List<Instance>,
        origin: Long
    ): BlockReturn {

        if (LOGGER.isInfoEnabled) LOGGER.info("Calling $specialization on $methodOwnerInstance with $valueParameters")
        check(specialization.isMethodLike())

        if (isNull(methodOwnerInstance)) {
            throw IllegalArgumentException("Cannot execute $specialization on null instance")
        }

        val method = specialization.method
        method.scope[ScopeInitType.CODE_GENERATION] // load method

        if (method.isExternal()) {
            val parameterTypes = method.valueParameters.map { it.type }
            if (LOGGER.isInfoEnabled) LOGGER.info("Method-params: $method -> $parameterTypes")
            val key = ExternalKey(method.scope.parent!!, method.name, parameterTypes)
            val methodImpl = externalMethods[key]
                ?: error("Missing external method ${key.str()}")
            val value = try {
                methodImpl.process(methodOwnerInstance, valueParameters)
            } catch (e: Exception) {
                LOGGER.warn("Issue in $method\n  at ${resolveOrigin(origin)}")
                throw e
            }
            return BlockReturn(ReturnType.RETURN, value)
        }

        if (method.body == null) {
            error("Missing body for $method\n  at ${resolveOrigin(method.origin)}")
        }

        val graph = ASTSimplifier.simplify(specialization, readOnly = true)

        val call = Call.create(method)
        prepareCall(graph, call, methodOwnerInstance, explicitSelfInstance, valueParameters)

        val result = try {
            executeBlock(graph.startBlock)
        } catch (e: Exception) {
            LOGGER.warn("Issue in $method\n  at ${resolveOrigin(origin)}")
            throw e
        }

        check(callStack.removeLast() === call)
        call.recycle()
        // println("Returning $result from call to $method")
        return result
    }

    fun <V> ArrayList<V?>.resizeTo(newSize: Int) {
        while (size > newSize) removeLast()
        while (size < newSize) add(null)
    }

    private fun prepareCall(
        graph: SimpleGraph, call: Call,
        thisInstance: Instance,
        selfInstance: Instance?,
        valueParameters: List<Instance>,
    ) {
        call.graph = graph
        callStack.add(call)

        call.simpleFields.resizeTo(graph.simpleFields.size)
        call.localFields.resizeTo(graph.localFields.size)

        val thisField = graph.thisField
        if (thisField != null) {
            call.setLocal(thisField, thisInstance)
        }

        val selfField = graph.selfField
        if (selfField != null) {
            check(selfInstance != null) {
                "Method ${graph.method} needs self, not just this"
            }
            call.setLocal(selfField, selfInstance)
        }

        val params = graph.parameterFields
        check(params.size == valueParameters.size)
        for (i in valueParameters.indices) {
            call.setLocal(params[i], valueParameters[i])
        }

        assignCapturedFields(graph, call)
    }

    private fun assignCapturedFields(graph: SimpleGraph, call: Call) {
        for ((capture, dstField) in graph.capturedFields) {
            val (owner, capturedField) = capture
            val prevCall = findPrevCall(owner)
            val prevCallInstanceRef = prevCall.graph.thisField
                ?: error("Missing thisField for ${capturedField.ownerScope} in $owner")
            val prevCallInstance = prevCall.localFields[prevCallInstanceRef.id]
                ?: error("Missing thisInstance for ${capturedField.ownerScope} in $owner")
            call.setSimple(dstField, prevCallInstance[capturedField])
        }
    }

    fun findPrevCall(owner: MethodLike): Call {
        for (i in callStack.lastIndex downTo 0) {
            val call = callStack[i]
            if (call.method == owner) return call
        }
        error("Missing $owner in callStack")
    }

    fun getUnit(): Instance {
        return getObjectInstance(Types.Unit)
    }

    fun getObjectInstance(type: ClassType): Instance {
        return getObjectInstance(type.clazz)
    }

    fun getObjectInstance(scope: Scope): Instance {
        check(scope.isObjectLike() || scope.scopeType == null) {
            "Only objects have an object instance, not $scope (${scope.scopeType})"
        }
        return getClass(scope.typeWithArgs2).getOrCreateObjectInstance()
    }

    fun executeBlock(block0: SimpleBlock): BlockReturn {
        var block = block0
        loop@ while (true) {

            val instructions = block.instructions

            if (LOGGER.isInfoEnabled) {
                LOGGER.info(
                    "Starting ${block.idStr()} " +
                            "in ${style(block0.graph.method.name, GREEN)}"
                )
            }

            if (LOGGER.isDebugEnabled) {
                for (i in instructions.indices) {
                    val instr = instructions[i]
                    LOGGER.debug("Block[$i] $instr")
                }
            }

            var lastValue: BlockReturn?
            for (i in instructions.indices) {
                val instr = instructions[i]
                if (LOGGER.isDebugEnabled) LOGGER.debug("Executing $instr")
                lastValue = instr.execute()
                if (lastValue != null) {
                    if (lastValue.type == ReturnType.THROW && instr is SimpleCallable) {
                        val handler = instr.onThrown
                        if (handler != null) {
                            this[handler.value] = lastValue.value
                            block = handler.block
                            continue@loop
                        }
                    }

                    if (lastValue.type != ReturnType.VALUE) {
                        when (lastValue.type) {
                            ReturnType.YIELD -> {
                                TODO(
                                    "yield: store all fields in a (new?) lambda instance, " +
                                            "return without handlers"
                                )
                            }
                            // handlers inside the function all were processed already :3
                            ReturnType.RETURN, ReturnType.THROW -> {
                                // todo validate return type
                                val method0 = block.graph.method0
                                val method = method0.method
                                if (false) {
                                    val expectedType = method.returnType!!.specialize(method0)
                                    val actualType = lastValue.value.clazz.type
                                    assertTrue(isSubTypeOf(expectedType, actualType)) {
                                        "Mismatched return type in ${method}: Expected $expectedType, but got $actualType"
                                    }
                                }
                                if (LOGGER.isInfoEnabled) {
                                    LOGGER.info("Exited with $lastValue (${instr.javaClass.simpleName}) from $method")
                                }
                                return lastValue
                            }
                            else -> throw NotImplementedError("Unknown exit type")
                        }
                    }
                }
            }

            // find the next block to execute
            val condition = block.branchCondition
            block = if (condition != null) {
                val conditionI = this[condition].castToBool()
                if (LOGGER.isInfoEnabled) {
                    LOGGER.info(
                        "Finished ${block.idStr()} " +
                                "in ${style(block0.graph.method.name, GREEN)}, " +
                                "condition: ${style(conditionI.toString(), StringStyles.ORANGE)}" +
                                " -> ${(if (conditionI) block.ifBranch else block.elseBranch)?.idStr()}"
                    )
                }
                if (conditionI) block.ifBranch else block.elseBranch
            } else {
                if (LOGGER.isInfoEnabled) {
                    LOGGER.info(
                        "Finished ${block.idStr()} " +
                                "in ${style(block0.graph.method.name, GREEN)}, " +
                                "next: ${block.nextBranch?.idStr()}"
                    )
                }
                block.nextBranch
            } ?: error("Exited without return from ${block0.graph} at ${block.idStr()}")
        }
    }

    private val typeInstances = HashMap<Type, Instance>()
    fun getTypeInstance(type: Type): Instance {
        return typeInstances.getOrPutRecursive(type, { type ->
            val clazz0 = when (type) {
                is ClassType -> Types.ClassType
                is UnionType -> Types.UnionType
                is GenericType -> Types.GenericType
                else -> Types.TypeT
            }
            val clazz = getClass(clazz0)
            val instance = clazz.createInstance()
            clazz.callPrimaryConstructor(instance)
            instance.rawValue = type
            instance
        }) { type, instance ->
            when (type) {
                is ClassType -> {
                    val clazz = type.clazz[ScopeInitType.AFTER_DISCOVERY]
                    instance.set("name", clazz.name)
                    if (instance.hasProperty("fields")) {
                        val zType = getClass(type)
                        val fields0 = zType.fields
                        val fieldClass = getClass(Types.Field)
                        val fields1 = fields0.mapArray { field ->
                            val instance = fieldClass.createInstance()
                            instance["name"] = createString(field.name)
                            if (false) {
                                val fieldType = field.resolveValueType(ResolutionContext.minimal)
                                instance["type"] = getTypeInstance(fieldType)
                            }
                            instance
                        }
                        instance["fields"] = fieldClass.createArray(fields1)
                    }
                    // todo set methods, child classes and more...
                }
                is UnionType -> {
                    // todo set members
                }
            }
        }
    }

}