package me.anno.zauberei.typeresolution

import me.anno.zauberei.types.Type
import me.anno.zauberei.types.impl.UnionType.Companion.unionTypes

class FillInParameterList(override val size: Int) : List<Type?> {

    val types = arrayOfNulls<Type>(size)

    /**
     * return types are weak indicators, while parameters are strong indicators;
     * weak indicators are completely overridden by strong indicators;
     * when the strength is the same, the types need to be union-ed.
     * */
    val isStrong = BooleanArray(size)

    fun union(index: Int, newType: Type, valueIsStrong: Boolean) {
        if (isStrong[index] == valueIsStrong) {
            val oldType = types[index]
            types[index] = if (oldType != null) unionTypes(newType, oldType) else newType
        } else if (valueIsStrong) {
            types[index] = newType
            isStrong[index] = true
        } // else ignored
    }

    override fun isEmpty(): Boolean = size == 0
    override fun contains(element: Type?): Boolean = element in types
    override fun iterator(): Iterator<Type?> = types.iterator()
    override fun containsAll(elements: Collection<Type?>): Boolean = types.asList().containsAll(elements)

    override fun get(index: Int): Type? = types[index]
    override fun indexOf(element: Type?): Int = types.indexOf(element)
    override fun lastIndexOf(element: Type?): Int = types.lastIndexOf(element)
    override fun listIterator(): ListIterator<Type?> = listIterator(0)
    override fun listIterator(index: Int): ListIterator<Type?> = types.asList().listIterator(index)
    override fun subList(fromIndex: Int, toIndex: Int): List<Type?> = types.asList().subList(fromIndex, toIndex)

}