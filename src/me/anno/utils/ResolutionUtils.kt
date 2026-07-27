package me.anno.utils

import me.anno.generation.Specializations
import me.anno.utils.StringStyles.bold
import me.anno.utils.StringStyles.style
import me.anno.zauber.Zauber
import me.anno.zauber.ast.rich.member.Constructor
import me.anno.zauber.ast.rich.member.Field
import me.anno.zauber.ast.rich.member.Method
import me.anno.zauber.ast.rich.parser.ZauberASTClassScanner
import me.anno.zauber.expansion.DependencyData
import me.anno.zauber.logging.LogManager
import me.anno.zauber.scope.Scope
import me.anno.zauber.scope.ScopeInitType
import me.anno.zauber.scope.ScopeType
import me.anno.zauber.tokenizer.ZauberTokenizer
import me.anno.zauber.typeresolution.ResolutionContext
import me.anno.zauber.types.Type

object ResolutionUtils {

    private val LOGGER = LogManager.getLogger(ResolutionUtils::class)

    fun typeResolveScope(code: String, reset: Boolean = true): Scope {

        if (reset) {
            ResetThreadLocal.reset()
            Specializations.reset()
            ctr = 0
        }

        val testScopeName = "test${ctr++}"

        val sources = code
            .split("\npackage ")
            .mapIndexed { index, content ->
                if (index == 0) {
                    testScopeName to "package $testScopeName\n\n$content"
                } else {
                    var linebreak = content.indexOf('\n')
                    if (linebreak < 0) linebreak = content.length
                    val packageName = content.substring(0, linebreak).trim()
                    packageName to "package $content"
                }
            }

        for (i in sources.indices) {
            val (packageName, content) = sources[i]
            if (false) {
                println("Test.zbr")
                println(content.formatLines())
            }
            val scope = getPackageScope(packageName)
            // sit, so we can add the parts no matter what...
            // may cause us to skip some initialization :/
            scope.addInitPart(scope.scopeInitType) {
                val tokenizer = ZauberTokenizer(content, "Test.zbr")
                ZauberASTClassScanner.scanClasses(tokenizer.tokenize())
            }
        }

        return Zauber.root.children.first { it.name == testScopeName }
    }

    fun getPackageScope(path: String): Scope {
        return getPackageScope(path.split('.'))
    }

    fun getPackageScope(parts: List<String>): Scope {
        var scope = Zauber.root
        for (part in parts) {
            check(part.trim() == part)
            scope = scope.getOrPut(part, ScopeType.PACKAGE)
            scope.setEmptyTypeParams()
        }
        return scope
    }

    fun String.formatLines(): String {
        val lines = lines()
        val pad = lines.size.toString().length
        return lines.mapIndexed { lineIndex, line ->
            "${(lineIndex + 1).toString().padStart(pad)} | $line"
        }.joinToString("\n")
    }

    operator fun Scope.get(name: String): Scope {
        return this[ScopeInitType.AFTER_DISCOVERY].children.firstOrNull { it.name == name }
            ?: error("Tried finding '$name', but only found ${children.map { it.name }}")
    }

    fun Scope.getField(name: String): Field {
        return this[ScopeInitType.AFTER_DISCOVERY].fields.firstOrNull { it.name == name && it.byParameter == null }
            ?: error("Tried finding '$name', but only found ${fields.map { it.name }}")
    }

    fun Scope.firstChild(scopeType: ScopeType): Scope {
        return this[ScopeInitType.AFTER_DISCOVERY].children.firstOrNull { it.scopeType == scopeType }
            ?: error("Tried finding '$scopeType', but only found ${children.map { it.scopeType }}")
    }


    var ctr = 0

    fun testTypeResolution0(code: String, reset: Boolean): Scope {

        // clean slate
        if (reset) ResetThreadLocal.reset()

        val testScopeName = "test${ctr++}"
        val tokens = ZauberTokenizer(
            """
            package $testScopeName
            
            $code
        """.trimIndent(), "Test.zbr"
        ).tokenize()
        ZauberASTClassScanner.scanClasses(tokens)
        return Zauber.root.children.first { it.name == testScopeName }
    }

    fun testTypeResolutionGetField(code: String, reset: Boolean): Field {
        return testTypeResolution0(code, reset).fields.first { it.name == "tested" }
    }

    fun testTypeResolution(code: String, reset: Boolean = false): Type {
        val field = testTypeResolutionGetField(code, reset)
        val context = ResolutionContext(field.scope, null, false, null)
        return field.resolveValueType(context)
    }

    fun printDependencies(data: DependencyData) {
        if (!LOGGER.isInfoEnabled) return

        LOGGER.info(bold("Classes:"))
        for (clazz in data.createdClasses.map { clazz ->
            "  - ${style(clazz.clazz.pathStr, StringStyles.MEDIUM_BLUE)}, $clazz"
        }.sorted()) {
            LOGGER.info(clazz)
        }

        LOGGER.info(bold("Methods:"))
        for (method in data.calledMethods.map { method ->
            val methodStr = when (val method = method.method) {
                is Method -> {
                    style(method.ownerScope.pathStr, StringStyles.MEDIUM_BLUE) + "." +
                            style(method.flags().toString() + "fun ", StringStyles.ORANGE) +
                            style(method.appendSelfType(), StringStyles.MEDIUM_BLUE) +
                            style(method.appendTypeParams(), StringStyles.GREEN) +
                            style(method.name, StringStyles.YELLOW) +
                            method.appendValueParams()
                }
                is Constructor -> {
                    style("new " + method.flags(), StringStyles.ORANGE) +
                            style(method.selfTypeI.toString(), StringStyles.MEDIUM_BLUE) +
                            method.appendValueParams()
                }
                else -> method.toString()
            }
            "  - $methodStr, $method"
        }.sorted()) {
            LOGGER.info(method)
        }

        val setStr = style("set", StringStyles.ORANGE)
        val getStr = style("get", StringStyles.GREEN)
        val getSetStr = "$getStr+$setStr"

        LOGGER.info(bold("Fields:"))
        val fields = data.getFields + data.setFields
        for (field in fields.map { fieldSpec ->
            val get = fieldSpec in data.getFields
            val set = fieldSpec in data.setFields
            val field = fieldSpec.field
            val str = when {
                !get -> setStr
                !set -> getStr
                else -> getSetStr
            }
            val fieldStr = "${field.selfType ?: field.ownerScope.pathStr}"
            "  - ${style(fieldStr, StringStyles.MEDIUM_BLUE)}." +
                    "${style(field.name, StringStyles.YELLOW)}, $fieldSpec: $str"
        }.sorted()) {
            LOGGER.info(field)
        }
    }

}