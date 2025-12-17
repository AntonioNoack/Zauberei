package me.anno.zauberei.generator.c

import me.anno.zauberei.types.ClassType
import me.anno.zauberei.types.Scope
import me.anno.zauberei.types.ScopeType
import java.io.File

object CSourceGenerator {

    // todo generate runnable C code from what we parsed
    // todo just produce all code for now as-is

    // todo we need .h and .c files...

    fun getName(scope: Scope): String = scope.path.joinToString("_")

    fun generateCode(dst: File, root: Scope) {
        dst.deleteRecursively()

        val builder = StringBuilder()

        var depth = 0
        fun indent() {
            repeat(depth) { builder.append("  ") }
        }

        fun generateCode(scope: Scope) {
            when (scope.scopeType) {
                ScopeType.PACKAGE, null -> {
                    if (scope.name != null) {
                        indent()
                        builder.append("// package ").append(scope.name).append('\n')
                        depth++
                    }
                    for (child in scope.children) {
                        generateCode(child)
                    }
                    if (scope.name != null) depth--
                }
                ScopeType.NORMAL_CLASS, ScopeType.ENUM_CLASS, ScopeType.INTERFACE, ScopeType.OBJECT -> {
                    indent()
                    builder.append("struct ").append(getName(scope)).append(" {\n")
                    depth++

                    for (parent in scope.superCalls) {
                        val pt = parent.type as ClassType
                        indent()
                        builder.append("struct ").append(getName(pt.clazz)).append(" super").append(pt.clazz.name)
                            .append(";\n")
                    }

                    // todo append proper type...
                    for (field in scope.fields) {
                        // if (field.selfType != null) continue

                        indent()
                        when (val type = field.valueType) {
                            is ClassType -> builder.append(getName(type.clazz))
                            is Scope -> builder.append(getName(type))
                            null -> builder.append("void /* null */")
                            else -> builder.append("void /* $type */")
                        }
                        builder.append("* ").append(field.name).append(";\n")
                    }

                    // todo interfaces as fields
                    // todo super class as a field

                    depth--
                    indent()
                    builder.append("}\n")
                }
                ScopeType.TYPE_ALIAS -> {} // nothing to do
                else -> {
                    indent()
                    builder.append("// todo: ").append(scope.name).append(" (${scope.scopeType})").append('\n')
                }
            }
        }

        generateCode(root)

        val dstFile = File(dst, "Root.c")
        dstFile.parentFile.mkdirs()
        dstFile.writeText(builder.toString())
    }
}