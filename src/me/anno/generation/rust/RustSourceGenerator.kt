package me.anno.generation.rust

import me.anno.generation.BoxedType
import me.anno.generation.FileEntry
import me.anno.generation.FileWithImportsWriter
import me.anno.generation.Specializations.specialization
import me.anno.generation.c.CSourceGenerator.Companion.hashMethodParameters
import me.anno.generation.java.Import2
import me.anno.generation.java.JavaSourceGenerator
import me.anno.support.java.tokenizer.JavaTokenizer
import me.anno.utils.Half.Companion.toHalf
import me.anno.utils.ResetThreadLocal.Companion.threadLocal
import me.anno.zauber.SpecialFieldNames.OBJECT_FIELD_NAME
import me.anno.zauber.ast.reverse.CodeReconstruction
import me.anno.zauber.ast.reverse.SimpleBranch
import me.anno.zauber.ast.reverse.SimpleLoop
import me.anno.zauber.ast.reverse.SimpleTailCall
import me.anno.zauber.ast.rich.expression.constants.NumberExpression
import me.anno.zauber.ast.rich.expression.constants.NumberExpression.Companion.isFloat
import me.anno.zauber.ast.rich.member.Constructor
import me.anno.zauber.ast.rich.member.Field
import me.anno.zauber.ast.rich.member.Method
import me.anno.zauber.ast.rich.member.MethodLike
import me.anno.zauber.ast.rich.parameter.Parameter
import me.anno.zauber.ast.simple.SimpleGraph
import me.anno.zauber.ast.simple.expression.SimpleAllocateInstance
import me.anno.zauber.ast.simple.expression.SimpleBoxCast
import me.anno.zauber.ast.simple.expression.SimpleInstanceOf
import me.anno.zauber.ast.simple.expression.SimpleMethodCall
import me.anno.zauber.ast.simple.fields.LocalField
import me.anno.zauber.ast.simple.fields.SimpleField
import me.anno.zauber.ast.simple.fields.SimpleInstruction
import me.anno.zauber.expansion.DependencyData
import me.anno.zauber.scope.Scope
import me.anno.zauber.scope.ScopeInitType
import me.anno.zauber.typeresolution.ParameterList.Companion.emptyParameterList
import me.anno.zauber.typeresolution.ResolutionContext
import me.anno.zauber.types.Specialization
import me.anno.zauber.types.Type
import me.anno.zauber.types.Types
import me.anno.zauber.types.impl.ClassType
import java.io.File

/**
 * this has the same complexity as C, plus we must define ownership somehow...
 *  wrapping everything into GC is kind of lame...
 * */
class RustSourceGenerator : JavaSourceGenerator() {

    companion object {

        val protectedRustTypes by threadLocal {
            Types.run {
                mapOf(
                    Boolean to BoxedType("Boolean", "bool"),

                    Byte to BoxedType("Byte", "i8"),
                    Short to BoxedType("Short", "i16"),
                    Int to BoxedType("Int", "i32"),
                    Long to BoxedType("Long", "i64"),

                    UByte to BoxedType("Byte", "u8"),
                    UShort to BoxedType("Short", "u16"),
                    UInt to BoxedType("Int", "u32"),
                    ULong to BoxedType("Long", "u64"),

                    Char to BoxedType("Char", "char"),
                    Half to BoxedType("Half", "f32"), // f16 is unstable
                    Float to BoxedType("Float", "f32"),
                    Double to BoxedType("Double", "f64"),
                )
            }
        }

        val nativeRustTypes by threadLocal { protectedRustTypes.filter { (_, it) -> it.boxed != it.native } }
        val nativeRustNumbers by threadLocal { nativeRustTypes - Types.Boolean }

        val refCellImport = "std::cell::RefCell".split("::")
        val gcCellImport = "gc::GcCell".split("::")
        val gcImport = "gc::Gc".split("::")
        val gcTraceImport = "gc::Trace".split("::")
        val gcFinalizeImport = "gc::Finalize".split("::")
        val mutexGuardImport = "std::sync::MutexGuard".split("::")
    }

    override val protectedTypes: Map<ClassType, BoxedType> get() = protectedRustTypes
    override val nativeTypes: Map<ClassType, BoxedType> get() = nativeRustTypes
    override val nativeNumbers: Map<ClassType, BoxedType> get() = nativeRustNumbers

    override fun getExtension(headerOnly: Boolean): String = "rs"

    override fun generateCode(dst: File, data: DependencyData, mainMethod: Method) {
        val writer = FileWithImportsWriter(this, dst)
        try {

            generateCodeImpl(dst, data, writer)

            generateModuleHierarchy(dst, writer)
            defineMainMethodCall(dst, writer, mainMethod)

        } finally {
            writer.finish()
        }
    }

    fun generateModuleHierarchy(dst: File, writer: FileWithImportsWriter) {
        val folders = writer.newContent
            // .filter { it.value == null }
            .keys.groupBy { it.parentFile }
            .entries
            .sortedBy { it.key.absolutePath.length }
        for ((folder, children) in folders) {
            if (children.first() == dst) continue

            val entry = FileEntry(emptyList(), this)
            for (child in children) {
                entry.content.append("pub mod ").append(child.nameWithoutExtension).append(";\n")
            }
            writer[File(folder, "mod.rs")] = entry
        }
    }

    override fun getMainMethodFile(dst: File): File {
        return File(dst, "main.rs")
    }

    override fun getMethodName(method0: Specialization): String {
        val base = if (method0.method is Constructor) "__init_" else super.getMethodName0(method0)
        return "${base}_${hashMethodParameters(method0)}"
    }

    override fun defineMainMethodCallEntry(
        dst: File, writer: FileWithImportsWriter,
        mainMethod: Method, className: String
    ): FileEntry {
        val needsArgs = mainMethod.valueParameters.isNotEmpty()
        val spec = Specialization(mainMethod.scope, emptyParameterList())
        val methodName = getMethodName(spec)
        return FileEntry(emptyList(), this)
            .apply {
                // todo convert argc/argv to String-array, if needed
                // include all imports
                val modRS = writer[File(dst, "mod.rs")]
                    ?: error("Missing mod.rs")
                content.append(modRS.content)
                content.append(
                    "\n" + """
                fn main() {
                    $className.$methodName(${if (needsArgs) "std::env::args()" else ""});
                }
            """.trimIndent()
                )
            }
    }

    override fun appendClassFlags(scope: Scope) {
        val ownership = RustOwnership[resolveType(scope.typeWithArgs)]
        if (scope.isValueType() && ownership.isImmutable) {
            builder.append("#[derive(Copy,Clone)]") // simple mem-copy
            nextLine()
        } else if (scope.isObjectLike() ||
            ownership.isDeepMutable ||
            scope.typeWithArgs2 in nativeTypes
        ) {
            builder.append("#[derive(Clone)]") // maybe complicated copy
            nextLine()
        } else {
            imports["Trace"] = Import2(gcTraceImport, null)
            imports["Finalize"] = Import2(gcFinalizeImport, null)
            builder.append("#[derive(Trace, Finalize, Clone)]") // maybe complicated copy
            nextLine()
        }
    }

    override fun appendClass(
        className: String, classScope: Scope, specialization: Specialization,
        methods: Collection<Specialization>, fields: Collection<Specialization>,
        headerOnly: Boolean
    ) {
        declareImport(classScope, specialization)
        specialization.use {
            // todo writing the class must be done in two phases like for C++:
            //  traits and structs
            //  traits describe open class or interfaces
            //  structs define classes

            appendSpecializationInfoComment()

            if (classScope.isObjectLike()) {
                appendStaticInstance(classScope, className)
            }

            appendClassFlags(classScope)
            builder.append("pub struct ").append(className)
            writeBlock {
                val allowFinalFields = !classScope.isValueType() &&
                        methods.any { it.method is Constructor }
                declareClassFields(classScope, fields, allowFinalFields, headerOnly)
            }
            nextLine()

            builder.append("impl ").append(className)
            writeBlock {
                if (classScope.isObjectLike()) {
                    builder.append("pub fn get").append(OBJECT_FIELD_NAME)
                        .append("() -> MutexGuard<'static, ").append(className).append(">")
                    writeBlock {
                        builder.append(OBJECT_FIELD_NAME)
                            .append(".lock().unwrap()")
                        nextLine()
                    }
                }

                appendConstructors(classScope, className, methods, headerOnly)
                appendMethods(classScope, className, methods, headerOnly)
            }
            nextLine()

            if (classScope.isOpen() || classScope == Types.Any.clazz) {
                // todo only if a class actually overrides it?
                builder.append("pub trait ").append(className).append(traitSuffix)
                writeBlock {
                    // todo add all open methods
                }
                nextLine()
            }

            for (call in classScope.superCalls) {
                val superScope = call.type.clazz
                val superClassName = getClassName(superScope, specialization.withScope(superScope)) + traitSuffix
                // we have to import it...
                imports[superClassName] = Import2(superScope.path.dropLast(1) + superClassName, superScope)
                builder.append("impl ")
                    .append(superClassName)
                    .append(" for ").append(className)
                writeBlock {
                    // todo add all methods
                }
                nextLine()
            }
        }
    }

    override fun appendArrayContentField(classScope: Scope, headerOnly: Boolean) {
        val elementType = specialization.typeParameters[0]
        val ownership = getOwnership(elementType)
        builder.append("content: Vec<")
        builder.append(ownership.typePrefix)
        appendType(elementType, classScope, false)
        builder.append(ownership.typeSuffix)
        builder.append(">,")
        nextLine()
    }

    override fun appendArrayGetter(method0: Specialization) {
        writeBlock {
            builder.append("return self.content[index as usize];")
            nextLine()
        }
    }

    override fun appendArraySetter(method0: Specialization) {
        writeBlock {
            builder.append("self.content[index as usize] = value;")
            nextLine()

            if (hasReturn(method0.method)) {
                imports["MutexGuard"] = Import2(mutexGuardImport, null)

                builder.append("return ")
                appendGetObjectInstance(Types.Unit.clazz, method0.method.memberScope)
                builder.append(';')
                nextLine()
            }
        }
    }

    /*override fun getClassName(scope: Scope, specialization: Specialization): String {
        return toUpperCamelCase(super.getClassName(scope, specialization))
    }*/

    var cleanRustNames = true

    fun toUpperCamelCase(s: String): String {
        if (!cleanRustNames) return s

        val b = StringBuilder(s.length)
        for (i in s.indices) {
            val c = s[i]
            if (c == '_') continue
            val capitalize = i == 0 || s[i - 1] == '_'
            b.append(if (capitalize) c.uppercaseChar() else c)
        }
        if (b.isEmpty()) b.append('_')
        return b.toString()
    }

    val traitSuffix = "Trait"

    override fun appendStaticInstance(classScope: Scope, className: String) {
        builder.append("use lazy_static::lazy_static;\n")
        builder.append("use std::sync::{Mutex, MutexGuard};\n\n")
        builder.append("lazy_static! {\n")
        builder.append("  static ref ").append(OBJECT_FIELD_NAME)
            .append(": Mutex<").append(className)
            .append("> = Mutex::new(").append(className)
            .append("::new());\n")
        builder.append("}")
        nextLine()
        nextLine()
    }

    fun appendCreateEmptyInstance(classScope: Scope, className: String) {
        builder.append(className).append(" { ")
        for (field in classScope.fields) {
            if (field.name == OBJECT_FIELD_NAME || !isStoredField(field)) continue

            builder.append(field.newName).append(": ")
            val type = field.valueType!!
            appendDefaultValue(type)
            builder.append(", ")
        }
        if (classScope == Types.Array.clazz) {
            builder.append("content: Vec::with_capacity(size as usize), ")
        }
        if (builder.endsWith(", ")) builder.setLength(builder.length - 2)
        builder.append(" }")
    }

    override fun appendMethod(
        classScope: Scope, className: String,
        method0: Specialization, headerOnly: Boolean
    ) {
        super.appendMethod(classScope, className, method0, headerOnly)
        nextLine()
    }

    override fun appendMethodFlags(classScope: Scope, method0: Specialization, headerOnly: Boolean) {
        // nothing to do yet
        builder.append("pub fn ")
    }

    override fun appendMethodHeader(
        classScope: Scope, className: String,
        method0: Specialization, headerOnly: Boolean
    ) {
        val method = method0.method as Method
        method.scope[ScopeInitType.CODE_GENERATION]

        appendMethodFlags(classScope, method0, headerOnly)

        appendTypeParameterDeclaration(method.typeParameters, classScope)

        builder.append(getMethodName(method0))

        assignSelfType(classScope, method)
        appendValueParameterDeclaration(method, classScope)

        if (hasReturn(method)) {
            builder.append(" -> ")
            val returnType = resolveType(method.resolveReturnType(method0))
            val ownership = getOwnership(returnType)
            val isObject = returnType is ClassType && returnType.clazz.isObjectLike()
            if (isObject) builder.append("MutexGuard<'static, ")
            else builder.append(ownership.typePrefix)
            appendType(returnType, classScope, false)
            if (isObject) builder.append(">")
            else builder.append(ownership.typeSuffix)
        }
    }

    override fun appendMethods(
        classScope: Scope, className: String,
        methods: Collection<Specialization>, headerOnly: Boolean
    ) {
        for (method0 in methods) {
            val method = method0.method
            if (method !is Method) continue
            if (method.scope.parent != classScope) {
                // an inherited method -> skip, because it's already defined in the parent
                continue
            }

            if (method.body == null && getNativeImplementation(method) == null) continue
            appendMethod(classScope, className, method0, headerOnly)
        }
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
                    classScope, constructor.selfType,
                    true, null, emptyMap()
                )
                writeBlock {
                    if (classScope == Types.Array.clazz) {
                        appendArrayContentInitialization(constructor)
                    }

                    val methodSpec = specialization
                    check(methodSpec.method === constructor)
                    appendCode(context, methodSpec, body, true)
                }
            }
        }
    }

    override fun appendFieldFlags(classScope: Scope, field: Field, allowFinal: Boolean) {
        builder.append("pub ")
    }

    override fun declareClassField(classScope: Scope, field: Field, allowFinal: Boolean, headerOnly: Boolean) {
        appendFieldFlags(classScope, field, allowFinal)

        var valueType = (field.valueType ?: Types.NullableAny)
        valueType = valueType.resolve(classScope)
        valueType = resolveType(valueType)

        appendFieldName(field)
        builder.append(": ")
        appendTypeDecl(valueType, classScope)
        builder.append(',')
        nextLine()
    }

    fun appendTypeDecl(valueType: Type, classScope: Scope) {
        val ownership = getOwnership(valueType)
        builder.append(ownership.typePrefix)
        appendType(valueType, classScope, false)
        builder.append(ownership.typeSuffix)
    }

    override fun prepareGraph(graph: SimpleGraph) {
        graph.removeWriteOnlyFields()
        graph.removeObjectFields()
        graph.removeConstantFields()
        graph.giveLocalFieldsUniqueNames(JavaTokenizer.KEYWORDS)
        graph.removeSimpleGetObject()
        graph.removeMergeInfoInstructions()
        graph.renumberFields()
        // graph.markSimpleReadImmediatelyAfterAssignment()
        graph.findBoxingAndUnboxing()

        CodeReconstruction.createCodeFromGraph(graph)
        graph.renumberFields() // necessary
    }

    override fun appendConstructorHeader(
        classScope: Scope, className: String,
        constructor: Constructor, headerOnly: Boolean
    ) {
        constructor.scope[ScopeInitType.CODE_GENERATION]

        builder.append("pub fn new")
        appendValueParameterDeclaration1(constructor.valueParameters, classScope)
        builder.append(" -> Self")
        writeBlock {
            // todo assign all fields...
            builder.append("let mut obj = ")
            appendCreateEmptyInstance(classScope, "Self")
            builder.append(";")
            nextLine()
            builder.append("obj.__init__(")
            for (param in constructor.valueParameters) {
                if (!builder.endsWith("(")) builder.append(", ")
                appendFieldName(param)
            }
            builder.append(");")
            nextLine()
            builder.append("obj")
            nextLine()
        }
        nextLine()

        builder.append("fn __init__")
        appendValueParameterDeclaration(constructor, classScope)
    }

    fun appendValueParameterDeclaration1(
        valueParameters: List<Parameter>, scope: Scope
    ) {
        builder.append("(")
        for (param in valueParameters) {
            if (!builder.endsWith("(")) builder.append(", ")
            appendFieldName(param)
            builder.append(": ")
            appendTypeDecl(resolveType(param.type), scope)
        }
        builder.append(')')
    }

    override fun appendValueParameterDeclaration(method: MethodLike, scope: Scope) {
        builder.append("(&mut self")
        val selfTypeIfNecessary = method.selfTypeIfNecessary
        if (selfTypeIfNecessary != null) {
            builder.append(", ")
            builder.append(" __self: ")
            val ownership = getOwnership(selfTypeIfNecessary)
            builder.append(ownership.typePrefix)
            appendType(selfTypeIfNecessary, scope, false)
            builder.append(ownership.typeSuffix)
        }
        for (param in method.valueParameters) {
            builder.append(", ")
            appendFieldName(param)
            builder.append(": ")
            appendTypeDecl(param.type, scope)
        }
        builder.append(')')
    }

    fun getOwnership(type: Type): RustOwnershipType {
        val ownership = RustOwnership[resolveType(type)]
        if (ownership.isFlatMutable) imports["RefCell"] = Import2(refCellImport, null)
        if (ownership.isDeepMutable) {
            imports["GcCell"] = Import2(gcCellImport, null)
            imports["Gc"] = Import2(gcImport, null)
        }
        return ownership
    }

    override fun beginPackageDeclaration(
        packagePath: List<String>, file: File,
        imports: Map<String, Import2>, nativeImports: Set<String>
    ) {
        appendImports(packagePath, imports)
    }

    override fun endPackageDeclaration(packagePath: List<String>, file: File) {
        // nothing to do
    }

    override fun appendImport(packagePath: List<String>, import: List<String>, importedScope: Scope?) {
        builder.append("use ")
        val fromZauber = importedScope != null
        if (fromZauber) {
            // internal imports start with "crate::"
            builder.append("crate::")
        }
        appendPath(import, "::")
        if (fromZauber) {
            //hack!
            if (builder.endsWith(traitSuffix)) {
                builder.setLength(builder.length - traitSuffix.length)
            }
            // name must appear twice, once for the file, once for the class
            builder.append("::").append(import.last())
        }
        builder.append(";")
        nextLine()
    }

    override fun filterImports(name: String, packageScope: Scope, headerOnly: Boolean) {
        // remove self-include
        imports.remove(name)
    }

    override fun appendDeclare(graph: SimpleGraph, dst: SimpleField, scope: Scope, withEquals: Boolean) {
        builder.append("let mut ")
        appendFieldName(graph, dst)
        builder.append(": ")
        appendTypeDecl(dst.type, scope)
        if (withEquals) builder.append(" = ")
    }

    override fun declareLocalField(graph: SimpleGraph, field: LocalField) {
        val valueType = field.type
        builder.append("let mut ")
        builder.append(field.name)
        builder.append(": ")
        appendTypeDecl(valueType, graph.method.memberScope)
        if (valueType in nativeTypes) {
            builder.append(" = ")
            appendDefaultValue(valueType)
        }
        builder.append(";")
        nextLine()
    }

    override fun appendTailCallCode(graph: SimpleGraph) {
        builder.append("let mut nextBlockId: i32 = 0;"); nextLine()
        builder.append("'blockTable: loop ")
        writeBlock {
            builder.append("match nextBlockId ")
            writeBlock {
                val targets = findTailCallTargets(graph)
                val blocks = graph.blocks
                for (i in blocks.indices) {
                    val block = blocks[i]
                    if (i == 0 || targets[block.id]) {
                        builder.append(block.id).append(" => ")
                        writeBlock {
                            appendBlock(graph, block)
                        }
                        removeTrailingWhitespace()
                        builder.append(',')
                        nextLine()
                    }
                }
                builder.append("_ => {},")
                nextLine()
            }
        }
    }

    override fun appendFieldName(
        graph: SimpleGraph,
        field: SimpleField,
        forFieldAccess: String
    ) {
        if (field.isOwnerThis(graph)) {
            builder.append("self").append(forFieldAccess)
        } else if (field.isObjectLike()) {
            val objectType = (field.type as ClassType).clazz
            appendGetObjectInstance(objectType, graph.method.scope)
            builder.append(forFieldAccess)
        } else {
            val field = field.dst
            when (val expr = field.constantRef) {
                is NumberExpression -> appendNumber(field.type, expr)
                null -> {
                    check(field.id >= 0) { "Invalid field $field in $graph" }
                    val localField = field.fromLocalField
                    if (localField != null) {
                        builder.append(localField.name)
                    } else {
                        builder.append("tmp").append(field.id)
                        usedFields.add(field)
                    }
                }
                else -> throw NotImplementedError("Append constant field $expr")
            }
            when (forFieldAccess) {
                "" -> {}
                "." -> {
                    val ownership = getOwnership(field.type)
                    builder.append(ownership.callPrefix)
                    builder.append(forFieldAccess)
                }
            }
        }
    }

    override fun appendArrayContentInitialization(constructor: Constructor) {
        // todo we need to somehow get a null/zero element...
        val sizeName = constructor.valueParameters[0].newName
        val elementType = specialization.typeParameters[0]
        val ownership = getOwnership(elementType)
        builder.append("for i in 0..$sizeName { self.content.push(")
        builder.append(ownership.allocPrefix)

        // todo depending on type, set a value,
        if (elementType in nativeTypes) builder.append('0')
        else appendDefaultValue(elementType) // todo <- should this contain ownership-data?

        builder.append(ownership.allocSuffix)
        builder.append("); }")
        nextLine()
    }

    override fun appendGetObjectInstance(objectScope: Scope, exprScope: Scope) {
        if (objectScope == outsideClassLike(exprScope)) {
            builder.append("self")
        } else {
            appendType(objectScope.typeWithArgs, objectScope, false)
            builder.append("::get").append(OBJECT_FIELD_NAME).append("()")
        }
    }

    override fun appendObjectInstance(field: Field, exprScope: Scope, forFieldAccess: String) {
        appendGetObjectInstance(field.ownerScope, exprScope)
        builder.append(forFieldAccess)
    }

    override fun appendInstrImpl(graph: SimpleGraph, expr: SimpleInstruction) {
        when (expr) {
            is SimpleAllocateInstance -> {
                val ownership = getOwnership(expr.allocatedType)
                builder.append(ownership.allocPrefix)
                appendType(expr.allocatedType, expr.scope, true)
                builder.append("::new")
                appendValueParams(graph, expr.paramsForLater)
                builder.append(ownership.allocSuffix)
            }
            is SimpleBranch -> {
                builder.append("if ")
                appendFieldName(graph, expr.condition)
                builder.append(' ')
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
                check(expr.conditionBlock == null) { "Loop with condition not yet implemented" }
                // builder.append("'b").append(expr.body.blockId).append(": ") // todo check if we use the label anywhere
                builder.append("loop ")
                writeBlock {
                    appendBlock(graph, expr.body)
                }
            }
            is SimpleTailCall -> {
                builder.append("nextBlockId = ").append(expr.toBeCalled.id).append(';')
                nextLine()
                builder.append("continue 'blockTable;")
            }
            is SimpleInstanceOf -> {
                // https://doc.rust-lang.org/std/any/index.html
                // use std::any::{Any, TypeId};
                // (&*boxed).type_id() == TypeId::of::<i32>()
                TODO("Implement $expr for Rust")
            }
            is SimpleBoxCast -> {

                // todo to support this properly, we need <dyn Any> instead of just Any,
                //  https://doc.rust-lang.org/std/any/index.html

                val srcType = expr.src.type
                val dstType = expr.dst.type
                val srcNum = srcType in nativeNumbers
                val dstNum = dstType in nativeNumbers

                val srcRef = !srcNum && !srcType.isValue()
                val dstRef = !dstNum && !dstType.isValue()

                when {
                    srcNum && dstNum -> {
                        appendFieldName(graph, expr.src)
                        builder.append(" as ")
                        appendType(dstType, expr.scope, false)
                    }
                    srcNum -> {
                        check(dstRef) { "Expected $expr with srcNum to have dstRef" }
                        val ownership = getOwnership(dstType)
                        builder.append(ownership.allocPrefix)
                        appendType(srcType, expr.scope, true)
                        builder.append("::new(")
                        ensureImport(srcType as ClassType)
                        appendFieldName(graph, expr.src)
                        builder.append(')')
                        builder.append(ownership.allocSuffix)
                    }
                    dstNum -> {
                        check(srcRef) { "Expected $expr with dstNum to have srcRef" }
                        // appendOwnershipSuffix(expr.dst.type, true)
                        builder.append("(")
                        appendFieldName(graph, expr.src)
                        builder.append(" as ")
                        val ownership = getOwnership(srcType)
                        builder.append(ownership.typePrefix)
                        appendType(dstType, expr.scope, true)
                        builder.append(ownership.typeSuffix)
                        builder.append(").content")
                    }
                    srcRef && dstRef -> {
                        builder.append("dynamic_cast<")
                        appendType(dstType, expr.scope, true)
                        // appendOwnershipSuffix(expr.dst.type, true)
                        builder.append(">(")
                        appendFieldName(graph, expr.src)
                        builder.append(')')
                    }
                    else -> {
                        builder.append('(')
                        appendType(dstType, expr.scope, true)
                        // appendOwnershipSuffix(expr.dst.type, true)
                        builder.append(") ")
                        appendFieldName(graph, expr.src)
                    }
                }
            }
            else -> super.appendInstrImpl(graph, expr)
        }
    }

    override fun appendDefaultValue(valueType: Type) {
        when (val type = resolveType(valueType)) {
            Types.Boolean -> builder.append("false")
            Types.Char -> builder.append("'\\u{0000}'")
            in nativeTypes -> builder.append(if (type.isFloat()) "0.0" else "0")
            is ClassType -> appendCreateEmptyInstance(
                type.clazz,
                getClassName(type.clazz, Specialization(type))
            )
            else -> builder.append("()")
        }
    }

    override fun appendCopy(graph: SimpleGraph, valueType: Type) {
        // not necessary, this is done by the owner-ship types
    }

    override fun appendNativeCall(needsCastForFirstValue: BoxedType, expr: SimpleMethodCall, graph: SimpleGraph) {
        // ensure import
        val selfType = expr.thisInstance.type as ClassType
        ensureImport(selfType)

        builder.append(needsCastForFirstValue.boxed).append("::new(")
        appendFieldName(graph, expr.thisInstance)
        builder.append(").")
        builder.append(getMethodName(expr.specialization))
        appendValueParams(graph, expr.valueParameters)
    }

    fun Type.isObjectLike() = this is ClassType && clazz.isObjectLike()

    override fun appendCallImpl(graph: SimpleGraph, expr: SimpleMethodCall) {
        val needsCastForFirstValue = nativeTypes[expr.thisInstance.type]
        if (needsCastForFirstValue != null) {
            appendNativeCall(needsCastForFirstValue, expr, graph)
        } else {
            appendFieldName(graph, expr.thisInstance, "")
            if (!expr.thisInstance.type.isObjectLike()) {
                val ownership = getOwnership(expr.thisInstance.type)
                builder.append(ownership.callPrefix)
            }
            builder.append(".")
            val methodName = getMethodName(expr.specialization)
            builder.append(methodName)
            appendValueParams(graph, expr.valueParameters)
        }
    }

    override fun appendNumber(type: Type, expr: NumberExpression) {
        when (type) {
            Types.Byte -> builder.append(expr.asInt.toByte()).append("i8")
            Types.UByte -> builder.append(expr.asInt.toUByte()).append("u8")
            Types.Short -> builder.append(expr.asInt.toShort()).append("i16")
            Types.UShort -> builder.append(expr.asInt.toUShort()).append("u16")
            Types.Int -> builder.append(expr.asInt.toInt()).append("i32")
            Types.UInt -> builder.append(expr.asInt.toUInt()).append("u32")
            Types.Long -> builder.append(expr.asInt).append("i64")
            Types.ULong -> builder.append(expr.asInt.toULong()).append("u64")
            Types.Half -> builder.append(expr.asFloat.toHalf().toFloat()).append("f16")
            Types.Float -> builder.append(expr.asFloat.toFloat()).append("f32")
            Types.Double -> builder.append(expr.asFloat).append("f64")
            Types.Char -> {
                builder.append('\'')
                when (val value = expr.asInt.toInt().toChar()) {
                    in 'A'..'Z', in 'a'..'z', in '0'..'9' -> builder.append(value)
                    else -> builder.append("\\u{").append(
                        value.code.toString(16)
                            .padStart(4, '0')
                    ).append('}')
                }
                builder.append('\'')
            }
            else -> throw NotImplementedError("Append number of type $type")
        }
    }

    override fun getBinarySymbol(type: Type, methodName: String): String? {
        return if (type.isFloat()) super.getBinarySymbol(type, methodName)
        else if (type in nativeNumbers) {
            when (methodName) {
                "plus" -> ".wrapping_add"
                "minus" -> ".wrapping_sub"
                "times" -> ".wrapping_mul"
                "div" -> ".wrapping_div"
                "rem" -> ".wrapping_rem"
                "and" -> " & "
                "or" -> " | "
                "xor" -> " ^ "
                "shl" -> "<<"
                "shr" -> ">>"
                "ushr" -> ">>>"
                "rotateLeft" -> "rotateLeft"
                "rotateRight" -> "rotateRight"
                else -> super.getBinarySymbol(type, methodName)
            }
        } else null
    }

    override fun appendUnaryOperator(graph: SimpleGraph, expr: SimpleMethodCall, methodName: String): Boolean {
        val thisType = expr.thisInstance.type
        return if (methodName == "inv" && thisType in nativeNumbers) {
            builder.append('!')
            appendFieldName(graph, expr.thisInstance)
            true
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

        if (symbol.startsWith('.')) {
            appendFirstParameter(graph, type, expr)
            builder.append(symbol).append('(')
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