package me.anno.zauber.dependency

import me.anno.utils.ResolutionUtils
import me.anno.utils.ResolutionUtils.printDependencies
import me.anno.zauber.Zauber
import me.anno.zauber.expansion.Dependencies
import me.anno.zauber.scope.ScopeInitType
import me.anno.zauber.scope.ScopeType
import me.anno.zauber.typeresolution.ParameterList.Companion.emptyParameterList
import me.anno.zauber.typeresolution.TypeResolution.langScope
import me.anno.zauber.types.Types
import me.anno.zauber.types.Specialization
import me.anno.utils.assertEquals
import org.junit.jupiter.api.Test

class DependencyGraphTests {

    // todo we should also define a more complex example,
    //  e.g. with generics

    @Test
    fun testSimpleDependencyGraph() {
        val code = """
            val x = 1 + 2
            fun main() {
                println(x)
            }
        """.trimIndent()
        val testScope = ResolutionUtils.typeResolveScope(code)
        val method = testScope[ScopeInitType.AFTER_DISCOVERY].methods0.first { it.name == "main" }
        Dependencies.addMethod(Specialization(method.scope, emptyParameterList()))

        val dependencies = Dependencies.collectDependencies()
        printDependencies(dependencies)
        val classes = dependencies.createdClasses
        val methods = dependencies.calledMethods

        val zauberScope = Zauber.root.getOrPut("zauber", ScopeType.PACKAGE)
        val printlnMethod = zauberScope
            .methods0.first { it.name == "println" }
        val intPlusMethod = Types.Int.clazz
            .methods0.first { it.name == "plus" }

        // validate with what we expect
        val expectedClasses = listOf(
            Types.Any,
            Types.Int,
            Types.Unit,
            zauberScope.typeWithArgs2,
            testScope.typeWithArgs2,
        ).map { Specialization(it) }
        assertEquals(expectedClasses.toSet(), classes)

        val expectedMethods = setOf(
            method, printlnMethod, intPlusMethod,
            // zauberScope.primaryConstructorScope!!.selfAsConstructor!!,
            Types.Any.clazz.getOrCreatePrimaryConstructor(),
            Types.Int.clazz.getOrCreatePrimaryConstructor(),
            Types.Unit.clazz.getOrCreatePrimaryConstructor(),
            langScope.getOrCreatePrimaryConstructor(),
            testScope.primaryConstructorScope!!.selfAsConstructor!!,
        )
            .map { method -> Specialization(method.scope, emptyParameterList()) }
            .toSet()
        assertEquals(expectedMethods, methods)
    }
}