package me.anno.zauber.expansion

import me.anno.zauber.ast.rich.Flags
import me.anno.zauber.ast.rich.controlflow.ReturnExpression
import me.anno.zauber.ast.rich.expression.ExpressionList
import me.anno.zauber.ast.rich.expression.resolved.ThisExpression
import me.anno.zauber.ast.rich.expression.unresolved.CallExpression
import me.anno.zauber.ast.rich.expression.unresolved.NamedCallExpression
import me.anno.zauber.ast.rich.expression.unresolved.UnresolvedFieldExpression
import me.anno.zauber.ast.rich.member.Constructor
import me.anno.zauber.ast.rich.member.Method
import me.anno.zauber.ast.rich.parameter.InnerSuperCall
import me.anno.zauber.ast.rich.parameter.InnerSuperCallTarget
import me.anno.zauber.ast.rich.parameter.NamedParameter
import me.anno.zauber.ast.rich.parameter.Parameter
import me.anno.zauber.logging.LogManager
import me.anno.zauber.scope.Scope
import me.anno.zauber.scope.ScopeInit
import me.anno.zauber.scope.ScopeInitType
import me.anno.zauber.scope.ScopeType
import me.anno.zauber.types.impl.GenericType

object DefaultParameters {

    private val LOGGER = LogManager.getLogger(DefaultParameters::class)

    val defaultParameterCreator = ScopeInit(ScopeInitType.DEFAULT_PARAMETERS) { scope: Scope ->
        createDefaultParameterMethodsImpl(scope)
    }

    private fun createDefaultParameterMethodsImpl(scope: Scope) {
        val children = scope.children
        for (i in children.indices) {
            val child = children[i]
            child[ScopeInitType.AFTER_DISCOVERY]
            when (child.scopeType) {
                ScopeType.METHOD -> createDefaultParameterMethod(child)
                ScopeType.CONSTRUCTOR -> createDefaultParameterConstructor(child)
                else -> {}
            }
        }
    }

    private fun createDefaultParameterMethod(methodScope: Scope) {
        val scopeParent = methodScope.parent!!
        val self = methodScope.selfAsMethod
            ?: error("$methodScope (${methodScope.scopeType}) misses selfAsMethod")
        val valueParameters = self.valueParameters
        for (i in valueParameters.lastIndex downTo 0) {
            val param = valueParameters[i]
            if (param.defaultValue == null) return

            // check if class has another function with that parameter defined
            val expectedParamsForMatch = self.valueParameters.subList(0, i)
            val match = scopeParent.children.firstOrNull { scope ->
                val method = scope.selfAsMethod
                method != null && method.name == self.name &&
                        method.selfType == self.selfType &&
                        matchesParameters(expectedParamsForMatch, method.valueParameters)
            }

            if (match != null) {
                LOGGER.info("Unused default-parameter: '$self'.${param.name} is already defined by $match")
                continue
            }

            val origin = self.origin

            val scope = scopeParent.generate(self.name, ScopeType.METHOD)
            scope.setTypeParams(self.typeParameters.map { it.withScope(scope) })

            val subValueParameters = self.valueParameters.subList(0, i).map { it.withScope(scope) }
            subValueParameters.forEach { param -> param.getOrCreateField(null, Flags.NONE) }

            val newTypeParameters = self.typeParameters.map { GenericType(scope, it.name) }
            val newValueParameters = self.valueParameters.mapIndexed { index, parameter ->
                val value =
                    if (index < i) UnresolvedFieldExpression(parameter.name, emptyList(), scope, origin)
                    else parameter.defaultValue!!
                NamedParameter(parameter.name, value)
            }

            val newBody = if (self.selfType == null) {
                CallExpression(
                    UnresolvedFieldExpression(self.name, emptyList(), scope, origin),
                    newTypeParameters, newValueParameters, origin
                )
            } else {
                NamedCallExpression(
                    ThisExpression(scopeParent, scope, origin),
                    self.name, emptyList(),
                    newTypeParameters, newValueParameters, scope, origin
                )
            }
            val newMethod = Method(
                self.selfType, self.hasExplicitSelfType, self.name,
                self.typeParameters, subValueParameters,
                scope, self.returnType, self.extraConditions,
                ReturnExpression(newBody, null, scope, origin),
                self.flags,
                origin
            )
            scope.selfAsMethod = newMethod
            LOGGER.info("Created $newMethod")
        }
    }

    private fun matchesParameters(expected: List<Parameter>, actual: List<Parameter>): Boolean {
        if (expected.size != actual.size) return false
        for (i in expected.indices) {
            val expectedParam = expected[i]
            val actualParam = actual[i]
            if (expectedParam.type != actualParam.type) return false
        }
        return true
    }

    private fun createDefaultParameterConstructor(constructorScope: Scope) {
        val classScope = constructorScope.parent ?: return
        val self = constructorScope.selfAsConstructor ?: return
        val valueParameters = self.valueParameters
        for (i in valueParameters.lastIndex downTo 0) {
            val param = valueParameters[i]
            if (param.defaultValue == null) return

            // check if class has another function with that parameter defined
            val expectedParamsForMatch = self.valueParameters.subList(0, i)
            for (i in classScope.children.indices) {
                val childScope = classScope.children[i]
                val method = childScope[ScopeInitType.DEFAULT_PARAMETERS].selfAsConstructor
                val matches = method != null &&
                        method.selfType == self.selfType &&
                        matchesParameters(expectedParamsForMatch, method.valueParameters)
                if (matches) {
                    LOGGER.info("Unused default-parameter: '$self'.${param.name} is already defined by $childScope")
                    continue
                }
            }

            val scopeName = classScope.generateName("synthetic:constructor")
            val scope = classScope.getOrPut(scopeName, ScopeType.CONSTRUCTOR)
            scope.setTypeParams(self.typeParameters)

            val origin = self.origin
            val newValueParameters = self.valueParameters.mapIndexed { index, parameter ->
                val value =
                    if (index < i) UnresolvedFieldExpression(parameter.name, emptyList(), scope, origin)
                    else parameter.defaultValue!!
                NamedParameter(parameter.name, value)
            }

            val superCall = InnerSuperCall(
                InnerSuperCallTarget.THIS, newValueParameters,
                self.origin // is this fine?
            )
            val newConstructor = Constructor(
                self.valueParameters.subList(0, i),
                scope, superCall, ExpressionList(emptyList(), scope, origin),
                self.flags, origin
            )
            scope.selfAsConstructor = newConstructor
            LOGGER.info("Created $newConstructor")
        }
    }
}
