package me.anno.generation.python

import me.anno.generation.BoxedType
import me.anno.generation.FileEntry
import me.anno.generation.FileWithImportsWriter
import me.anno.generation.Specializations.specialization
import me.anno.generation.c.CSourceGenerator.Companion.hashMethodParameters
import me.anno.generation.java.JavaSourceGenerator
import me.anno.generation.java.JavaSuperCallWriter.appendSuperCallParams
import me.anno.utils.Half.Companion.toHalf
import me.anno.utils.NumberUtils.getMaxIntValue
import me.anno.utils.NumberUtils.getMinIntValue
import me.anno.utils.ResetThreadLocal.Companion.threadLocal
import me.anno.zauber.ast.reverse.SimpleBranch
import me.anno.zauber.ast.reverse.SimpleLoop
import me.anno.zauber.ast.reverse.SimpleTailCall
import me.anno.zauber.ast.rich.Flags
import me.anno.zauber.ast.rich.Flags.hasFlag
import me.anno.zauber.ast.rich.expression.Expression
import me.anno.zauber.ast.rich.expression.constants.NumberExpression
import me.anno.zauber.ast.rich.expression.constants.NumberExpression.Companion.isFloat
import me.anno.zauber.ast.rich.expression.constants.SpecialValue
import me.anno.zauber.ast.rich.expression.constants.SpecialValueExpression
import me.anno.zauber.ast.rich.expression.constants.StringExpression
import me.anno.zauber.ast.rich.member.Constructor
import me.anno.zauber.ast.rich.member.Field
import me.anno.zauber.ast.rich.member.Method
import me.anno.zauber.ast.rich.member.MethodLike
import me.anno.zauber.ast.rich.parameter.InnerSuperCall
import me.anno.zauber.ast.rich.parameter.InnerSuperCallTarget
import me.anno.zauber.ast.rich.parameter.SuperCall
import me.anno.zauber.ast.simple.ASTSimplifier
import me.anno.zauber.ast.simple.SimpleGraph
import me.anno.zauber.ast.simple.expression.*
import me.anno.zauber.ast.simple.fields.LocalField
import me.anno.zauber.ast.simple.fields.SimpleField
import me.anno.zauber.ast.simple.fields.SimpleInstruction
import me.anno.zauber.scope.Scope
import me.anno.zauber.typeresolution.ParameterList.Companion.emptyParameterList
import me.anno.zauber.typeresolution.ResolutionContext
import me.anno.zauber.typeresolution.ValueParameterImpl
import me.anno.zauber.typeresolution.members.ConstructorResolver
import me.anno.zauber.types.Specialization
import me.anno.zauber.types.Type
import me.anno.zauber.types.Types
import me.anno.zauber.types.impl.ClassType
import me.anno.zauber.types.impl.GenericType
import java.io.File

/**
 * this is just like JavaScript source code,
 *  just a little different indentation, and classes look different
 * */
class PythonSourceGenerator : JavaSourceGenerator() {

    companion object {
        val protectedPythonTypes by threadLocal {
            Types.run {
                mapOf(
                    Boolean to BoxedType("Boolean", "bool"),

                    Byte to BoxedType("Byte", "int"),
                    Short to BoxedType("Short", "int"),
                    Int to BoxedType("Int", "int"),
                    Long to BoxedType("Long", "int"),

                    UByte to BoxedType("Byte", "int"),
                    UShort to BoxedType("Short", "int"),
                    UInt to BoxedType("Int", "int"),
                    ULong to BoxedType("Long", "int"),

                    Char to BoxedType("Char", "int"), // string, but math is difficult...
                    Half to BoxedType("Half", "float"),
                    Float to BoxedType("Float", "float"),
                    Double to BoxedType("Double", "float"),
                )
            }
        }

        val nativePythonTypes by threadLocal { protectedPythonTypes.filter { (_, it) -> it.boxed != it.native } }
        val nativePythonNumbers by threadLocal { nativePythonTypes - Types.Boolean }

        private const val STATIC_INSTANCE_SUFFIX = "_S"
    }

    override val protectedTypes: Map<ClassType, BoxedType> get() = protectedPythonTypes
    override val nativeTypes: Map<ClassType, BoxedType> get() = nativePythonTypes
    override val nativeNumbers: Map<ClassType, BoxedType> get() = nativePythonNumbers

    override fun getExtension(headerOnly: Boolean): String = "py"

    override fun getMethodName(method0: Specialization): String {

        if (method0.method is Constructor && method0.method.ownerScope.typeWithArgs2 in nativeNumbers) {
            return "__init__"
        }

        val base = if (method0.method is Constructor) "init_" else getMethodName0(method0)
        return "${base}_${hashMethodParameters(method0)}"
    }

    override fun getMainMethodFile(dst: File): File {
        return File(dst, "main.${getExtension(false)}")
    }

    override fun defineNullableAnnotation(dst: File, writer: FileWithImportsWriter) {
        // skip
    }

    override fun defineMainMethodCallEntry(
        dst: File, writer: FileWithImportsWriter,
        mainMethod: Method, className: String
    ): FileEntry {
        val needsArgs = mainMethod.valueParameters.isNotEmpty()
        val spec = Specialization(mainMethod.memberScope, emptyParameterList())
        val methodName = getMethodName(spec)
        return FileEntry(emptyList(), this)
            .apply {
                content.append(
                    """
                $className.$methodName(${if (needsArgs) "args" else ""});
            """.trimIndent()
                )
            }
    }

    override fun appendPackageDeclaration(packagePath: List<String>, file: File) {
        builder.append("# ")
        appendPath(packagePath)
        builder.append('.')
        builder.append(file.nameWithoutExtension)
        nextLine()
        nextLine()
    }

    override fun appendImport(packagePath: List<String>, import: List<String>, importedScope: Scope?) {
        builder.append("from ")
        appendPath(import)
        builder.append(" import ")
        builder.append(import.last())

        if (importedScope != null && importedScope.isObjectLike()) {
            builder.append(STATIC_INSTANCE_SUFFIX)
        }

        nextLine()
    }

    override fun appendArrayContentField(classScope: Scope, headerOnly: Boolean) {
        builder.append("content = null;")
        nextLine()
    }

    override fun getClassType(scope: Scope): String {
        // todo what about enums?
        return "class"
    }

    override fun appendClassFlags(scope: Scope) {
        if (scope.flags.hasFlag(Flags.ABSTRACT)) builder.append("abstract ")
    }

    override fun appendClass(
        className: String, classScope: Scope, specialization: Specialization,
        methods: Collection<Specialization>, fields: Collection<Specialization>,
        headerOnly: Boolean
    ) {
        declareImport(classScope, specialization)
        specialization.use {
            appendSpecializationInfoComment()

            appendClassFlags(classScope)
            appendClassPrefix(classScope, className)

            // skipped type parameters
            appendSuperTypes(classScope)

            appendClassBody(classScope, className, methods, fields, headerOnly)
        }
    }

    override fun appendSuperTypes(scope: Scope) {
        if (scope.superCalls.isEmpty() && scope != Types.Any.clazz && !scope.isInterface()) {
            scope.superCalls.add(SuperCall(Types.Any, emptyList(), null, -1))
        }
        builder.append('(')
        for (superCall in scope.superCalls) {
            if (superCall.isInterfaceCall) continue
            val type = superCall.type
            if (!builder.endsWith('(')) builder.append(", ")
            appendType(type, scope, true,)
        }
        builder.append(')')
    }

    override fun appendFieldFlags(classScope: Scope, field: Field, allowFinal: Boolean) {
        if (field == classScope.objectField) builder.append("static ")
    }

    override fun appendConstructorFlags(classScope: Scope, constructor: Constructor, headerOnly: Boolean) {
        // nothing
        builder.append("def ")
    }

    override fun appendConstructorHeader(
        classScope: Scope, className: String,
        constructor: Constructor, headerOnly: Boolean
    ) {
        appendConstructorFlags(classScope, constructor, headerOnly)
        check(specialization.method === constructor)
        if (classScope.isObjectLike()) builder.append("__init__")
        else builder.append(getMethodName(specialization))
        appendValueParameterDeclaration(constructor, classScope)
    }

    override fun appendConstructor(
        classScope: Scope, className: String,
        constructor: Constructor, headerOnly: Boolean
    ) {
        // some spacing
        nextLine()

        appendConstructorHeader(classScope, className, constructor, headerOnly)
        appendConstructorBody(classScope, className, constructor, headerOnly)
    }

    override fun appendConstructorBody(
        classScope: Scope, className: String,
        constructor: Constructor, headerOnly: Boolean
    ) {
        val body = constructor.body
        val context = ResolutionContext(
            constructor.scope, constructor.selfType,
            true, null, emptyMap()
        )

        writeBlock {

            val i0 = builder.length

            if (false) {
                // todo do this later, somehow...
                appendSuperCall0(classScope, className, constructor)
                nextLine()
            }

            if (classScope == Types.Array.clazz) {
                appendArrayContentInitialization(constructor)
            }

            if (body != null) {
                val methodSpec = specialization
                check(methodSpec.method === constructor)
                appendCode(context, methodSpec, body, true)
            }

            removeTrailingWhitespace()
            nextLine()

            if (builder.length <= i0) {
                builder.append("pass")
                nextLine()
            }
        }
    }

    override fun appendCode(
        context: ResolutionContext, method1: Specialization,
        body: Expression, skipSuperCall: Boolean
    ) {
        val graph = ASTSimplifier.simplify(method1)
        if (skipSuperCall) graph.removeSuperCalls()
        prepareGraph(graph)

        // todo simplify all entry points as methods...

        val pos0 = builder.length
        declareLocalFields(graph)

        if (graph.hasTailCalls()) appendTailCallCode(graph)
        else appendBlock(graph, graph.startBlock)

        removeTailingReturn()

        appendMissingDeclarations(graph, pos0)
    }

    override fun appendMissingDeclarations(graph: SimpleGraph, pos0: Int) {
        val pos1 = builder.length
        for (field in usedFields - declaredFields) {
            appendDeclare(graph, field, graph.method.memberScope, false)
            builder.append(" = ")
            appendDefaultValue(field.type)
            nextLine()
        }
        swapSections(pos0, pos1)

        usedFields.clear()
        declaredFields.clear()
    }

    override fun appendSuperCall0(classScope: Scope, className: String, constructor: Constructor) {
        // interfaces don't need super calls :)
        val superCall = constructor.superCall
        val superType0 = classScope.superCalls
            .firstOrNull { it.isClassCall }?.typeI
            ?: Types.Any
        val superType = resolveType(superType0)

        if (superCall != null) {
            appendSuperCall0Name(
                classScope, className, constructor,
                superType as ClassType, superCall
            )

            val context = ResolutionContext(
                classScope, null, specialization,
                true, null
            )
            if (!classScope.isObjectLike()) {
                // find out hash of super-call...
                val valueParams = superCall.valueParameters.map {
                    val type = it.value.resolveValueType(context)
                    ValueParameterImpl(it.name, type, false)
                }
                val foundConstructor = ConstructorResolver.findMemberInScope(
                    superType.clazz, superCall.origin, superType.clazz.name,
                    null, valueParams, context
                )
                    ?: error("Missing $superCall in $superType for $className, valueParams: $valueParams")
                builder.append(".__init__")
                    .append(hashMethodParameters(foundConstructor.specialization))
            }

            appendSuperCallParams(context, superCall)
        } else {
            comment { builder.append("superCall is null") }
        }
    }

    override fun appendSuperCall0Name(
        classScope: Scope, className: String, constructor: Constructor,
        superType: Type, superCall: InnerSuperCall
    ) {
        if (superCall.target == InnerSuperCallTarget.THIS) {
            builder.append("this") // is this supported? yes
        } else {
            builder.append("super")
        }
    }

    override fun appendField(classScope: Scope, field: Field, allowFinal: Boolean, headerOnly: Boolean) {
        appendFieldFlags(classScope, field, allowFinal)

        var valueType = (field.valueType ?: Types.NullableAny)
        valueType = valueType.resolve(classScope)
        valueType = resolveType(valueType)

        appendFieldName(field)
        builder.append(" = ")
        appendDefaultValue(valueType)
        nextLine()
    }

    override fun appendMethodFlags(classScope: Scope, method0: Specialization, headerOnly: Boolean) {
        builder.append("def ")
    }

    override fun appendMethodHeader(
        classScope: Scope, className: String,
        method0: Specialization, headerOnly: Boolean
    ) {
        appendMethodFlags(classScope, method0, headerOnly)

        builder.append(getMethodName(method0))

        val method = method0.method as Method
        assignSelfType(classScope, method)
        appendValueParameterDeclaration(method, classScope)
    }

    override fun appendMethod(classScope: Scope, className: String, method0: Specialization, headerOnly: Boolean) {
        // some spacing
        nextLine()

        appendMethodHeader(classScope, className, method0, headerOnly)
        writeBlock { appendMethodBody(method0, headerOnly) }
    }

    override fun appendMethodBody(methodSpec: Specialization, headerOnly: Boolean) {
        val nativeImpl = getNativeImplementation(methodSpec.method)
        val body = methodSpec.method.body

        val i0 = builder.length
        when {
            body != null -> {
                val context = ResolutionContext(
                    methodSpec.scope!!, methodSpec.method.selfType,
                    methodSpec, true, null
                )
                appendCode(context, methodSpec, body, false)
            }
            nativeImpl != null -> {
                appendNativeImplementation(nativeImpl, methodSpec.method)
            }
            isArrayGetter(methodSpec) -> appendArrayGetter(methodSpec)
            isArraySetter(methodSpec) -> appendArraySetter(methodSpec)
            else -> {
                builder.append("raise Exception(\"Missing implementation for $methodSpec\")")
                nextLine()
            }
        }

        if (i0 == builder.length) {
            builder.append("pass")
            nextLine()
        }
    }

    override fun appendNativeImplementation(nativeImpl: String, method: MethodLike) {
        val i0 = builder.length
        if ('\n' in nativeImpl) {
            val lines = nativeImpl.lines()
            for (line in lines) {
                builder.append(line)
                nextLine()
            }
        } else {
            builder.append(nativeImpl)
            nextLine()
        }
        appendReturnIfMissing(method, i0)
    }

    override fun appendArrayContentInitialization(constructor: Constructor) {
        val elementType = specialization.typeParameters[0]
        val sizeName = constructor.valueParameters[0].newName
        builder.append("self.content = [")
        appendDefaultValue(elementType)
        builder.append("] * ").append(sizeName)
        nextLine()
    }

    override fun appendDefaultValue(valueType: Type) {
        val str = when (valueType) {
            Types.Boolean -> "False"
            in nativeTypes -> if (isNumberFloat(valueType)) "0.0" else "0"
            else -> "None"
        }
        builder.append(str)
    }

    override fun appendReturnIfMissing(method: MethodLike, i0: Int) {
        if (hasReturn(method) && builder.indexOf("return", i0) < 0) {
            builder.append("return ")
            appendGetObjectInstance(Types.Unit.clazz, method.scope)
            nextLine()
        }
    }

    override fun appendArrayGetter(method0: Specialization) {
        builder.append("return self.content[index]")
        nextLine()
    }

    override fun appendArraySetter(method0: Specialization) {
        builder.append("self.content[index] = value")
        nextLine()

        builder.append("return ")
        appendGetObjectInstance(Types.Unit.clazz, method0.method.memberScope)
        nextLine()
    }

    override fun declareLocalField(graph: SimpleGraph, field: LocalField) {
        builder.append(field.name).append(" = None")
        nextLine()
    }

    override fun appendClassBody(
        classScope: Scope, className: String,
        methods: Collection<Specialization>,
        fields: Collection<Specialization>,
        headerOnly: Boolean
    ) {
        writeBlock {
            appendConstructors(classScope, className, methods, headerOnly)
            appendMethods(classScope, className, methods, headerOnly)
            appendCastToMethod(classScope)
        }

        if (classScope.isObjectLike()) {
            nextLine()
            appendStaticInstance(classScope, className)
        }
    }

    fun appendCastToMethod(classScope: Scope) {
        val castToImpl = when (classScope) {
            Types.Byte.clazz -> {
                val indent = "  ".repeat(indentation + 1) // +1 for method block
                "mask = (1 << 8) - 1;\n" +
                        indent + "low = value & mask;\n" +
                        indent + "return low - (1 << 8) if low & (1 << 7) else low;"
            }
            Types.Short.clazz -> {
                val indent = "  ".repeat(indentation + 1) // +1 for method block
                "mask = (1 << 16) - 1;\n" +
                        indent + "low = value & mask;\n" +
                        indent + "return low - (1 << 16) if low & (1 << 15) else low;"
            }
            Types.Int.clazz -> {
                val indent = "  ".repeat(indentation + 1) // +1 for method block
                "mask = (1 << 32) - 1;\n" +
                        indent + "low = value & mask;\n" +
                        indent + "return low - (1 << 32) if low & (1 << 31) else low;"
            }
            Types.Long.clazz -> {
                val indent = "  ".repeat(indentation + 1) // +1 for method block
                "mask = (1 << 64) - 1;\n" +
                        indent + "low = value & mask;\n" +
                        indent + "return low - (1 << 64) if low & (1 << 63) else low;"
            }
            Types.Char.clazz -> "return value & 0xFFFF;"
            Types.UByte.clazz -> "return value & 0xFF;"
            Types.UShort.clazz -> "return value & 0xFFFF;"
            Types.UInt.clazz -> "return value & 0xFFFF_FFFF"
            Types.ULong.clazz -> "return value & 0xFFFF_FFFF_FFFF_FFFF"
            else -> null
        }
        if (castToImpl != null) {
            nextLine()
            builder.append("@staticmethod")
            nextLine()
            builder.append("def castTo(value)")
            writeBlock {
                builder.append(castToImpl)
                nextLine()
            }
        }
    }

    override fun appendStaticInstance(classScope: Scope, className: String) {
        builder.append(className).append(STATIC_INSTANCE_SUFFIX)
            .append(" = ").append(className).append("()")
        nextLine()
    }

    override fun appendGetObjectInstance(objectScope: Scope, exprScope: Scope) {
        if (objectScope == outsideClassLike(exprScope)) {
            builder.append("self")
        } else {
            appendType(objectScope.typeWithArgs, objectScope, false)
            builder.append(STATIC_INSTANCE_SUFFIX)
        }
    }

    override fun appendDeclare(graph: SimpleGraph, dst: SimpleField, scope: Scope, withEquals: Boolean) {
        appendFieldName(graph, dst)
        if (withEquals) builder.append(" = ")
    }

    override fun appendValueParameterDeclaration(method: MethodLike, scope: Scope) {
        builder.append("(self")
        val selfTypeIfNecessary = method.selfTypeIfNecessary
        if (selfTypeIfNecessary != null) {
            builder.append(", __self")
        }
        for (param in method.valueParameters) {
            builder.append(", ")
            appendFieldName(param)
        }
        builder.append(')')
    }

    override fun appendInstrSuffix(graph: SimpleGraph, expr: SimpleInstruction) {
        if (/*expr !is SimpleBlock &&*/ expr !is SimpleBranch) nextLine()
        if (expr is SimpleAssignment && expr.dst.type == Types.Nothing) {
            builder.append("raise AssertionError(\"Unreachable\")")
            nextLine()
        }
    }

    override fun appendInstrImpl(graph: SimpleGraph, expr: SimpleInstruction) {
        when (expr) {
            is SimpleAllocateInstance -> {
                appendType(expr.allocatedType, expr.scope, true)
                builder.append("()")
            }
            is SimpleConstructorCall -> {
                appendFieldName(graph, expr.thisInstance, ".")
                builder.append(getMethodName(expr.specialization))
                appendValueParams(graph, expr.valueParameters)
            }
            is SimpleBranch -> {
                builder.append("if ")
                appendFieldName(graph, expr.condition)
                writeBlock {
                    appendBlock(graph, expr.ifTrue)
                }
                if (expr.ifFalse != null) {
                    builder.append("else")
                    writeBlock {
                        appendBlock(graph, expr.ifFalse)
                    }
                }
            }
            is SimpleLoop -> {
                builder.append("while True")
                writeBlock {
                    if (expr.condition != null) {
                        appendBlock(graph, expr.conditionBlock!!)
                        builder.append("if ")
                        if (!expr.negate) builder.append("not ")
                        appendFieldName(graph, expr.condition)
                        builder.append(":"); nextLine()
                        builder.append("  break"); nextLine()
                        nextLine()
                    }
                    appendBlock(graph, expr.body)
                }
            }
            is SimpleTailCall -> {
                builder.append("nextBlockId = ").append(expr.toBeCalled.id)
                nextLine()
                builder.append("raise StopIteration")
            }
            is SimpleMethodCall -> {
                // Number.toX() needs to be converted to a cast
                val methodName = expr.methodName
                val done = when (expr.valueParameters.size) {
                    0 -> appendUnaryOperator(graph, expr, methodName)
                    1 -> appendBinaryOperator(graph, expr, methodName)
                    else -> false
                }
                if (!done) {
                    appendCallImpl(graph, expr)
                }
            }
            is SimpleInstanceOf -> {
                builder.append("isinstance(")
                appendFieldName(graph, expr.value)
                builder.append(", ")
                appendType(expr.type, expr.scope, false)
                builder.append(')')
            }
            else -> super.appendInstrImpl(graph, expr)
        }
    }

    override fun appendObjectInstance(field: Field, exprScope: Scope, forFieldAccess: String) {
        if (field.ownerScope == outsideClassLike(exprScope)) {
            // if there is nothing dangerous in-between, we could use this.
            builder.append("self")
        } else {
            appendGetObjectInstance(field.ownerScope, exprScope)
        }
        builder.append(forFieldAccess)
    }

    override fun appendType(type: Type, scope: Scope, needsBoxedType: Boolean, withSuffix: Boolean) {
        val type = resolveType(type)

        if (!needsBoxedType) {
            val protected = protectedTypes[type]
            if (protected != null) {
                builder.append(protected.native)
                return
            }
        }

        if (type is GenericType) {
            return appendTypeImpl(type.superBounds, scope, needsBoxedType)
        }

        appendTypeImpl(type, scope, needsBoxedType)
    }

    override fun appendNativeCall(needsCastForFirstValue: BoxedType, expr: SimpleMethodCall, graph: SimpleGraph) {
        // ensure import
        val selfType = expr.thisInstance.type as ClassType
        ensureImport(selfType)

        builder.append(needsCastForFirstValue.boxed).append("(")
        appendFieldName(graph, expr.thisInstance)
        builder.append(").")
        builder.append(getMethodName(expr.specialization))
        appendValueParams(graph, expr.valueParameters)
    }

    override fun appendNumber(type: Type, expr: NumberExpression) {
        when (type) {
            Types.Byte -> builder.append(expr.asInt.toByte())
            Types.UByte -> builder.append(expr.asInt.toUByte())
            Types.Short -> builder.append(expr.asInt.toShort())
            Types.UShort -> builder.append(expr.asInt.toUShort())
            Types.Int -> builder.append(expr.asInt.toInt())
            Types.UInt -> builder.append(expr.asInt.toUInt())
            Types.Long -> builder.append(expr.asInt)
            Types.ULong -> builder.append(expr.asInt.toULong())
            Types.Char -> builder.append(expr.asInt.toInt())
            Types.Half -> {
                if (!expr.asFloat.toHalf().isFinite()) appendInfinite(expr.asFloat)
                else builder.append(expr.asFloat.toHalf().toFloat())
            }
            Types.Float -> {
                if (!expr.asFloat.toFloat().isFinite()) appendInfinite(expr.asFloat)
                else builder.append(expr.asFloat.toFloat())
            }
            Types.Double -> {
                if (!expr.asFloat.isFinite()) appendInfinite(expr.asFloat)
                else builder.append(expr.asFloat)
            }
            else -> throw NotImplementedError("Append number of type $type")
        }
    }

    private fun appendInfinite(value: Double) {
        val asString = when (value) {
            Double.POSITIVE_INFINITY -> "float('inf')"
            Double.NEGATIVE_INFINITY -> "float('-inf')"
            else -> "0.0/0.0"
        }
        builder.append(asString)
    }

    override fun appendCopy(graph: SimpleGraph, valueType: Type) {
        builder.append(".copy_0()")
    }

    override fun filterImports(name: String, packageScope: Scope, headerOnly: Boolean) {
        // remove self-include
        imports.remove(name)
    }

    override fun appendTailCallCode(graph: SimpleGraph) {
        builder.append("nextBlockId = 0"); nextLine()
        builder.append("while True")
        writeBlock {
            builder.append("try")
            writeBlock {
                builder.append("match nextBlockId")
                writeBlock {
                    val targets = findTailCallTargets(graph)
                    val blocks = graph.blocks
                    for (i in blocks.indices) {
                        val block = blocks[i]
                        if (i == 0 || targets[block.id]) {
                            builder.append("case ").append(block.id)
                            writeBlock {
                                appendBlock(graph, block)
                            }
                        }
                    }
                }
            }
            builder.append("except StopIteration")
            writeBlock {
                builder.append("pass")
            }
        }
        nextLine()
    }

    override fun comment(body: () -> Unit) {
        commentDepth++
        try {
            builder.append("# ")
            val len0 = builder.length
            body()
            removeTrailingWhitespace()
            for (i in len0 until builder.length) {
                if (builder[i] == '\n') builder[i] = '#'
            }
            nextLine()
        } finally {
            commentDepth--
        }
    }

    override fun writeBlock(run: () -> Unit) {
        builder.append(':')

        indentation++
        nextLine()

        try {
            run()
            dedent()
        } finally {
            indentation--
        }
    }

    override fun appendFieldName(graph: SimpleGraph, field: SimpleField, forFieldAccess: String) {
        if (field.isOwnerThis(graph)) {
            builder.append(if (meansContent(field, "")) "self.content" else "self")
        } else if (field.isObjectLike()) {
            val objectScope = (field.type as ClassType).clazz
            appendGetObjectInstance(objectScope, graph.method.scope)
        } else {
            val field = field.dst
            when (val expr = field.constantRef) {
                is NumberExpression -> appendNumber(field.type, expr)
                is StringExpression -> appendString(expr.value)
                is SpecialValueExpression -> when (expr.type) {
                    SpecialValue.NULL -> builder.append("None")
                    SpecialValue.TRUE -> builder.append("True")
                    SpecialValue.FALSE -> builder.append("False")
                }
                null -> {
                    check(field.id >= 0) { "Invalid field $field in $graph" }
                    val localField = field.fromLocalField
                    val immediateValue = field.immediateValue
                    if (localField != null) {
                        builder.append(localField.name)
                    } else if (immediateValue != null) {
                        appendInstrImpl(graph, immediateValue)
                    } else {
                        builder.append("tmp").append(field.id)
                        usedFields.add(field)
                    }
                }
                else -> throw NotImplementedError("Append constant field $expr")
            }
        }
        builder.append(forFieldAccess)
    }

    override fun getBinarySymbol(type: Type, methodName: String): String? {
        return if (methodName == "div") {
            if (isNumberFloat(type)) " / " else " // "
        } else super.getBinarySymbol(type, methodName)
    }

    override fun appendBinaryOperator(graph: SimpleGraph, expr: SimpleMethodCall, methodName: String): Boolean {
        val type = expr.thisInstance.type
        when (type) {
            Types.String, in nativeTypes -> {}
            else -> return false
        }

        val symbol = getBinarySymbol(type, methodName)
            ?: return false

        val needsCast = type != Types.String && !type.isFloat()
        if (needsCast) {
            // cast to the corresponding type
            appendType(type, expr.scope, true)
            builder.append(".castTo(")
        }

        appendFirstParameter(graph, type, expr)
        builder.append(symbol)
        appendFieldName(graph, expr.valueParameters[0])

        if (needsCast) builder.append(')')

        return true
    }

    override fun appendUnaryOperator(graph: SimpleGraph, expr: SimpleMethodCall, methodName: String): Boolean {
        val thisType = expr.thisInstance.type
        val targetType = getCastTargetType(methodName)
        if (targetType != null && thisType in nativeNumbers) {
            when {
                thisType.isFloat() && !targetType.isFloat() -> {
                    val minValue = getMinIntValue(targetType).toString()
                    val maxValue = getMaxIntValue(targetType).toString()
                    builder.append("int(max(min(")
                    appendFieldName(graph, expr.thisInstance)
                    builder.append(", ").append(maxValue)
                    builder.append("), ").append(minValue).append("))")
                }
                targetType.isFloat() -> {
                    builder.append("float(")
                    appendFieldName(graph, expr.thisInstance)
                    builder.append(')')
                }
                else -> {
                    appendType(targetType, expr.scope, true)
                    builder.append(".castTo(")
                    appendFieldName(graph, expr.thisInstance)
                    builder.append(')')
                }
            }
            return true
        } else if (methodName == "not" && thisType == Types.Boolean) {
            builder.append("not ")
            appendFieldName(graph, expr.thisInstance)
            return true
        } else if (thisType in nativeNumbers) {
            when (methodName) {
                "inv" -> {
                    appendType(thisType, expr.scope, true)
                    builder.append(".castTo(~")
                    appendFieldName(graph, expr.thisInstance)
                    builder.append(')')
                    return true
                }
                "unaryPlus" -> {
                    appendFieldName(graph, expr.thisInstance)
                    return true
                }
                "unaryMinus" -> {
                    val needsCast = !thisType.isFloat()
                    if (needsCast) {
                        appendType(thisType, expr.scope, true)
                        builder.append(".castTo(-")
                    } else builder.append('-')
                    appendFieldName(graph, expr.thisInstance)
                    if (needsCast) builder.append(')')
                    return true
                }
                else -> return super.appendBinaryOperator(graph, expr, methodName)
            }
        }

        return super.appendUnaryOperator(graph, expr, methodName)
    }

}
