package me.anno.zauber.expansion

import me.anno.generation.Specializations
import me.anno.zauber.ast.rich.Flags
import me.anno.zauber.ast.rich.Flags.hasFlag
import me.anno.zauber.ast.rich.TokenListIndex.resolveOrigin
import me.anno.zauber.ast.rich.TokenListIndex.resolveOriginShort
import me.anno.zauber.ast.rich.expression.DynamicMacroExpression
import me.anno.zauber.ast.rich.expression.Expression
import me.anno.zauber.ast.rich.expression.resolved.ThisExpression
import me.anno.zauber.ast.rich.expression.unresolved.CallExpression
import me.anno.zauber.ast.rich.expression.unresolved.MemberNameExpression.Companion.nameExpression
import me.anno.zauber.ast.rich.member.Method
import me.anno.zauber.ast.rich.parameter.NamedParameter
import me.anno.zauber.ast.rich.parser.ZauberASTBuilderBase
import me.anno.zauber.interpreting.BlockReturn
import me.anno.zauber.interpreting.ConstExpr.evaluateExpression
import me.anno.zauber.interpreting.Instance
import me.anno.zauber.interpreting.ReturnType
import me.anno.zauber.interpreting.Runtime
import me.anno.zauber.interpreting.RuntimeCreate.createString
import me.anno.zauber.scope.Scope
import me.anno.zauber.scope.lazy.LazyExpression
import me.anno.zauber.tokenizer.TokenList
import me.anno.zauber.tokenizer.ZauberTokenizer
import me.anno.zauber.typeresolution.CallWithNames.resolveNamedParameters
import me.anno.zauber.typeresolution.ResolutionContext
import me.anno.zauber.typeresolution.ValueParameterImpl
import me.anno.zauber.typeresolution.members.MethodResolver
import me.anno.zauber.typeresolution.members.ResolvedMember
import me.anno.zauber.typeresolution.members.ResolvedMethod
import me.anno.zauber.types.Import
import me.anno.zauber.types.Type
import me.anno.zauber.types.Types
import me.anno.zauber.types.impl.GenericType

object Macro {

    val macroContextParam get() = Types.MacroContext
    var macroCallDepth = 0

    fun ZauberASTBuilderBase.evaluateMacro(
        namePath: Expression, typeParameters: List<Type>?,
        valueParameters: List<NamedParameter>, origin: Long
    ): Expression {
        TODO("Resolve macro $namePath")
    }

    fun ZauberASTBuilderBase.evaluateMacro(
        namePath: String,
        i0: Int,
        typeParameters: List<Type>?,
        origin: Long
    ): Expression {

        val i1 = i - 1 // 'i0' is on name, 'i' is on virtual '('
        skipCall() // 'i' is now after call

        val content = tokens.extractString(i1 + 2, i - 2)
        val valueParameters = listOf(Runtime.runtime.createString(content))
        return evaluateMacroNow(namePath, i0, typeParameters, valueParameters, origin)
    }

    private fun createContext(codeScope: Scope): ResolutionContext {
        return ResolutionContext(codeScope, null, Specializations.specialization, true, null)
    }

    private fun ZauberASTBuilderBase.resolveMacroByName(
        namePath: String, i0: Int, typeParameters: List<Type>?,
        valueParameterTypes: List<Type>, origin: Long
    ): ResolvedMethod {
        val scope = currPackage
        val context = createContext(scope)

        val valueParameters1 = valueParameterTypes.map { type ->
            ValueParameterImpl(null, type, false)
        } + ValueParameterImpl(null, macroContextParam, false)

        val byMethodCall = MethodResolver.resolveCallable(
            context, scope,
            namePath, imports.filter { it.name == namePath },
            constructor = null, typeParameters, valueParameters1, origin
        )

        if (byMethodCall == null || byMethodCall.resolved !is Method) {
            val base = nameExpression(namePath, i0, origin, scope)
            val tmpExpr = CallExpression(base, typeParameters, emptyList(), origin)
            MethodResolver.printScopeForMissingMethod(context, tmpExpr, namePath, typeParameters, valueParameters1)
        }

        check(byMethodCall.resolved.flags.hasFlag(Flags.MACRO)) {
            "Expected to resolve to macro, got $byMethodCall"
        }

        return byMethodCall as ResolvedMethod
    }

    private fun codeIsInsideAMacro(currPackage: Scope): Boolean {
        var scope = currPackage
        while (true) {
            val method = scope.selfAsMethod
            if (method != null && method.flags.hasFlag(Flags.MACRO)) {
                return true
            }

            scope = scope.parentIfSameFile ?: return false
        }
    }

    private fun getObjectScope(currPackage: Scope): Scope {
        var scope = currPackage
        while (true) {
            if (scope.isObjectLike()) return scope
            if (scope.isClassLike()) {
                error("Expected objectLike scope for macro-execution in $currPackage, not $scope")
            }

            scope = scope.parentIfSameFile
                ?: error("Missing objectLike scope for macro-execution in $currPackage")
        }
    }

    fun ZauberASTBuilderBase.evaluateMacro(
        namePath: String, i0: Int, typeParameters: List<Type>?,
        valueParameters: List<NamedParameter>, scope: Scope, origin: Long
    ): Expression {
        val context = createContext(currPackage)
        val valueParameterTypes = valueParameters.map { it.value.resolveValueType(context) }
        if (codeIsInsideAMacro(scope)) {
            val macro = resolveMacroByName(namePath, i0, typeParameters, valueParameterTypes, origin)
            // todo how is self found inside CallExpression/NamedCallExpression???
            val self = ThisExpression(macro.resolved.scope.parent!!, scope, origin)
            val expected = macro.resolved.valueParameters
                // ignore last, which is the context
                .run { subList(0, size - 1) }
            val valueParameters1 = resolveNamedParameters(expected, valueParameters, scope, origin)
                ?: error(
                    "Unable to properly reorder parameters for $macro at ${resolveOrigin(origin)}, " +
                            "${expected.map { it.name }} vs ${valueParameters.map { it.name }}"
                )
            return DynamicMacroExpression(self, macro, valueParameters1, imports, generics, scope, origin)
        } else {
            val valueParameters = valueParameters.mapIndexed { index, parameter ->
                evaluateExpression(parameter.value, Flags.NONE, valueParameterTypes[index])
            }
            return evaluateMacroNow(namePath, i0, typeParameters, valueParameters, origin)
        }
    }

    fun ZauberASTBuilderBase.evaluateMacroNow(
        namePath: String, i0: Int, typeParameters: List<Type>?,
        valueParameters: List<Instance>, origin: Long
    ): Expression {
        val valueParameterTypes = valueParameters.map { it.clazz.type }
        val macro = resolveMacroByName(namePath, i0, typeParameters, valueParameterTypes, origin)
        return evaluateMacroNow(macro, valueParameters, origin)
    }

    fun ZauberASTBuilderBase.evaluateMacroNow(
        macro: ResolvedMethod,
        valueParameters: List<Instance>, origin: Long,
    ): Expression = evaluateMacroNow(macro, valueParameters, imports, generics, currPackage, origin)

    fun evaluateMacroNow(
        macro: ResolvedMethod, valueParameters: List<Instance>,
        imports: List<Import>, generics: HashMap<String, GenericType>,
        scope: Scope, origin: Long,
    ): Expression {

        macroCallDepth++
        println("[$macroCallDepth] Evaluating macro '${macro.resolved.name}' using $valueParameters")

        if (macroCallDepth >= 100) error("Macro-death-spiral")

        // todo we should be able to cache valueParameters...
        //  only if they're immutable though... maybe we can enforce all parameters to be values?

        val method = macro.resolved
        val result = executeMacroInRuntime(method, macro, valueParameters, origin)
        val tokenList = extractTokensFromRuntime(result, method, origin)

        return LazyExpression.eval(
            tokenList, 0, tokenList.size, isBody = true,
            scope, imports, generics
        ).apply {
            println("[$macroCallDepth] Finished macro '${macro.resolved.name}' using $valueParameters")
            macroCallDepth--
        }
    }

    fun executeMacroInRuntime(
        method: Method,
        byMethodCall: ResolvedMember<*>,
        valueParameters: List<Instance>,
        origin: Long
    ): BlockReturn {
        val runtime = Runtime.runtime
        val ownerScope = method.scope.parent
        check(ownerScope != null && ownerScope.isObjectLike())

        val owner = runtime.getObjectInstance(ownerScope)
        val method1 = byMethodCall.specialization
        check(byMethodCall.resolved.memberScope == method1.scope)

        val valueParameters1 = valueParameters + runtime.getObjectInstance(macroContextParam)
        return runtime.executeCall2(
            owner, null,
            method1, valueParameters1, origin
        )
    }

    fun extractTokensFromRuntime(result: BlockReturn, method: Method, origin: Long): TokenList {
        val runtime = Runtime.runtime
        check(result.type == ReturnType.THROW) { "Failed calling $method at ${resolveOrigin(origin)}" }
        check(result.value.clazz == runtime.getClass(Types.MacroContext))

        val resultIndex = result.value.clazz.fields.indexOfFirst { it.name == "result" }
        val value = result.value.fields.getOrNull(resultIndex)
            ?: error("Missing first property of TokenResult")

        // todo allow special character codes to encode where something came from...
        val tokenSource = value.castToString()
        val pseudoFilename = "${method.name}@${resolveOriginShort(origin)}"
        return ZauberTokenizer(tokenSource, pseudoFilename)
            .tokenize()
    }

}