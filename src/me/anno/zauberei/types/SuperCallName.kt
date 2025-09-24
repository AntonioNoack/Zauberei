package me.anno.zauberei.types

class SuperCallName(val name: String, val imports: List<Import>) {
    var resolved: Type? = null
}