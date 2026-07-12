package me.anno.generation.java

import me.anno.generation.*
import me.anno.generation.Specializations.specialization
import me.anno.generation.java.JavaSuperCallWriter.appendSuperCall
import me.anno.generation.java.JavaSuperCallWriter.appendSuperCallParams
import me.anno.support.java.tokenizer.JavaTokenizer
import me.anno.utils.NumberUtils.toInt
import me.anno.utils.ResetThreadLocal.Companion.threadLocal
import me.anno.utils.StringStyles
import me.anno.utils.StringUtils.capitalize1
import me.anno.zauber.SpecialFieldNames.OBJECT_FIELD_NAME
import me.anno.zauber.Zauber.root
import me.anno.zauber.ast.FlagSet
import me.anno.zauber.ast.reverse.CodeReconstruction
import me.anno.zauber.ast.reverse.SimpleBranch
import me.anno.zauber.ast.reverse.SimpleLoop
import me.anno.zauber.ast.reverse.SimpleTailCall
import me.anno.zauber.ast.rich.Flags
import me.anno.zauber.ast.rich.Flags.hasFlag
import me.anno.zauber.ast.rich.expression.Expression
import me.anno.zauber.ast.rich.expression.constants.NumberExpression
import me.anno.zauber.ast.rich.expression.constants.NumberExpression.Companion.getNumBits
import me.anno.zauber.ast.rich.expression.constants.NumberExpression.Companion.isSigned
import me.anno.zauber.ast.rich.expression.constants.NumberExpression.Companion.isUnsigned
import me.anno.zauber.ast.rich.expression.constants.SpecialValue
import me.anno.zauber.ast.rich.expression.constants.SpecialValueExpression
import me.anno.zauber.ast.rich.expression.constants.StringExpression
import me.anno.zauber.ast.rich.member.Constructor
import me.anno.zauber.ast.rich.member.Field
import me.anno.zauber.ast.rich.member.Method
import me.anno.zauber.ast.rich.member.MethodLike
import me.anno.zauber.ast.rich.parameter.InnerSuperCall
import me.anno.zauber.ast.rich.parameter.InnerSuperCallTarget
import me.anno.zauber.ast.rich.parameter.Parameter
import me.anno.zauber.ast.simple.ASTSimplifier
import me.anno.zauber.ast.simple.ASTSimplifier.needsFieldByParameter
import me.anno.zauber.ast.simple.SimpleBlock
import me.anno.zauber.ast.simple.SimpleBlock.Companion.isNullable
import me.anno.zauber.ast.simple.SimpleBlock.Companion.needsCopy
import me.anno.zauber.ast.simple.SimpleGraph
import me.anno.zauber.ast.simple.SimpleMerge
import me.anno.zauber.ast.simple.constants.SimpleNumber
import me.anno.zauber.ast.simple.constants.SimpleSpecialValue
import me.anno.zauber.ast.simple.constants.SimpleString
import me.anno.zauber.ast.simple.controlflow.SimpleExit
import me.anno.zauber.ast.simple.controlflow.SimpleReturn
import me.anno.zauber.ast.simple.controlflow.SimpleThrow
import me.anno.zauber.ast.simple.expression.*
import me.anno.zauber.ast.simple.fields.*
import me.anno.zauber.expansion.DependencyData
import me.anno.zauber.interpreting.ExternalKey
import me.anno.zauber.scope.Scope
import me.anno.zauber.scope.ScopeInitType
import me.anno.zauber.scope.ScopeType
import me.anno.zauber.typeresolution.ParameterList.Companion.resolveGenerics
import me.anno.zauber.typeresolution.ResolutionContext
import me.anno.zauber.types.Specialization
import me.anno.zauber.types.Type
import me.anno.zauber.types.Types
import me.anno.zauber.types.impl.*
import me.anno.zauber.types.impl.arithmetic.NullType
import me.anno.zauber.types.impl.arithmetic.UnionType
import me.anno.zauber.types.impl.arithmetic.UnknownType
import java.io.File
import java.util.*

/**
 * before generating JVM bytecode, create source code to be compiled with a normal Java compiler
 *  big difference: stack-based,
 *  small differences:
 *   - Java only supports one interface of each kind [-> can be solved by specialization :3]
 *   - Generics are always boxed, and overrides must be boxed, too [-> we just must be careful with declarations and reflections]
 *   - Java forbids duplicate field names in cascading method scopes [-> we must rename them]
 *   - fields into lambdas must be effectively final [-> must create wrapper objects of all local variables in these cases just like in C (unless inlined)]
 * */
open class JavaSourceGenerator : Generator() {

    companion object {

        /**
         * we may have multiple mangling logic,
         * and defaults shall be empty, so we need prefixes.
         * These prefixes must be special characters.
         * Reserved for that role: UVWXYZ, U = unsigned, Z = specialization
         * */
        val manglingBasis = 10 + ('U'.code - 'A'.code)

        val protectedJavaTypes by threadLocal {
            Types.run {
                mapOf(
                    String to BoxedType("java.lang.String", "java.lang.String"),
                    Boolean to BoxedType("Boolean", "boolean"),

                    Byte to BoxedType("Byte", "byte"),
                    Short to BoxedType("Short", "short"),
                    Int to BoxedType("Integer", "int"),
                    Long to BoxedType("Long", "long"),

                    UByte to BoxedType("UByte", "byte"), // mangled
                    UShort to BoxedType("UShort", "short"), // mangled
                    UInt to BoxedType("UInt", "int"), // mangled
                    ULong to BoxedType("ULong", "long"), // mangled

                    Char to BoxedType("Character", "char"),
                    Half to BoxedType("zauber.Half", "float"), // mangled
                    Float to BoxedType("Float", "float"),
                    Double to BoxedType("Double", "double"),
                    Any to BoxedType("Object", "Object"),

                    // what about these native types???
                    //  for now, we can use custom types, later on, we should replace them for compatibility
                    /*Array.withTypeParameter(Boolean) to BoxedType("Array_zauberBoolean", "boolean[]"),
                    Array.withTypeParameter(Byte) to BoxedType("Array_zauberByte", "byte[]"),
                    Array.withTypeParameter(Short) to BoxedType("Array_zauberShort", "short[]"),
                    Array.withTypeParameter(Int) to BoxedType("Array_zauberInt", "int[]"),
                    Array.withTypeParameter(Long) to BoxedType("Array_zauberLong", "long[]"),
                    Array.withTypeParameter(Char) to BoxedType("Array_zauberChar", "char[]"),
                    Array.withTypeParameter(Float) to BoxedType("Array_zauberFloat", "float[]"),
                    Array.withTypeParameter(Double) to BoxedType("Array_zauberDouble", "double[]"),*/
                )
            }
        }

        val nativeJavaTypes by threadLocal { protectedJavaTypes.filter { (_, it) -> it.boxed != it.native } }
        val nativeJavaNumbers by threadLocal { nativeJavaTypes - Types.Boolean }

        val registeredMethods by threadLocal { HashMap<ExternalKey, String>() }
        fun register(key: ExternalKey, implementation: String) {
            registeredMethods[key] = implementation
        }

        fun register(scope: Scope, name: String, valueParameterTypes: List<Type>, implementation: String) {
            register(ExternalKey(scope, name, valueParameterTypes), implementation)
        }

        fun resolveType(type: Type): Type {
            var type = type
            while (true) {
                try {
                    val resolved = type.resolve().specialize()
                    if (resolved == type) break
                    type = resolved
                } catch (e: Exception) {
                    e.printStackTrace()
                    break
                }
            }
            return type
        }

        fun isStoredField0(field: Field): Boolean {
            return if (field.isObjectInstance()) false
            else needsFieldByParameter(field.byParameter) && needsBackingField(field)
        }

        fun needsBackingField(field: Field): Boolean {
            val getter = field.getter
            val setter = field.setter
            if (getter?.body == null || getter.body!!.needsBackingField(getter.scope)) return true
            if (setter?.body == null) return field.isMutable
            return setter.body!!.needsBackingField(setter.scope)
        }

        fun StringBuilder.appendRelativePath(packagePath: List<String>, import: List<String>) {
            var i = 0
            while (i + 1 < import.size && i < packagePath.size && packagePath[i] == import[i]) {
                // nothing to do
                i++
            }
            val numBackwards = packagePath.size - i
            if (numBackwards > 0) {
                repeat(numBackwards) {
                    append("../")
                }
            } else {
                append("./")
            }
            var needsSlash = false
            while (i < import.size) {
                // todo use underscores instead of slashes, if the scope is not a package
                if (needsSlash) append("/")
                val importI = import[i]
                append(if (i < import.lastIndex) importI.lowercase() else importI.capitalize1())
                needsSlash = true
                i++
            }
        }

        fun getCastTargetType(methodName: String): ClassType? {
            return when (methodName) {
                "toByte" -> Types.Byte
                "toUByte" -> Types.UByte
                "toShort" -> Types.Short
                "toUShort" -> Types.UShort
                "toChar" -> Types.Char
                "toInt" -> Types.Int
                "toUInt" -> Types.UInt
                "toLong" -> Types.Long
                "toULong" -> Types.ULong
                "toHalf" -> Types.Half
                "toFloat" -> Types.Float
                "toDouble" -> Types.Double
                else -> null
            }
        }

        fun isCast(methodName: String): Boolean {
            return getCastTargetType(methodName) != null
        }

    }

    open val protectedTypes: Map<ClassType, BoxedType> get() = protectedJavaTypes
    open val nativeTypes: Map<ClassType, BoxedType> get() = nativeJavaTypes
    open val nativeNumbers: Map<ClassType, BoxedType> get() = nativeJavaNumbers
    open val keywords: Set<String> get() = JavaTokenizer.KEYWORDS

    val declaredFields = HashSet<SimpleField>()
    val usedFields = HashSet<SimpleField>()

    override fun generateCode(dst: File, data: DependencyData, mainMethod: Method) {
        val writer = FileWithImportsWriter(this, dst)
        try {

            defineNullableAnnotation(dst, writer)
            defineMainMethodCall(dst, writer, mainMethod)

            generateCodeImpl(dst, data, writer)

        } finally {
            writer.finish()
        }
    }

    fun ensureImport(scope: Scope) {
        imports.getOrPut(scope.pathStr) {
            Import2(
                if (scope.isPackage()) scope.path + getPackageName(scope)
                else scope.path, scope
            )
        }
    }

    fun ensureImport(type: ClassType) {
        val scope = type.clazz
        val spec = Specialization(type)
        imports.getOrPut(scope.pathStr + createSpecializationSuffix(spec)) {
            Import2(
                if (scope.isPackage()) scope.path + getPackageName(scope)
                else scope.path, scope
            )
        }
    }

    fun generateCodeImpl(dst: File, data: DependencyData, writer: FileWithImportsWriter) {
        val methodsByClass = data.calledMethods.groupBy { methodSpec ->
            val owner = methodSpec.method.ownerScope
            methodSpec.withScope(owner)
        }

        val fieldsByClass = (data.getFields + data.setFields).groupBy { fieldSpec ->
            val owner = fieldSpec.field.ownerScope
            fieldSpec.withScope(owner)
        }

        val createdClasses = data.createdClasses
        val classes = (methodsByClass.keys + fieldsByClass.keys + createdClasses)
            .filter { classSpec -> classSpec.scope!!.isClassLike() }

        for (classSpec in classes) {
            val methods = methodsByClass[classSpec] ?: emptyList()
            val fields = fieldsByClass[classSpec] ?: emptyList()
            val scope = classSpec.scope!!
            scope[ScopeInitType.CODE_GENERATION]
            generateClassForScope(scope, dst, writer, classSpec, methods, fields)
        }
    }

    open fun isStoredField(field: Field): Boolean {
        return isStoredField0(field)
    }

    fun hasThis(method: MethodLike): Boolean {
        // only where we need it. Objects don't need it, except for overridden methods
        // todo and we'd need it, if we had runtime reflections (but we wanted to live without them)
        if (method.isOverride() || method.isOpen()) return true
        if (method is Constructor) return true

        val owner = method.ownerScope
        return !owner.isObjectLike()
    }

    fun hasSelf(method: MethodLike): Boolean {
        return method.hasExplicitSelfType
    }

    fun hasReturn(method: MethodLike): Boolean {
        // todo in theory, no Unit-method needs it,
        //  but we may use that Unit... not really, I think -> fine
        return method.returnType != Types.Unit
    }

    fun isArrayGetter(method0: Specialization): Boolean {
        val method = method0.method
        return method.ownerScope == Types.Array.clazz && method.name == "get"
    }

    fun isArraySetter(method0: Specialization): Boolean {
        val method = method0.method
        return method.ownerScope == Types.Array.clazz && method.name == "set"
    }

    @Deprecated("Use .isSigned()")
    fun isNumberSigned(valueType: Type): Boolean {
        return when (valueType) {
            Types.Byte, Types.Short, Types.Int, Types.Long -> true
            Types.UByte, Types.UShort, Types.Char, Types.UInt, Types.ULong -> false
            // Types.Half, Types.Float, Types.Double -> {}
            else -> throw NotImplementedError("Unknown integer type")
        }
    }

    @Deprecated("Use .isFloat()")
    fun isNumberFloat(valueType: Type): Boolean {
        return when (valueType) {
            Types.Byte, Types.Short, Types.Int, Types.Long,
            Types.UByte, Types.UShort, Types.Char, Types.UInt, Types.ULong -> false
            Types.Half, Types.Float, Types.Double -> true
            else -> throw NotImplementedError("Unknown number type")
        }
    }

    @Deprecated("Use .getNumBits()")
    fun getNumberSizeInBytes(valueType: Type, supportsHalfs: Boolean): Int {
        return when (valueType) {
            Types.Byte, Types.UByte -> 1
            Types.Short, Types.UShort, Types.Char -> 2
            Types.Int, Types.UInt, Types.Float -> 4
            Types.Long, Types.ULong, Types.Double -> 8
            Types.Half -> if (supportsHalfs) 2 else 4
            else -> throw NotImplementedError("Unknown number type")
        }
    }

    fun hasImplementation(method0: Specialization): Boolean {
        val method = method0.method
        return method.body != null || isArrayGetter(method0) || isArraySetter(method0)
    }

    open fun defineNullableAnnotation(dst: File, writer: FileWithImportsWriter) {
        val file = File(dst, "org/jetbrains/annotations/Nullable.java")
        writer[file] = FileEntry("org.jetbrains.annotations".split('.'), this)
            .apply {
                content.append(
                    """
                import java.lang.annotation.*;
                        
                @Retention(RetentionPolicy.RUNTIME)
                @Target(ElementType.TYPE)
                public @interface Nullable {}
            """.trimIndent()
                )
            }
    }

    open fun getMainMethodFile(dst: File): File {
        return File(dst, "zauber/LaunchZauber.${getExtension(false)}")
    }

    open fun defineMainMethodCall(dst: File, writer: FileWithImportsWriter, mainMethod: Method) {
        appendGetObjectInstance(mainMethod.ownerScope, root)
        val className = builder.toString()
        builder.clear()

        writer[getMainMethodFile(dst)] = defineMainMethodCallEntry(dst, writer, mainMethod, className)
    }

    open fun defineMainMethodCallEntry(
        dst: File, writer: FileWithImportsWriter,
        mainMethod: Method, className: String
    ): FileEntry {
        val needsArgs = mainMethod.valueParameters.isNotEmpty()
        return FileEntry(listOf("zauber"), this)
            .apply {
                content.append(
                    """
                public class LaunchZauber {
                    public static void main(String[] args) {
                        $className.${mainMethod.name}(${if (needsArgs) "args" else ""});
                    }
                }
            """.trimIndent()
                )
            }
    }

    open fun createSpecializationSuffix(specialization: Specialization): String {
        if (specialization.isEmpty()) return ""
        // todo ensure the name is original, but keep it readable
        return "_${specialization.createUniqueName()}"
    }

    fun createFile(packageScope: Scope, name: String, dst: File, extension: String): File {
        val packageI = packageScope.path.joinToString("/").lowercase()
        return File(dst, "$packageI/$name.$extension")
    }

    open fun getClassName(scope: Scope, specialization: Specialization): String {
        return if (scope.isPackage()) getPackageName(scope)
        else scope.name + createSpecializationSuffix(specialization)
    }

    open fun getPackageName(scope: Scope): String {
        check(scope.isPackage()) { "Expected scope for packageName to be package" }
        return scope.name.capitalize1() + "Kt"
    }

    open fun generateClassForScope(
        scope: Scope, dst: File, writer: FileWithImportsWriter, specialization: Specialization,
        methods: Collection<Specialization>, fields: Collection<Specialization>
    ) {
        val (name, packageScope) = getNameAndScope(scope, specialization)
        appendClass(name, scope, specialization, methods, fields, false)
        writeInto(packageScope, name, dst, writer, false)
    }

    fun getNameAndScope(
        scope: Scope, specialization: Specialization
    ): Pair<String, Scope> {
        val name: String
        val packageScope: Scope
        if (scope.scopeType == ScopeType.PACKAGE) {
            // we need some package helper
            name = getPackageName(scope)
            packageScope = scope
        } else {
            name = getClassName(scope, specialization)
            packageScope = scope.parent!!
        }
        return name to packageScope
    }

    open fun getExtension(headerOnly: Boolean): String {
        return "java"
    }

    /**
     * whether classes in this package are already included
     * */
    open fun filterImports(name: String, packageScope: Scope, headerOnly: Boolean) {
        imports.entries.removeIf { (name1, path) ->
            // these are automatically imported:
            name == name1 || path.path.dropLast(1) == packageScope.path
        }
    }

    fun writeInto(
        packageScope: Scope,
        name: String,
        dst: File,
        writer: FileWithImportsWriter,
        headerOnly: Boolean
    ): File {
        val file = createFile(packageScope, name, dst, getExtension(headerOnly))
        filterImports(name, packageScope, headerOnly)
        val entry = writer[file]
        if (entry != null) {
            entry.content.append(builder); builder.clear()
            entry.imports.putAll(imports)
            entry.nativeImports.addAll(nativeImports)
            imports.clear()
            nativeImports.clear()
            writer[file] = entry
        } else {
            writer[file] = FileEntry(packageScope.path, this)
        }
        return file
    }

    open fun getClassType(scope: Scope): String {
        return when (scope.scopeType) {
            ScopeType.ENUM_CLASS -> "final class"
            ScopeType.NORMAL_CLASS -> {
                val isFinal = isFinal(scope.flags)
                if (isFinal) "final class" else "class"
            }
            ScopeType.INTERFACE -> "interface"
            ScopeType.OBJECT, ScopeType.COMPANION_OBJECT -> "final class"
            ScopeType.ENUM_ENTRY_CLASS -> "final class"
            ScopeType.PACKAGE -> "final class"
            else -> scope.scopeType.toString()
        }
    }

    fun appendSpecializationInfoComment() {
        if (specialization.isNotEmpty()) {
            comment {
                builder.append("Specialization[${specialization.scope!!.pathStr}]: ")
                val params = specialization.typeParameters
                for (i in params.indices) {
                    if (!builder.endsWith(": ")) builder.append(", ")
                    appendFieldName(params.generics[i])
                    val paramType = params.getOrNull(i)?.toString() ?: "null"
                    builder.append('=').append(StringStyles.removeStyles(paramType))
                }
                nextLine()
            }
        }
    }

    open fun appendClassFlags(scope: Scope) {
        builder.append("public ")

        if (scope.flags.hasFlag(Flags.ABSTRACT)) builder.append("abstract ")
    }

    open fun appendClassPrefix(scope: Scope, className: String) {
        builder.append(getClassType(scope))
            .append(' ').append(className)
    }

    open fun appendStaticInstance(classScope: Scope, className: String) {
        builder.append("public static final ")
        builder.append(className).append(' ').append(OBJECT_FIELD_NAME)
            .append(" = new ").append(className).append("();")
        nextLine()
    }

    open fun appendClass(
        className: String, classScope: Scope, specialization: Specialization,
        methods: Collection<Specialization>, fields: Collection<Specialization>,
        headerOnly: Boolean
    ) {
        declareImport(classScope, specialization)
        specialization.use {
            appendSpecializationInfoComment()

            appendClassFlags(classScope)
            appendClassPrefix(classScope, className)

            if (specialization.containsGenerics()) {
                appendTypeParams(classScope)
            }
            appendSuperTypes(classScope)

            appendClassBody(classScope, className, methods, fields, headerOnly)
        }
    }

    open fun appendClassBody(
        classScope: Scope, className: String,
        methods: Collection<Specialization>,
        fields: Collection<Specialization>,
        headerOnly: Boolean
    ) {
        writeBlock {

            if (classScope.isObjectLike()) {
                appendStaticInstance(classScope, className)
            }

            val allowFinalFields = !classScope.isValueType() &&
                    methods.any { it.method is Constructor }

            appendFields(classScope, fields, allowFinalFields, headerOnly)
            appendConstructors(classScope, className, methods, headerOnly)
            appendMethods(classScope, className, methods, headerOnly)
        }
    }

    open fun appendFields(
        classScope: Scope, fields: Collection<Specialization>, allowFinal: Boolean,
        headerOnly: Boolean
    ) {
        if (classScope.scopeType == ScopeType.INTERFACE) return // no backing fields
        for (fieldSpec in fields) {
            val field = fieldSpec.field
            if (isStoredField(field)) {
                fieldSpec.use {
                    appendField(classScope, field, allowFinal, headerOnly)
                }
            }
        }

        if (classScope == Types.Array.clazz) {
            appendArrayContentField(classScope, headerOnly)
        }
    }

    open fun appendArrayContentField(classScope: Scope, headerOnly: Boolean) {
        val valueType = specialization.typeParameters[0]
        builder.append("private final ")
        appendType(valueType, classScope, false)
        builder.append("[] content;")
        nextLine()
    }

    open fun appendFieldFlags(classScope: Scope, field: Field, allowFinal: Boolean) {
        builder.append("public ")
        if (field == classScope.objectField) builder.append("static ")
        if (!field.isMutable && allowFinal) builder.append("final ")
    }

    open fun appendField(classScope: Scope, field: Field, allowFinal: Boolean, headerOnly: Boolean) {
        appendFieldFlags(classScope, field, allowFinal)

        var valueType = (field.valueType ?: Types.NullableAny)
        valueType = valueType.resolve(classScope)
        valueType = resolveType(valueType)

        appendType(valueType, classScope, false)
        builder.append(' ')
        appendFieldName(field)
        builder.append(';')
        nextLine()
    }

    open fun appendMethods(
        classScope: Scope, className: String,
        methods: Collection<Specialization>,
        headerOnly: Boolean
    ) {
        for (method0 in methods) {
            val method = method0.method
            if (method !is Method) continue
            if (canSkipMethod(classScope, method)) {
                // an inherited method -> skip, because it's already defined in the parent
                continue
            }

            appendMethod(classScope, className, method0, headerOnly)
        }
    }

    open fun canSkipMethod(classScope: Scope, method: Method): Boolean {
        return method.scope.parent != classScope
    }

    open fun appendConstructors(
        classScope: Scope, className: String,
        methods: Collection<Specialization>,
        headerOnly: Boolean
    ) {
        for (spec in methods) {
            val constructor = spec.method
            if (constructor !is Constructor) continue
            spec.use {
                appendConstructor(classScope, className, constructor, headerOnly)
            }
        }
    }

    fun isFinal(keywords: FlagSet): Boolean {
        return keywords.hasFlag(Flags.FINAL) || (
                !keywords.hasFlag(Flags.OPEN) &&
                        !keywords.hasFlag(Flags.OVERRIDE) &&
                        !keywords.hasFlag(Flags.ABSTRACT)
                )
    }

    open fun appendMethod(classScope: Scope, className: String, method0: Specialization, headerOnly: Boolean) {
        // some spacing
        nextLine()

        appendMethodHeader(classScope, className, method0, headerOnly)
        appendMethodBody(method0, headerOnly)
    }

    fun assignSelfType(classScope: Scope, method: Method) {
        val selfType = method.selfType
        val isBySelf = selfType == classScope.typeWithArgs ||
                method.flags.hasFlag(Flags.OVERRIDE) ||
                method.flags.hasFlag(Flags.ABSTRACT)

        val selfTypeIfNecessary = if (!isBySelf) selfType else null
        method.selfTypeIfNecessary = selfTypeIfNecessary
    }

    open fun appendMethodHeader(
        classScope: Scope,
        className: String,
        method0: Specialization,
        headerOnly: Boolean
    ) {
        appendMethodFlags(classScope, method0, headerOnly)

        val method = method0.method as Method
        appendTypeParameterDeclaration(method.typeParameters, classScope)
        if (hasReturn(method)) {
            appendType(method.resolveReturnType(method0), classScope, false)
        } else {
            builder.append("void")
        }

        builder.append(' ').append(getMethodName(method0))

        assignSelfType(classScope, method)
        appendValueParameterDeclaration(method, classScope)
    }

    open fun appendMethodBody(methodSpec: Specialization, headerOnly: Boolean) {
        val method = methodSpec.method
        val nativeImpl = getNativeImplementation(method)
        val body = method.body

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
                builder.append(";")
                nextLine()
            }
        }
    }

    open fun appendArrayGetter(method0: Specialization) {
        writeBlock {
            builder.append("return this.content[index];")
            nextLine()
        }
    }

    open fun appendArraySetter(method0: Specialization) {
        writeBlock {
            builder.append("this.content[index] = value;")
            nextLine()

            if (hasReturn(method0.method)) {
                builder.append("return ")
                appendGetObjectInstance(Types.Unit.clazz, method0.method.memberScope)
                builder.append(';'); nextLine()
            }
        }
    }

    open fun appendNativeImplementation(nativeImpl: String, method: MethodLike) {
        writeBlock {
            val i0 = builder.length
            builder.append(nativeImpl)
            nextLine()
            appendReturnIfMissing(method, i0)
        }
    }

    open fun appendReturnIfMissing(method: MethodLike, i0: Int) {
        if (hasReturn(method) && builder.indexOf("return", i0) < 0) {
            builder.append("return ")
            appendGetObjectInstance(Types.Unit.clazz, method.scope)
            builder.append(";")
            nextLine()
        }
    }

    open fun getMethodName(method0: Specialization): String {
        return getMethodName0(method0) + getUnsignedMangling(method0)
    }

    fun getMethodName0(method0: Specialization): String {
        val method = method0.method
        val name = method.name
        return if (method0.isNotEmpty()) {
            "${name}_Z${method0.hash.toString(manglingBasis)}"
        } else if (name in keywords) {
            "${name}_"
        } else name
    }

    /**
     * println(Int) and println(UInt) would have the same signature in Java,
     * so we must separate them
     * */
    fun getUnsignedMangling(method0: Specialization): String {
        var flags = 0L
        val params = method0.method.valueParameters
        var j = 0
        for (i in params.indices) {
            val type = params[i].type
            if (type.isUnsigned() || type == Types.Half) {
                check(j < 64) {
                    "Only parameters<64 may be unsigned (causes mangling issues)"
                }
                flags = flags or (1L shl j)
                j++
            } else if (type.isSigned() || type == Types.Float) j++
        }
        return if (flags == 0L) ""
        else "_U${flags.toString(manglingBasis)}"
    }

    open fun appendMethodFlags(classScope: Scope, method0: Specialization, headerOnly: Boolean) {

        val method = method0.method
        if (method.flags.hasFlag(Flags.OVERRIDE)) {
            // todo we must check if the super-type is actually defined,
            //  before we can define this
            // builder.append("@Override")
            nextLine()
        }

        if (method.flags.hasFlag(Flags.ABSTRACT) && classScope.scopeType != ScopeType.INTERFACE) {
            builder.append("abstract ")
        }

        val isPublic = classScope.scopeType != ScopeType.INTERFACE
        val isNative = method.flags.hasFlag(Flags.EXTERNAL)
        val isFinal = (classScope.scopeType != ScopeType.INTERFACE && isFinal(method.flags)) ||
                isNative || classScope.isObjectLike()
        val isDefault = classScope.scopeType == ScopeType.INTERFACE && method.body != null

        val nativeImpl = getNativeImplementation(method)

        if (isPublic) builder.append("public ")
        if (isNative &&
            nativeImpl == null &&
            !isArrayGetter(method0) &&
            !isArraySetter(method0) &&
            method.body == null
        ) builder.append("native ")
        if (isFinal) builder.append("final ")
        if (isDefault) builder.append("default ")

    }

    open fun getNativeImplementation(method: MethodLike): String? {
        val classScope = method.ownerScope
        return if (method.flags.hasFlag(Flags.EXTERNAL)) {
            val valueParameterTypes = method.valueParameters.map { it.type.resolve(classScope) }
            registeredMethods[ExternalKey(classScope, method.name, valueParameterTypes)]
        } else null
    }

    open fun appendConstructorFlags(classScope: Scope, constructor: Constructor, headerOnly: Boolean) {
        val visibility = if (classScope.isObject()) "private " else "public "
        builder.append(visibility)
    }

    open fun appendConstructor(classScope: Scope, className: String, constructor: Constructor, headerOnly: Boolean) {

        // some spacing
        nextLine()

        appendConstructorHeader(classScope, className, constructor, headerOnly)
        appendConstructorBody(classScope, className, constructor, headerOnly)
    }

    open fun appendSuperCall0(
        classScope: Scope, className: String,
        constructor: Constructor,
    ) {
        // interfaces don't need super calls :)
        val superCall = constructor.superCall
        val superType = classScope.superCalls
            .firstOrNull { it.isClassCall }?.typeI
            ?: Types.Any
        if (superCall != null) {
            appendSuperCall0Name(
                classScope, className, constructor,
                superType, superCall
            )

            val context = ResolutionContext(
                constructor.scope, null, specialization,
                true, null
            )
            appendSuperCallParams(context, superCall)
        } else {
            comment { builder.append("superCall is null") }
        }
    }

    open fun appendSuperCall0Name(
        classScope: Scope, className: String, constructor: Constructor,
        superType: Type, superCall: InnerSuperCall,
    ) {
        val name = if (superCall.target == InnerSuperCallTarget.THIS) "this" else "super"
        builder.append(name)
    }

    open fun appendConstructorBody(
        classScope: Scope, className: String,
        constructor: Constructor, headerOnly: Boolean
    ) {
        val body = constructor.body
        val context = ResolutionContext(
            constructor.scope, constructor.selfType,
            true, null, emptyMap()
        )

        writeBlock {
            val superCall = constructor.superCall
            if (superCall != null) {
                appendSuperCall(context, superCall)
            }

            if (classScope == Types.Array.clazz &&
                constructor.valueParameters.size == 1 &&
                constructor.valueParameters[0].type == Types.Int
            ) {
                appendArrayContentInitialization(constructor)
            }

            if (body != null) {
                val methodSpec = specialization
                check(methodSpec.method === constructor)
                appendCode(context, methodSpec, body, true)
            }
        }
    }

    open fun appendArrayContentInitialization(constructor: Constructor) {
        val elementType = specialization.typeParameters[0]
        builder.append("content = new ")
        appendType(elementType, constructor.scope, false)
        builder.append("[")
        appendFieldName(constructor.valueParameters[0])
        builder.append("];")
        nextLine()
    }

    open fun appendConstructorHeader(
        classScope: Scope, className: String,
        constructor: Constructor, headerOnly: Boolean
    ) {
        appendConstructorFlags(classScope, constructor, headerOnly)
        builder.append(className)
        appendValueParameterDeclaration(constructor, classScope)
    }

    open fun appendTypeParameterDeclaration(valueParameters: List<Parameter>, scope: Scope) {
        if (valueParameters.isEmpty()) return
        builder.append('<')
        for (param in valueParameters) {
            if (!builder.endsWith("<")) builder.append(", ")
            ensureFieldName(param)
            builder.append(param.newName)
            if (param.type != Types.Any && param.type != Types.NullableAny) {
                builder.append(" extends ")
                appendType(param.type, scope, false)
            }
        }
        builder.append("> ")
    }

    open fun appendValueParameterDeclaration(method: MethodLike, scope: Scope) {
        builder.append('(')
        val selfTypeIfNecessary = method.selfTypeIfNecessary
        if (selfTypeIfNecessary != null) {
            appendType(selfTypeIfNecessary, scope, false)
            builder.append(" __self")
        }
        for (param in method.valueParameters) {
            if (!builder.endsWith("(")) builder.append(", ")
            appendType(param.type, scope, false)
            builder.append(' ')
            appendFieldName(param)
        }
        builder.append(')')
    }

    open fun prepareGraph(graph: SimpleGraph) {
        graph.removeWriteOnlyFields()
        graph.removeObjectFields()
        graph.removeConstantFields()
        graph.giveLocalFieldsUniqueNames(JavaTokenizer.KEYWORDS)
        graph.removeSimpleGetObject()
        graph.removeMergeInfoInstructions()
        graph.renumberFields()
        graph.markSimpleReadImmediatelyAfterAssignment()

        CodeReconstruction.createCodeFromGraph(graph)
        graph.renumberFields() // necessary
    }

    open fun appendCode(
        context: ResolutionContext,
        method1: Specialization,
        body: Expression,
        skipSuperCall: Boolean
    ) {
        writeBlock {
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
    }

    fun findTailCallTargets(graph: SimpleGraph): BitSet {
        val targets = BitSet(graph.blocks.size)
        for (block in graph.blocks) {
            for (instr in block.instructions) {
                if (instr is SimpleTailCall) {
                    targets.set(instr.toBeCalled.id)
                }
            }
        }
        return targets
    }

    open fun appendTailCallCode(graph: SimpleGraph) {
        builder.append("int nextBlockId = 0;"); nextLine()
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

    open fun removeTailingReturn() {
        removeTrailingSuffix("return;")
    }

    fun findSuffixOffset(suffix: String): Int {
        var i = builder.length
        while (i > 0 && builder[i - 1].isWhitespace()) i--
        i -= suffix.length
        return i
    }

    fun removeTrailingSuffix(suffix: String) {
        val i = findSuffixOffset(suffix)
        if (builder.startsWith(suffix, i)) {
            builder.setLength(i)
        }
    }

    open fun appendTypeParams(scope: Scope) {
        val typeParams = scope.typeParameters
        if (typeParams.isNotEmpty()) {
            builder.append('<')
            for ((i, param) in typeParams.withIndex()) {
                if (i > 0) builder.append(", ")
                appendFieldName(param)
                if (param.type != Types.NullableAny) {
                    builder.append(" extends ")
                    appendType(param.type, scope, true)
                }
            }
            builder.append('>')
        }
    }

    open fun appendSuperTypes(scope: Scope) {
        try {
            val superCall0 = scope.superCalls.firstOrNull()
            if (superCall0 != null &&
                superCall0.isClassCall &&
                superCall0.type != Types.Any
            ) {
                val type = superCall0.type
                builder.append(" extends ")
                appendType(type, scope, true)
            }

            var implementsKeyword = if (scope.scopeType == ScopeType.INTERFACE) " extends " else " implements "
            for (superCall in scope.superCalls) {
                if (superCall.isInterfaceCall) {
                    val type = superCall.type
                    builder.append(implementsKeyword)
                    appendType(type, scope, true)
                    implementsKeyword = ", "
                }
            }
        } catch (e: Exception) {
            comment { builder.append(e) }
        }
    }

    fun declareImport(classScope: Scope, specialization: Specialization) {
        val length = builder.length
        appendClassType(classScope, specialization)
        builder.setLength(length)
    }

    val imports = HashMap<String, Import2>()
    val nativeImports = LinkedHashSet<String>()

    open fun appendAssign(graph: SimpleGraph, expression: SimpleAssignment) {
        val dst = expression.dst
        if (dst.mergeInfo != null) {
            appendFieldName(graph, dst)
            builder.append(" = ")
        } else if (dst.id >= 0) {
            appendDeclare(graph, expression)
        } // else unused
    }

    fun appendDeclare(graph: SimpleGraph, expression: SimpleAssignment) {
        appendDeclare(graph, expression.dst, expression.scope, true)
        declaredFields.add(expression.dst)
    }

    open fun appendDeclare(graph: SimpleGraph, dst: SimpleField, scope: Scope, withEquals: Boolean) {
        builder.append("final ")
        appendType(dst.type, scope, false)
        builder.append(' ')
        appendFieldName(graph, dst)
        if (withEquals) builder.append(" = ")
    }

    open fun appendMissingDeclarations(graph: SimpleGraph, pos0: Int) {
        val pos1 = builder.length
        for (field in usedFields - declaredFields) {
            appendDeclare(graph, field, graph.method.memberScope, false)
            builder.append(";")
            nextLine()
        }
        swapSections(pos0, pos1)

        usedFields.clear()
        declaredFields.clear()
    }

    fun declareLocalFields(graph: SimpleGraph) {
        val fields = graph.localFields
        val i0 = 1 + graph.method.hasExplicitSelfType.toInt() + graph.method.valueParameters.size
        for (i in i0 until fields.size) {
            declareLocalField(graph, fields[i])
        }
    }

    open fun declareLocalField(graph: SimpleGraph, field: LocalField) {
        val type = field.type
        appendType(type, graph.method.memberScope, false)
        builder.append(' ')
        ensureFieldName(field)
        builder.append(field.newName).append(" = ")
        appendDefaultValue(type)
        builder.append(";")
        nextLine()
    }

    open fun appendDefaultValue(valueType: Type) {
        when (valueType) {
            Types.Boolean -> builder.append("false")
            in nativeTypes -> builder.append("0")
            else -> builder.append("null")
        }
    }

    fun swapSections(pos0: Int, pos1: Int) {
        if (pos1 == pos0) return
        check(pos1 >= pos0)
        val middleSection = builder.substring(pos0, pos1)
        val afterSection = builder.substring(pos1)
        builder.setLength(pos0)
        builder.append(afterSection)
        builder.append(middleSection)
    }

    fun SimpleField.isObjectLike() = type is ClassType && type.clazz.isObjectLike()

    fun SimpleField.isOwnerThis(graph: SimpleGraph): Boolean {
        return fromLocalField === graph.thisField
    }

    open fun appendGetObjectInstance(objectScope: Scope, exprScope: Scope) {
        if (objectScope == outsideClassLike(exprScope)) {
            builder.append("this")
        } else {
            appendType(objectScope.typeWithArgs, objectScope, false)
            builder.append('.').append(OBJECT_FIELD_NAME)
        }
    }

    fun appendFloat(asFloat: Double, suffix: String) {
        if (asFloat.isFinite()) builder.append(asFloat).append(suffix)
        else appendInfinite(asFloat, suffix)
    }

    fun appendInfinite(asFloat: Double, suffix: String) {
        if (asFloat.isNaN()) builder.append("0 / 0.0")
        else if (asFloat.isFinite()) builder.append(asFloat)
        else if (asFloat > 0f) builder.append("1 / 0.0") else builder.append("(-1) / 0.0")

        builder.append(suffix)
    }

    open fun appendNumber(type: Type, expr: NumberExpression) {
        when (type) {
            // todo how do we want to handle U-ints? ideally as ints, but whenever we call or so...
            //  -> we need to mangle the method name for unsigned parameters
            Types.Byte, Types.UByte -> builder.append(expr.asInt.toByte())
            Types.Short, Types.UShort -> builder.append(expr.asInt.toShort())
            Types.Int, Types.UInt -> builder.append(expr.asInt.toInt())
            Types.Long, Types.ULong -> builder.append(expr.asInt).append('L')
            Types.Float, Types.Half -> appendFloat(expr.asFloat, "f")
            Types.Double -> appendFloat(expr.asFloat, "d")
            Types.Char -> {
                builder.append('\'')
                when (val value = expr.asInt.toInt().toChar()) {
                    in 'A'..'Z', in 'a'..'z', in '0'..'9' -> builder.append(value)
                    else -> builder.append("\\u")
                        .append(value.code.toString(16).padStart(4, '0'))
                }
                builder.append('\'')
            }
            else -> throw NotImplementedError("Append number of type $type")
        }
    }

    fun meansContent(field: SimpleField, forFieldAccess: String): Boolean {
        return forFieldAccess == "" && field.type in nativeNumbers
    }

    open fun appendFieldName(
        graph: SimpleGraph, field: SimpleField,
        forFieldAccess: String = ""
    ) {
        if (field.isOwnerThis(graph)) {
            builder.append(if (meansContent(field, forFieldAccess)) "this.content" else "this")
        } else if (field.isObjectLike()) {
            val objectScope = (field.type as ClassType).clazz
            appendGetObjectInstance(objectScope, graph.method.scope)
        } else {
            val field = field.dst
            when (val expr = field.constantRef) {
                is NumberExpression -> appendNumber(field.type, expr)
                is StringExpression -> appendString(expr.value)
                is SpecialValueExpression -> when (expr.type) {
                    SpecialValue.NULL -> builder.append("null")
                    SpecialValue.TRUE -> builder.append("true")
                    SpecialValue.FALSE -> builder.append("false")
                }
                null -> {
                    check(field.id >= 0) { "Invalid field $field in $graph" }
                    val localField = field.fromLocalField
                    val immediateValue = field.immediateValue
                    if (localField != null) {
                        builder.append(localField.newName)
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

    open fun appendString(expr: String) {
        builder.ensureCapacity(builder.length + expr.length + 2)
        builder.append('\"')
        for (c in expr) {
            when (c) {
                '\"' -> builder.append("\\\"")
                '\n' -> builder.append("\\n")
                '\r' -> builder.append("\\r")
                '\t' -> builder.append("\\t")
                in ' '..'~' -> builder.append(c) // printable
                else -> builder.append("\\u").append(c.code.toString(16).padStart(4, '0'))
            }
        }
        builder.append('\"')
    }

    open fun appendValueParams(graph: SimpleGraph, valueParameters: List<SimpleField>, withBrackets: Boolean = true) {
        if (withBrackets) builder.append('(')
        for (i in valueParameters.indices) {
            if (!builder.endsWith('(')) builder.append(", ")
            val parameter = valueParameters[i]
            appendFieldName(graph, parameter)
        }
        if (withBrackets) builder.append(')')
    }

    open fun appendBlock(graph: SimpleGraph, block: SimpleBlock) {
        val instructions = block.instructions
        for (i in instructions.indices) {
            val instr = instructions[i]
            appendSimpleInstruction(graph, instr)
        }
        if (block.nextBranch != null) {
            // this may or may not be simply be possible...
            // this may be a loop, branch, or similar...
            throw NotImplementedError("Arbitrary jumps are not supported in Java")
        }
    }

    open fun needsAssignment(expr: SimpleAssignment): Boolean {
        val dst = expr.dst.dst
        return dst.numReads > 0 && !dst.isObjectLike() && expr.dst.type != Types.Nothing
    }

    open fun appendInstrPrefix(graph: SimpleGraph, expr: SimpleInstruction) {
        if (expr is SimpleAssignment && needsAssignment(expr)) {
            appendAssign(graph, expr)
        }
    }

    open fun appendInstrSuffix(graph: SimpleGraph, expr: SimpleInstruction) {
        when (expr) {
            is SimpleConstructorCall, is SimpleMerge -> {}
            is SimpleMethodCall -> {
                if (expr.sample !is Constructor) {
                    builder.append(";")
                }// else we only placed a comment
            }
            is SimpleAssignment,
            is SimpleSetClassField,
            is SimpleSetLocalField,
            is SimpleExit -> builder.append(';')
            else -> {}
        }
        if (/*expr !is SimpleBlock &&*/ expr !is SimpleBranch) nextLine()
        if (expr is SimpleAssignment && expr.dst.type == Types.Nothing) {
            builder.append("throw new AssertionError(\"Unreachable\");")
            nextLine()
        }
    }

    open fun canSkipInstruction(expr: SimpleInstruction): Boolean {
        if (expr is SimpleGetObject) return true
        if (expr is SimpleGetLocalField && expr.dst.dst.fromLocalField == expr.field) return true
        if (expr is SimpleConstructorCall && expr.forAllocation) return true
        return false
    }

    open fun appendSimpleInstruction(
        graph: SimpleGraph, expr: SimpleInstruction,
        // loop: SimpleLoop? = null
    ) {
        if (canSkipInstruction(expr)) return

        appendInstrPrefix(graph, expr)
        appendInstrImpl(graph, expr)
        appendInstrSuffix(graph, expr)
    }

    open fun appendInstrImpl(graph: SimpleGraph, expr: SimpleInstruction) {
        when (expr) {
            is SimpleBranch -> {
                builder.append("if (")
                appendFieldName(graph, expr.condition)
                builder.append(')')
                writeBlock {
                    appendBlock(graph, expr.ifTrue)
                }
                if (expr.ifFalse != null) {
                    removeTrailingWhitespace()
                    builder.append(" else ")
                    writeBlock {
                        appendBlock(graph, expr.ifFalse)
                    }
                }
            }
            is SimpleLoop -> {
                builder.append("while (true)")
                writeBlock {
                    if (expr.condition != null) {
                        appendBlock(graph, expr.conditionBlock!!)
                        builder.append("if (")
                        if (!expr.negate) builder.append("!(")
                        appendFieldName(graph, expr.condition)
                        if (!expr.negate) builder.append(')')
                        builder.append(") break;")
                        nextLine()
                        nextLine()
                    }
                    appendBlock(graph, expr.body)
                }
            }
            is SimpleConstructorCall -> {
                // done already
            }
            is SimpleString -> {
                // todo escape unsupported/problematic characters
                builder.append('"').append(expr.base.value).append('"')
            }
            is SimpleNumber -> appendNumber(expr.dst.type, expr.base)
            is SimpleGetLocalField -> {
                if (expr.field.id == 0 && expr.field.type in nativeNumbers) builder.append("this.content")
                else builder.append(expr.field.newName)
            }
            is SimpleSetLocalField -> {
                builder.append(expr.field.newName)
                builder.append(" = ")

                appendFieldName(graph, expr.value)

                val needsCopy = expr.value.type.needsCopy()
                if (needsCopy) appendCopy(graph, expr.value.type)
            }
            is SimpleGetClassField -> {
                if (expr.dst.dst.id >= 0) {
                    appendSelfForFieldAccess(graph, expr.self, expr.field, expr.scope)
                    appendFieldName(expr.field)
                } // else skip
            }
            is SimpleSetClassField -> {
                appendSelfForFieldAccess(graph, expr.self, expr.field, expr.scope)
                appendFieldName(expr.field)
                builder.append(" = ")

                appendFieldName(graph, expr.value)

                val needsCopy = expr.value.type.needsCopy()
                if (needsCopy) appendCopy(graph, expr.value.type)
            }
            is SimpleCompare -> {
                appendFieldName(graph, expr.left)
                builder.append(' ')
                builder.append(expr.type.symbol)
                builder.append(' ')
                appendFieldName(graph, expr.right)
            }
            is SimpleInstanceOf -> {
                appendFieldName(graph, expr.value)
                builder.append(" instanceof ")
                appendType(expr.type, expr.scope, true)
            }
            is SimpleCheckEquals -> {
                // todo this could be converted into a SimpleBranch + SimpleCall
                // todo simple types use ==, while complex types use .equals()

                // todo if left cannot be null, skip null check
                // todo if left side is a native field, use the static class for comparison

                val leftCanBeNull = expr.left.type.isNullable()
                val rightCanBeNull = expr.right.type.isNullable()

                val leftNative = nativeTypes[expr.left.type]
                val rightNative = nativeTypes[expr.right.type]
                when {
                    leftNative != null && rightNative != null -> {
                        appendFieldName(graph, expr.left)
                        builder.append(" == ")
                        appendFieldName(graph, expr.right)
                    }
                    leftCanBeNull && rightCanBeNull -> {
                        appendFieldName(graph, expr.left)
                        builder.append(" == null ? ")
                        appendFieldName(graph, expr.right)
                        builder.append(" == null : ")
                        appendFieldName(graph, expr.left, ".")
                        builder.append("equals(")
                        appendFieldName(graph, expr.right)
                        builder.append(")")
                    }
                    leftCanBeNull -> {
                        appendFieldName(graph, expr.left)
                        builder.append(" != null && ")
                        appendFieldName(graph, expr.left, ".")
                        builder.append("equals(")
                        appendFieldName(graph, expr.right)
                        builder.append(")")
                    }
                    rightCanBeNull -> {
                        appendFieldName(graph, expr.right)
                        builder.append(" != null && ")
                        appendFieldName(graph, expr.left, ".")
                        builder.append("equals(")
                        appendFieldName(graph, expr.right)
                        builder.append(")")
                    }
                    else -> {
                        appendFieldName(graph, expr.left, ".")
                        builder.append("equals(")
                        appendFieldName(graph, expr.right)
                        builder.append(")")
                    }
                }
            }
            is SimpleCheckIdentical -> {
                appendFieldName(graph, expr.left)
                builder.append(" == ")
                appendFieldName(graph, expr.right)
            }
            is SimpleSpecialValue -> {
                when (expr.type) {
                    SpecialValue.TRUE -> builder.append("true")
                    SpecialValue.FALSE -> builder.append("false")
                    SpecialValue.NULL -> builder.append("null")
                }
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
            is SimpleAllocateInstance -> {
                // handled in SimpleCall, because only there do we have the value parameters
                builder.append("new ")
                appendType(expr.allocatedType, expr.scope, true)
                appendValueParams(graph, expr.paramsForLater)
            }
            is SimpleReturn -> {
                builder.append("return")
                if (hasReturn(graph.method)) {
                    // todo cast if necessary
                    builder.append(' ')
                    appendFieldName(graph, expr.field)
                }
            }
            is SimpleThrow -> {
                // todo cast if necessary
                builder.append("throw ")
                appendFieldName(graph, expr.field)
            }
            is SimpleMerge -> { /* not usable in Java */
            }
            is SimpleTailCall -> {
                builder.append("nextBlockId = ").append(expr.toBeCalled.id).append(';')
                nextLine()
                builder.append("continue blockTable;")
            }
            else -> throw NotImplementedError("Implement ${expr.javaClass.simpleName}")
        }
    }

    open fun appendUnaryOperator(graph: SimpleGraph, expr: SimpleMethodCall, methodName: String): Boolean {
        val thisType = expr.thisInstance.type
        val castTargetType = getCastTargetType(methodName)
        if (castTargetType != null && thisType in nativeNumbers) {
            builder.append('(').append(nativeNumbers[castTargetType]!!.native).append(") ")
        } else if (methodName == "not" && thisType == Types.Boolean) {
            builder.append('!')
        } else if (thisType in nativeNumbers) {
            val needsCast = thisType.getNumBits() <= 16
            when (methodName) {
                "inv" -> if (needsCast) {
                    builder.append('(')
                    appendType(expr.dst.type, expr.scope, false)
                    builder.append(") ~")
                } else {
                    builder.append('~')
                }
                "inc", "dec" -> {
                    if (needsCast) {
                        builder.append('(')
                        appendType(expr.dst.type, expr.scope, false)
                        builder.append(") (")
                    }
                    appendFieldName(graph, expr.thisInstance)
                    builder.append(if (methodName == "inc") " + 1" else " - 1")
                    if (needsCast) builder.append(')')
                    return true
                }
                "unaryPlus" -> {
                    // UByte and UShort need to be properly cast to Int
                    when (thisType) {
                        Types.UByte -> builder.append("255 & ")
                        Types.UShort -> builder.append("65535 & ")
                    }
                }
                "unaryMinus" -> {
                    when {
                        thisType == Types.UByte -> {
                            builder.append("-(255 & ")
                            appendFieldName(graph, expr.thisInstance)
                            builder.append(')')
                            return true
                        }
                        thisType == Types.UShort -> {
                            builder.append("-(65535 & ")
                            appendFieldName(graph, expr.thisInstance)
                            builder.append(')')
                            return true
                        }
                        needsCast -> {
                            builder.append('(')
                            appendType(expr.dst.type, expr.scope, false)
                            builder.append(") -")
                        }
                        else -> {
                            builder.append('-')
                        }
                    }
                }
                else -> return false
            }
        } else return false

        appendFieldName(graph, expr.thisInstance)
        return true
    }

    open fun getBinarySymbol(type: Type, methodName: String): String? {
        return when (methodName) {
            "plus" -> " + "
            "minus" -> " - "
            "times" -> " * "
            "div" -> " / "
            "rem" -> " % "
            "and" -> " & "
            "or" -> " | "
            "xor" -> " ^ "
            "shl" -> "<<"
            "shr" -> if (type.isUnsigned()) ">>>" else ">>"
            "ushr" -> ">>>"
            else -> null
        }
    }

    fun appendFirstParameter(graph: SimpleGraph, type: Type, expr: SimpleMethodCall) {
        if (type != Types.String && expr.thisInstance.isOwnerThis(graph)) {
            check(type is ClassType && type.clazz.fields.any { it.name == "content" }) {
                "$type is missing field 'content'"
            }
            appendFieldName(graph, expr.thisInstance, ".")
            builder.append("content")
        } else {
            appendFieldName(graph, expr.thisInstance)
        }
    }

    open fun appendBinaryOperator(graph: SimpleGraph, expr: SimpleMethodCall, methodName: String): Boolean {
        val type = expr.thisInstance.type
        when (type) {
            Types.String, in nativeTypes -> {}
            else -> return false
        }

        val symbol = getBinarySymbol(type, methodName)
            ?: return false

        // some unsigned operations need special helpers: unsigned div, unsigned rem
        if ((methodName == "div" || methodName == "rem") && type.isUnsigned()) {
            TODO("Special call: $methodName on $type")
        }

        when (type) {
            Types.Short, Types.UShort -> builder.append("(short) (")
            Types.Byte, Types.UByte -> builder.append("(byte) (")
            else -> {}
        }

        appendFirstParameter(graph, type, expr)
        builder.append(symbol)
        appendFieldName(graph, expr.valueParameters[0])

        when (type) {
            Types.Short, Types.UShort,
            Types.Byte, Types.UByte -> builder.append(")")
            else -> {}
        }

        return true
    }

    open fun appendCopy(graph: SimpleGraph, valueType: Type) {
        builder.append(".copy()")
    }

    open fun appendCallImpl(graph: SimpleGraph, expr: SimpleMethodCall) {
        val needsCastForFirstValue = nativeTypes[expr.thisInstance.type]
        if (needsCastForFirstValue != null) {
            appendNativeCall(needsCastForFirstValue, expr, graph)
        } else {
            appendNonNativeCall(expr, graph)
        }
    }

    open fun appendNonNativeCall(expr: SimpleMethodCall, graph: SimpleGraph) {
        appendFieldName(graph, expr.thisInstance, ".")
        val methodName = getMethodName(expr.specialization)
        builder.append(methodName)
        appendValueParams(graph, expr.valueParameters)
    }

    open fun appendNativeCall(
        needsCastForFirstValue: BoxedType, expr: SimpleMethodCall,
        graph: SimpleGraph,
    ) {
        var methodName = getMethodName(expr.specialization)
        when (expr.methodName) {
            "compareTo" -> {
                builder.append(needsCastForFirstValue.boxed).append('.')
                if (methodName == "compareTo") methodName = "compare"
                builder.append(methodName)

                builder.append('(')
                appendFieldName(graph, expr.thisInstance)
                if (expr.valueParameters.isNotEmpty()) {
                    appendValueParams(graph, expr.valueParameters, false)
                }
                builder.append(')')
            }
            else -> {
                // todo we could make them static, too...
                val thisType = (expr.thisInstance.type as ClassType).clazz
                builder.append("new ")
                appendClassType(thisType, Specialization.fromSimple(thisType))
                builder.append('(')
                appendFieldName(graph, expr.thisInstance)
                builder.append(").").append(methodName)
                appendValueParams(graph, expr.valueParameters)
            }
        }
    }

    fun outsideClassLike(scope: Scope): Scope? {
        var scope = scope
        while (true) {
            if (scope.isClassLike()) return scope
            scope = scope.parentIfSameFile ?: return null
        }
    }

    open fun appendObjectInstance(field: Field, exprScope: Scope, forFieldAccess: String) {
        if (field.ownerScope == outsideClassLike(exprScope)) {
            // if there is nothing dangerous in-between, we could use this.
            builder.append("this")
        } else {
            appendGetObjectInstance(field.ownerScope, exprScope)
        }
        builder.append(forFieldAccess)
    }

    open fun appendSelfForFieldAccess(graph: SimpleGraph, self: SimpleField, field: Field, exprScope: Scope) {
        if (self.type is ClassType && self.type.clazz.isObjectLike()) {
            appendObjectInstance(field, exprScope, ".")
        } else {
            val fieldSelfType = field.selfType
            val needsCast = self.type != fieldSelfType
            if (needsCast && fieldSelfType != null) {
                builder.append("((")
                appendType(fieldSelfType, exprScope, true)
                builder.append(')')
                appendFieldName(graph, self, ").")
            } else {
                appendFieldName(graph, self, ".")
            }
        }
    }

    open fun appendType(
        type: Type, scope: Scope, needsBoxedType: Boolean,
        withSuffix: Boolean
    ) {
        check(!withSuffix) { "Suffix needs to be implemented for ${javaClass.simpleName}" }

        val type = resolveType(type)

        val protected = protectedTypes[type]
        // println("appending $type -> $protected, $needsBoxedType")
        if (protected != null) {
            builder.append(if (needsBoxedType) protected.boxed else protected.native)
            return
        }

        appendTypeImpl(type, scope, needsBoxedType)
    }

    open fun appendType(type: Type, scope: Scope, needsBoxedType: Boolean) {
        appendType(type, scope, needsBoxedType, withSuffix = false)
    }

    open fun appendTypeImpl(type: Type, scope: Scope, needsBoxedType: Boolean) {
        when (type) {
            NullType -> {
                builder.append("Object ")
                comment { builder.append("null") }
            }
            Types.Nothing -> {
                builder.append("Object ")
                comment { builder.append("Nothing") }
            }
            is ClassType -> appendClassType(type, scope, needsBoxedType)
            is UnionType if type.types.size == 2 && NullType in type.types -> {
                // builder.append("@org.jetbrains.annotations.Nullable ")
                appendType(
                    type.types.first { it != NullType }, scope,
                    true /* native types cannot be null */
                )
                comment { builder.append("or null") }
            }
            is SelfType if (type.scope == scope) -> builder.append(scope.name)
            is ThisType -> appendType(type.type, scope, needsBoxedType)
            UnknownType -> builder.append('?')
            is LambdaType -> {
                val selfType = type.selfType
                builder.append("zauber.Function")
                    .append(type.parameters.size + if (selfType != null) 1 else 0)
                    .append('<')
                if (selfType != null) {
                    appendType(selfType, scope, true)
                    builder.append(", ")
                }
                for (param in type.parameters) {
                    appendType(param.type, scope, true)
                    builder.append(", ")
                }
                appendType(type.returnType, scope, true)
                builder.append('>')
            }
            is GenericType -> {
                val lookup = specialization[type]
                if (lookup != null) appendType(lookup, scope, needsBoxedType)
                else {
                    comment { builder.append(type.scope.pathStr) }
                    builder.append(type.name)
                }
            }
            is TypeOfField -> {
                val valueType = type.resolve()
                appendType(valueType, scope, needsBoxedType)
            }
            else -> {
                appendUnknownType(type, scope)
            }
        }
    }

    open fun appendUnknownType(type: Type, scope: Scope) {
        check(type != Types.NullableAny)
        appendType(Types.NullableAny, scope, true)
        comment {
            builder.append(type)
                .append(" (")
                .append(type.javaClass.simpleName)
                .append(')')
        }
    }

    fun appendClassType(type: ClassType, scope: Scope, needsBoxedType: Boolean) {

        if (type.clazz.scopeType == ScopeType.TYPE_ALIAS) {
            val newType0 = type.clazz.selfAsTypeAlias!!
            val newType = type.typeParameters.resolveGenerics(
                null, /* used for 'This'/'Self' */ newType0
            )
            appendType(newType, scope, needsBoxedType)
            return
        }

        val specialization = Specialization(type)
        appendClassType(type.clazz, specialization)
    }

    open fun appendClassType(type: Scope, specialization: Specialization) {

        check(!type.isTypeAlias()) {
            "Resolved type $type cannot be a type-alias"
        }

        val className = getClassName(type, specialization)
        val path0 = (if (type.isPackage()) type else type.parent!!).path + className
        appendClassName(path0, type)
    }

    open fun appendClassName(path: List<String>, scope: Scope) {
        val name = path.last()
        val existingImport = imports.getOrPut(name) { Import2(path, scope) }
        if (existingImport.path == path) {
            // good :)
            builder.append(name)
        } else {
            // duplicate path -> full path needed
            appendPathLc(path, true)
        }
    }

    open fun isProtectedFieldName(fieldName: String): Boolean {
        // todo this may result in collisions :/
        return fieldName in JavaTokenizer.KEYWORDS
    }

    fun ensureFieldName(field: Field) {
        // todo avoid duplicates
        if (isProtectedFieldName(field.newName)) {
            field.newName += "_"
        }
    }

    fun ensureFieldName(field: LocalField) {
        // todo avoid duplicates
        if (isProtectedFieldName(field.newName)) {
            field.newName += "_"
        }
    }

    fun ensureFieldName(param: Parameter) {
        // todo avoid duplicates
        if (isProtectedFieldName(param.newName)) {
            param.newName += "_"
            val field = param.getOrCreateField(null, Flags.NONE)
            field.newName = param.newName
            // todo that must be synced to the LocalFields, too
        }
    }

    fun appendFieldName(field: Field) {
        if (!field.ownerScope.isClassLike()) {
            // append("__").append(field.ownerScope.depth).append('_')
        }
        ensureFieldName(field)
        builder.append(field.newName)
    }

    fun appendFieldName(field: Parameter) {
        // append("__").append(field.scope.depth).append('_')
        ensureFieldName(field)
        builder.append(field.newName)
    }

    fun appendFieldName(field: LocalField) {
        // append("__").append(field.scope.depth).append('_')
        ensureFieldName(field)
        builder.append(field.newName)
    }

    open fun appendPackageDeclaration(packagePath: List<String>, file: File) {
        builder.append("package ")
        appendPathLc(packagePath, false)
        builder.append(";\n\n")
    }

    open fun beginPackageDeclaration(
        packagePath: List<String>, file: File, imports: Map<String, Import2>,
        nativeImports: Set<String>
    ) {
        appendPackageDeclaration(packagePath, file)
        appendImports(packagePath, imports)
    }

    open fun endPackageDeclaration(packagePath: List<String>, file: File) {
        // nothing to do in Java
    }

    open fun appendImports(packagePath: List<String>, imports: Map<String, Import2>) {
        val importList = imports.values.sortedWith { a, b ->
            ImportSorter.compare(a.path, b.path)
        }
        val position = builder.length
        for (import in importList) {
            appendImport(packagePath, import.path, import.scope)
        }
        if (builder.length > position) nextLine()
    }

    fun appendPath(path: List<String>, separator: String = ".") {
        if (path.isEmpty()) builder.append("ROOT")
        for (i in path.indices) {
            if (i > 0) builder.append(separator)
            builder.append(path[i])
        }
    }

    fun appendPathLc(path: List<String>, lastUpper: Boolean) {
        if (path.isEmpty()) builder.append("ROOT")
        for (i in path.indices) {
            if (i > 0) builder.append(".")
            val pathI = path[i]
            builder.append(if (i < path.lastIndex || !lastUpper) pathI.lowercase() else pathI)
        }
    }

    open fun appendImport(packagePath: List<String>, import: List<String>, importedScope: Scope?) {
        builder.append("import ")
        appendPathLc(import, true)
        builder.append(";\n")
    }

}