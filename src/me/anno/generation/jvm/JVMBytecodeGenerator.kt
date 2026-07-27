package me.anno.generation.jvm

import me.anno.generation.FileEntry
import me.anno.generation.FileWithImportsWriter
import me.anno.generation.java.JavaSourceGenerator
import me.anno.generation.jvm.VerificationTypeInfo.Companion.buildStackMapTable
import me.anno.support.java.tokenizer.JavaTokenizer
import me.anno.zauber.SpecialFieldNames.OBJECT_FIELD_NAME
import me.anno.zauber.ast.rich.expression.CompareType
import me.anno.zauber.ast.rich.expression.constants.SpecialValue
import me.anno.zauber.ast.rich.member.Constructor
import me.anno.zauber.ast.rich.member.Method
import me.anno.zauber.ast.rich.member.MethodLike
import me.anno.zauber.ast.simple.ASTSimplifier
import me.anno.zauber.ast.simple.SimpleGraph
import me.anno.zauber.ast.simple.constants.SimpleNumber
import me.anno.zauber.ast.simple.constants.SimpleSpecialValue
import me.anno.zauber.ast.simple.constants.SimpleString
import me.anno.zauber.ast.simple.controlflow.SimpleReturn
import me.anno.zauber.ast.simple.controlflow.SimpleThrow
import me.anno.zauber.ast.simple.expression.*
import me.anno.zauber.ast.simple.fields.*
import me.anno.zauber.expansion.DependencyData
import me.anno.zauber.scope.Scope
import me.anno.zauber.scope.ScopeInitType
import me.anno.zauber.scope.ScopeType
import me.anno.zauber.types.Specialization
import me.anno.zauber.types.Specialization.Companion.noSpecialization
import me.anno.zauber.types.Type
import me.anno.zauber.types.Types
import me.anno.zauber.types.impl.ClassType
import me.anno.zauber.types.impl.arithmetic.NullType
import me.anno.zauber.types.impl.arithmetic.UnionType
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.objectweb.asm.Opcodes as AsmOpcodes

/**
 * JVM bytecode backend.
 *
 * Like WASM, we append binary directly; but like LLVM (future), we skip CodeReconstruction and
 * just linearize the SimpleGraph CFG into blocks + branches.
 *
 * For debugging, we also write a ".java" text file next to each ".class" containing Java-style
 * method headers, but bytecode-style instruction mnemonics in the body.
 *
 * todo respect hasReturn()
 */
class JVMBytecodeGenerator : JavaSourceGenerator() {

    companion object {
        const val MAJOR = 51 // Java 7
        fun toJVMValueType(type0: Type): JVMValueType {
            val type = resolveType(type0)
            val t = if (type is UnionType) type.types.first { it != NullType } else type
            return when (t) {
                Types.Boolean,
                Types.Byte, Types.UByte,
                Types.Short, Types.UShort,
                Types.Char,
                Types.Int, Types.UInt -> JVMValueType.INT
                Types.Long, Types.ULong -> JVMValueType.LONG
                Types.Float, Types.Half -> JVMValueType.FLOAT
                Types.Double -> JVMValueType.DOUBLE
                else -> JVMValueType.REFERENCE
            }
        }

        private val inlineBinaryNames = setOf(
            "plus", "minus", "times", "div", "rem",
            "shl", "shr", "ushr", "and", "or", "xor"
        )

        private val inlineUnaryNames = setOf(
            "hashCode", "toString", "inv"
        )

    }

    override fun getExtension(headerOnly: Boolean): String = "class"

    val binary = JVMBytecodeWriter()

    override fun generateCode(dst: File, data: DependencyData, mainMethod: Method) {
        val writer = FileWithImportsWriter(this, dst)
        try {

            File(dst, "Zauber.jar").outputStream().buffered().use { fos ->
                ZipOutputStream(fos).use { zos ->
                    zos.putNextEntry(ZipEntry("META-INF/MANIFEST.MF"))
                    val manifest = """
                    Manifest-Version: 1.0
                    Main-Class: zauber.LaunchZauber
                    Implementation-Title: minimal
                    Implementation-Version: 1.0-SNAPSHOT
                """.trimIndent()
                    zos.write((manifest + "\n\n").encodeToByteArray())
                    zos.closeEntry()

                    generateAllClasses(writer, data, mainMethod, zos)
                }
            }
        } finally {
            writer.finish()
        }
    }

    private fun generateAllClasses(
        writer: FileWithImportsWriter, data: DependencyData,
        mainMethod: Method, zos: ZipOutputStream
    ) {
        // same grouping as JavaSourceGenerator.generateCodeImpl(), but producing .class instead of .java
        val methodsByClass = data.calledMethods.groupBy { methodSpec ->
            val owner = methodSpec.method.ownerScope
            owner to methodSpec.typeParameters.filterByGenerics { it.scope == owner }
        }
        val fieldsByClass = (data.getFields + data.setFields).groupBy { fieldSpec ->
            val owner = fieldSpec.field.ownerScope
            owner to fieldSpec.typeParameters.filterByGenerics { it.scope == owner }
        }
        val classes1 = data.createdClasses.map { it.clazz to it.typeParameters }
        val classes = (methodsByClass.keys + fieldsByClass.keys + classes1)
            .filter { it.first.isClassLike() }
            .distinct()

        // Zauber entry point
        val (bytes, dbg) = buildLaunchZauberClass(mainMethod)
        writeClassAndDebug(writer, "zauber/LaunchZauber", bytes, dbg, zos)

        for (clazz in classes) {
            val (scope, typeParams) = clazz
            val classSpec = Specialization(scope, typeParams)
            scope[ScopeInitType.CODE_GENERATION]
            val methods = methodsByClass[clazz] ?: emptyList()
            val fields = fieldsByClass[clazz] ?: emptyList()
            val (name, packageScope) = getNameAndScope(scope, classSpec)
            val internalName = (packageScope.path + name).joinToString("/")
            val gen = JVMClass(scope, classSpec, methods, fields, name, packageScope, internalName)
            val (bytes, dbg) = gen.specialization.use { buildZauberClass(gen) }
            writeClassAndDebug(writer, gen.internalName, bytes, dbg, zos)
        }
    }

    private fun writeClassAndDebug(
        writer: FileWithImportsWriter, internalName: String, bytes: ByteArray, debugJava: String,
        zos: ZipOutputStream
    ) {
        zos.putNextEntry(ZipEntry("$internalName.class"))
        zos.write(bytes)
        zos.closeEntry()

        val dbgFile = File(writer.root, "$internalName.java")
        val entry = FileEntry(internalName.split('/').dropLast(1), this)
        entry.content.append(debugJava)
        writer[dbgFile] = entry
    }

    private fun buildLaunchZauberClass(mainMethod: Method): Pair<ByteArray, String> {
        val cp = ConstantPool()
        val thisInternal = "zauber/LaunchZauber"
        val thisClass = cp.clazz(thisInternal)
        val superClass = cp.clazz("java/lang/Object")
        val codeUtf8 = cp.utf8("Code")

        val methods = ArrayList<MethodInfo>(2)

        // <init>()
        run {
            val code = JVMCodeBuilder(cp)
            code.aload0()
            code.invokespecial("java/lang/Object", "<init>", "()V")
            code.ret()
            val codeAttr = binary.buildCodeAttributeInfo(8, 1, code.finish())
            methods.add(
                MethodInfo(
                    AsmOpcodes.ACC_PUBLIC,
                    cp.utf8("<init>"),
                    cp.utf8("()V"),
                    listOf(AttributeInfo(codeUtf8, codeAttr))
                )
            )
        }

        // public static void main(String[] args)
        run {
            val code = JVMCodeBuilder(cp)

            // Invoke the compiled main-method via the object-like singleton field.
            val ownerInternal = getJVMName(mainMethod.ownerScope.typeWithArgs)

            code.getstatic(ownerInternal, OBJECT_FIELD_NAME, "L$ownerInternal;")
            val targetDesc = methodDescriptorOf(mainMethod, noSpecialization, mainMethod.ownerScope)
            code.invokevirtual(ownerInternal, mainMethod.name, targetDesc)
            code.pop()
            code.ret()

            val codeAttr = binary.buildCodeAttributeInfo(32, 1, code.finish())
            methods.add(
                MethodInfo(
                    AsmOpcodes.ACC_PUBLIC or AsmOpcodes.ACC_STATIC,
                    cp.utf8("main"),
                    cp.utf8("([Ljava/lang/String;)V"),
                    listOf(AttributeInfo(codeUtf8, codeAttr))
                )
            )
        }

        val bytes = JVMBytecodeWriter().run {
            writeClassFile(
                major = MAJOR,
                minor = 0,
                constantPool = cp,
                accessFlags = AsmOpcodes.ACC_PUBLIC or AsmOpcodes.ACC_SUPER,
                thisClass = thisClass,
                superClass = superClass,
                interfaces = IntArray(0),
                fields = emptyList(),
                methods = methods,
                attributes = emptyList()
            )
            out.toByteArray()
        }

        val debugText = buildDebugText(
            className = "LaunchZauber",
            methods = listOf(
                DebugMethod(
                    "public LaunchZauber()",
                    listOf("ALOAD_0", "INVOKESPECIAL java/lang/Object.<init>()V", "RETURN")
                ),
                DebugMethod("public static void main(String[] args)", listOf("...", "RETURN"))
            )
        )
        return bytes to debugText
    }

    private fun buildZauberClass(clazz: JVMClass): Pair<ByteArray, String> {
        val cp = ConstantPool()
        val thisClass = cp.clazz(clazz.internalName)
        val superInternalName = getSuperInternalName(clazz.scope)
        val superClass = cp.clazz(superInternalName)
        val codeUtf8 = cp.utf8("Code")

        val fields = ArrayList<FieldInfo>()
        val methods = ArrayList<MethodInfo>()
        val debugMethods = ArrayList<DebugMethod>()

        // Object-like scopes get: public static final <This> __object__ and <clinit> initializer.
        if (clazz.scope.isObjectLike()) {
            fields.add(
                FieldInfo(
                    AsmOpcodes.ACC_PUBLIC or AsmOpcodes.ACC_STATIC or AsmOpcodes.ACC_FINAL,
                    cp.utf8(OBJECT_FIELD_NAME),
                    cp.utf8("L${clazz.internalName};"),
                    emptyList()
                )
            )

            val code = JVMCodeBuilder(cp)
            code.new0(clazz.internalName)
            code.dup()
            code.invokespecial(clazz.internalName, "<init>", "()V")
            code.putstatic(clazz.internalName, OBJECT_FIELD_NAME, "L${clazz.internalName};")
            code.ret()
            val codeAttr = binary.buildCodeAttributeInfo(16, 0, code.finish())
            methods.add(
                MethodInfo(
                    AsmOpcodes.ACC_STATIC,
                    cp.utf8("<clinit>"),
                    cp.utf8("()V"),
                    listOf(AttributeInfo(codeUtf8, codeAttr))
                )
            )
            debugMethods.add(
                DebugMethod(
                    "static {}",
                    listOf(
                        "NEW ${clazz.internalName}",
                        "DUP",
                        "INVOKESPECIAL ${clazz.internalName}.<init>()V",
                        "PUTSTATIC ${clazz.internalName}.$OBJECT_FIELD_NAME : L${clazz.internalName};",
                        "RETURN"
                    )
                )
            )
        }

        // Backing fields
        val allowFinalFields = !clazz.scope.isValueType() && clazz.methods.any { it.method is Constructor }
        for (fieldSpec in clazz.fields) {
            val field = fieldSpec.field
            if (!isStoredField(field)) continue
            fieldSpec.use {
                val acc =
                    AsmOpcodes.ACC_PUBLIC or (if (!field.isMutable && allowFinalFields) AsmOpcodes.ACC_FINAL else 0)
                val valueType = resolveType((field.valueType ?: Types.NullableAny).resolve(clazz.scope))
                fields.add(
                    FieldInfo(
                        acc,
                        cp.utf8(field.newName),
                        cp.utf8(descriptorOf(valueType, clazz.scope)),
                        emptyList()
                    )
                )
            }
        }

        if (clazz.scope == Types.Array.clazz) {
            val elemType = resolveType(clazz.specialization.typeParameters[0])
            fields.add(
                FieldInfo(
                    AsmOpcodes.ACC_PRIVATE or AsmOpcodes.ACC_FINAL,
                    cp.utf8("content"),
                    cp.utf8(arrayDescriptorOf(elemType, clazz.scope)),
                    emptyList()
                )
            )
        }

        // Constructors
        val constructors = clazz.methods.filter { it.method is Constructor }
        if (constructors.isNotEmpty()) {
            for (spec in constructors) {
                val constructor = spec.method as Constructor
                val (m, dbg) = buildConstructor(clazz, spec, constructor, cp, codeUtf8)
                methods.add(m)
                debugMethods.add(dbg)
            }
        } else if (clazz.scope.scopeType != ScopeType.INTERFACE && !clazz.scope.isObjectLike()) {
            val (m, dbg) = buildDefaultConstructor(clazz, cp, codeUtf8, superInternalName)
            methods.add(m)
            debugMethods.add(dbg)
        }

        // Methods
        for (methodSpec in clazz.methods) {
            val method = methodSpec.method
            if (method !is Method) continue
            if (method.scope.parent != clazz.scope) continue // skip inherited
            val (m, dbg) = buildMethod(clazz, methodSpec, method, cp, codeUtf8)
            methods.add(m)
            if (dbg != null) debugMethods.add(dbg)
        }

        val bytes = JVMBytecodeWriter().run {
            writeClassFile(
                major = MAJOR,
                minor = 0,
                constantPool = cp,
                accessFlags = AsmOpcodes.ACC_PUBLIC or AsmOpcodes.ACC_SUPER,
                thisClass = thisClass,
                superClass = superClass,
                interfaces = IntArray(0), // todo this must be correctly set...
                fields = fields,
                methods = methods,
                attributes = emptyList()
            )
            out.toByteArray()
        }

        val pkg = clazz.internalName.substringBeforeLast('/', missingDelimiterValue = "").replace('/', '.')
        val debugText = buildDebugText(clazz.className, debugMethods)
        return bytes to debugText
    }

    private fun buildDefaultConstructor(
        gen: JVMClass,
        cp: ConstantPool,
        codeUtf8: Int,
        superInternalName: String
    ): Pair<MethodInfo, DebugMethod> {
        val code = JVMCodeBuilder(cp)
        code.aload0()
        code.invokespecial(superInternalName, "<init>", "()V")
        code.ret()
        val codeAttr = binary.buildCodeAttributeInfo(8, 1, code.finish())
        val m = MethodInfo(
            AsmOpcodes.ACC_PUBLIC,
            cp.utf8("<init>"),
            cp.utf8("()V"),
            listOf(AttributeInfo(codeUtf8, codeAttr))
        )
        return m to DebugMethod(
            "public ${gen.className}()",
            listOf("ALOAD_0", "INVOKESPECIAL $superInternalName.<init>()V", "RETURN")
        )
    }

    private fun buildConstructor(
        gen: JVMClass,
        spec: Specialization,
        constructor: Constructor,
        constants: ConstantPool,
        codeUtf8: Int
    ): Pair<MethodInfo, DebugMethod> {

        val dbg = ArrayList<String>()
        val code = JVMCodeBuilder(constants, dbg)

        if (constructor.superCall == null ||
            constructor.body == null ||
            constructor.ownerScope.isPackage()
        //  constructor.ownerScope.isObjectLike() // todo why is this missing for packages???
        ) {
            val superInternalName = getSuperInternalName(gen.scope)
            code.aload0()
            code.invokespecial(superInternalName, "<init>", "()V")
        }

        // Array content init (mirrors JavaSourceGenerator.appendArrayContentInitialization())
        if (gen.scope == Types.Array.clazz &&
            constructor.valueParameters.size == 1 &&
            constructor.valueParameters[0].type == Types.Int
        ) {
            val elemType = resolveType(gen.specialization.typeParameters[0])
            code.aload0()
            code.iload(1)
            val refName = (elemType as? ClassType)?.let { getJVMName(it) }
            code.newArray(elemType, refName)
            code.putfield(gen.internalName, "content", arrayDescriptorOf(elemType, gen.scope))
        }

        val body = constructor.body
        if (body != null) {
            translateBody(spec, code)
        } else {
            code.ret()
        }

        val desc = ctorDescriptorOf(constructor, gen.scope)
        val codeAttr = buildBody(code, constants, constructor)
        val m = MethodInfo(
            AsmOpcodes.ACC_PUBLIC,
            constants.utf8("<init>"),
            constants.utf8(desc),
            listOf(AttributeInfo(codeUtf8, codeAttr))
        )

        val header = run {
            val b0 = builder.length
            try {
                appendConstructorHeader(gen.scope, gen.className, constructor, headerOnly = true)
                builder.substring(b0).trim()
            } finally {
                builder.setLength(b0)
            }
        }
        return m to DebugMethod(header, dbg)
    }

    fun buildBody(code: JVMCodeBuilder, constants: ConstantPool, method: MethodLike): ByteArray {
        val bytecode = code.finish()

        val stackMapUtf8 = constants.utf8("StackMapTable")

        val stackFrames = if (method.body != null) {
            val graph = graphForFrames!!
            val locals = localsForFrames!!
            JVMStackMapBuilder(this, constants, locals, graph, code).buildFrames()
        } else {
            emptyList()
        }

        val stackMapAttribute = AttributeInfo(stackMapUtf8, buildStackMapTable(stackFrames))
        return binary.buildCodeAttributeInfo(
            64, code.maxLocals, bytecode,
            attributes = listOf(stackMapAttribute)
        )
    }

    private fun buildMethod(
        gen: JVMClass,
        methodSpec: Specialization,
        method: Method,
        constants: ConstantPool,
        codeUtf8: Int
    ): Pair<MethodInfo, DebugMethod?> {

        val name = getMethodName(methodSpec)
        val desc = methodDescriptorOf(method, methodSpec, gen.scope)

        val nativeImpl = getNativeImplementation(method)
        val isAbstract = method.body == null && nativeImpl == null &&
                !isArrayGetter(methodSpec) && !isArraySetter(methodSpec)

        if (isAbstract || (gen.scope.scopeType == ScopeType.INTERFACE && method.body == null)) {
            return MethodInfo(
                AsmOpcodes.ACC_PUBLIC or AsmOpcodes.ACC_ABSTRACT,
                constants.utf8(name),
                constants.utf8(desc),
                emptyList()
            ) to null
        }

        val dbg = ArrayList<String>()
        val code = JVMCodeBuilder(constants, dbg)

        when {
            isArrayGetter(methodSpec) -> appendArrayGetter(code, gen)
            isArraySetter(methodSpec) -> appendArraySetter(code, gen)
            nativeImpl != null -> appendNativeMethod(code, gen.scope, method, nativeImpl)
            method.body != null -> translateBody(methodSpec, code)
            else -> appendReturnIfMissing(code, method, methodSpec)
        }

        val codeAttr = buildBody(code, constants, method)
        val m = MethodInfo(
            AsmOpcodes.ACC_PUBLIC,
            constants.utf8(name),
            constants.utf8(desc),
            listOf(AttributeInfo(codeUtf8, codeAttr))
        )

        val header = buildJavaMethodHeader(gen.scope, gen.className, methodSpec)
        return m to DebugMethod(header, dbg)
    }

    fun translateBody(methodSpec: Specialization, code: JVMCodeBuilder) {
        val graph = ASTSimplifier.simplify(methodSpec)
        prepareGraphForBytecode(graph)
        graphForFrames = graph
        val locals = JVMLocals(this, code, graph)
        localsForFrames = locals
        appendGraph(code, graph, locals)
    }

    var graphForFrames: SimpleGraph? = null
    var localsForFrames: JVMLocals? = null

    private fun buildJavaMethodHeader(classScope: Scope, className: String, methodSpec: Specialization): String {
        val b0 = builder.length
        try {
            appendMethodHeader(classScope, className, methodSpec, headerOnly = true)
            val header = builder.substring(b0).trim()
            return header
        } finally {
            builder.setLength(b0)
        }
    }

    private fun appendNativeMethod(code: JVMCodeBuilder, scope: Scope, method: Method, nativeImpl: String) {
        val impl = nativeImpl.trim().removeSuffix(";")
        if (impl == "System.out.println(arg0)") {
            code.getstatic("java/lang/System", "out", "Ljava/io/PrintStream;")
            code.iload(1)
            code.invokevirtual("java/io/PrintStream", "println", "(I)V")
            appendReturnIfMissing(code, method, noSpecialization)
        } else {
            code.new0("java/lang/UnsupportedOperationException")
            code.dup()
            code.ldc("Unimplemented native: $impl")
            code.invokespecial("java/lang/UnsupportedOperationException", "<init>", "(Ljava/lang/String;)V")
            code.athrow()
        }
    }

    private fun appendArrayGetter(code: JVMCodeBuilder, gen: JVMClass) {
        val elementType = resolveType(gen.specialization.typeParameters[0])
        code.aload0()
        code.getfield(gen.internalName, "content", arrayDescriptorOf(elementType, gen.scope))
        code.iload(1)
        code.arrayLoad(elementType)
        code.returnByType(elementType)
    }

    private fun appendArraySetter(code: JVMCodeBuilder, gen: JVMClass) {
        val elementType = resolveType(gen.specialization.typeParameters[0])
        code.aload0()
        code.getfield(gen.internalName, "content", arrayDescriptorOf(elementType, gen.scope))
        code.iload(1)
        code.loadByType(2, elementType)
        code.arrayStore(elementType)
        // return Unit.__object__
        val unitInternal = getJVMName(Types.Unit)
        code.getstatic(unitInternal, OBJECT_FIELD_NAME, "L$unitInternal;")
        code.areturn()
    }

    private fun prepareGraphForBytecode(graph: SimpleGraph) {
        // Unlike JavaSourceGenerator.prepareGraph(), we do NOT reconstruct to if/while nodes.
        // We also keep fields (no aggressive DCE) because stack optimization is out of scope for now.
        graph.giveLocalFieldsUniqueNames(JavaTokenizer.KEYWORDS)
        graph.findBoxingAndUnboxing()
        graph.renumberFields()
    }

    private fun appendGraph(code: JVMCodeBuilder, graph: SimpleGraph, locals: JVMLocals) {
        code.maxLocals = locals.maxLocals

        val labels = graph.blocks.associateWith { "B${it.id}" }
        code.blockLabels = labels

        for (block in graph.blocks) {
            code.label(labels.getValue(block))
            for (instr in block.instructions) {
                if (instr is me.anno.zauber.ast.simple.SimpleMerge) continue // handled by local coalescing
                appendInstr(code, locals, graph, instr)
                if (instr is SimpleReturn || instr is SimpleThrow) break
            }
            val last = block.instructions.lastOrNull()
            if (last is SimpleReturn || last is SimpleThrow) continue
            val cond = block.branchCondition
            if (cond != null && block.ifBranch != null && block.elseBranch != null && block.ifBranch != block.elseBranch) {
                locals.loadField(cond)
                code.ifne(labels.getValue(block.ifBranch!!))
                code.goTo(labels.getValue(block.elseBranch!!))
            } else {
                val next = block.nextBranch
                if (next != null) code.goTo(labels.getValue(next))
            }
        }
    }

    private fun appendInstr(
        code: JVMCodeBuilder, locals: JVMLocals,
        graph: SimpleGraph, instr: SimpleInstruction
    ) {
        when (instr) {
            is SimpleNumber -> {
                locals.pushConst(instr.dst, instr.base)
                locals.storeField(instr.dst)
            }
            is SimpleString -> {
                code.ldc(instr.base.value)
                locals.storeField(instr.dst)
            }
            is SimpleSpecialValue -> {
                when (instr.type) {
                    SpecialValue.NULL -> code.aconstNull()
                    SpecialValue.TRUE -> code.iconst(1)
                    SpecialValue.FALSE -> code.iconst(0)
                }
                locals.storeField(instr.dst)
            }
            is SimpleGetLocalField -> {
                locals.loadLocal(instr.field)
                locals.storeField(instr.dst)
            }
            is SimpleSetLocalField -> {
                locals.loadField(instr.value)
                locals.storeLocal(instr.field)
            }
            is SimpleGetObject -> {
                val objInternal = getJVMName(instr.objectScope.typeWithArgs)
                val insideOfSameObject = instr.objectScope == graph.method.ownerScope
                if (insideOfSameObject) {
                    code.aload0()
                } else {
                    code.getstatic(objInternal, OBJECT_FIELD_NAME, "L$objInternal;")
                }
                locals.storeField(instr.dst)
            }
            is SimpleGetClassField -> {
                locals.loadField(instr.self)
                val ownerInternal = getJVMName(instr.field.ownerScope.typeWithArgs.specialize(instr.specialization))
                val valueType = resolveType(instr.field.valueType ?: Types.NullableAny)
                code.getfield(ownerInternal, instr.field.newName, descriptorOf(valueType, instr.scope))
                locals.storeField(instr.dst)
            }
            is SimpleSetClassField -> {
                locals.loadField(instr.self)
                locals.loadField(instr.value)
                val ownerInternal = getJVMName(instr.field.ownerScope.typeWithArgs.specialize(instr.specialization))
                val valueType = resolveType(instr.field.valueType ?: Types.NullableAny)
                code.putfield(ownerInternal, instr.field.newName, descriptorOf(valueType, instr.scope))
            }
            is SimpleAllocateInstance -> {
                val internal = getJVMName(instr.allocatedType)
                code.new0(internal)
                code.dup()
                locals.storeField(instr.dst) // keep for ctor call
            }
            is SimpleConstructorCall -> {
                locals.loadField(instr.thisInstance)
                for (p in instr.valueParameters) locals.loadField(p)
                val ownerInternal =
                    getJVMName((instr.method as Constructor).ownerScope.typeWithArgs.specialize(instr.specialization))
                val desc = ctorDescriptorOf(instr.method as Constructor, (instr.method as Constructor).ownerScope)
                code.invokespecial(ownerInternal, "<init>", desc)
            }
            is SimpleMethodCall -> {
                val methodName = instr.methodName
                val thisType = resolveType(instr.thisInstance.type)
                val thisIsNumber = thisType in nativeNumbers
                val binaryInline = thisIsNumber &&
                        instr.valueParameters.size == 1 &&
                        methodName in inlineBinaryNames
                val unaryInline = thisIsNumber &&
                        instr.valueParameters.isEmpty() &&
                        methodName in inlineUnaryNames
                when {
                    unaryInline -> {
                        val jvmType = toJVMValueType(thisType)
                        locals.loadField(instr.thisInstance)
                        val tmp = toJVMValueType(instr.dst.type)
                        code.invokestatic(
                            when (jvmType) {
                                JVMValueType.INT -> "java/lang/Integer"
                                JVMValueType.LONG -> "java/lang/Long"
                                JVMValueType.FLOAT -> "java/lang/Float"
                                JVMValueType.DOUBLE -> "java/lang/Double"
                                else -> error("Unreachable")
                            }, methodName, "(" + tmp.letter + when (tmp) {
                                JVMValueType.INT -> ")I"
                                JVMValueType.LONG -> ")J"
                                JVMValueType.FLOAT -> ")F"
                                JVMValueType.DOUBLE -> ")D"
                                JVMValueType.REFERENCE -> ")L${getJVMName(instr.dst.type)};"
                            }
                        )
                        locals.storeField(instr.dst)
                    }
                    binaryInline -> {
                        val jvmType = toJVMValueType(thisType)
                        locals.loadField(instr.thisInstance)
                        locals.loadField(instr.valueParameters[0])
                        when (methodName) {
                            "plus" -> when (jvmType) {
                                JVMValueType.INT -> code.iadd()
                                JVMValueType.LONG -> code.ladd()
                                JVMValueType.FLOAT -> code.fadd()
                                JVMValueType.DOUBLE -> code.dadd()
                                JVMValueType.REFERENCE -> error("inline plus on ref")
                            }
                            "minus" -> when (jvmType) {
                                JVMValueType.INT -> code.isub()
                                JVMValueType.LONG -> code.lsub()
                                JVMValueType.FLOAT -> code.fsub()
                                JVMValueType.DOUBLE -> code.dsub()
                                JVMValueType.REFERENCE -> error("inline minus on ref")
                            }
                            "times" -> when (jvmType) {
                                JVMValueType.INT -> code.imul()
                                JVMValueType.LONG -> code.lmul()
                                JVMValueType.FLOAT -> code.fmul()
                                JVMValueType.DOUBLE -> code.dmul()
                                JVMValueType.REFERENCE -> error("inline times on ref")
                            }
                            // todo unsigned types need other implementations
                            "div" -> when (jvmType) {
                                JVMValueType.INT -> code.idiv()
                                JVMValueType.LONG -> code.ldiv()
                                JVMValueType.FLOAT -> code.fdiv()
                                JVMValueType.DOUBLE -> code.ddiv()
                                JVMValueType.REFERENCE -> error("inline div on ref")
                            }
                            "rem" -> when (jvmType) {
                                JVMValueType.INT -> code.irem()
                                JVMValueType.LONG -> code.lrem()
                                JVMValueType.FLOAT -> code.frem()
                                JVMValueType.DOUBLE -> code.drem()
                                JVMValueType.REFERENCE -> error("inline rem on ref")
                            }
                        }
                        locals.storeField(instr.dst)
                    }
                    else -> {
                        locals.loadField(instr.thisInstance)
                        instr.selfInstance?.let { locals.loadField(it) }
                        for (p in instr.valueParameters) locals.loadField(p)
                        val target = instr.methods.values.first()
                        val ownerInternal = getJVMName(target.ownerScope.typeWithArgs.specialize(instr.specialization))
                        val targetDesc = methodDescriptorOf(target, instr.specialization, target.ownerScope)
                        val isInterface = target.ownerScope.scopeType == ScopeType.INTERFACE
                        if (isInterface) {
                            code.invokeinterface(
                                ownerInternal, getMethodName(instr.specialization),
                                targetDesc, 1 + instr.valueParameters.size
                            )
                        } else {
                            code.invokevirtual(ownerInternal, getMethodName(instr.specialization), targetDesc)
                        }
                        locals.storeField(instr.dst)
                    }
                }
            }
            is SimpleCompare -> {
                val vt = toJVMValueType(instr.left.type)
                locals.loadField(instr.left)
                locals.loadField(instr.right)
                val lTrue = code.newLabel("cmp_true")
                val lEnd = code.newLabel("cmp_end")

                addPendingFrames(locals, code, lTrue, lEnd)

                when (vt) {
                    JVMValueType.INT -> {
                        val op = when (instr.type) {
                            CompareType.LESS -> Opcodes.IF_ICMPLT
                            CompareType.LESS_EQUALS -> Opcodes.IF_ICMPLE
                            CompareType.GREATER -> Opcodes.IF_ICMPGT
                            CompareType.GREATER_EQUALS -> Opcodes.IF_ICMPGE
                        }
                        code.jump(op, lTrue)
                    }
                    JVMValueType.LONG -> {
                        code.lcmp()
                        val op = when (instr.type) {
                            CompareType.LESS -> Opcodes.IFLT
                            CompareType.LESS_EQUALS -> Opcodes.IFLE
                            CompareType.GREATER -> Opcodes.IFGT
                            CompareType.GREATER_EQUALS -> Opcodes.IFGE
                        }
                        code.jump(op, lTrue)
                    }
                    JVMValueType.FLOAT -> {
                        code.fcmpg()
                        val op = when (instr.type) {
                            CompareType.LESS -> Opcodes.IFLT
                            CompareType.LESS_EQUALS -> Opcodes.IFLE
                            CompareType.GREATER -> Opcodes.IFGT
                            CompareType.GREATER_EQUALS -> Opcodes.IFGE
                        }
                        code.jump(op, lTrue)
                    }
                    JVMValueType.DOUBLE -> {
                        code.dcmpg()
                        val op = when (instr.type) {
                            CompareType.LESS -> Opcodes.IFLT
                            CompareType.LESS_EQUALS -> Opcodes.IFLE
                            CompareType.GREATER -> Opcodes.IFGT
                            CompareType.GREATER_EQUALS -> Opcodes.IFGE
                        }
                        code.jump(op, lTrue)
                    }
                    JVMValueType.REFERENCE -> {
                        error("Compare on references not supported by SimpleCompare")
                    }
                }
                code.iconst(0)
                code.goTo(lEnd)
                code.label(lTrue)
                code.iconst(1)
                code.label(lEnd)
                locals.storeField(instr.dst)
            }
            is SimpleCheckIdentical -> {
                locals.loadField(instr.left)
                locals.loadField(instr.right)
                val lTrue = code.newLabel("id_true")
                val lEnd = code.newLabel("id_end")

                addPendingFrames(locals, code, lTrue, lEnd)

                val op = if (instr.negated) Opcodes.IF_ACMPNE else Opcodes.IF_ACMPEQ
                code.jump(op, lTrue)
                code.iconst(0)
                code.goTo(lEnd)
                code.label(lTrue)
                code.iconst(1)
                code.label(lEnd)
                locals.storeField(instr.dst)
            }
            is SimpleCheckEquals -> {
                // Primitive int compare; otherwise Objects.equals
                val vt = toJVMValueType(instr.left.type)
                if (vt != JVMValueType.REFERENCE) {
                    locals.loadField(instr.left)
                    locals.loadField(instr.right)
                    val lTrue = code.newLabel("eq_true")
                    val lEnd = code.newLabel("eq_end")

                    addPendingFrames(locals, code, lTrue, lEnd)

                    when (vt) {
                        JVMValueType.INT -> {
                            val op = if (instr.negated) Opcodes.IF_ICMPNE else Opcodes.IF_ICMPEQ
                            code.jump(op, lTrue)
                        }
                        JVMValueType.LONG -> {
                            code.lcmp()
                            val op = if (instr.negated) Opcodes.IFNE else Opcodes.IFEQ
                            code.jump(op, lTrue)
                        }
                        JVMValueType.FLOAT -> {
                            code.fcmpg()
                            val op = if (instr.negated) Opcodes.IFNE else Opcodes.IFEQ
                            code.jump(op, lTrue)
                        }
                        JVMValueType.DOUBLE -> {
                            code.dcmpg()
                            val op = if (instr.negated) Opcodes.IFNE else Opcodes.IFEQ
                            code.jump(op, lTrue)
                        }
                        else -> error("Unreachable")
                    }
                    code.iconst(0)
                    code.goTo(lEnd)
                    code.label(lTrue)
                    code.iconst(1)
                    code.label(lEnd)
                } else {
                    locals.loadField(instr.left)
                    locals.loadField(instr.right)
                    code.invokestatic("java/util/Objects", "equals", "(Ljava/lang/Object;Ljava/lang/Object;)Z")
                    if (instr.negated) {
                        code.iconst(1)
                        code.ixor()
                    }
                }
                locals.storeField(instr.dst)
            }
            is SimpleInstanceOf -> {
                locals.loadField(instr.value)
                val internal = getJVMName(resolveType(instr.type))
                code.instanceof0(internal)
                locals.storeField(instr.dst)
            }
            is SimpleReturn -> {
                if (graph.method is Constructor) {
                    code.ret()
                } else {
                    locals.loadField(instr.value)
                    code.returnByType(resolveType(instr.value.type))
                }
            }
            is SimpleThrow -> {
                locals.loadField(instr.value)
                code.athrow()
            }
            else -> {
                code.new0("java/lang/UnsupportedOperationException")
                code.dup()
                code.ldc("Unimplemented IR: ${instr.javaClass.simpleName}")
                code.invokespecial("java/lang/UnsupportedOperationException", "<init>", "(Ljava/lang/String;)V")
                code.athrow()
            }
        }
    }

    private fun addPendingFrames(locals: JVMLocals, code: JVMCodeBuilder, lTrue: String, lEnd: String) {
        val snapshot = locals.snapshotForFrame(code.cp)
        code.pendingFrames.add(PendingFrame(lTrue, snapshot, emptyList()))
        code.pendingFrames.add(PendingFrame(lEnd, snapshot, listOf(VerificationTypeInfo.IntegerVariable)))
    }

    private fun appendReturnIfMissing(code: JVMCodeBuilder, method: MethodLike, spec: Specialization) {
        val rt = resolveType(if (method is Constructor) Types.Unit else method.resolveReturnType(spec))
        if (rt == Types.Unit) {
            val unitInternal = getJVMName(Types.Unit)
            code.getstatic(unitInternal, OBJECT_FIELD_NAME, "L$unitInternal;")
            code.areturn()
        } else {
            when (rt) {
                Types.Boolean, Types.Byte, Types.Short, Types.Char, Types.Int, Types.UInt -> {
                    code.iconst(0)
                    code.ireturn()
                }
                Types.Long, Types.ULong -> {
                    code.lconst0()
                    code.lreturn()
                }
                Types.Float, Types.Half -> {
                    code.fconst0()
                    code.freturn()
                }
                Types.Double -> {
                    code.dconst0()
                    code.dreturn()
                }
                else -> {
                    code.aconstNull()
                    code.areturn()
                }
            }
        }
    }

    private fun getSuperInternalName(scope: Scope): String {
        val superClass = scope.superCalls.firstOrNull { it.isClassCall }?.typeI ?: Types.Any
        return when (val superType = resolveType(superClass)) {
            Types.Any -> "java/lang/Object"
            is ClassType -> getJVMName(superType)
            else -> "java/lang/Object"
        }
    }

    internal fun getJVMName(type0: Type): String {
        return when (val type = resolveType(type0)) {
            Types.Any -> "java/lang/Object"
            is UnionType -> getJVMName(type.types.first { it != NullType })
            is ClassType -> getJVMName(type)
            else -> "java/lang/Object"
        }
    }

    internal fun getJVMName(type: ClassType): String {
        val spec = Specialization(type)
        val (name, packageScope) = getNameAndScope(type.clazz, spec)
        return (packageScope.path + name).joinToString("/")
    }

    private fun descriptorOf(type0: Type, scope: Scope): String {
        return when (val type = resolveType(type0)) {
            Types.Boolean -> "Z"
            Types.Byte, Types.UByte -> "B"
            Types.Short, Types.UShort -> "S"
            Types.Char -> "C"
            Types.Int, Types.UInt -> "I"
            Types.Long, Types.ULong -> "J"
            Types.Float, Types.Half -> "F"
            Types.Double -> "D"
            Types.Any, Types.Nothing -> "Ljava/lang/Object;"
            is UnionType -> descriptorOf(type.types.first { it != NullType }, scope)
            is ClassType -> "L${getJVMName(type)};"
            else -> "Ljava/lang/Object;"
        }
    }

    private fun arrayDescriptorOf(elementType0: Type, scope: Scope): String {
        val elementType = resolveType(elementType0)
        return "[${descriptorOf(elementType, scope)}"
    }

    private fun ctorDescriptorOf(ctor: Constructor, scope: Scope): String {
        val sb = StringBuilder()
        sb.append('(')
        for (p in ctor.valueParameters) sb.append(descriptorOf(p.type.resolve(scope), scope))
        sb.append(")V")
        return sb.toString()
    }

    private fun methodDescriptorOf(method: MethodLike, spec: Specialization, scope: Scope): String {
        val sb = StringBuilder()
        sb.append('(')
        if (method.hasExplicitSelfType) sb.append(descriptorOf(method.selfType!!.specialize(spec), scope))
        for (p in method.valueParameters) sb.append(descriptorOf(p.type.specialize(spec), scope))
        sb.append(')')
        sb.append(if (method is Constructor) "V" else descriptorOf(method.resolveReturnType(spec), scope))
        return sb.toString()
    }

    private data class DebugMethod(val header: String, val body: List<String>)

    private fun buildDebugText(className: String, methods: List<DebugMethod>): String {
        val b = StringBuilder()
        b.append("public class ").append(className).append(" {\n")
        for (m in methods) {
            b.append("  ").append(m.header).append(" {\n")
            for (line in m.body) {
                b.append("    // ").append(line).append('\n')
            }
            b.append("  }\n\n")
        }
        b.append("}\n")
        return b.toString()
    }

}
