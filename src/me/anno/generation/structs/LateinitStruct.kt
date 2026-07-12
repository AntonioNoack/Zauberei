package me.anno.generation.structs

abstract class LateinitStruct<P> {
    var sizeInBytes = 0
    val properties = ArrayList<P>()
}