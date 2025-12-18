package me.anno.zauberei.generator.c

import me.anno.zauberei.types.ClassType
import me.anno.zauberei.types.Scope
import me.anno.zauberei.types.ScopeType
import me.anno.zauberei.types.Type
import java.io.File

object CSourceGenerator {

    // todo generate runnable C code from what we parsed
    // todo just produce all code for now as-is

    // todo we need .h and .c files...

    fun getName(scope: Scope): String = scope.path.joinToString("_")

    val builder = StringBuilder()

    var depth = 0
    fun indent() {
        repeat(depth) { builder.append("  ") }
    }

    fun generateCode(dst: File, root: Scope) {
        builder.clear()
        dst.deleteRecursively()

        fun generateCode(scope: Scope) {
            when (scope.scopeType) {
                ScopeType.PACKAGE, null -> {
                    if (scope.name != "*") {
                        indent()
                        builder.append("// package ").append(scope.name).append('\n')
                        depth++
                    }
                    for (child in scope.children) {
                        generateCode(child)
                    }
                    if (scope.name != "*") depth--
                }
                ScopeType.NORMAL_CLASS, ScopeType.ENUM_CLASS, ScopeType.INTERFACE, ScopeType.OBJECT -> {
                    writeClassReflectionStruct(scope)
                    writeClassInstanceStruct(scope)
                }
                ScopeType.TYPE_ALIAS -> {} // nothing to do
                else -> {
                    indent()
                    builder.append("// todo: ").append(scope.name).append(" (${scope.scopeType})").append('\n')
                }
            }
            // todo write all methods...
        }

        generateCode(root)

        val dstFile = File(dst, "Root.c")
        dstFile.parentFile.mkdirs()
        dstFile.writeText(builder.toString())
    }

    fun writeClassReflectionStruct(scope: Scope) {
        indent()
        builder.append("struct ").append(getName(scope)).append("_Class {\n")
        depth++

        // super class as a field
        for (parent in scope.superCalls) {
            // todo make them structs??? can already have resolved methods
            // todo include all to-be-resolved (open/interface) methods
            val pt = parent.type as ClassType
            indent()
            builder.append("struct ").append(getName(pt.clazz)).append("_Class super").append(pt.clazz.name)
                .append(";\n")
        }

        depth--
        indent()
        builder.append("}\n")
    }

    fun writeClassInstanceStruct(scope: Scope) {
        indent()
        builder.append("struct ").append(getName(scope)).append(" {\n")
        depth++

        // super class as a field
        for (parent in scope.superCalls) {
            // only primary super instance is needed, the rest should be put into Class
            if (parent.params == null) continue
            val pt = parent.type as ClassType
            indent()
            builder.append("struct ").append(getName(pt.clazz)).append(" super").append(pt.clazz.name)
                .append(";\n")
        }

        // todo append proper type...
        for (field in scope.fields) {
            // if (field.selfType != null) continue

            indent()
            builder.append("struct ")
            val type = field.valueType
            when (type) {
                is ClassType -> builder.append(getName(type.clazz))
                is Scope -> builder.append(getName(type))
                null -> builder.append("void /* null */")
                else -> builder.append("void /* $type */")
            }
            if (!type.isValueType()) {
                builder.append("* ")
            } else builder.append(' ')
            builder.append(field.name).append(";\n")
        }

        // todo interfaces as fields

        depth--
        indent()
        builder.append("}\n")
    }

    // todo "value" should also be a property for fields to embed them into the class

    fun Type?.isValueType(): Boolean {
        if (this == null) return false
        if (this is ClassType) return clazz.isValueType()
        if (this !is Scope) return false
        return "value" in this.keywords
    }
}