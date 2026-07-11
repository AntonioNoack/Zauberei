package me.anno.generation.java

import me.anno.zauber.scope.Scope

class Import2(val path: List<String>, val scope: Scope?) {
    override fun toString(): String {
        return path.joinToString("/")
    }
}