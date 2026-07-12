package me.anno.generation.js

import me.anno.generation.BoxedType
import me.anno.generation.FileEntry
import me.anno.generation.FileWithImportsWriter
import me.anno.generation.Specializations.specialization
import me.anno.generation.c.CSourceGenerator.Companion.hashMethodParameters
import me.anno.generation.java.Import2
import me.anno.generation.java.JavaSourceGenerator
import me.anno.generation.java.JavaSuperCallWriter.appendSuperCallParams
import me.anno.utils.Half.Companion.toHalf
import me.anno.utils.NumberUtils.getMaxIntValue
import me.anno.utils.NumberUtils.getMinIntValue
import me.anno.utils.ResetThreadLocal.Companion.threadLocal
import me.anno.zauber.SpecialFieldNames.OBJECT_FIELD_NAME
import me.anno.zauber.ast.rich.Flags
import me.anno.zauber.ast.rich.Flags.hasFlag
import me.anno.zauber.ast.rich.expression.constants.NumberExpression
import me.anno.zauber.ast.rich.expression.constants.NumberExpression.Companion.getNumBits
import me.anno.zauber.ast.rich.expression.constants.NumberExpression.Companion.isFloat
import me.anno.zauber.ast.rich.expression.constants.NumberExpression.Companion.isUnsigned
import me.anno.zauber.ast.rich.member.Constructor
import me.anno.zauber.ast.rich.member.Field
import me.anno.zauber.ast.rich.member.Method
import me.anno.zauber.ast.rich.member.MethodLike
import me.anno.zauber.ast.rich.parameter.InnerSuperCall
import me.anno.zauber.ast.rich.parameter.InnerSuperCallTarget
import me.anno.zauber.ast.rich.parameter.SuperCall
import me.anno.zauber.ast.simple.SimpleGraph
import me.anno.zauber.ast.simple.expression.SimpleAllocateInstance
import me.anno.zauber.ast.simple.expression.SimpleConstructorCall
import me.anno.zauber.ast.simple.expression.SimpleMethodCall
import me.anno.zauber.ast.simple.fields.LocalField
import me.anno.zauber.ast.simple.fields.SimpleField
import me.anno.zauber.ast.simple.fields.SimpleInstruction
import me.anno.zauber.scope.Scope
import me.anno.zauber.scope.ScopeType
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
 * this is just like Java source code, except that
 *  a) we don't need to specify what each type is
 *  b) we must generate unique method names from their signature, if overloads exist
 * */
open class JavaScriptSourceGenerator : JavaSourceGenerator() {

    companion object {
        val protectedJSTypes by threadLocal {
            Types.run {
                mapOf(
                    Boolean to BoxedType("Boolean", "Boolean"),
                    Byte to BoxedType("Byte", "Number"),
                    UByte to BoxedType("UByte", "Number"),
                    Short to BoxedType("Short", "Number"),
                    UShort to BoxedType("UShort", "Number"),
                    Int to BoxedType("Int", "Number"),
                    UInt to BoxedType("UInt", "BigInt"),
                    Long to BoxedType("Long", "BigInt"),
                    ULong to BoxedType("ULong", "BigInt"),
                    Char to BoxedType("Char", "String"), // ?
                    Half to BoxedType("Half", "Number"),
                    Float to BoxedType("Float", "Number"),
                    Double to BoxedType("Double", "Number"),
                )
            }
        }

        val nativeJSTypes by threadLocal { protectedJSTypes.filter { (_, it) -> it.boxed != it.native } }
        val nativeJSNumbers by threadLocal { nativeJSTypes - Types.Boolean }
    }

    override val protectedTypes: Map<ClassType, BoxedType> get() = protectedJSTypes
    override val nativeTypes: Map<ClassType, BoxedType> get() = nativeJSTypes
    override val nativeNumbers: Map<ClassType, BoxedType> get() = nativeJSNumbers

    override fun getExtension(headerOnly: Boolean): String = "js"

    override fun getMethodName(method0: Specialization): String {
        val base = if (method0.method is Constructor) "__init_" else super.getMethodName(method0)
        return "${base}_${hashMethodParameters(method0)}"
    }

    override fun getMainMethodFile(dst: File): File {
        return File(dst, "main.${getExtension(false)}")
    }

    override fun defineNullableAnnotation(dst: File, writer: FileWithImportsWriter) {
        // skip
    }

    override fun beginPackageDeclaration(
        packagePath: List<String>, file: File, imports: Map<String, Import2>,
        nativeImports: Set<String>
    ) {
        super.beginPackageDeclaration(packagePath, file, imports, nativeImports)
        if (file.name == "main.js") {
            appendGlobalHelpers()
        }
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
        builder.append("// $packagePath")
        nextLine()
        builder.append("'use strict';")
        nextLine()
        nextLine()
    }

    private fun appendGlobalHelpers() {
        appendLines(
            """
            const Float16ArrayPoly = (typeof globalThis.Float16Array === 'function')
              ? globalThis.Float16Array
              : Float32Array;
            const __halfBuffer = new Float16ArrayPoly(1);
        """.trimIndent()
        )

        builder.append("globalThis.__toHalf = function(value) ")
        writeBlock {
            appendLines(
                """
                __halfBuffer[0] = value;
                return __halfBuffer[0];
                """.trimIndent()
            )
        }

        builder.append("globalThis.__floatToLong = function(value) ")
        writeBlock {
            appendLines(
                """
                if (Number.isNaN(value)) return 0n;
                if (!Number.isFinite(value)) return value > 0 ? 9223372036854775807n : -9223372036854775808n;
                const trunc = Math.trunc(value);
                if (trunc >= 9223372036854776000) return 9223372036854775807n;
                if (trunc <= -9223372036854776000) return -9223372036854775808n;
                return BigInt(trunc);
                """.trimIndent()
            )
        }

        builder.append("globalThis.__floatToULong = function(value) ")
        writeBlock {
            appendLines(
                """
                if (Number.isNaN(value)) return 0n;
                if (!Number.isFinite(value)) return value > 0 ? 18446744073709551615n : 0n;
                const trunc = Math.trunc(value);
                if (trunc <= 0) return 0n;
                if (trunc >= 18446744073709552000) return 18446744073709551615n;
                return BigInt(trunc);
                """.trimIndent()
            )
        }
    }

    override fun appendImport(packagePath: List<String>, import: List<String>, importedScope: Scope?) {
        builder.append("import { ")
        builder.append(import.last())
        builder.append(" } from \"")
        builder.appendRelativePath(packagePath, import)
        builder.append(".js\";")
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

            builder.append("export ")
            appendClassFlags(classScope)
            appendClassPrefix(classScope, className)

            // skipped type parameters
            appendSuperTypes(classScope)

            appendClassBody(classScope, className, methods, fields, headerOnly)
        }
    }

    override fun appendMethods(
        classScope: Scope, className: String,
        methods: Collection<Specialization>, headerOnly: Boolean
    ) {
        super.appendMethods(classScope, className, methods, headerOnly)
        appendCastToMethod(classScope)
    }

    fun appendCastToMethod(classScope: Scope) {
        val castToImpl = when (classScope) {
            Types.Byte.clazz -> "return (value << 24) >> 24;"
            Types.UByte.clazz -> "return value & 0xFF;"
            Types.Short.clazz -> "return (value << 16) >> 16;"
            Types.UShort.clazz -> "return value & 0xFFFF;"
            Types.Int.clazz -> "return value | 0;"
            Types.UInt.clazz -> "return BigInt.asUintN(32, value);"
            Types.Long.clazz -> {
                val indent = "  ".repeat(indentation + 1) // +1 for method block
                "const mask = (1n << 64n) - 1n;\n" +
                        indent + "const low = value & mask;\n" +
                        indent + "return (low & (1n << 63n)) ? low - (1n << 64n) : low;"
            }
            Types.ULong.clazz -> "return value & (0xFFFF_FFFF_FFFF_FFFFn)"
            else -> null
        }
        if (castToImpl != null) {
            nextLine()
            builder.append("static castTo(value) ")
            writeBlock {
                builder.append(castToImpl)
                nextLine()
            }
        }
    }

    override fun appendSuperTypes(scope: Scope) {
        if (scope.superCalls.isEmpty() && scope != Types.Any.clazz && !scope.isInterface()) {
            scope.superCalls.add(SuperCall(Types.Any, emptyList(), null, -1))
        }
        for (superCall in scope.superCalls) {
            if (superCall.isInterfaceCall) continue
            val type = superCall.type
            builder.append(" extends ")
            appendType(type, scope, true)
        }
    }

    override fun appendFieldFlags(classScope: Scope, field: Field, allowFinal: Boolean) {
        if (field == classScope.objectField) builder.append("static ")
    }

    override fun appendConstructorFlags(classScope: Scope, constructor: Constructor, headerOnly: Boolean) {
        // nothing
    }

    override fun appendConstructorHeader(
        classScope: Scope, className: String,
        constructor: Constructor, headerOnly: Boolean
    ) {
        appendConstructorFlags(classScope, constructor, headerOnly)
        check(specialization.method === constructor)
        if (classScope.isObjectLike()) builder.append("constructor")
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

            appendSuperCall0(classScope, className, constructor)
            builder.append(';')
            nextLine()

            if (classScope == Types.Array.clazz) {
                appendArrayContentInitialization(constructor)
            }

            if (body != null) {
                val methodSpec = specialization
                check(methodSpec.method === constructor)
                appendCode(context, methodSpec, body, true)
            }
        }
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

            if (!classScope.isObjectLike()) {
                // find out hash of super-call...
                val context = ResolutionContext(
                    classScope, null,
                    specialization, true, null
                )
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

            val context = ResolutionContext(
                classScope, null,
                specialization, true, null
            )
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
        builder.append(';')
        nextLine()
    }

    override fun appendMethodFlags(classScope: Scope, method0: Specialization, headerOnly: Boolean) {
        val method = method0.method
        if (method.flags.hasFlag(Flags.ABSTRACT) && classScope.scopeType != ScopeType.INTERFACE) {
            builder.append("abstract ")
        }
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

    override fun appendMethodBody(methodSpec: Specialization, headerOnly: Boolean) {
        val nativeImpl = getNativeImplementation(methodSpec.method)
        val body = methodSpec.method.body

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
                writeBlock {
                    builder.append("throw 'Missing implementation for $methodSpec';")
                    nextLine()
                }
            }
        }
    }

    override fun appendArrayContentInitialization(constructor: Constructor) {
        val elementType = specialization.typeParameters[0]
        val sizeName = constructor.valueParameters[0].newName
        builder.append("this.content = new ")
        val arrayName = when (elementType) {
            Types.Byte -> "Int8Array"
            Types.UByte -> "UInt8Array"
            Types.Short -> "Int16Array"
            Types.UShort, Types.Char -> "UInt16Array"
            Types.Int -> "Int32Array"
            Types.UInt, Types.ULong -> "BigUint64Array"
            Types.Long -> "Int64Array"
            Types.Half -> "Float16ArrayPoly"
            Types.Float -> "Float32Array"
            Types.Double -> "Float64Array"
            else -> "Array"
        }
        builder.append(arrayName).append('(').append(sizeName).append(");")
        nextLine()
    }

    override fun appendDefaultValue(valueType: Type) {
        when (valueType) {
            Types.UInt -> builder.append("0n")
            else -> super.appendDefaultValue(valueType)
        }
    }

    override fun appendArrayGetter(method0: Specialization) {
        writeBlock {
            builder.append("return this.content[index];")
            nextLine()
        }
    }

    override fun appendArraySetter(method0: Specialization) {
        writeBlock {
            builder.append("this.content[index] = value;")
            nextLine()

            builder.append("return ")
            appendGetObjectInstance(Types.Unit.clazz, method0.method.memberScope)
            builder.append(';')
            nextLine()
        }
    }

    override fun declareLocalField(graph: SimpleGraph, field: LocalField) {
        builder.append("let ")
        builder.append(field.name)
        builder.append(';')
        nextLine()
    }

    override fun appendStaticInstance(classScope: Scope, className: String) {
        builder.append("static ").append(OBJECT_FIELD_NAME)
            .append(" = new ").append(className).append("();")
        nextLine()
    }

    override fun appendDeclare(graph: SimpleGraph, dst: SimpleField, scope: Scope, withEquals: Boolean) {
        builder.append(if (withEquals) "const " else "let ")
        appendFieldName(graph, dst)
        if (withEquals) builder.append(" = ")
    }

    override fun appendValueParameterDeclaration(method: MethodLike, scope: Scope) {
        builder.append('(')
        val selfTypeIfNecessary = method.selfTypeIfNecessary
        if (selfTypeIfNecessary != null) {
            builder.append(" __self")
        }
        for (param in method.valueParameters) {
            if (!builder.endsWith("(")) builder.append(", ")
            appendFieldName(param)
        }
        builder.append(')')
    }

    override fun appendInstrImpl(graph: SimpleGraph, expr: SimpleInstruction) {
        when (expr) {
            is SimpleAllocateInstance -> {
                builder.append("new ")
                appendType(expr.allocatedType, expr.scope, true)
                builder.append("()")
            }
            is SimpleConstructorCall -> {
                appendFieldName(graph, expr.thisInstance, ".")
                builder.append(getMethodName(expr.specialization))
                appendValueParams(graph, expr.valueParameters)
            }
            else -> super.appendInstrImpl(graph, expr)
        }
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
        // todo bug: why is this not being imported???

        builder.append("Object.assign(new ")
        builder.append(needsCastForFirstValue.boxed).append("(), { content: ")
        appendFieldName(graph, expr.thisInstance)
        builder.append(" }).")
        builder.append(getMethodName(expr.specialization))
        appendValueParams(graph, expr.valueParameters)
    }

    override fun appendNumber(type: Type, expr: NumberExpression) {
        when (type) {
            Types.Byte -> builder.append(expr.asInt.toByte())
            Types.UByte -> builder.append(expr.asInt.toUByte())
            Types.Short -> builder.append(expr.asInt.toShort())
            Types.UShort -> builder.append(expr.asInt.toUShort())
            Types.Int -> builder.append(expr.asInt)
            Types.UInt -> builder.append(expr.asInt.toUInt()).append('n')
            Types.Long -> builder.append(expr.asInt).append('n')
            Types.ULong -> builder.append(expr.asInt.toULong()).append('n')
            Types.Half -> builder.append(expr.asFloat.toHalf().toFloat())
            Types.Float -> builder.append(expr.asFloat.toFloat()) // f is not supported
            Types.Double -> builder.append(expr.asFloat)
            else -> throw NotImplementedError("Append number of type $type")
        }
    }

    private fun Type.isBigIntType(): Boolean {
        return this == Types.Long || this == Types.ULong || this == Types.UInt
    }

    private fun appendSourceValue(graph: SimpleGraph, expr: SimpleMethodCall) {
        appendFieldName(graph, expr.thisInstance)
    }

    private fun appendFloatClamp(graph: SimpleGraph, expr: SimpleMethodCall, targetType: Type) {
        val minValue = getMinIntValue(targetType).toDouble()
        val maxValue = getMaxIntValue(targetType).toDouble()
        builder.append("Math.trunc(Math.min(")
        builder.append(maxValue)
        builder.append(", Math.max(")
        builder.append(minValue)
        builder.append(", ")
        appendSourceValue(graph, expr)
        builder.append(")))")
    }

    private fun appendIntegerCast(graph: SimpleGraph, expr: SimpleMethodCall, sourceType: Type, targetType: Type) {
        when {
            sourceType.isFloat() -> {
                when (targetType) {
                    Types.Byte -> {
                        appendFloatClamp(graph, expr, Types.Byte)
                        builder.append(" << 24 >> 24")
                    }
                    Types.UByte -> {
                        appendFloatClamp(graph, expr, Types.UByte)
                        builder.append(" & 0xFF")
                    }
                    Types.Short -> {
                        appendFloatClamp(graph, expr, Types.Short)
                        builder.append(" << 16 >> 16")
                    }
                    Types.UShort, Types.Char -> {
                        appendFloatClamp(graph, expr, Types.UShort)
                        builder.append(" & 0xFFFF")
                    }
                    Types.Int -> {
                        appendFloatClamp(graph, expr, Types.Int)
                        builder.append(" | 0")
                    }
                    Types.UInt -> {
                        builder.append("BigInt(")
                        appendFloatClamp(graph, expr, Types.UInt)
                        builder.append(')')
                    }
                    Types.Long -> {
                        builder.append("globalThis.__floatToLong(")
                        appendSourceValue(graph, expr)
                        builder.append(')')
                    }
                    Types.ULong -> {
                        builder.append("globalThis.__floatToULong(")
                        appendSourceValue(graph, expr)
                        builder.append(')')
                    }
                    else -> error("Not an integer target type: $targetType")
                }
            }
            sourceType.isBigIntType() -> {
                val castFunction = if (targetType.isUnsigned()) "BigInt.asUintN" else "BigInt.asIntN"
                when (targetType) {
                    Types.Byte, Types.UByte, Types.Short, Types.UShort, Types.Char, Types.Int, Types.UInt -> {
                        if (targetType == Types.UInt) {
                            builder.append(castFunction).append("(32, ")
                            appendSourceValue(graph, expr)
                            builder.append(')')
                        } else {
                            builder.append("Number(")
                            builder.append(castFunction).append('(').append(targetType.getNumBits()).append(", ")
                            appendSourceValue(graph, expr)
                            builder.append("))")
                        }
                    }
                    Types.Long, Types.ULong -> {
                        builder.append(castFunction).append("(64, ")
                        appendSourceValue(graph, expr)
                        builder.append(')')
                    }
                    else -> error("Not an integer target type: $targetType")
                }
            }
            else -> {
                when (targetType) {
                    Types.Byte -> {
                        builder.append("((")
                        appendSourceValue(graph, expr)
                        builder.append(" << 24) >> 24)")
                    }
                    Types.UByte -> {
                        builder.append('(')
                        appendSourceValue(graph, expr)
                        builder.append(" & 0xFF)")
                    }
                    Types.Short -> {
                        builder.append("((")
                        appendSourceValue(graph, expr)
                        builder.append(" << 16) >> 16)")
                    }
                    Types.UShort, Types.Char -> {
                        builder.append('(')
                        appendSourceValue(graph, expr)
                        builder.append(" & 0xFFFF)")
                    }
                    Types.Int -> {
                        builder.append('(')
                        appendSourceValue(graph, expr)
                        builder.append(" | 0)")
                    }
                    Types.UInt -> {
                        builder.append("BigInt.asUintN(32, BigInt(")
                        appendSourceValue(graph, expr)
                        builder.append("))")
                    }
                    Types.Long, Types.ULong -> {
                        val castFunction = if (targetType.isUnsigned()) "BigInt.asUintN" else "BigInt.asIntN"
                        builder.append(castFunction).append("(64, BigInt(")
                        appendSourceValue(graph, expr)
                        builder.append("))")
                    }
                    else -> error("Not an integer target type: $targetType")
                }
            }
        }
    }

    private fun appendNumericCast(graph: SimpleGraph, expr: SimpleMethodCall, targetType: Type): Boolean {
        val sourceType = resolveType(expr.thisInstance.type)
        when (targetType) {
            Types.Half -> {
                builder.append("globalThis.__toHalf(")
                if (sourceType.isBigIntType()) builder.append("Number(")
                appendSourceValue(graph, expr)
                if (sourceType.isBigIntType()) builder.append(')')
                builder.append(')')
            }
            Types.Float -> {
                builder.append("Math.fround(")
                if (sourceType.isBigIntType()) builder.append("Number(")
                appendSourceValue(graph, expr)
                if (sourceType.isBigIntType()) builder.append(')')
                builder.append(')')
            }
            Types.Double -> {
                if (sourceType.isBigIntType()) builder.append("Number(")
                appendSourceValue(graph, expr)
                if (sourceType.isBigIntType()) builder.append(')')
            }
            else -> appendIntegerCast(graph, expr, sourceType, targetType)
        }
        return true
    }

    override fun appendUnaryOperator(graph: SimpleGraph, expr: SimpleMethodCall, methodName: String): Boolean {
        val thisType = expr.thisInstance.type
        val targetType = getCastTargetType(methodName)
        return if (targetType != null && thisType in nativeNumbers) {
            appendNumericCast(graph, expr, targetType)
            true
        } else if (thisType in nativeNumbers) {
            val needsCast = thisType.isUnsigned()
            when (methodName) {
                "inv" -> {
                    if (needsCast) {
                        // cast to the corresponding type
                        appendType(thisType, expr.scope, true)
                        builder.append(".castTo(")
                    }
                    builder.append('~')
                    appendFieldName(graph, expr.thisInstance)
                    if (needsCast) builder.append(')')
                    true
                }
                "unaryPlus" -> {
                    appendFieldName(graph, expr.thisInstance)
                    true
                }
                "unaryMinus" -> {
                    if (needsCast) {
                        // cast to the corresponding type
                        appendType(thisType, expr.scope, true)
                        builder.append(".castTo(")
                    }
                    builder.append('-')
                    appendFieldName(graph, expr.thisInstance)
                    if (needsCast) builder.append(')')
                    true
                }
                "inc", "dec" -> {
                    if (needsCast) {
                        // cast to the corresponding type
                        appendType(thisType, expr.scope, true)
                        builder.append(".castTo(")
                    }
                    appendFieldName(graph, expr.thisInstance)
                    builder.append(if (methodName == "inc") " + 1" else " - 1")
                    if (thisType.isBigIntType()) builder.append('n')
                    if (needsCast) builder.append(')')
                    true
                }
                else -> false
            }
        } else super.appendUnaryOperator(graph, expr, methodName)
    }

    override fun appendBinaryOperator(graph: SimpleGraph, expr: SimpleMethodCall, methodName: String): Boolean {
        val type = expr.thisInstance.type
        when (type) {
            Types.String, in nativeTypes -> {}
            else -> return false
        }

        val symbol = getBinarySymbol(type, methodName)
            ?: return false

        // for Int.times use Math.imul()
        if (methodName == "mul" && type == Types.Int) {
            builder.append("Math.imul(")
            appendFirstParameter(graph, type, expr)
            builder.append(", ")
            appendFieldName(graph, expr.valueParameters[0])
            builder.append(')')
            return true
        }

        val needsCast = when (methodName) {
            "and", "or", "xor" -> false
            else -> type != Types.String
        }
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

    override fun appendCopy(graph: SimpleGraph, valueType: Type) {
        builder.append(".copy_0()")
    }

    override fun filterImports(name: String, packageScope: Scope, headerOnly: Boolean) {
        // remove self-include
        imports.remove(name)
    }

    override fun appendTailCallCode(graph: SimpleGraph) {
        builder.append("let nextBlockId = 0;"); nextLine()
        builder.append("blockTable: while (true) ")
        writeBlock {
            builder.append("switch (nextBlockId) ")
            writeBlock {
                val targets = findTailCallTargets(graph)
                val blocks = graph.blocks
                for (i in blocks.indices) {
                    val block = blocks[i]
                    if (i == 0 || targets[block.id]) {
                        builder.append("case ").append(block.id).append(':')
                        writeBlock {
                            appendBlock(graph, block)
                        }
                    }
                }
            }
        }
    }

}
