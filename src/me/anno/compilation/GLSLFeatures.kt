package me.anno.compilation

class GLSLFeatures {
    /**
     * support for printing strings on a by-char level
     * */
    var strings = false

    /**
     * to share high level objects with the CPU and between multiple passes
     * */
    var gc = false

    /**
     * for recursive functions
     * */
    var stack = false
}