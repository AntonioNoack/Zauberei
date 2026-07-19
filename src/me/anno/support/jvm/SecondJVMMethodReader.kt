package me.anno.support.jvm

import me.anno.generation.Specializations
import me.anno.support.jvm.FirstJVMClassReader.Companion.API_LEVEL
import me.anno.support.jvm.FirstJVMClassReader.Companion.parseMethodSignature
import me.anno.support.jvm.expression.*
import me.anno.utils.ResetThreadLocal.Companion.threadLocal
import me.anno.utils.StringStyles.GREEN
import me.anno.utils.StringStyles.style
import me.anno.utils.assertEquals
import me.anno.zauber.Zauber.root
import me.anno.zauber.ast.rich.Flags
import me.anno.zauber.ast.rich.controlflow.ReturnExpression
import me.anno.zauber.ast.rich.expression.CompareType
import me.anno.zauber.ast.rich.expression.Expression
import me.anno.zauber.ast.rich.expression.ExpressionList
import me.anno.zauber.ast.rich.expression.constants.NumberExpression
import me.anno.zauber.ast.rich.expression.resolved.ThisExpression
import me.anno.zauber.ast.rich.expression.unresolved.FieldExpression
import me.anno.zauber.ast.rich.expression.unresolved.NamedCallExpression
import me.anno.zauber.ast.rich.member.Constructor
import me.anno.zauber.ast.rich.member.Field
import me.anno.zauber.ast.rich.member.Method
import me.anno.zauber.ast.rich.member.MethodLike
import me.anno.zauber.ast.rich.parameter.NamedParameter
import me.anno.zauber.ast.rich.parameter.Parameter
import me.anno.zauber.ast.rich.parameter.SuperCall
import me.anno.zauber.logging.LogManager
import me.anno.zauber.scope.Scope
import me.anno.zauber.scope.ScopeInitType
import me.anno.zauber.scope.ScopeType
import me.anno.zauber.typeresolution.ParameterList
import me.anno.zauber.typeresolution.ResolutionContext
import me.anno.zauber.typeresolution.members.MatchScore
import me.anno.zauber.typeresolution.members.ResolvedConstructor
import me.anno.zauber.typeresolution.members.ResolvedMember
import me.anno.zauber.typeresolution.members.ResolvedMethod
import me.anno.zauber.types.Specialization
import me.anno.zauber.types.Specialization.Companion.noSpecialization
import me.anno.zauber.types.Type
import me.anno.zauber.types.Types
import me.anno.zauber.types.impl.ClassType
import me.anno.zauber.types.impl.GenericType
import me.anno.zauber.types.impl.arithmetic.NullType
import me.anno.zauber.types.impl.arithmetic.UnknownType
import org.objectweb.asm.Handle
import org.objectweb.asm.Label
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes.*


/**
 * This just reads the commands and structures.
 * No specialization is applied yet.
 *
 * todo Chatchy suggests that we can replace all local fields with SimpleFields, too
 *  and it is kind of right... that would simplify lots of other code, too...
 *  -> but our fields can only be used in one place, and that would be violated...
 * */
class SecondJVMMethodReader(val method: MethodLike, val isStatic: Boolean, parameters: List<Parameter>) :
    MethodVisitor(API_LEVEL) {

    companion object {

        private val LOGGER = LogManager.getLogger(SecondJVMMethodReader::class)

        private const val INVOKE_LAMBDA = 0

        /**
         * cache for often-used constants, don't want to have to recreate them
         * */
        class ConstantsImpl {
            val scope = root
            val i0 = NumberExpression("0", scope, -1).apply { resolvedType = Types.Int }
            val i1 = NumberExpression("1", scope, -1).apply { resolvedType = Types.Int }
            val i2 = NumberExpression("2", scope, -1).apply { resolvedType = Types.Int }
            val i3 = NumberExpression("3", scope, -1).apply { resolvedType = Types.Int }
            val i4 = NumberExpression("4", scope, -1).apply { resolvedType = Types.Int }
            val i5 = NumberExpression("5", scope, -1).apply { resolvedType = Types.Int }
            val im1 = NumberExpression("-1", scope, -1).apply { resolvedType = Types.Int }

            val l0 = NumberExpression("0l", scope, -1).apply { resolvedType = Types.Long }
            val l1 = NumberExpression("1l", scope, -1).apply { resolvedType = Types.Long }

            val f0 = NumberExpression("0f", scope, -1).apply { resolvedType = Types.Float }
            val f1 = NumberExpression("1f", scope, -1).apply { resolvedType = Types.Float }
            val f2 = NumberExpression("2f", scope, -1).apply { resolvedType = Types.Float }

            val d0 = NumberExpression("0d", scope, -1).apply { resolvedType = Types.Double }
            val d1 = NumberExpression("1d", scope, -1).apply { resolvedType = Types.Double }
        }

        val Constants by threadLocal { ConstantsImpl() }
    }

    init {
        check(Specializations.specialization === noSpecialization)
        if (LOGGER.isDebugEnabled) LOGGER.debug("Starting $method")
    }

    fun descToType(desc: String): Type {
        return SignatureReader(desc, methodScope).readType()
    }

    fun nameToType(desc: String): Type {
        // todo can be optimized
        return SignatureReader("L$desc;", methodScope).readType()
    }

    fun nameOrDescToType(desc: String): Type {
        return when {
            desc.endsWith(';') || desc.length <= 2 -> descToType(desc)
            else -> nameToType(desc)
        }
    }

    val methodScope get() = method.scope
    val classScope = methodScope.parent!!

    val origin = -1L
    val graph = JVMGraph(methodScope, isStatic, origin)

    val locals = HashMap<Int, JVMSimpleField>()
    val stack = ArrayList<JVMSimpleField>()

    var block = graph.startBlock

    init {
        var writePos = 0
        if (!graph.isStatic) {
            locals[0] = graph.thisField!!
            writePos = 1
        }

        for (i in parameters.indices) {
            val type = parameters[i].type
            locals[writePos] = graph.field(type)
            writePos += if (isType2(type)) 2 else 1
        }

        block.newStartLocals = HashMap(locals)
    }

    override fun visitInsn(opcode: Int) {
        LOGGER.debug(OpCode[opcode])
        when (opcode) {

            // constants
            ICONST_0 -> pushConst(Constants.i0)
            ICONST_1 -> pushConst(Constants.i1)
            ICONST_2 -> pushConst(Constants.i2)
            ICONST_3 -> pushConst(Constants.i3)
            ICONST_4 -> pushConst(Constants.i4)
            ICONST_5 -> pushConst(Constants.i5)
            ICONST_M1 -> pushConst(Constants.im1)
            LCONST_0 -> pushConst(Constants.l0)
            LCONST_1 -> pushConst(Constants.l1)
            FCONST_0 -> pushConst(Constants.f0)
            FCONST_1 -> pushConst(Constants.f1)
            FCONST_2 -> pushConst(Constants.f2)
            DCONST_0 -> pushConst(Constants.d0)
            DCONST_1 -> pushConst(Constants.d1)

            ACONST_NULL -> pushNull()

            // math
            IADD -> binaryCall(Types.Int, "plus")
            ISUB -> binaryCall(Types.Int, "minus")
            IMUL -> binaryCall(Types.Int, "times")
            IDIV -> binaryCall(Types.Int, "div")
            IREM -> binaryCall(Types.Int, "rem")
            ISHL -> binaryCall(Types.Int, "shl")
            ISHR -> binaryCall(Types.Int, "shr")
            IUSHR -> binaryCall(Types.Int, "ushr")
            IAND -> binaryCall(Types.Int, "and")
            IOR -> binaryCall(Types.Int, "or")
            IXOR -> binaryCall(Types.Int, "xor")
            INEG -> unaryCall(Types.Int, "unaryMinus")

            LADD -> binaryCall(Types.Long, "plus")
            LSUB -> binaryCall(Types.Long, "minus")
            LMUL -> binaryCall(Types.Long, "times")
            LDIV -> binaryCall(Types.Long, "div")
            LREM -> binaryCall(Types.Long, "rem")
            LSHL -> binaryCallShift(Types.Long, "shl", Types.Int)
            LSHR -> binaryCallShift(Types.Long, "shr", Types.Int)
            LUSHR -> binaryCallShift(Types.Long, "ushr", Types.Int)
            LAND -> binaryCall(Types.Long, "and")
            LOR -> binaryCall(Types.Long, "or")
            LXOR -> binaryCall(Types.Long, "xor")
            LNEG -> unaryCall(Types.Long, "unaryMinus")
            LCMP -> compareToCall(Types.Long)

            FADD -> binaryCall(Types.Float, "plus")
            FSUB -> binaryCall(Types.Float, "minus")
            FMUL -> binaryCall(Types.Float, "times")
            FDIV -> binaryCall(Types.Float, "div")
            FREM -> binaryCall(Types.Float, "rem")
            FNEG -> unaryCall(Types.Float, "unaryMinus")
            // todo choose the correct variant...
            FCMPL, FCMPG -> compareToCall(Types.Float)

            DADD -> binaryCall(Types.Double, "plus")
            DSUB -> binaryCall(Types.Double, "minus")
            DMUL -> binaryCall(Types.Double, "times")
            DDIV -> binaryCall(Types.Double, "div")
            DREM -> binaryCall(Types.Double, "rem")
            DNEG -> unaryCall(Types.Double, "unaryMinus")
            // todo choose the correct variant...
            DCMPL, DCMPG -> compareToCall(Types.Double)

            // number conversions
            I2L -> convertCall(Types.Int, Types.Long, "toLong")
            I2F -> convertCall(Types.Int, Types.Float, "toFloat")
            I2D -> convertCall(Types.Int, Types.Double, "toDouble")

            L2I -> convertCall(Types.Long, Types.Int, "toInt")
            L2F -> convertCall(Types.Long, Types.Float, "toFloat")
            L2D -> convertCall(Types.Long, Types.Double, "toDouble")

            F2I -> convertCall(Types.Float, Types.Int, "toInt")
            F2L -> convertCall(Types.Float, Types.Long, "toLong")
            F2D -> convertCall(Types.Float, Types.Double, "toDouble")

            D2I -> convertCall(Types.Double, Types.Int, "toInt")
            D2L -> convertCall(Types.Double, Types.Long, "toLong")
            D2F -> convertCall(Types.Double, Types.Float, "toFloat")

            I2B -> {
                visitLdcInsn(24)
                visitInsn(ISHL)
                visitLdcInsn(24)
                visitInsn(ISHR)
            }
            I2C -> {
                visitLdcInsn(16)
                visitInsn(ISHL)
                visitLdcInsn(16)
                visitInsn(IUSHR)
            }
            I2S -> {
                visitLdcInsn(16)
                visitInsn(ISHL)
                visitLdcInsn(16)
                visitInsn(ISHR)
            }

            AALOAD,
            IALOAD, LALOAD, FALOAD, DALOAD,
            BALOAD, SALOAD, CALOAD -> {
                // todo BooleanArray is encoded as BALOAD, too
                val baseType = when (opcode) {
                    AALOAD -> Types.Any
                    IALOAD -> Types.Int
                    LALOAD -> Types.Long
                    FALOAD -> Types.Float
                    DALOAD -> Types.Double
                    BALOAD -> Types.Byte
                    SALOAD -> Types.Short
                    CALOAD -> Types.Char
                    else -> error("Unreachable")
                }
                val index = pop().use()
                val array = pop().use()
                val dst = block.field(baseType)
                val method = findMethod(Types.Array.clazz, "get", Types.Int)
                val typeParams = ParameterList(Types.Array.clazz.typeParameters, listOf(baseType))
                val spec = Specialization(method.memberScope, typeParams)
                block.add(
                    JVMSimpleCall(
                        dst, method, array, spec,
                        listOf(index),
                        false, methodScope, origin
                    )
                )
                push(dst)
                convertToInt(baseType)
            }

            AASTORE,
            IASTORE, LASTORE, FASTORE, DASTORE,
            BASTORE, SASTORE, CASTORE -> {
                // todo BooleanArray is encoded as BALOAD, too
                val baseType = when (opcode) {
                    AASTORE -> Types.Any
                    IASTORE -> Types.Int
                    LASTORE -> Types.Long
                    FASTORE -> Types.Float
                    DASTORE -> Types.Double
                    BASTORE -> Types.Byte
                    SASTORE -> Types.Short
                    CASTORE -> Types.Char
                    else -> error("Unreachable")
                }
                convertFromInt(baseType)
                val value = pop().use()
                val index = pop().use()
                val array = pop().use()
                val dst = block.field(Types.Unit)
                val method = findMethod(
                    Types.Array.clazz, "set", Types.Int,
                    GenericType(Types.Array.clazz, Types.Array.clazz.typeParameters[0].name)
                )
                val paramTypes = ParameterList(Types.Array.clazz.typeParameters, listOf(baseType))
                val spec = Specialization(method.memberScope, paramTypes)
                val call = JVMSimpleCall(
                    dst, method, array, spec,
                    listOf(index, value),
                    false, methodScope, origin
                )
                block.add(call)
            }

            ARETURN, IRETURN, LRETURN, FRETURN, DRETURN -> {
                block.add(JVMSimpleReturn(pop().use(), methodScope, origin))
            }

            ATHROW -> {
                val thrown = pop().use()
                block.add(JVMSimpleThrow(thrown, methodScope, origin))
            }

            RETURN -> {
                // return unit
                val tmp = graph.field(Types.Unit)
                block.add(JVMSimpleGetObject(tmp, Types.Unit.clazz, methodScope, origin))
                block.add(JVMSimpleReturn(tmp.use(), methodScope, origin))
            }

            ARRAYLENGTH -> {
                val dst = graph.field(Types.Int)
                val array = pop().use()
                val field = findField(Types.Array.clazz, "size")
                    ?: error("Missing field 'size' in zauber.Array")
                val instr = JVMSimpleGetClassField(dst, array, field, methodScope, origin)
                block.add(instr)
                push(dst)
            }

            MONITORENTER -> {
                // todo add call...
                pop()
            }
            MONITOREXIT -> {
                // todo add call...
                pop()
            }

            // duplications
            // no .use() required, because we haven't read them yet
            POP -> {
                pop()
            }

            POP2 -> {
                val v1 = pop()
                if (!isType2(v1)) {
                    pop()
                }
            }

            DUP -> {
                push(peek().copy())
            }

            DUP_X1 -> {
                // ..., value2, value1 ->
                // ..., value1, value2, value1

                val v1 = pop()
                val v2 = pop()

                push(v1)
                push(v2)
                push(v1.copy())
            }

            DUP_X2 -> {

                val v1 = pop()

                if (isType2(v1)) {
                    // ..., value2, value1 ->
                    // ..., value1, value2, value1

                    val v2 = pop()

                    push(v1)
                    push(v2)
                    push(v1.copy())

                } else {

                    val v2 = pop()

                    if (isType2(v2)) {
                        // ..., value2, value1 ->
                        // ..., value1, value2, value1

                        push(v1)
                        push(v2)
                        push(v1.copy())

                    } else {
                        // ..., value3, value2, value1 ->
                        // ..., value1, value3, value2, value1

                        val v3 = pop()

                        push(v1)
                        push(v3)
                        push(v2)
                        push(v1.copy())
                    }
                }
            }

            DUP2 -> {

                val v1 = pop()

                if (isType2(v1)) {
                    // ..., value ->
                    // ..., value, value

                    push(v1)
                    push(v1.copy())

                } else {

                    val v2 = pop()

                    // ..., value2, value1 ->
                    // ..., value2, value1, value2, value1

                    push(v2)
                    push(v1)
                    push(v2.copy())
                    push(v1.copy())
                }
            }

            DUP2_X1 -> {

                val v1 = pop()

                if (isType2(v1)) {

                    // ..., value2, value1 ->
                    // ..., value1, value2, value1

                    val v2 = pop()

                    push(v1)
                    push(v2)
                    push(v1.copy())

                } else {

                    val v2 = pop()
                    val v3 = pop()

                    // ..., value3, value2, value1 ->
                    // ..., value2, value1, value3, value2, value1

                    push(v2)
                    push(v1)
                    push(v3)
                    push(v2.copy())
                    push(v1.copy())
                }
            }

            DUP2_X2 -> {

                val v1 = pop()

                if (isType2(v1)) {

                    val v2 = pop()

                    if (isType2(v2)) {

                        // ..., value2, value1 ->
                        // ..., value1, value2, value1

                        push(v1)
                        push(v2)
                        push(v1.copy())

                    } else {

                        // ..., value3, value2, value1 ->
                        // ..., value1, value3, value2, value1

                        val v3 = pop()

                        push(v1)
                        push(v3)
                        push(v2)
                        push(v1.copy())
                    }

                } else {

                    val v2 = pop()

                    if (isType2(v2)) {

                        // ..., value2, value1_2, value1_1 ->
                        // ..., value1_2, value1_1, value2, value1_2, value1_1

                        push(v1)
                        push(v2)
                        push(v1.copy())

                    } else {

                        val v3 = pop()

                        if (isType2(v3)) {

                            // ..., value3, value2, value1 ->
                            // ..., value2, value1, value3, value2, value1

                            push(v2)
                            push(v1)
                            push(v3)
                            push(v2.copy())
                            push(v1.copy())

                        } else {

                            // ..., value4, value3, value2, value1 ->
                            // ..., value2, value1, value4, value3, value2, value1

                            val v4 = pop()

                            push(v2)
                            push(v1)
                            push(v4)
                            push(v3)
                            push(v2.copy())
                            push(v1.copy())
                        }
                    }
                }
            }

            SWAP -> {
                val v1 = pop()
                val v2 = pop()

                push(v1)
                push(v2)
            }

            else -> TODO("Handle ${OpCode[opcode]}")
        }
    }

    fun pop() = stack.removeLast()
    fun peek() = stack.last()
    fun push(v: JVMSimpleField) {
        stack.add(v)
    }

    fun isType2(field: JVMSimpleField): Boolean {
        return isType2(field.type)
    }

    fun isType2(type: Type): Boolean {
        return type == Types.Long || type == Types.Double
    }

    fun convertCall(fromType: ClassType, toType: ClassType, name: String) {
        val p0 = pop().use()
        val dst = graph.field(toType)
        val method = findMethod(fromType.clazz, name)
        val spec = Specialization.fromSimple(method.memberScope)
        val call = JVMSimpleCall(
            dst, method, p0, spec, emptyList(),
            false, methodScope, origin
        )
        block.add(call)
        push(dst)
    }

    fun unaryCall(type: ClassType, name: String) {
        convertCall(type, type, name)
    }

    fun binaryCall(type: ClassType, name: String) {
        return binaryCall(type, name, type, type)
    }

    fun compareToCall(type: ClassType) {
        return binaryCall(type, "compareTo", type, Types.Int)
    }

    fun binaryCallShift(type: ClassType, name: String, argType: Type) {
        return binaryCall(type, name, argType, type)
    }

    fun binaryCall(type: ClassType, name: String, argType: Type, retType: Type) {
        val p1 = pop().use()
        val p0 = pop().use()
        val dst = graph.field(retType)
        val method = findMethod(type.clazz, name, argType)
        block.add(
            JVMSimpleCall(
                dst, method, p0, Specialization.fromSimple(method.memberScope),
                listOf(p1),
                false, methodScope, origin
            )
        )
        push(dst)
    }

    fun equalsCall(type: ClassType, negated: Boolean, dst: JVMSimpleField) {
        val p1 = pop()
        val p0 = pop()
        val method = findMethod(type.clazz, "equals", type) as Method
        val spec = Specialization.fromSimple(method.memberScope)
        val ctx = ResolutionContext.minimal.withSpec(spec)
        val method1 = ResolvedMethod(method, ctx, methodScope, MatchScore.zero)
        block.add(JVMSimpleCheckEquals(dst, p0, p1, negated, method1, methodScope, origin))
        push(dst)
    }

    fun equalsParams(expected: Array<out Type>, actual: List<Parameter>): Boolean {
        if (expected.size != actual.size) return false
        for (i in expected.indices) {
            val actualType = actual[i].type.resolvedName
            if (expected[i] != actualType) return false
        }
        return true
    }

    fun findMethod(clazz: Scope, name: String, vararg params: Type): MethodLike {
        val candidates = clazz[ScopeInitType.AFTER_DISCOVERY].methods
        return candidates.firstOrNull {
            it.name == name && equalsParams(params, it.valueParameters)
        } ?: error(
            "Missing $clazz.$name(${params.joinToString()}), " +
                    "candidates: ${if (candidates.any { it.name == name }) candidates.filter { it.name == name } else candidates}"
        )
    }

    fun pushConst(ne: NumberExpression, type: Type = Types.Int) {
        val dst = graph.field(type)
        block.add(JVMSimpleNumber(dst, ne, methodScope, origin))
        push(dst)
    }

    fun pushNull() {
        val dst = graph.field(NullType)
        block.add(JVMSimpleNull(dst, methodScope, origin))
        push(dst)
    }

    override fun visitIntInsn(opcode: Int, operand: Int) {
        LOGGER.debug("${OpCode[opcode]}($operand)")
        when (opcode) {
            BIPUSH -> {
                val ne = NumberExpression(operand.toString(), methodScope, origin)
                    .apply { resolvedType = Types.Int }
                pushConst(ne, Types.Int)
            }
            SIPUSH -> {
                val ne = NumberExpression(operand.toString(), methodScope, origin)
                    .apply { resolvedType = Types.Int }
                pushConst(ne, Types.Int)
            }
            NEWARRAY -> {
                val elementType = when (operand) {
                    T_BOOLEAN -> Types.Boolean
                    T_CHAR -> Types.Char
                    T_FLOAT -> Types.Float
                    T_DOUBLE -> Types.Double
                    T_BYTE -> Types.Byte
                    T_SHORT -> Types.Short
                    T_INT -> Types.Int
                    T_LONG -> Types.Long
                    else -> error("Unexpected operand: $operand")
                }
                createArray(elementType)
            }
            else -> error("Unexpected opcode ${OpCode[opcode]}")
        }
    }

    fun createArray(elementType: Type) {
        val arrayType = Types.Array.withTypeParameter(elementType)
        val size = pop().use()
        val dst = block.field(arrayType)
        val tmp = block.field(Types.Unit)
        val allocation = JVMSimpleAllocateInstance(dst, arrayType, methodScope, origin)
        allocation.valueParameters = listOf(size)
        block.add(allocation)
        dst.allocation = allocation // not really needed
        val constr = resolveConstructor(
            arrayType.clazz, "(Int)",
            ParameterList(arrayType),
            listOf(Types.Int)
        )
        val initCall = JVMSimpleCall(
            tmp, constr.resolved, dst.use(), constr.specialization,
            allocation.valueParameters, false, methodScope, origin
        )
        block.add(initCall)
        push(dst)
        println("creating array of $elementType, size: $size, dst: $dst")
    }

    fun convertToInt(type: Type) {
        when (type) {
            Types.Boolean -> convertBooleanToInt()
            Types.Byte -> convertCall(Types.Byte, Types.Int, "toInt")
            Types.Short -> convertCall(Types.Short, Types.Int, "toInt")
            Types.Char -> convertCall(Types.Char, Types.Int, "toInt")
        }
    }

    fun convertBooleanToInt() {
        convertCall(Types.Boolean, Types.Int, "toInt")
    }

    fun convertFromInt(type: Type) {
        when (type) {
            Types.Boolean -> convertIntToBoolean()
            Types.Byte -> convertCall(Types.Int, Types.Byte, "toByte")
            Types.Short -> convertCall(Types.Int, Types.Short, "toShort")
            Types.Char -> convertCall(Types.Int, Types.Char, "toChar")
        }
    }

    fun convertIntToBoolean() {
        convertCall(Types.Int, Types.Boolean, "toBoolean")
    }

    override fun visitFieldInsn(opcode: Int, owner: String, name: String, descriptor: String) {
        LOGGER.debug("${OpCode[opcode]}($owner.$name, $descriptor)")
        val fieldType = descToType(descriptor)
        var ownerType = nameToType(owner) as ClassType
        if (opcode == GETSTATIC || opcode == PUTSTATIC) {
            ownerType = ownerType.clazz
                .getOrPutCompanion().typeWithArgs2
        }
        when (opcode) {
            GETSTATIC -> {
                val dst = graph.field(fieldType)
                val self = graph.field(nameToType(owner))
                val clazz = ownerType.clazz[ScopeInitType.AFTER_DISCOVERY]
                block.add(JVMSimpleGetObject(self, clazz, methodScope, origin))
                val field = findField(clazz, name)
                    ?: error("Missing field '$name' in $ownerType, candidates: ${clazz.fields}, ${clazz.scopeInitType}")
                val instr = JVMSimpleGetClassField(dst, self, field, methodScope, origin)
                block.add(instr)
                push(dst)
                convertToInt(fieldType)
            }
            GETFIELD -> {
                // todo check non-null
                val dst = graph.field(fieldType)
                val self = pop().use()
                val field = findField(ownerType.clazz, name)
                    ?: error("Missing field '$name' in $ownerType")
                val instr = JVMSimpleGetClassField(dst, self, field, methodScope, origin)
                block.add(instr)
                push(dst)
                convertToInt(fieldType)
            }
            PUTSTATIC -> {
                convertFromInt(fieldType)
                val value = pop().use()
                val self = graph.field(nameToType(owner))
                block.add(JVMSimpleGetObject(self, ownerType.clazz, methodScope, origin))
                val field = findField(ownerType.clazz, name)
                    ?: error("Missing field '$name' in $ownerType")
                val instr = JVMSimpleSetClassField(self, field, value, methodScope, origin)
                block.add(instr)
            }
            PUTFIELD -> {
                // todo check non-null
                convertFromInt(fieldType)
                val value = pop().use()
                val self = pop().use()
                LOGGER.debug("$self.$name = $value")
                val field = findField(ownerType.clazz, name)
                    ?: error("Missing field '$name' in $ownerType")
                val instr = JVMSimpleSetClassField(self, field, value, methodScope, origin)
                block.add(instr)
            }
            else -> error("Unknown instruction ${OpCode[opcode]}")
        }
    }

    override fun visitVarInsn(opcode: Int, varIndex: Int) {

        // load or store a local variable...
        //  first N indices are the parameters incl. self if not static
        LOGGER.debug("visitVarInsn: ${OpCode[opcode]}, $varIndex")

        when (opcode) {
            ILOAD, LLOAD, FLOAD, DLOAD, ALOAD -> {
                val local = locals[varIndex]
                    ?: error("Missing local $varIndex in ${OpCode[opcode]} for $method")
                push(local.copy()) // copy to avoid creating multiple unrelated merged
            }
            ISTORE, LSTORE, FSTORE, DSTORE, ASTORE -> {
                val value = pop().use()
                locals[varIndex] = value
            }
            else -> error("Unsupported opcode ${OpCode[opcode]}")
        }
    }

    fun JVMSimpleField.copy(): JVMSimpleField {
        val dst = block.field(type)
        block.add(JVMSimpleRename(dst, this, scope, origin))
        dst.allocation = allocation
        return dst
    }

    fun findField(scope: Scope, name: String): Field? {
        val field = scope[ScopeInitType.AFTER_DISCOVERY].fields
            .firstOrNull { it.name == name }
        if (field != null) return field

        if (scope.isCompanionObject()) {
            val memberScope = scope.parent!!
            for (superCall in memberScope.superCalls) {
                if (!superCall.isInterfaceCall) {
                    return findField(superCall.type.clazz.getOrPutCompanion(), name)
                }
            }
        } else {
            for (superCall in scope.superCalls) {
                if (!superCall.isInterfaceCall) {
                    return findField(superCall.type.clazz, name)
                }
            }
        }

        return null
    }

    override fun visitIincInsn(varIndex: Int, increment: Int) {
        visitVarInsn(ILOAD, varIndex)
        visitLdcInsnInt(increment)
        visitInsn(IADD)
        visitVarInsn(ISTORE, varIndex)
    }

    override fun visitMultiANewArrayInsn(descriptor: String, numDimensions: Int) {
        check(descriptor.startsWith('['))
        LOGGER.debug("newMultiArray($descriptor, $numDimensions)")
        val arrayType = descToType(descriptor) as ClassType
        val dst = block.field(arrayType)
        val dimensions = List(numDimensions) { pop() }.asReversed()
        block.add(JVMSimpleMultiArray(dst, arrayType, dimensions, methodScope, origin))
        push(dst)
    }

    override fun visitLdcInsn(value: Any) {
        val type = when (value) {
            is Int -> Types.Int
            is Long -> Types.Long
            is Float -> Types.Float
            is Double -> Types.Double
            is String -> {
                val dst = graph.field(Types.String)
                block.add(JVMSimpleString(dst, value, methodScope, origin))
                push(dst)
                return
            }
            is org.objectweb.asm.Type -> {
                val type = nameToType(value.className) as ClassType
                val dst = graph.field(Types.ClassType.withTypeParameter(type))
                block.add(JVMSimpleType(dst, type, methodScope, origin))
                push(dst)
                return
            }
            else -> throw NotImplementedError("Load constant ${value.javaClass}")
        }

        val ne = NumberExpression(value.toString(), methodScope, origin)
        ne.resolvedType = type
        val dst = graph.field(type)
        block.add(JVMSimpleNumber(dst, ne, methodScope, origin))
        push(dst)
    }

    fun visitLdcInsnInt(value: Int) {
        val type = Types.Int
        val ne = NumberExpression(value.toString(), methodScope, origin)
        ne.resolvedType = type
        val dst = graph.field(type)
        block.add(JVMSimpleNumber(dst, ne, methodScope, origin))
        push(dst)
    }

    val blocksByLabel = HashMap<Label, JVMBlockExpression>()
    fun getBlockByLabel(label: Label): JVMBlockExpression {
        return blocksByLabel.getOrPut(label) { graph.addNode() }
    }

    override fun visitJumpInsn(opcode: Int, label: Label) {
        val currBlock = block
        val nextBlock = getBlockByLabel(label)

        LOGGER.debug("jump(${OpCode[opcode]}) ${currBlock.idStr()} -> ${nextBlock.idStr()}")

        if (opcode == GOTO) {
            check(currBlock.nextBranch == null)

            nextBlock.startStacks.add(ArrayList(stack))
            nextBlock.startLocals.add(HashMap(locals))
            currBlock.nextBranch = nextBlock
            return
        }

        val condition = graph.field(Types.Boolean)
        when (opcode) {
            IFEQ, IFNE -> {
                pushConst(Constants.i0)
                equalsCall(Types.Int, negated = opcode == IFNE, condition)
            }
            IFLT, IFGE, IFGT, IFLE -> {
                // todo remember the compared values, so we can simplify 1f < 2f to not require a compareTo call
                val tmp = pop().use()
                val compareType = when (opcode) {
                    IFLT -> CompareType.LESS
                    IFGT -> CompareType.GREATER
                    IFLE -> CompareType.LESS_EQUALS
                    IFGE -> CompareType.GREATER_EQUALS
                    else -> error("Unreachable")
                }
                block.add(JVMSimpleCompare(condition, tmp, null, compareType, methodScope, origin))
            }
            IF_ICMPEQ, IF_ICMPNE -> {
                equalsCall(Types.Int, negated = opcode == IF_ICMPNE, condition)
            }
            IF_ICMPLT, IF_ICMPGE, IF_ICMPGT, IF_ICMPLE -> {
                val p1 = pop().use()
                val p0 = pop().use()
                val compareType = when (opcode) {
                    IF_ICMPLT -> CompareType.LESS
                    IF_ICMPGT -> CompareType.GREATER
                    IF_ICMPLE -> CompareType.LESS_EQUALS
                    IF_ICMPGE -> CompareType.GREATER_EQUALS
                    else -> error("Unreachable")
                }
                block.add(JVMSimpleCompare(condition, p0, p1, compareType, methodScope, origin))
            }
            IF_ACMPEQ, IF_ACMPNE -> {
                val p1 = pop().use()
                val p0 = pop().use()
                val negate = opcode == IF_ACMPNE
                block.add(JVMSimpleCheckIdentical(condition, p0, p1, negate, methodScope, origin))
            }
            IFNULL,
            IFNONNULL -> {
                pushNull()
                val p1 = pop().use()
                val p0 = pop().use()
                val negate = opcode == IFNONNULL
                block.add(JVMSimpleCheckIdentical(condition, p0, p1, negate, methodScope, origin))
            }
            else -> error("Unexpected opcode ${OpCode[opcode]}")
        }

        if (method.name == "initSystemClassLoader") {
            println("$method: Setting condition to $condition from ${OpCode[opcode]}")
        }

        check(currBlock.branchCondition == null)
        check(currBlock.ifBranch == null) {
            "Cannot branch from ${currBlock.idStr()}, " +
                    "already linked to ${currBlock.ifBranch!!.idStr()}"
        }
        check(currBlock.elseBranch == null)

        val stack = ArrayList(stack)
        val locals = HashMap(locals)
        val elseBlock = graph.addNode()
        currBlock.branchCondition = condition.use()
        currBlock.ifBranch = nextBlock
        currBlock.elseBranch = elseBlock

        nextBlock.startStacks.add(stack)
        nextBlock.startLocals.add(locals)
        elseBlock.startStacks.add(stack)
        elseBlock.startLocals.add(locals)
        elseBlock.newStartStack = stack
        elseBlock.newStartLocals = locals
        block = elseBlock
    }

    fun needsLink(block: JVMBlockExpression): Boolean {
        if (block.nextBranch != null) return false
        val last = block.instructions.lastOrNull()
        return when (last) {
            is JVMSimpleReturn,
            is JVMSimpleThrow -> false
            else -> true
        }
    }

    fun frameTypeToType(type0: Any?): Type {
        return when (type0) {
            TOP -> UnknownType // todo what is that???
            INTEGER -> Types.Int
            FLOAT -> Types.Float
            LONG -> Types.Long
            DOUBLE -> Types.Double
            NULL -> NullType
            UNINITIALIZED_THIS -> method.ownerScope.typeWithArgs
            null -> UnknownType
            is String -> nameOrDescToType(type0)

            // todo we can lookup this label to find out what object it is: it was allocated there
            is Label -> UnknownType

            else -> throw NotImplementedError("What type is $type0 (${type0.javaClass})?")
        }
    }

    override fun visitFrame(type: Int, numLocal: Int, local1: Array<out Any?>, numStack: Int, stack1: Array<out Any?>) {
        check(type == F_NEW)

        val currBlock = block
        check(currBlock.instructions.isEmpty())

        // check prev-stack, whether this is needed
        if (lastStackContinued) {
            currBlock.startStacks.add(ArrayList(stack))
            currBlock.startLocals.add(HashMap(locals))
        }
        stack.clear()

        val newStack = List(numStack) { j ->
            val i = numStack - 1 - j // todo flip or not???
            val type = frameTypeToType(stack1[i])
            graph.field(type)
        }

        val newLocals = List(numLocal) { j ->
            val i = numLocal - 1 - j
            val type = frameTypeToType(local1[i])
            j to graph.field(type)
        }.toMap()

        if (LOGGER.isDebugEnabled) {
            LOGGER.debug(
                "visitFrame($type, " +
                        "[$numLocal, ${local1.asList()}] -> $newLocals, " +
                        "[$numStack, ${stack1.asList()}] -> $newStack, ${currBlock.idStr()})"
            )
        }

        stack.addAll(newStack)
        currBlock.newStartStack = newStack
        currBlock.newStartLocals = newLocals
    }

    override fun visitInvokeDynamicInsn(name: String, descriptor: String, method: Handle, vararg args: Any?) {
        if (method.owner == "java/lang/invoke/LambdaMetafactory" && (method.name == "metafactory" || method.name == "altMetafactory") && args.size >= 3) {
            LOGGER.info("visitInvokeDynamicInsn: $name, $descriptor, $method, ${args.asList()}")

            if (true) TODO()

            val (typeArgs, valueArgs, interfaceType) = parseMethodSignature(methodScope, descriptor, false)
            val interfaceMethod = findLambdaMethod((interfaceType as ClassType).clazz)
            assertEquals(name, interfaceMethod.name)

            val lambdaScope = classScope.generate(name, ScopeType.NORMAL_CLASS)
            lambdaScope.setEmptyTypeParams()

            // register interface as SuperCall
            lambdaScope.superCalls.add(SuperCall(interfaceType, null, null, origin))

            val constructorScope = lambdaScope.getOrCreatePrimaryConstructorScope()

            val constructorGraph = JVMGraph(constructorScope, false, origin)
            val setters = ArrayList<Expression>()

            // todo check this...
            val lambdaFields = valueArgs.map { param ->
                lambdaScope.addField(
                    null, false, isMutable = false,
                    param, param.name, param.type,
                    null, Flags.SYNTHETIC, origin
                )
            }

            if (valueArgs.isNotEmpty()) {
                val lambdaConstrFields = valueArgs.map { param ->
                    constructorGraph.field(param.type)
                }

                val instanceSelf0 = constructorGraph.thisField!!
                val instanceSelf = constructorGraph.field(instanceSelf0.type)
                val constrBlock = constructorGraph.startBlock

                for (i in lambdaFields.indices) {
                    constrBlock.add(
                        JVMSimpleSetClassField(
                            instanceSelf,
                            lambdaFields[i],
                            lambdaConstrFields[i],
                            constructorScope,
                            origin
                        )
                    )
                }
            }

            constructorScope.selfAsConstructor = Constructor(
                valueArgs, constructorScope,
                null, ExpressionList(setters, constructorScope, origin), Flags.SYNTHETIC, origin
            )

            val dst1 = args[1] as Handle
            val ownerType = nameToType(dst1.owner) as ClassType
            val staticScope = ownerType.clazz.getOrPutCompanion()

            val lambdaMethodScope = lambdaScope.getOrPut(interfaceMethod.name, ScopeType.METHOD)
            val lambdaMethod = Method(
                null, false, interfaceMethod.name,
                interfaceMethod.typeParameters.map { it.withScope(lambdaMethodScope) },
                interfaceMethod.valueParameters.map { it.withScope(lambdaMethodScope) },
                lambdaMethodScope, interfaceMethod.returnType,
                emptyList(), null, Flags.NONE, origin
            )
            lambdaMethodScope.selfAsMethod = lambdaMethod

            val joinedValueParams = lambdaFields.map { field ->
                FieldExpression(field, lambdaScope, origin)
            } + lambdaMethod.valueParameters.map { param ->
                FieldExpression(param.getOrCreateField(null, Flags.SYNTHETIC), lambdaMethodScope, origin)
            }
            val lambdaCallExpr = NamedCallExpression(
                ThisExpression(staticScope, methodScope, origin),
                interfaceMethod.name, emptyList(),
                emptyList(), joinedValueParams.map { NamedParameter(null, it) },
                methodScope, origin
            )
            lambdaMethod.body = ReturnExpression(lambdaCallExpr, null, methodScope, origin)

            val valueArgs2 = valueArgs
                .map { pop().use() }
                .asReversed()

            val dst = block.field(interfaceType)
            val unit = block.field(Types.Unit)
            val allocation = JVMSimpleAllocateInstance(dst, lambdaScope.typeWithArgs2, methodScope, origin)
            allocation.valueParameters = valueArgs2
            block.add(allocation)
            block.add(
                JVMSimpleCall(
                    unit, constructorScope.selfAsConstructor!!, dst,
                    Specialization.fromSimple(constructorScope), valueArgs2,
                    false, methodScope, origin
                )
            )
            push(dst)

        } else {
            throw NotImplementedError("Unknown lambda type: $method, ${args.toList()}")
        }
    }

    fun findLambdaMethod(interfaceScope: Scope): Method {
        interfaceScope[ScopeInitType.AFTER_OVERRIDES]
        val candidates = interfaceScope.methods.filter { it.isAbstract() }
        check(candidates.size == 1)
        return candidates.first()
    }

    var lastStackContinued = false
    override fun visitLabel(label: Label) {

        val currBlock = block
        val newBlock = getBlockByLabel(label)
        graph.blockOrder.add(newBlock) // needed for try-catch-blocks
        if (currBlock == newBlock) return // how can this happen??? e.g. after a GOTO

        val stack = ArrayList(stack)
        val locals = HashMap(locals)
        if (needsLink(currBlock)) {
            lastStackContinued = true

            if (LOGGER.isDebugEnabled) LOGGER.debug("visitLabel(${currBlock.idStr()} -> ${newBlock.idStr()})")

            currBlock.nextBranch = newBlock
            newBlock.startStacks.add(stack)
            newBlock.startLocals.add(locals)

        } else {
            lastStackContinued = false
            if (LOGGER.isDebugEnabled) LOGGER.debug("visitLabel(${currBlock.idStr()} -> x; new: ${newBlock.idStr()})")
        }

        newBlock.newStartStack = stack
        newBlock.newStartLocals = locals
        block = newBlock
    }

    override fun visitLineNumber(line: Int, start: Label) {
        LOGGER.debug("visitLineNumber: $line, start: ${getBlockByLabel(start).idStr()}")
    }

    override fun visitMethodInsn(
        opcode: Int, owner: String, name: String,
        descriptor: String, isInterface: Boolean
    ) {
        // what does the isInterface flag say? if the method's owner class is an interface.
        val (typeParameters, valueParameters, returnType) = parseMethodSignature(methodScope, descriptor, false)
        LOGGER.debug(
            "visitMethodInsn: ${OpCode[opcode]}, " +
                    "$owner.$name<${typeParameters.joinToString()}>(${valueParameters.joinToString()}): $returnType"
        )

        val valueParametersI = valueParameters
            .map { pop() }
            .asReversed()

        // resolve method
        val self: JVMSimpleField
        val method: ResolvedMember<*>

        when (opcode) {
            INVOKESPECIAL -> {
                // constructor or super.xyz()
                when (name) {
                    "<init>" -> {
                        self = pop().use()
                        if (self.allocation == null) {
                            println(
                                "calling constructor $owner, $descriptor, $valueParameters, " +
                                        "allocation is null :/, $self"
                            )
                        }
                        self.allocation?.valueParameters = valueParametersI
                        method = resolveConstructor(owner, descriptor, valueParameters)
                    }
                    else -> {
                        self = pop().use()
                        val scope = FirstJVMClassReader.getScope(owner, null)
                        method = resolveMethod(scope, name, descriptor, typeParameters, valueParameters)
                    }
                }
            }
            INVOKESTATIC -> {
                // call on companion object
                // println("Resolving static method $owner.$name$descriptor")
                method = resolveStaticMethod(owner, name, descriptor, typeParameters, valueParameters)
                val objectScope = method.resolved.scope.parent!!
                check(objectScope.isCompanionObject())
                self = block.field(objectScope.typeWithArgs).use()
                block.add(JVMSimpleGetObject(self, objectScope, methodScope, origin))
            }
            INVOKEVIRTUAL, // normal call on potentially open method
            INVOKEINTERFACE,
            INVOKE_LAMBDA -> {
                // interface call
                self = pop().use()
                method = resolveDynamicMethod(owner, name, descriptor, typeParameters, valueParameters)
            }
            else -> error("Unexpected opcode ${OpCode[opcode]}")
        }

        val needsResolution = when (opcode) {
            INVOKE_LAMBDA, INVOKEVIRTUAL, INVOKEINTERFACE -> true
            else -> false
        }

        val dst = block.field(returnType)
        block.add(
            JVMSimpleCall(
                dst, method.resolved, self, method.specialization,
                valueParametersI, needsResolution, methodScope, origin
            )
        )
        if (returnType != Types.Unit) {
            push(dst)
            convertToInt(dst.type)
        }
    }

    fun resolveStaticMethod(
        owner: String, name: String, descriptor: String,
        typeParameters: List<Parameter>, valueParameters: List<Parameter>,
    ): ResolvedMethod {
        val scope = FirstJVMClassReader.getScope(owner, null).getOrPutCompanion()
        return resolveMethod(scope, name, descriptor, typeParameters, valueParameters)
    }

    fun resolveDynamicMethod(
        owner: String, name: String, descriptor: String,
        typeParameters: List<Parameter>, valueParameters: List<Parameter>,
    ): ResolvedMethod {
        val ownerType = if (owner.startsWith('[') || owner.endsWith(';')) {
            descToType(owner)
        } else {
            nameToType(owner)
        }
        val scope = (ownerType as ClassType).clazz
        return resolveMethod(scope, name, descriptor, typeParameters, valueParameters)
    }

    fun resolveConstructor(
        owner: String, descriptor: String,
        valueParameters: List<Parameter>,
    ): ResolvedConstructor {
        val scope = FirstJVMClassReader.getScope(owner, null)
        return resolveConstructor1(scope, descriptor, valueParameters)
    }

    fun resolveConstructor1(
        scope: Scope, descriptor: String,
        valueParameters: List<Parameter>,
    ): ResolvedConstructor {
        return resolveConstructor(
            scope, descriptor,
            ParameterList.emptyParameterList(),
            valueParameters.map { it.type })
    }

    fun resolveConstructor(
        scope: Scope, descriptor: String,
        ownerTypes: ParameterList,
        valueParameters: List<Type>,
    ): ResolvedConstructor {
        // println("Resolving constructor ${scope.pathStr}$descriptor")
        val method = scope[ScopeInitType.AFTER_DISCOVERY]
            .constructors0
            .firstOrNull {
                // equals(typeParameters, it.typeParameters) && // checking valueParams is sufficient, and saves us work
                equals(valueParameters, it.valueParameters)
            }
            ?: error(
                "Missing constructor $scope<$ownerTypes>$descriptor -> " +
                        "(${valueParameters.joinToString { it.toString() }}), " +
                        "options: ${
                            scope.constructors0
                                .map { "(${valueParameters.joinToString { it.toString() }})" }
                        }"
            )
        val spec = Specialization(ClassType(scope, ownerTypes))
            .withScope(methodScope)
        val ctx = ResolutionContext(
            methodScope, null, spec, true, null,
            emptyMap(), emptyList()
        )
        return ResolvedConstructor(method, ctx, methodScope, MatchScore.zero)
    }

    fun resolveMethod(
        scope0: Scope, methodName: String, descriptor: String,
        typeParameters: List<Parameter>, valueParameters: List<Parameter>
    ): ResolvedMethod {
        var scope = scope0
        while (true) {

            val method = scope[ScopeInitType.AFTER_DISCOVERY]
                .methods.firstOrNull {
                    it.name == methodName &&
                            // equals(typeParameters, it.typeParameters) && // checking valueParams is sufficient, saves work
                            equals1(valueParameters, it.valueParameters)
                }
            if (method != null) {
                return resolveMethodI(scope, typeParameters, method)
            }

            // check super classes
            scope = if (scope.isCompanionObject()) {
                scope.parent!![ScopeInitType.AFTER_DISCOVERY]
                    .superCalls
                    .firstOrNull { it.isClassCall }
                    ?.type?.clazz
                    ?.getOrPutCompanion()
                    ?: break
            } else {
                scope.superCalls
                    .firstOrNull { it.isClassCall || scope.isInterface() }
                    ?.type?.clazz
                    ?: break
            }
        }

        val sameNameOptions = scope0.methods
            .filter { it.name == methodName }
            .map { "(${it.valueParameters.joinToString { vp -> vp.type.toString() }})" }
        val nameOptions = scope0.methods
            .map { style("\"${it.name}\"", GREEN) }
            .distinct().sorted()

        error(
            "Missing $scope0.$methodName, $descriptor -> " +
                    "(${valueParameters.joinToString { it.type.toString() }}), " +
                    "options: $sameNameOptions, $nameOptions"
        )
    }

    fun resolveMethodI(scope: Scope, typeParameters: List<Parameter>, method: Method): ResolvedMethod {
        val typeParams = ParameterList(
            scope.declaredTypeParameters,
            typeParameters.map { it.type }.ifEmpty { scope.typeParameters.map { it.type } }
        )
        val spec = Specialization.withScopeUnknownIfMissing(method.memberScope, typeParams)
        val ctx = ResolutionContext(
            methodScope, null, spec, true, null,
            emptyMap(), emptyList()
        )
        return ResolvedMethod(method, ctx, methodScope, MatchScore.zero)
    }

    fun equals1(expected: List<Parameter>, actual: List<Parameter>): Boolean {
        if (expected.size != actual.size) {
            // println("Size-Mismatch: $expected != $actual")
            return false
        }
        for (i in expected.indices) {
            var expected = expected[i].type.resolvedName
            var actual = actual[i].type.resolvedName
            if (actual is GenericType) actual = actual.superBounds
            if (expected is ClassType && expected.typeParameters != null) expected = expected.withTypeParameters(null)
            if (actual is ClassType && actual.typeParameters != null) actual = actual.withTypeParameters(null)
            if (expected != actual) {
                // println("Mismatch@$i: $expected != $actual (${expected.javaClass.simpleName} vs ${actual.javaClass.simpleName})")
                return false
            }
        }
        return true
    }

    fun equals(expected: List<Type>, actual: List<Parameter>): Boolean {
        if (expected.size != actual.size) {
            return false
        }
        for (i in expected.indices) {
            val expected = expected[i].resolvedName
            var actual = actual[i].type.resolvedName
            if (actual is GenericType) {
                actual = actual.superBounds
            }
            if (!equals(expected, actual)) {
                // LOGGER.info("Mismatch@$i: $expected != $actual")
                return false
            }
        }
        return true
    }

    fun equals(expected: Type, actual: Type): Boolean {
        if (expected is ClassType && actual is ClassType) {
            // generic-specifics aren't supported in Java, so we can just skip them
            return expected.clazz == actual.clazz
            /*if (expected.clazz != actual.clazz) return false
            val ts = expected.clazz.typeParameters.size
            val ep = expected.typeParameters ?: List(ts) { UnknownType }
            val ap = actual.typeParameters ?: List(ts) { UnknownType }
            assertEquals(ep.size, ap.size) {
                "Parameter mismatch in $expected =?= $actual"
            }
            return ep.indices.all {
                equals(ep[it], ap[it])
            }*/
        }
        if (expected is ClassType && actual is GenericType) {
            return actual.superBounds == expected
        }
        return expected == actual
    }

    override fun visitLookupSwitchInsn(defaultLabel: Label, keys: IntArray, labels: Array<out Label>) {
        if (LOGGER.isDebugEnabled) LOGGER.debug("visitLookupSwitchInsn: $defaultLabel, ${keys.asList()}, ${labels.asList()}")
        check(keys.size == labels.size)

        for (i in keys.indices) {
            visitInsn(DUP)
            visitLdcInsnInt(keys[i])
            visitJumpInsn(IF_ICMPEQ, labels[i])
        }
        visitJumpInsn(GOTO, defaultLabel)
    }

    override fun visitTableSwitchInsn(min: Int, max: Int, defaultLabel: Label, vararg labels: Label) {
        if (LOGGER.isDebugEnabled) LOGGER.debug("visitTableSwitchInsn: $min, $max, $defaultLabel, ${labels.asList()}")
        check(labels.size == max - min + 1)

        for (i in labels.indices) {
            visitInsn(DUP)
            visitLdcInsnInt(min + i)
            visitJumpInsn(IF_ICMPEQ, labels[i])
        }
        visitJumpInsn(GOTO, defaultLabel)
    }

    override fun visitTypeInsn(opcode: Int, type: String) {
        val type1 = if (type.startsWith('[') || type.endsWith(';')) {
            descToType(type)
        } else {
            nameToType(type)
        }

        val scope = (type1 as ClassType).clazz
        LOGGER.debug("visitTypeInsn: ${OpCode[opcode]}, type: $type -> $scope")
        when (opcode) {
            NEW -> {
                val dst = block.field(type1)
                val allocation = JVMSimpleAllocateInstance(dst, type1, methodScope, origin)
                block.add(allocation)
                dst.allocation = allocation
                push(dst)
            }
            ANEWARRAY -> {
                createArray(type1)
            }
            CHECKCAST -> {
                val currBlock = block
                val value = peek().use()
                val condition = currBlock.field(Types.Boolean)
                currBlock.add(JVMSimpleInstanceOf(condition, value, type1, methodScope, origin))

                val ifBlock = graph.addNode()
                val elseBlock = graph.addNode()
                currBlock.ifBranch = ifBlock
                currBlock.elseBranch = elseBlock
                currBlock.branchCondition = condition.use()

                val thrownType = Types.ClassCastException
                val thrown = currBlock.field(thrownType)
                val allocation = JVMSimpleAllocateInstance(thrown, thrownType, methodScope, origin)
                allocation.valueParameters = emptyList()
                elseBlock.add(allocation)

                val constrScope = thrownType.clazz[ScopeInitType.AFTER_DISCOVERY].children
                    .firstOrNull { it.selfAsConstructor?.valueParameters?.size == 0 }
                    ?: error("Missing constructor for $thrownType without type-args")
                val constr = constrScope.selfAsConstructor!!
                val constrSpec = Specialization.fromSimple(constrScope)

                val unit = block.field(Types.Unit)
                val constrCall = JVMSimpleCall(
                    unit, constr, thrown, constrSpec,
                    emptyList(), false, methodScope, origin
                )
                elseBlock.add(constrCall)
                elseBlock.add(JVMSimpleThrow(thrown, methodScope, origin))

                block = ifBlock
            }
            INSTANCEOF -> {
                val value = pop().use()
                val dst = block.field(Types.Boolean)
                block.add(JVMSimpleInstanceOf(dst, value, type1, methodScope, origin))
                push(dst)
                convertBooleanToInt()
            }
        }
    }

    override fun visitTryCatchBlock(start: Label, end: Label, handler: Label, type: String?) {
        graph.tryCatchBlocks.add(
            JVMTryCatchBlock(
                getBlockByLabel(start),
                getBlockByLabel(end),
                getBlockByLabel(handler),
                nameToType(type ?: "java/lang/Throwable")
            )
        )
    }

    fun setAllFieldsToNull() {
        val fields = method.ownerScope.fields
        if (fields.isNotEmpty()) {
            val nulls = HashMap<Type, JVMSimpleField>()
            val block = graph.startBlock

            val self = if (isStatic) {
                val selfScope = method.ownerScope
                val self = block.field(selfScope.typeWithArgs)
                block.add(JVMSimpleGetObject(self, selfScope, methodScope, origin))
                self
            } else {
                graph.thisField!!
            }

            for (field in fields) {
                val fieldType = field.resolveValueType(ResolutionContext.minimal)
                val value = nulls.getOrPut(fieldType) {
                    val tmp = block.field(fieldType)
                    block.add(JVMSimpleNull(tmp, methodScope, origin))
                    tmp
                }
                block.add(JVMSimpleSetClassField(self, field, value, methodScope, origin))
            }
        }
    }

    override fun visitEnd() {

        if (method is Constructor) {
            setAllFieldsToNull()
        }

        method.body = graph

        if (LOGGER.isDebugEnabled) LOGGER.debug("Finished $method")
    }
}