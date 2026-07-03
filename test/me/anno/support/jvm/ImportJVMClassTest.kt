package me.anno.support.jvm

import me.anno.utils.assertEquals
import me.anno.zauber.expansion.MethodOverrides.debuggedMethodName
import me.anno.zauber.interpreting.BasicRuntimeTests.Companion.testExecute
import me.anno.zauber.interpreting.Runtime.Companion.runtime
import me.anno.zauber.interpreting.RuntimeCreate.createInt
import me.anno.zauber.logging.LogManager
import me.anno.zauber.typeresolution.ParameterList
import me.anno.zauber.types.Types
import me.anno.zauber.types.getScope0
import me.anno.zauber.types.impl.ClassType
import me.anno.zauber.types.impl.arithmetic.UnknownType


// todo next step:
//   getClass() -> ClassType -> Java calls getClassLoader() -> we need to define our own classLoader...


// todo read a complex class like HashMap,
//  and decode it fully into simple instructions...

// todo ideally, we have some jars and can just lazy-load all contents

// then try to instantiate and use an instance...
// todo we need to fix generics... ArrayList.add() must return E, not Object

// todo is there an interesting, non-generic class we can test?
//  -> we could use any class we create, and Kotlin/Java compiles for us...

// todo why TF is this exploring Regex???

fun main() {

    LogManager.disableLoggersCompletely("OverriddenMethods")

    // define some standard functions...
    testExecute(
        """
val tested = 0 // unused

package zauber
import java.lang.ClassLoader
object ZauberClassLoader: ClassLoader() {
    
}
class ClassType<V> {
    var classLoader: ClassLoader = ZauberClassLoader
}
    """.trimIndent()
    )

    debuggedMethodName = "hashCode"

    LogManager.enableDebug(
        "Runtime," +
                "SimpleGetClassField,SimpleSetClassField," +
                "SimpleGetLocalField,SimpleSetLocalField," +
                "SecondJVMMethodReader"
    )

    registerJavaClass("java.util.ArrayList")
    runtime.register(getScope0("java.lang.ClassLoader.Companion"), "registerNatives", emptyList()) { _, _ ->
        runtime.getUnit()
    }
    runtime.register(getScope0("java.lang.System.Companion"), "registerNatives", emptyList()) { _, _ ->
        runtime.getUnit()
    }
    runtime.register(getScope0("zauber.ClassType"), "hashCode", emptyList()) { self, _ ->
        runtime.createInt(System.identityHashCode(self))
    }
    runtime.register(getScope0("sun.reflect.Reflection.Companion"), "getCallerClass", emptyList()) { _, _ ->
        // should we implement this truthfully? traverse call-stack, find entry,
        // which doesn't belong to java.lang.reflect.Method.invoke() or its implementation (sun.reflect.*)
        runtime.getTypeInstance(Types.Unit)
    }

    // todo why is this not being resolved?
    val cls = getScope0("java.lang.Class")
    val ct = ClassType(cls, ParameterList(
        cls.typeParameters, listOf(UnknownType)
    ))
    runtime.register(cls, "isAssignableFrom",
        listOf(ct)) { self, (param) ->
        // should we implement this truthfully? traverse call-stack, find entry,
        // which doesn't belong to java.lang.reflect.Method.invoke() or its implementation (sun.reflect.*)
        runtime.getBool(self == param)
    }

    val value = testExecute(
        """
        import java.util.ArrayList
        fun test(): Int {
            val x = ArrayList<Int>()
            x.add(1)
            return x[0]
        }
        
        val tested = test()
    """.trimIndent(), reset = false
    )
    assertEquals(1, value.castToInt())

}

fun registerJavaClass(path: String) {
    FirstJVMClassReader.getScope(path, null)
}