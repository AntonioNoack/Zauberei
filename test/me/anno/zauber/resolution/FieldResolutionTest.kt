package me.anno.zauber.resolution

import me.anno.utils.ResolutionUtils.get
import me.anno.utils.ResolutionUtils.typeResolveScope
import me.anno.zauber.ast.rich.member.Field
import me.anno.zauber.scope.Scope
import me.anno.zauber.scope.ScopeInitType
import me.anno.zauber.typeresolution.ResolutionContext
import me.anno.zauber.types.Type
import me.anno.zauber.types.Types
import me.anno.utils.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class FieldResolutionTest {

    companion object {

        fun findField(scope: Scope, name: String = "tested"): Field =
            tryFindField(scope, name) ?: error("Missing field '$name' in scope '$scope'")

        fun findFieldType(scope: Scope, name: String = "tested"): Type =
            findField(scope, name).resolveValueType(ResolutionContext.minimal)

        fun tryFindField(scope: Scope, name: String): Field? {
            val field = scope[ScopeInitType.CODE_GENERATION]
                .fields.firstOrNull { it.name == name }
            // println("Scanning $scope for $name: $field, options: ${scope.fields.map { it.name }}")
            if (field != null) return field
            for (child in scope.children) {
                val field = tryFindField(child, name)
                if (field != null) return field
            }
            return null
        }
    }

    @Test
    fun testSimple() {
        val code = """
        object Target {
            val x : Int = 0
            class Inner {
                val tested = x
            }
        }
        """.trimIndent()
        val scope = typeResolveScope(code)
        val tested0 = findFieldType(scope)
        assertEquals(Types.Int, tested0)
    }

    @Test
    fun testSimpleDirect() {
        val code = """
        object Target {
            val x : Int = 0
            class Inner {
                val tested = Target.x
            }
        }
        """.trimIndent()
        val scope = typeResolveScope(code)
        val tested0 = findFieldType(scope)
        assertEquals(Types.Int, tested0)
    }

    @Test
    fun testInnerClass() {
        val code = """
        class Target {
            val x : Int = 0
            inner class Inner {
                val tested = x
            }
        }
        """.trimIndent()
        val scope = typeResolveScope(code)
        val tested0 = findFieldType(scope)
        assertEquals(Types.Int, tested0)
    }

    @Test
    fun testWithShadowing() {
        val code = """
        object Shadowed {
            val x: Float = 0f
            object Target {
                val x : Int = 0
                class Inner {
                    val tested = x
                }
            }
        }
        """.trimIndent()
        val scope = typeResolveScope(code)
        val tested0 = findFieldType(scope)
        assertEquals(Types.Int, tested0)
    }

    @Test
    fun testWithImportMatching() {
        val code = """
        import helper002.Helper.x
        class Misleading {
            val x : Float = 0
            class Inner {
                val tested = x
            }
        }
        
        package helper002
        object Helper {
            val x : Int = 0
        }
        """.trimIndent()
        val scope = typeResolveScope(code)
        val tested0 = findField(scope)
            .resolveValueType(ResolutionContext.minimal)
        assertEquals(Types.Int, tested0)
    }

    @Test
    fun testWithImportMismatch() {
        // check what this code does in Kotlin -> uses the local field
        // todo get it to prefer the local field...
        val code = """
        import helper001.Helper.x
        object Target {
            val x : Int = 0
            class Inner {
                val tested = x
            }
        }
        
        package helper001
        object Helper {
            val x : Float = 0
        }
        """.trimIndent()
        val scope = typeResolveScope(code)
        val tested0 = findFieldType(scope)
        assertEquals(Types.Int, tested0)
    }

    @Test
    fun testWithImportAndAS() {
        val code = """
        import helper001.Helper.y as x
        class Misleading {
            val x : Float = 0
            class Inner {
                val tested = x
            }
        }
        
        package helper001
        object Helper {
            val y : Int = 0
        }
        """.trimIndent()
        val scope = typeResolveScope(code)
        val tested0 = findFieldType(scope)
        assertEquals(Types.Int, tested0)
    }

    @Test
    fun testNested() {
        val code = """
        object Target {
            val x : Int = 0
            class MisleadingNoInstance {
                val x: Float = 0f
                class NotInnerClassC {
                    val tested = x // must be Int
                }
                object NotInnerClassO {
                    val tested = x // must be Int
                }
                inner class InnerClass {
                    val tested = x // must be Float
                }
            }
        }
        """.trimIndent()
        val scope = typeResolveScope(code)
        val misleading = scope["Target"]["MisleadingNoInstance"]
        val tested0 = findFieldType(misleading["NotInnerClassC"])
        val tested1 = findFieldType(misleading["NotInnerClassO"])
        val tested2 = findFieldType(misleading["InnerClass"])
        assertEquals(Types.Int, tested0)
        assertEquals(Types.Int, tested1)
        assertEquals(Types.Float, tested2)
    }

    @Test
    fun testNestedWithInheritance1() {
        val code = """
        open class TargetBase {
            val x: Int = 0
        }
        object Target: TargetBase() {
            class MisleadingNoInstance {
                val x: Float = 0f
                class NotInnerClassC {
                    val tested = x // must be Int
                }
                object NotInnerClassO {
                    val tested = x // must be Int
                }
            }
        }
        """.trimIndent()
        val scope = typeResolveScope(code)
        val misleading = scope["Target"]["MisleadingNoInstance"]
        val tested0 = findFieldType(misleading["NotInnerClassC"])
        val tested1 = findFieldType(misleading["NotInnerClassO"])
        assertEquals(Types.Int, tested0)
        assertEquals(Types.Int, tested1)
    }

    @Test
    fun testNestedWithInheritance2() {
        val code = """
        open class TargetBase {
            val x: Int = 0
        }
        open class MisleadingBase {
            val x: Float = 0f
        }
        object Target: TargetBase() {
            class MisleadingNoInstance: MisleadingBase() {
                class NotInnerClassC {
                    val tested = x // must be Int
                }
                object NotInnerClassO {
                    val tested = x // must be Int
                }
            }
        }
        """.trimIndent()
        val scope = typeResolveScope(code)
        val misleading = scope["Target"]["MisleadingNoInstance"]
        val tested0 = findFieldType(misleading["NotInnerClassC"])
        val tested1 = findFieldType(misleading["NotInnerClassO"])
        assertEquals(Types.Int, tested0)
        assertEquals(Types.Int, tested1)
    }

    @Test
    fun testEnumBesideSelf() {
        val code = """
        enum class Color {
            RED
        }
        class Inner {
            val tested = Color.RED.ordinal
        }
        """.trimIndent()
        val scope = typeResolveScope(code)
        val actualType = findFieldType(scope)
        assertEquals(Types.Int, actualType)
    }

    @Test
    fun testEnumBelowSelf() {
        val code = """
        enum class Color {
            RED;
            
            class Inner {
                val tested = RED.ordinal
            }
        }
        """.trimIndent()
        val scope = typeResolveScope(code)
        val actualType = findFieldType(scope)
        assertEquals(Types.Int, actualType)
    }

    @Test
    fun testTargetTypeMismatch() {
        // todo why is this not failing?
        //  selfType is explicit, so objects cannot be applied randomly
        assertThrows<IllegalStateException> {
            val scope = typeResolveScope(
                """
                object Outer {
                    val x = 0
                    
                    class Inner {
                        val tested = 0.x
                    }
                }
                """.trimIndent()
            )
            val actualType = findFieldType(scope).resolvedName
            assertEquals(Types.Int, actualType)
        }
    }

    @Test
    fun testEnumInsideSelf() {
        val code = """
        class Inner {
            val tested = Color.RED.ordinal
            enum class Color {
                RED;
            }
        }
        """.trimIndent()
        val scope = typeResolveScope(code)
        val actualType = findFieldType(scope)
        assertEquals(Types.Int, actualType)
    }

    @Test
    fun testEnumBesideSelf2() {
        val code = """
        enum class Color {
            RED
        }
        class Inner {
            val tested = Color.RED
        }
        """.trimIndent()
        val scope = typeResolveScope(code)
        val actualType = findFieldType(scope)
        val colorType = scope["Color"].typeWithArgs
        assertEquals(colorType, actualType)
    }

    @Test
    fun testEnumBelowSelf2() {
        val code = """
        enum class Color {
            RED;
            
            class Inner {
                val tested = RED
            }
        }
        """.trimIndent()
        val scope = typeResolveScope(code)
        val actualType = findFieldType(scope)
        val expectedType = scope["Color"].typeWithArgs
        assertEquals(expectedType, actualType)
    }

    @Test
    fun testEnumInsideSelf2() {
        val code = """
        class Inner {
            val tested = Color.RED
            enum class Color {
                RED;
            }
        }
        """.trimIndent()
        val scope = typeResolveScope(code)
        val actualType = findFieldType(scope)
        val colorType = scope["Inner"]["Color"].typeWithArgs
        assertEquals(colorType, actualType)
    }

    @Test
    fun testExplicitCompanionForOther() {
        val code = """
        class Wrapper {
            companion object {
                val x = 0
            }
        }
        
        class Inner {
            val tested = Wrapper.Companion.x
        }
        """.trimIndent()
        val scope = typeResolveScope(code)
        val actualType = findFieldType(scope)
        assertEquals(Types.Int, actualType)
    }

    @Test
    fun testObjectInClass() {
        val code = """
        class Wrapper {
            object Inside {
                val x = 0
            }
        }
        
        class Inner {
            val tested = Wrapper.Inside.x
        }
        """.trimIndent()
        val scope = typeResolveScope(code)
        val actualType = findFieldType(scope)
        assertEquals(Types.Int, actualType)
    }

    @Test
    fun testCompanionBeingOptionalForOther() {
        val code = """
        class Wrapper {
            companion object {
                val x = 0
            }
        }
        
        class Inner {
            val tested = Wrapper.x
        }
        """.trimIndent()
        val scope = typeResolveScope(code)
        val actualType = findFieldType(scope)
        assertEquals(Types.Int, actualType)
    }

    @Test
    fun testCompanionBeingOptionalInsideSelf() {
        val code = """
        class Inner {
            val tested = x
            companion object {
                val x = 0
            }
        }
        """.trimIndent()
        val scope = typeResolveScope(code)
        val actualType = findFieldType(scope)
        assertEquals(Types.Int, actualType)
    }

    @Test
    fun testCompanionBeingOptionalForImport() {
        val code = """
        import helper004f.Wrapper
        class Inner {
            val tested = Wrapper.x
        }
        
        package helper004f
        class Wrapper {
            companion object {
                val x = 0
            }
        }
        """.trimIndent()
        val scope = typeResolveScope(code)
        val actualType = findFieldType(scope)
        assertEquals(Types.Int, actualType)
    }

    @Test
    fun testCompanionBeingOptionalForImported() {
        // todo why is helper005f.Wrapper.x a class-type?
        val code = """
        import helper005f.Wrapper.x
        class Inner {
            val tested = x
        }
        
        package helper005f
        class Wrapper {
            companion object {
                val x = 0
            }
        }
        """.trimIndent()
        val scope = typeResolveScope(code)
        val actualType = findFieldType(scope)
        println(actualType.javaClass.simpleName)
        assertEquals(Types.Int, actualType)
    }

    @Test
    fun testFieldExtension() {
        val code = """
        val Int.next get() = 0f
        class Inner {
            fun <V: Int> V.test(): Int {
                val tested = next
            }
        }
        """.trimIndent()
        val scope = typeResolveScope(code)
        val actualType = findFieldType(scope)
        assertEquals(Types.Float, actualType)
    }

    @Test
    fun testFieldExtensionInner() {
        // todo oh no, this has a dependency-order-issue
        val code = """
        val Int.next get() = 0f
        class Inner {
            fun <V: Int> V.test(): Int {
                class Tested {
                    val tested = next /* called on V */
                }
            }
        }
        """.trimIndent()
        val scope = typeResolveScope(code)
        val actualType = findFieldType(scope)
        assertEquals(Types.Float, actualType)
    }

    @Test
    fun testFieldExtensionImported() {
        val code = """
        import helper003.next
        class Inner {
            fun <V: Int> V.test(): Int {
                val tested = next
            }
        }
        
        package helper003
        val Int.next get() = 0f
        """.trimIndent()
        val scope = typeResolveScope(code)
        val actualType = findFieldType(scope)
        assertEquals(Types.Float, actualType)
    }

    @Test
    fun testFieldInPackageScope() {
        val code = """
        val target = 0
        val tested = target
        """.trimIndent()
        val scope = typeResolveScope(code)
        val actualType = findFieldType(scope)
        assertEquals(Types.Int, actualType)
    }

    @Test
    fun testExtensionFieldInPackageScope() {
        val code = """
        val Int.target = 0
        val tested = 0.target
        """.trimIndent()
        val scope = typeResolveScope(code)
        val actualType = findFieldType(scope)
        assertEquals(Types.Int, actualType)
    }

    @Test
    fun testExtensionFieldInMethodBody() {
        val code = """
        fun method(): Int {
            val Int.next = 1
            val tested = 0.next
            return tested
        }
        """.trimIndent()
        val scope = typeResolveScope(code)
        val actualType = findFieldType(scope)
        assertEquals(Types.Int, actualType)
    }

    @Test
    fun testFieldWithCallAfter() {
        val code = """
            val tested = sq(5)
            fun sq(x: Int) = x*x
        """.trimIndent()
        val scope = typeResolveScope(code)
        val actualType = findFieldType(scope)
        assertEquals(Types.Int, actualType)
    }
}