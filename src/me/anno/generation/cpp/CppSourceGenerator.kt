package me.anno.generation.cpp

import me.anno.generation.BoxedType
import me.anno.generation.FileEntry
import me.anno.generation.FileWithImportsWriter
import me.anno.generation.ImportSorter
import me.anno.generation.Specializations.specialization
import me.anno.generation.java.Import2
import me.anno.generation.java.JavaSourceGenerator
import me.anno.support.cpp.tokenizer.CppTokenizer
import me.anno.support.jvm.FirstJVMClassReader.Companion.isPrivate
import me.anno.utils.ResetThreadLocal.Companion.threadLocal
import me.anno.zauber.SpecialFieldNames.OBJECT_FIELD_NAME
import me.anno.zauber.ast.reverse.CodeReconstruction
import me.anno.zauber.ast.reverse.SimpleBranch
import me.anno.zauber.ast.reverse.SimpleLoop
import me.anno.zauber.ast.reverse.SimpleTailCall
import me.anno.zauber.ast.rich.Annotation
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
import me.anno.zauber.ast.rich.parameter.Parameter
import me.anno.zauber.ast.simple.ASTSimplifier
import me.anno.zauber.ast.simple.SimpleBlock
import me.anno.zauber.ast.simple.SimpleBlock.Companion.isValue
import me.anno.zauber.ast.simple.SimpleGraph
import me.anno.zauber.ast.simple.constants.SimpleString
import me.anno.zauber.ast.simple.expression.SimpleAllocateInstance
import me.anno.zauber.ast.simple.expression.SimpleBoxCast
import me.anno.zauber.ast.simple.expression.SimpleInstanceOf
import me.anno.zauber.ast.simple.expression.SimpleMethodCall
import me.anno.zauber.ast.simple.fields.LocalField
import me.anno.zauber.ast.simple.fields.SimpleField
import me.anno.zauber.ast.simple.fields.SimpleInstruction
import me.anno.zauber.scope.Scope
import me.anno.zauber.scope.ScopeInitType
import me.anno.zauber.typeresolution.ResolutionContext
import me.anno.zauber.types.Specialization
import me.anno.zauber.types.Type
import me.anno.zauber.types.Types
import me.anno.zauber.types.impl.ClassType
import me.anno.zauber.types.impl.GenericType
import java.io.File

/**
 * structs are directly supported, inheritance is still supported
 * todo needs custom GC
 * todo mode, where we pack all implementation into one cpp file
 * todo for types only containing simple classes, use atomic<shared_ptr> instead of gc<>/raw-ptr
 * */
open class CppSourceGenerator(val cppVersion: Int = 11) : JavaSourceGenerator() {

    companion object {

        val protectedCppTypes by threadLocal {
            Types.run {
                mapOf(
                    Boolean to BoxedType("Boolean", "bool"),

                    Byte to BoxedType("Byte", "int8_t"),
                    Short to BoxedType("Short", "int16_t"),
                    Int to BoxedType("Int", "int32_t"),
                    Long to BoxedType("Long", "int64_t"),

                    UByte to BoxedType("Byte", "uint8_t"),
                    UShort to BoxedType("Short", "uint16_t"),
                    UInt to BoxedType("Int", "uint32_t"),
                    ULong to BoxedType("Long", "uint64_t"),

                    // todo having uint16_t twice causes issues if we have multiple functions with the same name...
                    //  char is a dirty-type anyway, can we get rid of it? Maybe legacy Char = UShort.
                    Char to BoxedType("Char", "uint16_t"),

                    // todo having float twice causes issues if we have multiple functions with the same name...
                    Half to BoxedType("Half", "float"),
                    Float to BoxedType("Float", "float"),
                    Double to BoxedType("Double", "double"),
                )
            }
        }

        val nativeCppTypes by threadLocal { protectedCppTypes.filter { (_, it) -> it.boxed != it.native } }
        val nativeCppNumbers by threadLocal { nativeCppTypes - Types.Boolean }

    }

    override val protectedTypes: Map<ClassType, BoxedType> get() = protectedCppTypes
    override val nativeTypes: Map<ClassType, BoxedType> get() = nativeCppTypes
    override val nativeNumbers: Map<ClassType, BoxedType> get() = nativeCppNumbers
    override val keywords: Set<String> get() = CppTokenizer.cppKeywords

    val cppFiles = HashSet<File>()

    val CIncludeType = Types.getType("CInclude")

    val strBuilder = StringBuilder()
    val strings = HashMap<String, String>()
    var strIndex = 0

    fun isCIncludeMethod(method: MethodLike): Boolean {
        return method.memberScope[ScopeInitType.AFTER_RESOLVE_TYPES]
            .annotations.any { it.type == CIncludeType }
    }

    fun getCIncludeMethodName(method0: Specialization, cInclude: Annotation): String {
        include(cInclude)
        return method0.method.name
    }

    fun getCIncludeAnnotations(method0: Specialization): Annotation? {
        // check CInclude annotations
        return method0.method.memberScope[ScopeInitType.AFTER_RESOLVE_TYPES]
            .annotations.firstOrNull { it.type == CIncludeType }
    }

    override fun getMethodName(method0: Specialization): String {

        val cInclude = getCIncludeAnnotations(method0)
        if (cInclude != null) return getCIncludeMethodName(method0, cInclude)

        return super.getMethodName(method0)
    }

    open fun needsHeaders() = true

    override fun getExtension(headerOnly: Boolean): String {
        return if (headerOnly) "hpp" else "cpp"
    }

    override fun defineNullableAnnotation(dst: File, writer: FileWithImportsWriter) {
        // nothing to do here
    }

    override fun generateClassForScope(
        scope: Scope, dst: File, writer: FileWithImportsWriter, specialization: Specialization,
        methods: Collection<Specialization>, fields: Collection<Specialization>
    ) {
        if (needsHeaders()) {
            val (name, packageScope) = getNameAndScope(scope, specialization)
            appendClass(name, scope, specialization, methods, fields, true)
            writeInto(packageScope, name, dst, writer, true)

            appendClass(name, scope, specialization, methods, fields, false)
            cppFiles += writeInto(packageScope, name, dst, writer, false)
        } else {
            // just generate one class
            super.generateClassForScope(scope, dst, writer, specialization, methods, fields)
        }
    }

    override fun appendClass(
        className: String, classScope: Scope, specialization: Specialization,
        methods: Collection<Specialization>, fields: Collection<Specialization>,
        headerOnly: Boolean
    ) {
        declareImport(classScope, specialization)
        specialization.use {

            appendSpecializationInfoComment()

            if (headerOnly) {
                appendClassFlags(classScope)
                appendClassPrefix(classScope, className)

                // we specialize only the generics we need
                /*if (specialization.containsGenerics()) {
                    appendTypeParams(classScope)
                }*/

                appendSuperTypes(classScope)
                appendClassBody(classScope, className, methods, fields, true)
                removeTrailingWhitespace()
                builder.append(";")
                nextLine()

            } else {
                appendConstructors(classScope, className, methods, false)
                appendMethods(classScope, className, methods, false)
            }
        }
    }

    override fun appendMethods(
        classScope: Scope, className: String,
        methods: Collection<Specialization>,
        headerOnly: Boolean
    ) {
        if (headerOnly) markClassAsPolymorphic(className)
        val pos0 = builder.length

        super.appendMethods(classScope, className, methods, headerOnly)
        appendStringDeclarations(pos0)
    }

    open fun markClassAsPolymorphic(className: String) {
        // needed in C++ to be marked as polymorphic (for dynamic_cast to work)
        builder.append("virtual ~").append(className).append("() = default;")
        nextLine()
    }

    override fun appendSuperTypes(scope: Scope) {
        var hasSuper = false
        val superCall0 = scope.superCalls.firstOrNull()
        if (superCall0 != null && superCall0.isClassCall) {
            val type = superCall0.type
            if (!(scope.isInterface() && type == Types.Any)) {
                builder.append(" : ")
                appendType(type, scope, true)
                hasSuper = true
            }
        } else if (scope != Types.Any.clazz) {
            val type = Types.Any
            if (!scope.isInterface()) {
                builder.append(" : ")
                appendType(type, scope, true)
                hasSuper = true
            }
        }

        var implementsKeyword = if (hasSuper) ", " else " : "
        for (superCall in scope.superCalls) {
            if (superCall.isInterfaceCall) {
                val type = superCall.type
                builder.append(implementsKeyword)
                appendType(type, scope, true)
                implementsKeyword = ", "
            }
        }
    }

    override fun appendConstructors(
        classScope: Scope, className: String,
        methods: Collection<Specialization>, headerOnly: Boolean
    ) {
        super.appendConstructors(classScope, className, methods, headerOnly)

        // if this is a value class & we have no empty constructor, append one
        if (headerOnly && needsEmptyConstructor(classScope, methods)) {
            appendEmptyConstructor(className)
        }
    }

    open fun needsEmptyConstructor(classScope: Scope, methods: Collection<Specialization>): Boolean {
        return classScope.typeWithArgs.isValue() &&
                methods.none { spec ->
                    val method = spec.method
                    method is Constructor && method.valueParameters.isEmpty()
                }
    }

    fun appendEmptyConstructor(className: String) {
        builder.append("public: ")
        builder.append(className).append("(){}")
        nextLine()
    }

    override fun appendField(classScope: Scope, field: Field, allowFinal: Boolean, headerOnly: Boolean) {
        appendFieldFlags(classScope, field, allowFinal)

        var valueType = (field.valueType ?: Types.NullableAny)
        valueType = valueType.resolve(classScope)
        valueType = resolveType(valueType)

        appendType(valueType, classScope, false)
        appendOwnershipSuffix(valueType, false)
        builder.append(' ')
        appendFieldName(field)
        builder.append(
            when {
                valueType in nativeNumbers -> " = 0;"
                valueType.isValue() -> ";"
                else -> " = nullptr;"
            }
        )
        nextLine()
    }

    override fun appendStaticInstance(classScope: Scope, className: String) {
        // https://stackoverflow.com/a/1008289/4979303
        builder.append(
            """
        static $className* get$OBJECT_FIELD_NAME() {
            static $className $OBJECT_FIELD_NAME;
            return &$OBJECT_FIELD_NAME;
          }
        """.trimIndent()
        )
        nextLine()
        if (cppVersion in 3 until 11) {
            builder.append(
                """
    private:
        $className($className const&); // Don't Implement
          void operator=($className const&); // Don't implement 
      public:
        """.trimIndent()
            )
        } else if (cppVersion >= 11) {
            builder.append(
                """
        $className($className const&) = delete;
          void operator=($className const&) = delete;
            """.trimIndent()
            )
        } else {
            builder.append("public:")
        }
        nextLine()
        nextLine()
    }

    override fun getMainMethodFile(dst: File): File {
        return File(dst, "__main.cpp")
    }

    override fun defineMainMethodCallEntry(
        dst: File, writer: FileWithImportsWriter,
        mainMethod: Method, className: String
    ): FileEntry {
        val needsArgs = mainMethod.valueParameters.isNotEmpty()
        cppFiles += getMainMethodFile(dst)
        return FileEntry(emptyList(), this)
            .apply {
                // todo convert argc/argv to String-array, if needed
                content.append(
                    """
                int main(int argc, char** argv) {
                    int err = stdlibMain();
                    if (err) return err;
                    $className->${mainMethod.name}(${if (needsArgs) "argv" else ""});
                    return 0;
                }
            """.trimIndent()
                )
            }
    }

    override fun appendPackageDeclaration(packagePath: List<String>, file: File) {
        if (packagePath.isEmpty()) return

        if (cppVersion >= 17) {
            builder.append("namespace ")
            for (i in packagePath.indices) {
                if (i > 0) builder.append('_')
                builder.append(packagePath[i])
            }
            builder.append("{")
            nextLine()
        } else {
            for (part in packagePath) {
                builder.append("namespace ").append(part).append(" {")
                nextLine()
            }
        }
        indentation++
        nextLine()
    }

    override fun endPackageDeclaration(packagePath: List<String>, file: File) {
        if (packagePath.isEmpty()) return
        val packageDepth = if (cppVersion >= 17) 1 else packagePath.size
        indentation--
        nextLine()
        repeat(packageDepth) {
            builder.append("}")
        }
        nextLine()
    }

    override fun beginPackageDeclaration(
        packagePath: List<String>, file: File,
        imports: Map<String, Import2>,
        nativeImports: Set<String>
    ) {
        if (file.name.endsWith(".hpp")) builder.append("#pragma once\n")
        appendNativeImports(nativeImports)
        appendStdlibImport(packagePath)

        appendImports(packagePath, imports)
        writeUsingNamespace(imports)

        nextLine()
        appendPackageDeclaration(packagePath, file)
    }

    fun appendNativeImports(nativeImports: Set<String>) {
        if (nativeImports.isNotEmpty()) {
            for (import in nativeImports) {
                builder.append(import)
                nextLine()
            }
            nextLine()
        }
    }

    open fun appendStdlibImport(packagePath: List<String>) {
        // only really needed, if we have allocations...
        builder.append("#include \"${"../".repeat(packagePath.size)}CppStandardLib.hpp\"\n")
        nextLine()
    }

    override fun appendArrayContentField(classScope: Scope, headerOnly: Boolean) {
        if (!headerOnly) return

        val elementType = specialization.typeParameters[0]
        appendVisibility(isPrivate = false)
        appendType(elementType, classScope, false)
        appendOwnershipSuffix(elementType, false)
        builder.append("* content;")
        nextLine()
    }

    fun writeUsingNamespace(imports: Map<String, Import2>) {
        if (imports.none { it.value.path.size > 1 }) return

        val usingNamespace = imports.values
            .filter { it.path.size > 1 }
            .map { it.path.dropLast(1) }
            .distinct()
            .sortedWith(ImportSorter)

        for (import in usingNamespace) {
            builder.append("using namespace ")
            for (i in import.indices) {
                if (i > 0) builder.append("::")
                builder.append(import[i])
            }
            builder.append(";")
            nextLine()
        }
    }

    override fun appendImport(packagePath: List<String>, import: List<String>, importedScope: Scope?) {
        builder.append("#include \"")
        builder.appendRelativePath(packagePath, import)
        builder.append(".hpp\"")
        nextLine()
    }

    override fun appendClassFlags(scope: Scope) {
        // no flags yet
    }

    override fun appendConstructorBody(
        classScope: Scope, className: String,
        constructor: Constructor, headerOnly: Boolean
    ) {
        val body = constructor.body
        when {
            headerOnly -> {
                builder.append(";")
                nextLine()
            }
            body != null -> {
                val context = ResolutionContext(
                    constructor.memberScope, constructor.selfType,
                    true, null, emptyMap()
                )
                writeBlock {
                    when {
                        classScope == Types.Array.clazz -> {
                            appendArrayContentInitialization(constructor)
                        }
                        classScope.typeWithArgs2 in nativeNumbers -> {
                            builder.append("this->content = content;"); nextLine()
                        }
                        else -> {
                            val methodSpec = specialization
                            check(methodSpec.method === constructor)
                            appendCode(context, methodSpec, body, true)
                        }
                    }
                }
            }
        }
    }

    override fun appendArrayContentInitialization(constructor: Constructor) {
        val elementType = specialization.typeParameters[0]
        builder.append("this->content = size > 0 ? (")
        appendType(elementType, constructor.scope, false)
        appendOwnershipSuffix(elementType, false)
        builder.append("*) calloc(size, sizeof(")
        appendType(elementType, constructor.scope, false)
        appendOwnershipSuffix(elementType, false)
        builder.append(")) : NULL;")
        nextLine()
    }

    open fun appendVisibility(isPrivate: Boolean) {
        dedent()
        val visibility = if (isPrivate) "private:" else "public:"
        builder.append(visibility)
        nextLine()
    }

    override fun appendConstructorFlags(classScope: Scope, constructor: Constructor, headerOnly: Boolean) {
        if (headerOnly) {
            val isPrivate = classScope.isObjectLike() || constructor.flags.isPrivate()
            appendVisibility(isPrivate)
        }
        // no flags yet
    }

    override fun appendConstructorHeader(
        classScope: Scope, className: String,
        constructor: Constructor, headerOnly: Boolean
    ) {
        if (headerOnly) super.appendConstructorHeader(classScope, className, constructor, true)
        else {
            appendConstructorFlags(classScope, constructor, false)
            builder.append(className).append("::").append(className)
            appendValueParameterDeclaration(constructor, classScope)

            appendSuperCall0(classScope, className, constructor)
        }
    }

    override fun appendValueParameterDeclaration(method: MethodLike, scope: Scope) {
        builder.append('(')
        val selfTypeIfNecessary = method.selfTypeIfNecessary
        if (selfTypeIfNecessary != null) {
            appendType(selfTypeIfNecessary, scope, false)
            builder.append(" __self")
        }
        for (param in method.valueParameters) {
            if (!builder.endsWith("(")) builder.append(", ")
            appendType(param.type, scope, false)
            appendOwnershipSuffix(param.type, false)
            builder.append(' ')
            appendFieldName(param)
        }
        builder.append(')')
    }

    override fun appendSuperCall0Name(
        classScope: Scope, className: String, constructor: Constructor,
        superType: Type, superCall: InnerSuperCall
    ) {
        builder.append(" : ")
        if (superCall.target == InnerSuperCallTarget.THIS) {
            builder.append(className) // is this supported? yes
        } else {
            appendType(superType, constructor.scope, false)
        }
    }

    override fun getBinarySymbol(type: Type, methodName: String): String? {
        return when (methodName) {
            "shr", "ushr" -> ">>"
            "rem" -> if (type.isFloat()) "#include <math.h>\nfmod" else "%"
            else -> super.getBinarySymbol(type, methodName)
        }
    }

    override fun appendMethodFlags(classScope: Scope, method0: Specialization, headerOnly: Boolean) {
        if (headerOnly) {
            val method = method0.method
            appendVisibility(method.flags.isPrivate())
            if (method.flags.hasFlag(Flags.OVERRIDE)) {
                // override is a suffix...
            } else if (method.flags.hasFlag(Flags.OPEN) || method.ownerScope.isInterface()) {
                builder.append("virtual ")
            }
        }
    }

    override fun appendArrayGetter(method0: Specialization) {
        writeBlock {
            builder.append("return this->content[index];")
            nextLine()
        }
    }

    override fun appendArraySetter(method0: Specialization) {
        writeBlock {
            builder.append("this->content[index] = value;")
            nextLine()

            if (hasReturn(method0.method)) {
                builder.append("return ")
                appendGetObjectInstance(Types.Unit.clazz, method0.method.memberScope)
                builder.append(';')
                nextLine()
            }
        }
    }

    override fun appendFieldFlags(classScope: Scope, field: Field, allowFinal: Boolean) {
        // no flags yet
    }

    override fun getClassType(scope: Scope): String {
        return "struct"
    }

    override fun appendMethod(
        classScope: Scope, className: String,
        method0: Specialization, headerOnly: Boolean
    ) {
        val method = method0.method as Method
        if (!headerOnly &&
            method0.method.isExternal() &&
            getNativeImplementation(method) == null &&
            !isArrayGetter(method0) &&
            !isArraySetter(method0)
        ) {
            // missing implementation
            comment {
                appendNativeImports(method)
                super.appendMethod(classScope, className, method0, headerOnly)
            }
        } else {
            if (!headerOnly) appendNativeImports(method)
            super.appendMethod(classScope, className, method0, headerOnly)
        }
    }

    override fun getNativeImplementation(method: MethodLike): String? {
        if (method.name == "compareTo" && method.ownerScope.typeWithArgs2 in nativeNumbers.keys) {
            return "return (content > other ? 1 : 0) - (content < other ? 1 : 0);"
        }
        return super.getNativeImplementation(method)
    }

    override fun appendTypeParameterDeclaration(valueParameters: List<Parameter>, scope: Scope) {
        // skipped
    }

    override fun appendMethodHeader(
        classScope: Scope, className: String,
        method0: Specialization, headerOnly: Boolean
    ) {
        appendMethodFlags(classScope, method0, headerOnly)

        val method = method0.method as Method
        appendTypeParameterDeclaration(method.typeParameters, classScope)

        if (hasReturn(method)) {
            val returnType = resolveType(method.resolveReturnType(method0))
            appendType(returnType, classScope, false)
            appendOwnershipSuffix(returnType, false)
        } else {
            builder.append("void")
        }

        builder.append(' ')
        if (!headerOnly) {
            builder.append(className).append("::")
        }
        builder.append(getMethodName(method0))

        assignSelfType(classScope, method)
        appendValueParameterDeclaration(method, classScope)
        if (false && headerOnly && method.flags.hasFlag(Flags.OVERRIDE)) {
            // this flag is optional, and we must not declare it, if the super method wasn't defined
            // todo somehow check, whether the super method is available
            builder.append(" override")
        }
    }

    fun appendNativeImports(method: Method) {
        val nativeImpl = getNativeImplementation(method) ?: return
        val imports = nativeImpl.lines()
            .filter { it.startsWith("#include") }
        if (imports.isNotEmpty()) {
            nativeImports.addAll(imports)
        }
    }

    override fun appendMethodBody(methodSpec: Specialization, headerOnly: Boolean) {
        if (headerOnly) {
            builder.append(";")
            nextLine()
        } else {
            super.appendMethodBody(methodSpec, false)
        }
    }

    override fun appendNativeImplementation(nativeImpl: String, method: MethodLike) {
        val implWithoutImports = nativeImpl.lines()
            .filter { !it.startsWith("#include") }
            .joinToString("\n")
        super.appendNativeImplementation(implWithoutImports, method)
    }

    override fun appendGetObjectInstance(objectScope: Scope, exprScope: Scope) {
        if (objectScope == outsideClassLike(exprScope)) {
            builder.append("this")
        } else {
            appendType(objectScope.typeWithArgs, objectScope, false)
            builder.append("::get").append(OBJECT_FIELD_NAME).append("()")
        }
    }

    override fun appendDeclare(graph: SimpleGraph, dst: SimpleField, scope: Scope, withEquals: Boolean) {
        // without final
        appendType(dst.type, scope, false)
        appendOwnershipSuffix(dst.type, false)
        builder.append(' ')
        appendFieldName(graph, dst)
        if (withEquals) builder.append(" = ")
    }

    override fun appendObjectInstance(field: Field, exprScope: Scope, forFieldAccess: String) {
        if (!checkHasIncludeAnnotations(field)) {
            appendGetObjectInstance(field.ownerScope, exprScope)
            builder.append(if (forFieldAccess == ".") "->" else "")
        } // else is included -> all fine as long as it's not shadowed
    }

    override fun isStoredField(field: Field): Boolean {
        return super.isStoredField(field) &&
                !checkHasIncludeAnnotations(field)
    }

    private fun include(annotation: Annotation) {
        val path = annotation.params1[0].castToString()
        nativeImports.add("#include $path")
    }

    private fun checkHasIncludeAnnotations(field: Field): Boolean {
        var hasAnnotations = false
        for (annotation in field.annotations) {
            if (annotation.type == CIncludeType) {
                include(annotation)
                hasAnnotations = true
            }
        }
        return hasAnnotations
    }

    // add "virtual" fun getClassId() (?)
    // check if is native type, value type -> true instance
    // check if is object type -> reference
    // check if is nullable -> pointer

    fun appendOwnershipSuffix(type: Type, needsBoxedType: Boolean) {
        val type = resolveType(type)
        val symbol = if (!needsBoxedType && type.isValue()) "" else "*"
        builder.append(symbol)
    }

    override fun filterImports(name: String, packageScope: Scope, headerOnly: Boolean) {
        if (headerOnly) {
            // remove self-include
            imports.remove(name)
        }
    }

    override fun appendNumber(type: Type, expr: NumberExpression) {
        when {
            type.isFloat() -> appendFloat(expr.asFloat, "")
            else -> super.appendNumber(type, expr)
        }
    }

    override fun appendFieldName(graph: SimpleGraph, field: SimpleField, forFieldAccess: String) {
        val needsArrow = if (field.isOwnerThis(graph)) {
            builder.append(if (meansContent(field, forFieldAccess)) "this->content" else "this")
            true
        } else if (field.isObjectLike()) {
            val objectType = (field.type as ClassType).clazz
            appendGetObjectInstance(objectType, graph.method.scope)
            true
        } else {
            val field = field.dst
            when (val expr = field.constantRef) {
                is NumberExpression -> appendNumber(field.type, expr)
                is StringExpression -> appendStringImpl(expr.value, expr.scope)
                is SpecialValueExpression -> {
                    when (expr.type) {
                        SpecialValue.TRUE -> builder.append("true")
                        SpecialValue.FALSE -> builder.append("false")
                        SpecialValue.NULL -> builder.append("nullptr")
                    }
                }
                null -> {
                    check(field.id >= 0) { "Invalid field $field in $graph" }
                    val localField = field.fromLocalField
                    if (localField != null) {
                        appendFieldName(localField)
                    } else {
                        builder.append("tmp").append(field.id)
                        usedFields.add(field)
                    }
                }
                else -> throw NotImplementedError("Append constant field $expr (${expr.javaClass.simpleName})")
            }
            !(field.type in nativeNumbers || field.type.isValue())
        }
        if (forFieldAccess.isNotEmpty()) {
            val symbol = if (needsArrow) {
                when (forFieldAccess) {
                    "." -> "->"
                    ")." -> ")->"
                    else -> forFieldAccess.replace(".", "->")
                }
            } else forFieldAccess
            builder.append(symbol)
        }
    }

    override fun declareLocalField(graph: SimpleGraph, field: LocalField) {
        val type = field.type
        appendType(type, graph.method.memberScope, false)
        appendOwnershipSuffix(type, false)
        builder.append(' ')
        appendFieldName(field)
        builder.append(" = ")
        appendDefaultValue(type)
        builder.append(";")
        nextLine()
    }

    fun copyInto(dst: StringBuilder, writeImpl: () -> Unit) {
        val l0 = builder.length
        writeImpl()
        val l1 = builder.length
        dst.append(builder, l0, l1)
        builder.setLength(l0)
    }

    open fun declareStaticStringField(name: String, scope: Scope) {
        strBuilder.append("static ") // means only accessible in this file
        copyInto(strBuilder) {
            appendType(Types.String, scope, true)
        }
        strBuilder.append(' ').append(name).append("(nullptr);\n")
    }

    fun appendStringImpl(value: String, scope: Scope) {
        ensureImport(Types.String)

        val tmp = strings.getOrPut(value) {
            val tmp = "__str${strIndex++}"
            declareStaticStringField(tmp, scope)
            tmp
        }

        builder.append("__createString(")
        appendString(value)
        builder.append(", &").append(tmp).append(')')
    }

    override fun appendDefaultValue(valueType: Type) {
        when (valueType) {
            Types.Boolean -> builder.append("false")
            in nativeTypes -> builder.append("0")
            else -> {
                if (valueType.isValue()) builder.append("{}")
                else builder.append("nullptr")
            }
        }
    }

    override fun appendType(type: Type, scope: Scope, needsBoxedType: Boolean) {
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
        val selfType = expr.thisInstance.type
        val position = builder.length
        appendType(selfType, expr.scope, true)
        builder.setLength(position)

        builder.append(needsCastForFirstValue.boxed).append("(")
        appendFieldName(graph, expr.thisInstance)
        builder.append(").")
        builder.append(expr.methodName)
        appendValueParams(graph, expr.valueParameters)
    }

    override fun prepareGraph(graph: SimpleGraph) {
        graph.findBoxingAndUnboxing()
        graph.removeWriteOnlyFields()
        graph.removeObjectFields()
        graph.removeConstantFields()
        graph.giveLocalFieldsUniqueNames(CppTokenizer.cppKeywords)
        graph.removeMergeInfoInstructions()
        graph.renumberFields()

        CodeReconstruction.createCodeFromGraph(graph, true)
        graph.renumberFields() // necessary
    }

    override fun appendCode(
        context: ResolutionContext, method1: Specialization,
        body: Expression, skipSuperCall: Boolean
    ) {
        writeBlock {
            val graph = ASTSimplifier.simplify(method1)
            if (skipSuperCall) graph.removeSuperCalls()
            prepareGraph(graph)

            val pos0 = builder.length
            declareLocalFields(graph)

            val blocks = graph.blocks
            for (i in blocks.indices) {
                val block = graph.blocks[i]
                if (i == 0 || block.inputBlocks.isNotEmpty()) {
                    appendSimpleBlockI(graph, block, true)
                }
            }
            removeTailingReturn()

            appendMissingDeclarations(graph, pos0)
        }
    }

    fun appendStringDeclarations(pos0: Int) {
        if (strBuilder.isEmpty()) return

        val pos1 = builder.length
        builder.append('\n').append(strBuilder)
        swapSections(pos0, pos1)
        strBuilder.clear()
        strings.clear()
        strIndex = 0
    }

    override fun appendSimpleBlock(graph: SimpleGraph, block: SimpleBlock) {
        appendSimpleBlockI(graph, block, true)
    }

    fun appendSimpleBlockI(graph: SimpleGraph, block: SimpleBlock, withBlock: Boolean) {
        if (withBlock) {
            dedent()
            // mark block as jumpable
            if (block.inputBlocks.isNotEmpty()) {
                builder.append("b").append(block.id).append(": ")
            }

            builder.append("{"); nextLine()
        }

        val instructions = block.instructions
        for (i in instructions.indices) {
            val instr = instructions[i]
            appendSimpleInstruction(graph, instr)
        }

        // jump to next blocks
        if (block.nextBranch != null) {
            if (block.isBranch) {
                builder.append("if (")
                appendFieldName(graph, block.branchCondition!!)
                builder.append(") goto b").append(block.ifBranch!!.id)
                builder.append("; else goto b").append(block.elseBranch!!.id).append(';')
            } else {
                builder.append("goto b").append(block.nextBranch!!.id).append(';')
            }
            nextLine()
        }

        if (withBlock) {
            dedent()
            builder.append("}"); nextLine()
        }
    }

    override fun appendInstrImpl(graph: SimpleGraph, expr: SimpleInstruction) {
        when (expr) {
            is SimpleAllocateInstance -> {
                // todo test nullable variables
                // this allocation is a ClassType, so it cannot be null ever
                if (!expr.allocatedType.isValue()) {
                    // call GC-aware alloc instead
                    builder.append("__gcNew<")
                    appendType(expr.allocatedType, expr.scope, true)
                    builder.append(">")
                    appendValueParams(graph, expr.paramsForLater)
                } else {
                    appendType(expr.allocatedType, expr.scope, true)
                    appendValueParams(graph, expr.paramsForLater)
                }
            }
            is SimpleBranch -> {
                builder.append("if (")
                appendFieldName(graph, expr.condition)
                builder.append(')')
                writeBlock {
                    appendSimpleBlockI(graph, expr.ifTrue, false)
                }
                if (expr.ifFalse != null) {
                    removeTrailingWhitespace()
                    builder.append(" else ")
                    writeBlock {
                        appendSimpleBlockI(graph, expr.ifFalse, false)
                    }
                }
            }
            is SimpleLoop -> {
                builder.append("while (true)")
                writeBlock {
                    if (expr.condition != null) {
                        appendSimpleBlockI(graph, expr.conditionBlock!!, false)
                        builder.append("if (")
                        if (!expr.negate) builder.append("!(")
                        appendFieldName(graph, expr.condition)
                        if (!expr.negate) builder.append(')')
                        builder.append(") break;")
                        nextLine()
                        nextLine()
                    }
                    appendSimpleBlockI(graph, expr.body, false)
                }
            }
            is SimpleString -> appendStringImpl(expr.base.value, expr.scope)
            is SimpleTailCall -> {
                builder.append("goto b").append(expr.toBeCalled.id).append(';')
                nextLine()
            }
            is SimpleBoxCast -> {
                // todo use static_cast (num->num) or dynamic_cast (ref->ref) where possible
                val srcType = expr.src.type
                val dstType = expr.dst.type
                val srcNum = srcType in nativeNumbers
                val dstNum = dstType in nativeNumbers

                val srcRef = !srcNum && !srcType.isValue()
                val dstRef = !dstNum && !dstType.isValue()

                when {
                    srcNum && dstNum -> {
                        builder.append("static_cast<")
                        appendType(dstType, expr.scope, false)
                        builder.append(">(")
                        appendFieldName(graph, expr.src)
                        builder.append(')')
                    }
                    srcNum -> {
                        check(dstRef) { "Expected $expr with srcNum to have dstRef" }
                        builder.append("__gcNew<")
                        appendType(srcType, expr.scope, true)
                        builder.append(">(")
                        appendFieldName(graph, expr.src)
                        builder.append(')')
                    }
                    dstNum -> {
                        check(srcRef) { "Expected $expr with dstNum to have srcRef" }
                        builder.append("dynamic_cast<")
                        appendType(dstType, expr.scope, true)
                        appendOwnershipSuffix(expr.dst.type, true)
                        builder.append(">(")
                        appendFieldName(graph, expr.src)
                        builder.append(")->content")
                    }
                    srcRef && dstRef -> {
                        builder.append("dynamic_cast<")
                        appendType(dstType, expr.scope, true)
                        appendOwnershipSuffix(expr.dst.type, true)
                        builder.append(">(")
                        appendFieldName(graph, expr.src)
                        builder.append(')')
                    }
                    else -> {
                        builder.append('(')
                        appendType(dstType, expr.scope, true)
                        appendOwnershipSuffix(expr.dst.type, true)
                        builder.append(") ")
                        appendFieldName(graph, expr.src)
                    }
                }
            }
            is SimpleInstanceOf -> {
                builder.append("dynamic_cast<")
                appendType(expr.type, expr.scope, true)
                builder.append("*>(")
                appendFieldName(graph, expr.value)
                builder.append(") != nullptr")
            }
            else -> super.appendInstrImpl(graph, expr)
        }
    }

    override fun appendBinaryOperator(graph: SimpleGraph, expr: SimpleMethodCall, methodName: String): Boolean {
        val type = expr.thisInstance.type
        when (type) {
            in nativeTypes -> {}
            else -> return false
        }

        if (methodName == "ushr") {
            when (type) {
                Types.Int -> {
                    builder.append("(int32_t) ((uint32_t)")
                    appendFirstParameter(graph, type, expr)
                    builder.append(">>")
                    appendFieldName(graph, expr.valueParameters[0])
                    builder.append(")")
                    return true
                }
                Types.Long -> {
                    builder.append("(int64_t) ((uint64_t)")
                    appendFirstParameter(graph, type, expr)
                    builder.append(">>")
                    appendFieldName(graph, expr.valueParameters[0])
                    builder.append(")")
                    return true
                }
            }
        }

        val symbol = getBinarySymbol(type, methodName)
            ?: return false

        if ('#' in symbol) {
            val lines = symbol.lines()
            check(lines.size == 2)
            nativeImports.add(lines[0])
            builder.append(lines[1]).append('(')
            appendFirstParameter(graph, type, expr)
            builder.append(", ")
            appendFieldName(graph, expr.valueParameters[0])
            builder.append(')')
        } else {
            appendFirstParameter(graph, type, expr)
            builder.append(symbol)
            appendFieldName(graph, expr.valueParameters[0])
        }

        return true
    }

}