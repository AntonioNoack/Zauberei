package me.anno.generation.c

import me.anno.generation.BoxedType
import me.anno.generation.FileEntry
import me.anno.generation.FileWithImportsWriter
import me.anno.generation.InheritanceTable
import me.anno.generation.Specializations.specialization
import me.anno.generation.cpp.CppSourceGenerator
import me.anno.generation.java.Import2
import me.anno.support.cpp.tokenizer.CppTokenizer
import me.anno.utils.FullMap
import me.anno.zauber.ast.reverse.CodeReconstruction
import me.anno.zauber.ast.rich.expression.constants.NumberExpression
import me.anno.zauber.ast.rich.member.Constructor
import me.anno.zauber.ast.rich.member.Field
import me.anno.zauber.ast.rich.member.Method
import me.anno.zauber.ast.rich.member.MethodLike
import me.anno.zauber.ast.simple.SimpleBlock.Companion.isValue
import me.anno.zauber.ast.simple.SimpleGraph
import me.anno.zauber.ast.simple.expression.*
import me.anno.zauber.ast.simple.fields.SimpleField
import me.anno.zauber.ast.simple.fields.SimpleInstruction
import me.anno.zauber.expansion.DependencyData
import me.anno.zauber.scope.Scope
import me.anno.zauber.types.Specialization
import me.anno.zauber.types.Type
import me.anno.zauber.types.Types
import me.anno.zauber.types.impl.ClassType
import java.io.File
import java.util.*

/**
 * this is more custom than C++:
 * todo we need to implement inheritance explicitly
 * - deduplicate methods with same name, but different parameters
 * */
open class CSourceGenerator : CppSourceGenerator() {

    companion object {

        val CLASS_INDEX_NAME = "_class"
        val INHERITANCE_SWITCH_LIMIT = 7

        fun hashMethodParameters(method: Specialization): String {
            check(method.isMethodLike())
            if (method.method.valueParameters.isEmpty()) {
                // we rely on this special behavior -> make it explicit
                return "0"
            }
            return method.use {
                val hash = method.method.valueParameters.joinToString {
                    "${it.name}: ${resolveType(it.type)}"
                }.hashCode()
                hash.toUInt().toString(manglingBasis)
            }
        }
    }

    lateinit var inheritanceTable: InheritanceTable

    var onlyCheapSimplifications = true

    // todo non-boxed types don't need classIndex, but boxed-types do:
    //  - for all non-boxed types, create a boxed type, if it is cast to non-boxed
    //  - find these boxing transitions in dependency analysis

    // todo for normal inheritance, implement two ways, and choose the fastest:
    //  - if-else-chain/switch for few values
    //  - indirect call and table to all indirect calls

    override fun generateCode(dst: File, data: DependencyData, mainMethod: Method) {
        inheritanceTable = InheritanceTable(data)
        super.generateCode(dst, data, mainMethod)
        inheritanceTable.generateFiles(dst)
    }

    private fun getDefineName(packagePath: List<String>, file: File): String {
        return (packagePath + file.name.replace('.', '_'))
            .joinToString("_").uppercase(Locale.ENGLISH)
    }

    override fun beginPackageDeclaration(
        packagePath: List<String>, file: File,
        imports: Map<String, Import2>, nativeImports: Set<String>
    ) {
        if (file.name.endsWith(".h")) {
            val defineName = getDefineName(packagePath, file)
            builder.append("#ifndef ").append(defineName); nextLine()
            builder.append("#define ").append(defineName); nextLine()
            nextLine()
        }
        appendNativeImports(nativeImports)
        appendStdlibImport(packagePath)

        appendImports(packagePath, imports)
        nextLine()
    }

    override fun appendVisibility(isPrivate: Boolean) {
        // no visibility for C
    }

    override fun endPackageDeclaration(packagePath: List<String>, file: File) {
        if (file.name.endsWith(".h")) {
            nextLine()
            val defineName = getDefineName(packagePath, file)
            builder.append("#endif // ").append(defineName); nextLine()
        }
    }

    override fun appendStdlibImport(packagePath: List<String>) {
        // only really needed, if we have allocations...
        builder.append("#include \"${"../".repeat(packagePath.size)}CStandardLib.h\"\n")
        nextLine()
    }

    override fun appendImport(packagePath: List<String>, import: List<String>, importedScope: Scope?) {
        builder.append("#include \"")
        builder.appendRelativePath(packagePath, import)
        builder.append(".h\"")
        nextLine()
    }

    override fun canSkipMethod(classScope: Scope, method: Method): Boolean {
        return super.canSkipMethod(classScope, method) || isCIncludeMethod(method)
    }

    override fun appendMethodFlags(classScope: Scope, method0: Specialization, headerOnly: Boolean) {
        // nothing yet
    }

    override fun markClassAsPolymorphic(className: String) {}

    override fun needsEmptyConstructor(classScope: Scope, methods: Collection<Specialization>): Boolean {
        return false
    }

    var constructorName = "_init_"

    override fun getMethodName(method0: Specialization): String {

        val cInclude = getCIncludeAnnotations(method0)
        if (cInclude != null) return getCIncludeMethodName(method0, cInclude)

        val base = if (method0.method is Constructor) constructorName else super.getMethodName0(method0)
        return "${method0.method.ownerScope.pathStr.replace('.', '_')}_${base}_${hashMethodParameters(method0)}"
    }

    override fun appendCopy(graph: SimpleGraph, valueType: Type) {
        // done automatically
        check(valueType.isValue())
    }

    override fun getExtension(headerOnly: Boolean): String {
        return if (headerOnly) "h" else "c"
    }

    override fun appendClassPrefix(scope: Scope, className: String) {
        builder.append("typedef struct ")
    }

    override fun appendClass(
        className: String, classScope: Scope,
        specialization: Specialization,
        methods: Collection<Specialization>,
        fields: Collection<Specialization>,
        headerOnly: Boolean
    ) {
        declareImport(classScope, specialization)
        specialization.use {

            val packagePrefix = getPackagePrefix(classScope)

            appendSpecializationInfoComment()

            if (headerOnly) {
                declareStruct(classScope, className, packagePrefix, fields)
            }

            appendConstructors(classScope, className, methods, headerOnly)
            appendMethods(classScope, className, methods, headerOnly)

            if (classScope.isObjectLike()) {
                appendObjectGetter(classScope, className, packagePrefix, headerOnly)
            }
        }
    }

    open fun appendObjectGetter(
        classScope: Scope, className: String, packagePrefix: String,
        headerOnly: Boolean
    ) {
        nextLine()
        builder.append(packagePrefix)
        builder.append(className)
        builder.append("* ")
        builder.append(packagePrefix)
        builder.append(className)
        builder.append("__getObject()")

        if (headerOnly) {
            builder.append(';')
            nextLine()
        } else {
            writeBlock {
                builder.append("static ")
                    .append(packagePrefix).append(className)
                    .append(" instance;"); nextLine()
                builder.append("static char isInitialized = 0;"); nextLine()
                builder.append("if (!isInitialized) ")
                writeBlock {
                    builder.append("isInitialized = 1;"); nextLine()
                    val method = classScope.getOrCreatePrimaryConstructorScope().selfAsConstructor!!
                    val methodSpec = Specialization.fromSimple(method.memberScope)
                    builder.append(getMethodName(methodSpec))
                        .append("(&instance);")
                    nextLine()
                }
                builder.append("return &instance;")
                nextLine()
            }
        }
    }

    open fun declareStruct(
        classScope: Scope, className: String,
        packagePrefix: String, fields: Collection<Specialization>,
    ) {
        appendClassFlags(classScope)
        builder.append("typedef struct")
        writeBlock {
            // append fields; todo initialize this in constructor
            if (!classScope.isValueType()) {
                builder.append("uint32_t ").append(CLASS_INDEX_NAME).append(';')
                nextLine()
            }
            declareClassFields(classScope, fields, true, headerOnly = true)
        }
        removeTrailingWhitespace()

        builder.append(' ')
        builder.append(packagePrefix)
        builder.append(className)
        builder.append(";")
        nextLine()
    }

    override fun appendGetObjectInstance(objectScope: Scope, exprScope: Scope) {

        ensureImport(objectScope)

        val className = getClassName(objectScope, Specialization.fromSimple(objectScope))
        val packagePrefix = getPackagePrefix(objectScope)
        builder.append(packagePrefix)
        builder.append(className)
        builder.append("__getObject()")
    }

    fun getPackagePrefix(classScope: Scope): String {
        val scope = if (classScope.isPackage()) classScope else classScope.parent!!
        return scope.path.joinToString("") { name -> name + "_" }
    }

    override fun appendConstructorHeader(
        classScope: Scope, className: String,
        constructor: Constructor, headerOnly: Boolean
    ) {
        if (hasReturn(constructor)) {
            appendType(Types.Unit, classScope, false, withSuffix = true)
        } else {
            builder.append("void")
        }
        builder.append(' ').append(getMethodName(specialization))
        appendValueParameterDeclaration(constructor, classScope)
    }

    override fun declareThis(method: MethodLike, scope: Scope) {
        if (hasThis(method)) {
            appendType(scope.typeWithArgs.specialize(), scope, true, withSuffix = true)
            builder.append(' ').append(thisParamName)
        }
    }

    override fun declareClassField(classScope: Scope, field: Field, allowFinal: Boolean, headerOnly: Boolean) {
        appendFieldFlags(classScope, field, allowFinal)

        var valueType = (field.valueType ?: Types.NullableAny)
        valueType = valueType.resolve(classScope)
        valueType = resolveType(valueType)

        appendType(valueType, classScope, false, withSuffix = true)
        builder.append(' ')
        appendFieldName(field)
        builder.append(";")
        nextLine()
    }

    override fun appendMethodHeader(
        classScope: Scope, className: String,
        method0: Specialization, headerOnly: Boolean
    ) {
        // kinda-hack to not output the this-type
        super.appendMethodHeader(classScope, className, method0, headerOnly = true)
    }

    override fun appendClassName(path: List<String>, scope: Scope) {
        val name = path.joinToString(".") // everything needs to be imported
        imports.getOrPut(name) { Import2(path, scope) }
        builder.append(path.joinToString("_"))
    }

    override fun appendNonNativeCall(expr: SimpleMethodCall, graph: SimpleGraph) {

        if (expr.methods !is FullMap) {
            val options = inheritanceTable.createSwitchList(expr.specialization)
            println("Switch list for ${expr.specialization}: $options")
            if (options.size < 2) {

                // call directly -> fallthrough
                var specialization = expr.specialization
                if (options.isNotEmpty()) {
                    specialization = options.first().second
                }
                return appendNonNativeCall(graph, specialization, expr, true)

            } else if (options.size <= INHERITANCE_SWITCH_LIMIT) {

                val l0 = builder.length
                appendFieldName(graph, expr.thisInstance, "")
                val self = builder.substring(l0); builder.setLength(l0)
                for (i in options.indices) {
                    val (clazz, method) = options[i]

                    // todo it would be nice if we could cache the classIndex in a local field...
                    if (i > 0) builder.append(" : ")
                    if (i < options.lastIndex) {
                        builder.append(self)
                            .append("->").append(CLASS_INDEX_NAME)
                            .append(" == ").append(inheritanceTable.getClassIndex(clazz))
                            .append(" ? ")
                    }

                    appendNonNativeCall(graph, method, expr, true)
                }
                return
            } else {
                if (expr.sample.ownerScope.isInterface()) {
                    TODO("interface method-res")
                } else {
                    TODO("child-class method-res")
                }
            }
        }

        appendNonNativeCall(graph, expr.specialization, expr, false)
    }

    open fun appendNonNativeCall(
        graph: SimpleGraph, method0: Specialization, expr: SimpleMethodCall,
        withCast: Boolean
    ) {

        val ownerType = method0.method.ownerScope
            .typeWithArgs2.specialize(method0)
        ensureImport(ownerType)

        appendAssign(graph, expr)
        val methodName = getMethodName(method0)
        builder.append(methodName).append('(')

        if (hasThis(method0.method)) {
            if (withCast) {
                val ownerType = inheritanceTable.getMethodOwnerType(method0)
                appendOwnerCastPrefix(ownerType, expr.scope)
            }

            if (!isCIncludeMethod(method0.method)) {
                if (expr.thisInstance.type.isValue()) markValueAsReference()
                appendFieldName(graph, expr.thisInstance, "")
            }

            if (withCast) {
                appendOwnerCastSuffix(ownerType, expr.scope)
            }
        }

        if (hasSelf(method0.method)) {
            if (!builder.endsWith('(')) builder.append(", ")
            if (expr.selfInstance!!.type.isValue()) markValueAsReference()
            appendFieldName(graph, expr.selfInstance, "")
        }

        appendValueParams(graph, expr.valueParameters, withBrackets = false)
        builder.append(')')
    }

    open fun appendOwnerCastPrefix(ownerType: Type, scope: Scope) {
        builder.append('(')
        appendType(ownerType, scope, true, withSuffix = true)
        builder.append(") ")
    }

    open fun appendOwnerCastSuffix(ownerType: Type, scope: Scope) {
        // nothing to do here
    }

    open fun markValueAsReference() {
        builder.append('&')
    }

    override fun appendUnaryOperator(graph: SimpleGraph, expr: SimpleMethodCall, methodName: String): Boolean {
        val pos0 = builder.length
        return if (super.appendUnaryOperator(graph, expr, methodName)) {
            val pos1 = builder.length
            appendAssign(graph, expr)
            swapSections(pos0, pos1)
            true
        } else false
    }

    override fun appendBinaryOperator(graph: SimpleGraph, expr: SimpleMethodCall, methodName: String): Boolean {
        val pos0 = builder.length
        return if (super.appendBinaryOperator(graph, expr, methodName)) {
            val pos1 = builder.length
            appendAssign(graph, expr)
            swapSections(pos0, pos1)
            true
        } else false
    }

    override fun appendNativeCall(needsCastForFirstValue: BoxedType, expr: SimpleMethodCall, graph: SimpleGraph) {

        val selfType = expr.thisInstance.type as ClassType
        ensureImport(selfType)

        if (expr.methodName == "hashCode" && selfType == Types.Int) {
            // optimization for Int.hashCode
            appendAssign(graph, expr)
            appendFieldName(graph, expr.thisInstance)
            return
        }

        // create temporary instance on stack
        val tmpName = "__tmp${builder.length}"
        appendType(selfType, expr.scope, true, withSuffix = false)
        builder.append(' ').append(tmpName).append(";"); nextLine()
        // assign content
        builder.append(tmpName).append(".content = ")
        appendFieldName(graph, expr.thisInstance)
        builder.append(';'); nextLine()
        // assign class-index (probably not needed)
        builder.append(tmpName).append('.')
        builder.append(CLASS_INDEX_NAME).append(" = ")
            .append(inheritanceTable.getClassIndex(selfType))
        builder.append(';'); nextLine()

        // call method
        appendAssign(graph, expr)
        builder.append(getMethodName(expr.specialization))
        builder.append("(&").append(tmpName)
        appendValueParams(graph, expr.valueParameters, false)
        builder.append(')')
    }

    override fun getMainMethodFile(dst: File): File {
        return File(dst, "main.c")
    }

    override fun defineMainMethodCallEntry(
        dst: File, writer: FileWithImportsWriter,
        mainMethod: Method, className: String
    ): FileEntry {
        val needsArgs = mainMethod.valueParameters.isNotEmpty()
        cppFiles += getMainMethodFile(dst)
        val methodName = getMethodName(Specialization.fromSimple(mainMethod.memberScope))
        check(!hasThis(mainMethod)) { "Main method must not have this-parameter" }

        return FileEntry(emptyList(), this)
            .apply {
                // todo convert argc/argv to String-array, if needed
                content.append(
                    """
                int main(int argc, char** argv) {
                    stdlibMain();
                    $methodName(${if (needsArgs) "argv" else ""});
                    return 0;
                }
            """.trimIndent()
                )
            }
    }

    override fun appendDefaultValue(valueType: Type) {
        when (valueType) {
            Types.Boolean -> builder.append("false")
            in nativeTypes -> builder.append("0")
            else -> {
                if (valueType.isValue()) builder.append("{}")
                else builder.append("NULL")
            }
        }
    }

    override fun needsAssignment(expr: SimpleAssignment): Boolean {
        return super.needsAssignment(expr) &&
                expr !is SimpleConstructorCall &&
                expr !is SimpleMethodCall
    }

    override fun prepareGraph(graph: SimpleGraph) {
        graph.findBoxingAndUnboxing(true)
        graph.removeWriteOnlyFields()
        graph.removeObjectFields()
        graph.removeConstantFields()
        graph.giveLocalFieldsUniqueNames(CppTokenizer.cKeywords)
        graph.removeMergeInfoInstructions()
        graph.renumberFields()

        CodeReconstruction.createCodeFromGraph(graph, onlyCheapSimplifications)
        graph.renumberFields() // necessary
    }

    override fun canSkipInstruction(expr: SimpleInstruction): Boolean {
        if (expr is SimpleConstructorCall && expr.forAllocation) return false
        return super.canSkipInstruction(expr)
    }

    override fun appendInstrImpl(graph: SimpleGraph, expr: SimpleInstruction) {
        when (expr) {
            is SimpleAllocateInstance -> {
                // this allocation is a ClassType, so it cannot be null ever
                if (!expr.allocatedType.isValue()) {
                    // call GC-aware alloc instead
                    builder.append('(')
                    appendType(expr.allocatedType, expr.scope, true, withSuffix = true)
                    builder.append(") ")

                    builder.append("__gcNew(sizeof(")
                    appendType(expr.allocatedType, expr.scope, true, withSuffix = false)
                    builder.append("), ")
                        .append(inheritanceTable.getClassIndex(expr.specialization))
                        .append(')')
                } else {
                    builder.append("{}")
                }
            }
            is SimpleConstructorCall -> {
                val methodName = getMethodName(expr.specialization)
                builder.append(methodName).append('(')
                if (expr.thisInstance.type.isValue()) builder.append('&')
                appendFieldName(graph, expr.thisInstance, "")
                appendValueParams(graph, expr.valueParameters, withBrackets = false)
                builder.append(");")
            }
            is SimpleBoxCast -> {

                val src = expr.src
                val dst = expr.dst

                val srcType = src.type
                val dstType = dst.type

                val srcNum = srcType in nativeNumbers
                val dstNum = dstType in nativeNumbers

                val srcValue = srcType.isValue()
                val dstValue = dstType.isValue()

                val srcRef = !srcNum && !srcValue
                val dstRef = !dstNum && !dstValue

                when {
                    dstValue && srcValue -> error("Cannot convert $src to $dst implicitly")
                    srcValue -> {

                        builder.append('(')
                        appendType(dst.type, expr.scope, true, withSuffix = true)
                        builder.append(") ")

                        builder.append("__gcNew(sizeof(")
                        appendType(src.type, expr.scope, true, withSuffix = false)
                        val spec = Specialization(src.type as ClassType)
                        builder.append("), ")
                            .append(inheritanceTable.getClassIndex(spec))
                            .append(");"); nextLine()

                        if (src.type in nativeNumbers) {
                            builder.append("((")
                            appendType(src.type, expr.scope, true, withSuffix = true)
                            builder.append(") ")
                            appendFieldName(graph, dst)
                            builder.append(")->content = ")
                            appendFieldName(graph, src)
                        } else {
                            val fields = src.type.clazz.fields
                            for (field in fields) {
                                builder.append("((")
                                appendType(src.type, expr.scope, true, withSuffix = true)
                                builder.append(") ")
                                appendFieldName(graph, dst)
                                builder.append(")->").append(field.newName).append(" = ")
                                appendFieldName(graph, src)
                                builder.append(".").append(field.newName).append(';')
                                nextLine()
                            }
                        }
                    }
                    srcRef && dstNum -> {
                        // unboxing
                        builder.append("((")
                        appendType(dst.type, expr.scope, true, withSuffix = true)
                        builder.append(") ")
                        appendFieldName(graph, src)
                        builder.append(")->content")
                    }
                    dstValue -> error("Unboxing $src to $dst")
                    else -> {
                        builder.append('(')
                        appendType(expr.dst.type, expr.scope, true, withSuffix = true)
                        builder.append(") ")
                        appendFieldName(graph, expr.src)
                    }
                }
            }
            is SimpleInstanceOf -> {
                val type = expr.type
                val call =
                    if (type.clazz.isInterface()) inheritanceTable.instanceOfInterfaceCall
                    else inheritanceTable.instanceOfClassCall
                builder.append(getMethodName(call))
                builder.append("(")
                appendClassIndex(graph, expr.value)
                builder
                    .append(", ")
                    .append(inheritanceTable.getClassIndex(type))
                    .append(")")
            }
            else -> super.appendInstrImpl(graph, expr)
        }
    }

    fun appendClassIndex(graph: SimpleGraph, value: SimpleField) {
        appendFieldName(graph, value)
        builder.append("->").append(CLASS_INDEX_NAME)
    }

    override fun appendNumber(type: Type, expr: NumberExpression) {
        if (type == Types.Char) {
            builder.append("(uint16_t) ").append(expr.asInt.toUShort())
        } else super.appendNumber(type, expr)
    }

    override fun declareStaticStringField(name: String, scope: Scope) {
        strBuilder.append("static ") // means only accessible in this file
        copyInto(strBuilder) {
            appendType(Types.String, scope, true, withSuffix = false)
        }
        strBuilder.append(' ').append(name).append(";\n")
    }
}