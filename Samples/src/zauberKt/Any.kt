package zauberKt

class Any {
    open fun toString(): String = native("toStringNative(this)")
    open fun hashCode(): Int = native("systemHashCode(this)")
}