package me.anno.zauberei.generator.c

import me.anno.zauberei.astbuilder.expression.Expression
import me.anno.zauberei.astbuilder.expression.ExpressionList
import me.anno.zauberei.astbuilder.expression.VariableExpression
import me.anno.zauberei.astbuilder.flow.IfElseBranch
import me.anno.zauberei.astbuilder.flow.ReturnExpression
import me.anno.zauberei.astbuilder.flow.WhileLoop
import me.anno.zauberei.types.Scope
import me.anno.zauberei.types.ScopeType
import me.anno.zauberei.types.Type
import me.anno.zauberei.types.impl.ClassType
import java.io.File

object CSourceGenerator {

    // todo generate runnable C code from what we parsed
    // todo just produce all code for now as-is

    // todo we need .h and .c files...

    fun getName(scope: Scope?): String {
        scope ?: return "void /*???*/"
        return scope.path.joinToString("_")
            .replace("\$f:", "M_")
    }

    fun getName(scope: Type?): String {
        return when (scope) {
            is ClassType -> getName(scope.clazz)
            null -> "void /*???*/"
            else -> "void /* $scope */"
        }
    }

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
                    if (scope.name != "*") depth--
                }
                ScopeType.NORMAL_CLASS, ScopeType.ENUM_CLASS, ScopeType.INTERFACE, ScopeType.OBJECT -> {
                    writeClassReflectionStruct(scope)
                    writeClassInstanceStruct(scope)
                }
                ScopeType.TYPE_ALIAS -> {} // nothing to do
                ScopeType.METHOD -> {
                    writeMethod(scope)
                }
                else -> {
                    indent()
                    builder.append("// todo: ").append(scope.name).append(" (${scope.scopeType})").append('\n')
                }
            }
            for (child in scope.children) {
                generateCode(child)
            }
        }

        generateCode(root)

        val dstFile = File(dst, "Root.c")
        dstFile.parentFile.mkdirs()
        dstFile.writeText(builder.toString())
    }

    fun open() {
        indent()
        depth++
    }

    fun close() {
        depth--
        indent()
    }

    fun block(run: () -> Unit) {
        open()
        run()
        close()
        builder.append("}\n")
    }

    fun writeMethod(scope: Scope) {
        block {
            val self = scope.selfAsMethod!!
            val returnType = self.returnType

            builder.append("struct ").append(getName(returnType))
            builder.append(if (returnType.isValueType()) " " else "* ")
            builder.append(getName(scope)).append("(")

            // todo append context parameters, e.g. self

            depth++
            builder.append("\n")
            indent()
            builder.append("struct ")
                .append(getName(scope.parent))
                .append("* ")
                .append("__this")

            for (param in self.valueParameters) {
                if (!builder.endsWith("(")) builder.append(", ")
                builder.append("\n")
                indent()
                builder.append("struct ")
                    .append(getName(param.type))
                    .append(if (param.type.isValueType()) " " else "* ")
                    .append(param.name)
            }
            depth--

            builder.append(") {\n")

            fun writeExpr(expr: Expression, needsValue: Boolean) {
                when (expr) {
                    is IfElseBranch -> {
                        block {
                            builder.append(if (needsValue) "(" else "if (")
                            writeExpr(expr.condition, true)
                            builder.append(if (needsValue) ")" else ") {\n")
                            indent()
                            writeExpr(expr.ifBranch, needsValue)
                            if (expr.elseBranch != null) {
                                builder.append(if (needsValue) ") : (" else "} else {\n")
                                indent()
                                writeExpr(expr.elseBranch, needsValue)
                                if (needsValue) builder.append(")")
                            }
                        }
                    }
                    is WhileLoop -> {
                        block {
                            builder.append("while (")
                            writeExpr(expr.condition, true)
                            builder.append(") {\n")
                            indent()
                            writeExpr(expr.body, false)
                        }
                    }
                    is ExpressionList -> {
                        // check(!needsValue) // todo if needs value, we somehow need to extract the last one...
                        for (entry in expr.list) {
                            builder.append("\n")
                            indent()
                            writeExpr(entry, needsValue)
                        }
                    }
                    is ReturnExpression -> {
                        val value = expr.value
                        if (value != null) {
                            builder.append("return ")
                            writeExpr(value, true)
                            builder.append(";\n")
                        } else {
                            builder.append("return;\n")
                        }
                    }
                    is VariableExpression -> {
                        builder.append(expr.name)
                    }
                    else -> {
                        RuntimeException(" Write ${expr.javaClass}: $expr")
                            .printStackTrace()
                        builder.append("/* $expr */")
                    }
                }
            }

            val body = self.body
            if (body != null) {
                writeExpr(body, false)
            }

        }
    }

    fun writeClassReflectionStruct(scope: Scope) {
        block {
            builder.append("struct ").append(getName(scope)).append("_Class {\n")

            // super class as a field
            for (parent in scope.superCalls) {
                // todo make them structs??? can already have resolved methods
                // todo include all to-be-resolved (open/interface) methods
                val pt = parent.type as ClassType
                indent()
                builder.append("struct ").append(getName(pt.clazz)).append("_Class super").append(pt.clazz.name)
                    .append(";\n")
            }
        }
    }

    fun writeClassInstanceStruct(scope: Scope) {
        block {
            builder.append("struct ").append(getName(scope)).append(" {\n")

            // super class as a field
            for (parent in scope.superCalls) {
                // only primary super instance is needed, the rest should be put into Class
                if (parent.valueParams == null) continue
                val pt = parent.type as ClassType
                indent()
                builder.append("struct ").append(getName(pt.clazz)).append(" superStruct;\n")
            }

            // todo append proper type...
            for (field in scope.fields) {
                // if (field.selfType != null) continue

                indent()
                builder.append("struct ")
                val type = field.valueType
                when (type) {
                    is ClassType -> builder.append(getName(type.clazz))
                    null -> builder.append("void /* null */")
                    else -> builder.append("void /* $type */")
                }
                builder.append(if (type.isValueType()) " " else "* ")
                builder.append(field.name).append(";\n")
            }

            // todo interfaces as fields
        }
    }

    // todo "value" should also be a property for fields to embed them into the class

    fun Type?.isValueType(): Boolean {
        if (this == null) return false
        if (this is ClassType) {
            return "value" in clazz.keywords
        }
        return false
    }
}