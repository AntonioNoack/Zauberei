package zauberKt

import zauberKt.types.Self
import kotlin.reflect.KClass

class Any {
    open fun toString(): String = native("toStringNative(this)")
    open fun hashCode(): Int = native("systemHashCode(this)")

    private external val clazz: KClass<Self>
}