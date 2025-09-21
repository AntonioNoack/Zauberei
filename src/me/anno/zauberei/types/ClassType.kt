package me.anno.zauberei.types

class ClassType(val clazz: Package) : Type() {
    override fun toString(): String {
        return clazz.path.toString()
    }
}