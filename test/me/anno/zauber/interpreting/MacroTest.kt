package me.anno.zauber.interpreting

import me.anno.zauber.interpreting.BasicRuntimeTests.Companion.testExecute
import me.anno.utils.assertEquals
import org.junit.jupiter.api.Test

/**
 * implement that name + string = comptime call to name(string),
 *   and then the returned list of strings is tokenized and evaluated,
 *   and its result is used
 * */
class MacroTest {

    // todo we can implement structure-of-arrays using Macros,
    //  so we somehow need to support attaching them to types
    //  @Macro!() or @Macro""
    //  -
    //  class/type info is then put into MacroContext?
    //  macros may add types and functions, so we can only put partial types in there...

    // annotations having
    //  - beforeCall(), afterCall()
    //  - onReturn(), onThrow(), onYield()
    //  - onAllocate(), onDestroy()
    //  may be useful (like dotNET EntityFramework filters),
    //  and it may also be useful to define an annotation as for-fields/methods, and attaching it to a class then applies to all
    //  -> what can be done using reflections should be done using them, I don't think we need these features

    @Test
    fun testParsingXMLAtCompileTime() {
        val value = testExecute(
            $$"""
class XMLNode(val type: String) {
    var content = ""
    fun addContent(text: String): XMLNode {
        content += text
        return this
    }
    
    fun toString(): String {
        return "<$type>$content</$type>"
    }
}

macro XML(input: String, ctx: MacroContext): XMLNode {
    return ctx.parse<XMLNode>(
        "XMLNode(\"h1\")\n" +
            ".addContent(\"FakeTestMessage!\")"
    )
}

val xmlNode = XML"<h1>Hello World!</h1>"
val tested = xmlNode.toString()
        """.trimIndent()
        )
        assertEquals("<h1>FakeTestMessage!</h1>", value.castToString())
    }

    @Test
    fun testCreatingSerializerAtCompileTime() {
        val sourceCode = $$"""
class Sample(var a: Int, var b: Float)

macro <R> GetType(typeName: String, ctx: MacroContext): ClassType<R> {
    return ctx.parse<ClassType<R>>(typeName + "::class")
}

macro Serialize(input: String, ctx: MacroContext): String {
    val (fieldName, typeName) = input.split(": ")
    
    var result = "var str = \"{\\n\"\n"
    val type = GetType!(typeName)
    for (field in type.fields) {
        result += "str += \"    \\\"${field.name}\\\": \${$fieldName.${field.name}},\\n\"\n"
    }
    result += "str += \"}\"\nstr"
    return ctx.parse<String>(result)
}

fun serialize(sample: Sample): String {
    return Serialize"sample: Sample"
}

val tested = serialize(Sample(1, 2f))
        """.trimIndent()
        val value = testExecute(sourceCode)
        val expectedResult = """
            {
                "a": 1,
                "b": 2.0,
            }
        """.trimIndent()
        assertEquals(expectedResult, value.castToString())
    }
}