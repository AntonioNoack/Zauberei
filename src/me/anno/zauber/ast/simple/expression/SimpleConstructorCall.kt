package me.anno.zauber.ast.simple.expression

import me.anno.utils.StringStyles.ORANGE
import me.anno.utils.StringStyles.style
import me.anno.zauber.ast.rich.member.Constructor
import me.anno.zauber.ast.rich.member.MethodLike
import me.anno.zauber.ast.simple.SimpleGraph
import me.anno.zauber.ast.simple.fields.SimpleField
import me.anno.zauber.ast.simple.fields.SimpleInstruction
import me.anno.zauber.interpreting.BlockReturn
import me.anno.zauber.interpreting.Instance
import me.anno.zauber.interpreting.Runtime.Companion.runtime
import me.anno.zauber.scope.Scope
import me.anno.zauber.types.Specialization
import me.anno.zauber.types.Type
import me.anno.zauber.types.Types
import me.anno.zauber.types.impl.ClassType

class SimpleConstructorCall(
    unusedDst: SimpleField,
    val forAllocation: Boolean,
    self: SimpleField,
    specialization: Specialization,
    valueParameters: List<SimpleField>,
    scope: Scope, origin: Long
) : SimpleCallable(unusedDst, self, specialization, valueParameters, scope, origin) {

    val method get() = sample

    init {
        check(method is Constructor)
        check(method.valueParameters.size == valueParameters.size)
        check(self.type is ClassType) {
            "Cannot construct ${self.type} (${self.type.javaClass.simpleName})"
        }
        check(self.type.clazz.isClassLike()) { "Cannot invoke constructor on self $self" }
    }

    override fun toString(): String {
        return "$thisInstance.${style("new", ORANGE)} ${method.ownerScope}" +
                valueParameters.joinToString(", ", "(", ")")
    }

    override fun hasInput(field: SimpleField): Boolean {
        return thisInstance == field || field in valueParameters
    }

    override fun clone(src: SimpleGraph, dst: SimpleGraph): SimpleInstruction {
        return SimpleConstructorCall(
            src.cloned(this.dst, dst), forAllocation,
            src.cloned(thisInstance, dst),
            specialization, valueParameters.map { src.cloned(it, dst) },
            scope, origin
        )
    }

    override fun execute() = eval()
    override fun eval(): BlockReturn {
        val runtime = runtime
        val thisInstance = runtime[thisInstance]
        check((thisInstance.clazz.type as ClassType).clazz.isClassLike()) {
            "Cannot invoke constructor on $thisInstance"
        }

        initializeArrayIfNeeded(thisInstance, method)

        return runtime.executeCall(
            thisInstance, null,
            specialization, valueParameters, origin
        ).retToVal()
    }

    fun initializeArrayIfNeeded(self: Instance, method: MethodLike) {
        val selfType = self.clazz.type.resolvedName
        if (selfType is ClassType && selfType.clazz.pathStr == "zauber.Array" &&
            method is Constructor && valueParameters.size == 1 &&
            method.valueParameters[0].type.resolvedName == Types.Int
        ) {
            val sizeParam = valueParameters[0]
            val size = runtime[sizeParam].castToInt()
            self.rawValue = createArray(selfType.typeParameters?.get(0)?.resolvedName, size)
        }
    }

    fun createArray(type: Type?, size: Int): Any {
        return when (type) {
            Types.Boolean -> BooleanArray(size)
            Types.Byte, Types.UByte -> ByteArray(size)
            Types.Short, Types.UShort, Types.Half -> ShortArray(size)
            Types.Int, Types.UInt -> IntArray(size)
            Types.Long, Types.ULong -> LongArray(size)
            Types.Float -> FloatArray(size)
            Types.Double -> DoubleArray(size)
            else -> {
                val nullValue = runtime.getNull()
                Array(size) { nullValue }
            }
        }
    }
}