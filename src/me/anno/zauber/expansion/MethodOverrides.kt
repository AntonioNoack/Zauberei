package me.anno.zauber.expansion

import me.anno.support.Language
import me.anno.support.jvm.FirstJVMClassReader
import me.anno.utils.CollectionUtils.groupByMutable
import me.anno.utils.ResetThreadLocal.Companion.threadLocal
import me.anno.zauber.ast.rich.Flags
import me.anno.zauber.ast.rich.Flags.hasFlag
import me.anno.zauber.ast.rich.TokenListIndex.resolveOrigin
import me.anno.zauber.ast.rich.member.Field
import me.anno.zauber.ast.rich.member.Method
import me.anno.zauber.ast.rich.parameter.Parameter
import me.anno.zauber.ast.rich.parameter.SuperCall
import me.anno.zauber.logging.LogManager
import me.anno.zauber.scope.Scope
import me.anno.zauber.scope.ScopeInit
import me.anno.zauber.scope.ScopeInitType
import me.anno.zauber.scope.ScopeType
import me.anno.zauber.typeresolution.ParameterList.Companion.resolveGenerics
import me.anno.zauber.types.Type
import me.anno.zauber.types.impl.ClassType
import me.anno.zauber.types.impl.CollectionType
import me.anno.zauber.types.impl.GenericType
import me.anno.zauber.types.impl.arithmetic.NullType
import me.anno.zauber.types.impl.arithmetic.UnknownType

/**
 * start with high classes, and go down the hierarchy,
 *  and make each class complete, s.t. all methods are present for easier resolution
 * */
object MethodOverrides {

    private val LOGGER = LogManager.getLogger(MethodOverrides::class)
    private val processedScopes by threadLocal { HashSet<Scope>() }
    var debuggedMethodName = ""

    val methodOverrideCreator = ScopeInit(ScopeInitType.ADD_OVERRIDES) { scope: Scope ->
        resolveOverrides(scope)
    }

    fun sameParameters(
        superClass: Scope, childClass: Scope,
        superMethod: Scope, childMethod: Scope,
        options: List<Parameter>, expected: List<Type>
    ): Boolean {
        if (expected.size != options.size) {
            if (LOGGER.isInfoEnabled && superMethod.selfAsMethod!!.name == debuggedMethodName) {
                LOGGER.info("size mismatch: ${expected.size} != ${options.size}")
            }
            return false
        }
        for (index in options.indices) {
            // if (option.name != expected.name) return false // <- just a warning
            val optionType = options[index].type
            val expectedType = expected[index].adjustTo(superClass, childClass, superMethod, childMethod)
            if (!equalsIgnoreUnknowns(expectedType, optionType)) {
                if (LOGGER.isInfoEnabled && superMethod.selfAsMethod!!.name == debuggedMethodName) {
                    LOGGER.info("type mismatch: $optionType != $expectedType")
                }
                return false
            }
        }
        return true
    }

    private fun equalsIgnoreUnknowns(a: Type, b: Type): Boolean {
        val a = a.resolvedName
        val b = b.resolvedName
        if (a == UnknownType || b == UnknownType) return true
        if (a.javaClass != b.javaClass) return false
        return when (a) {
            is ClassType -> {
                b as ClassType
                val tpe = a.typeParameters ?: emptyList()
                val tpa = b.typeParameters ?: emptyList()
                a.clazz == b.clazz &&
                        (isJavaClass(a) || isJavaClass(b) || // Java doesn't care about type-parameters
                                tpe.size == tpa.size && tpe.indices.all { equalsIgnoreUnknowns(tpe[it], tpa[it]) })
            }
            NullType -> true
            is GenericType -> a == b
            is CollectionType -> {
                b as CollectionType
                a.types.all { ai ->
                    b.types.any { bi -> equalsIgnoreUnknowns(ai, bi) }
                } && b.types.none { bi ->
                    a.types.none { ai -> equalsIgnoreUnknowns(ai, bi) }
                }
            }
            else -> TODO("Check equals ${a.javaClass.simpleName}")
        }
    }

    fun isJavaClass(a: ClassType): Boolean {
        return isJavaClass(a.clazz)
    }

    fun isJavaClass(a: Scope): Boolean {
        val sourceLanguage = a.sourceLibrary?.language
        return sourceLanguage == Language.JAVA
    }

    private fun resolveOverrides(scope: Scope) {
        if (!scope.isClassLike() || scope.scopeType == ScopeType.PACKAGE) return
        if (!processedScopes.add(scope)) return

        scope[ScopeInitType.ADD_OVERRIDES]

        val selfMethods0 = scope.methods0
        val selfMethods = selfMethods0.groupByMutable { it.name }
        val foundMethods = HashSet<Method>()

        // todo can/should we also deduplicate fields? may be useful :)
        deduplicateMethods(scope, selfMethods)

        val selfFields0 = scope.fields
        val foundFields = HashSet<Field>()

        for (superCall in scope.superCalls) {
            val superScope = superCall.type.clazz
            resolveOverrides(superScope)
            addAllMethodOverrides(scope, superCall, superScope, selfMethods, foundMethods)
            addAllFieldOverrides(scope, superScope, selfFields0, foundFields)
        }

        // check that all methods with override-flag have found their partner
        for (method in selfMethods0) {
            if (method.flags.hasFlag(Flags.OVERRIDE) && method !in foundMethods &&
                // will be checked by getter; and avoid warnings for val size: Int + override var size: Int
                method.scope.scopeType != ScopeType.FIELD_SETTER
            ) {
                LOGGER.warn("No base-method found for $scope.$method, found: $foundMethods")
            }
        }

        for (field in selfFields0) {
            if (field.flags.hasFlag(Flags.OVERRIDE) && field !in foundFields) {
                LOGGER.warn("No base-field found for $field in $scope")
            }
        }
    }

    private fun deduplicateMethods(scope: Scope, methods: Map<String, ArrayList<Method>>) {
        val byValueParams = HashMap<List<Type>, Method>()
        for (candidates in methods.values) {
            if (candidates.size <= 1) continue

            var i = 0
            while (i < candidates.size) {
                val candidate = candidates[i++]

                val paramTypes = candidate.valueParameters
                    .map { it.type.resolve(scope) }
                val prevMethod = byValueParams.getOrPut(paramTypes) { candidate }
                if (prevMethod !== candidate) {
                    val prevIsBetter = joinMethods(prevMethod, candidate)
                    val removalIndex = if (prevIsBetter) i else candidates.indexOf(prevMethod)
                    candidates.removeAt(removalIndex); i--
                }
            }
            byValueParams.clear()
        }
    }

    private fun getGoodMethodScore(m: Method): Int {
        var score = 0
        if (m.flags.hasFlag(Flags.EXTERNAL)) score -= 10
        if (m.body != null) score += 100
        return score
    }

    private fun joinMethods(a: Method, b: Method): Boolean {
        check(a !== b)
        check(a.scope !== b.scope)

        val aIsBetter = getGoodMethodScore(a) >= getGoodMethodScore(b)
        val better = if (aIsBetter) a else b
        val worse = if (aIsBetter) b else a

        // link everything to the better method, as far as we can
        worse.body = better.body
        worse.scope.selfAsMethod = better
        worse.scope.removeFromParent()
        worse.scope = better.scope
        worse.flags = better.flags
        return aIsBetter
    }

    private fun addAllMethodOverrides(
        scope: Scope, superCall: SuperCall, superScope: Scope,
        selfMethods: HashMap<String, ArrayList<Method>>,
        foundMethods: HashSet<Method>
    ) {
        val ownerIsAbstract = scope.isAbstractClass()
        superScope[ScopeInitType.ADD_OVERRIDES]

        for (superMethod0 in superScope.children) {
            val superMethod = superMethod0.selfAsMethod
            if (superMethod == null || superMethod.isPrivate()) continue

            if (LOGGER.isInfoEnabled) LOGGER.info("Check: $superMethod for $superScope -> $scope")

            // find match
            val selfType = superMethod.selfType ?: scope.typeWithArgs
            val methodTypeParameters = superMethod.typeParameters.map { parameter ->
                superCall.type.typeParameters
                    .resolveGenerics(selfType, parameter.type)
                    .resolve(scope)
            }

            val methodValueParameters = superMethod.valueParameters.map { parameter ->
                superCall.type.typeParameters
                    .resolveGenerics(selfType, parameter.type)
                    .resolve(scope)
            }

            val selfMethods0 = selfMethods[superMethod.name] ?: emptyList()
            val selfMethodsI = selfMethods0
                .filter { candidate ->
                    sameParameters(
                        superScope, scope, superMethod.memberScope, candidate.memberScope,
                        candidate.typeParameters, methodTypeParameters
                    ) && sameParameters(
                        superScope, scope, superMethod.memberScope, candidate.memberScope,
                        candidate.valueParameters, methodValueParameters
                    )
                }

            if (selfMethodsI.size > 1) {
                LOGGER.warn("Expected only one candidate for $superMethod in $scope, but found $selfMethodsI")
            }

            val isOpen = superMethod.isOpen()
            val isExplicitlyClosed = superMethod.flags.hasFlag(Flags.FINAL)

            var selfMethod = selfMethodsI.firstOrNull()
            if (selfMethod == null) {

                if (LOGGER.isInfoEnabled && superMethod.name == debuggedMethodName) {
                    LOGGER.info("Copying $superMethod from $superScope to $scope, mismatches: ${selfMethods0.size}")
                }

                // somehow create a new method linking to the old one
                check(ownerIsAbstract || !superMethod.isAbstract()) {
                    val methods =
                        if (selfMethods.isEmpty()) selfMethodsI
                        else selfMethods.values.flatten().sortedBy { it.name }
                    val methods1 = methods.joinToString("") { method -> "\n- $method" }
                    "Missing $superMethod in $scope, for $superScope, candidates: ${methods1}\n" +
                            "Scope is not abstract, and superMethod has no body"
                }

                val newScope = scope.generate(superMethod.name, ScopeType.METHOD)
                selfMethod = superMethod.adjustTo(superScope, scope, newScope)
                newScope.setTypeParams(selfMethod.typeParameters)
                newScope.selfAsMethod = selfMethod

                // rarely needed, but sometimes we do need it (e.g. for java.util.ArrayList)
                selfMethods.getOrPut(superMethod.name, ::ArrayList)
                    .add(selfMethod)

            } else {

                if (LOGGER.isInfoEnabled && superMethod.name == debuggedMethodName) LOGGER.info("Found: $selfMethod in $scope for $superMethod in $superScope")

                foundMethods.add(selfMethod)

                check(
                    selfMethod.flags.hasFlag(Flags.OVERRIDE) ||
                            selfMethod.ownerScope.sourceLibrary == FirstJVMClassReader.jvmLibrary // <- doesn't know that flag
                ) {
                    println("$selfMethod is from ${resolveOrigin(selfMethod.origin)}")
                    "Expected $selfMethod to have override flag for $superScope.$superMethod"
                }

                check(!isOpen || !isExplicitlyClosed) {
                    "$selfMethod cannot both be open and closed, got ${Flags.toString(selfMethod.flags)} (${selfMethod.flags}), " +
                            "scopeType: ${scope.scopeType}, superCalls: ${scope.superCalls}"
                }
            }

            superMethod.childMethods += selfMethod
            selfMethod.superMethods += superMethod
        }
    }

    private fun addAllFieldOverrides(
        scope: Scope, superScope: Scope,
        selfFields0: List<Field>,
        foundFields: HashSet<Field>
    ) {
        val superFields = superScope.fields.filter { !it.hasExplicitSelfType }
        for (superField in superFields) {
            if (superField.isPrivate()) continue

            // find match
            val selfField = selfFields0.firstOrNull { it.name == superField.name }

            val isOpen = superField.flags.hasFlag(Flags.OPEN) ||
                    superField.flags.hasFlag(Flags.OVERRIDE) ||
                    superScope.isInterface()
            val isExplicitlyClosed = superField.flags.hasFlag(Flags.FINAL)

            if (selfField == null) {
                // somehow create a new method linking to the old one
                scope.addField(superField)
            } else {
                foundFields.add(selfField)
                if (false) check(selfField.flags.hasFlag(Flags.OVERRIDE)) {
                    "Expected $scope.$selfField to have override flag for $superScope.$superField"
                }
                if (false) check(isOpen && !isExplicitlyClosed) {
                    "$scope.$selfField cannot both be open and closed, got ${Flags.toString(selfField.flags)}"
                }

                superField.childMethods += selfField
                selfField.superMethods += superField
            }
        }
    }

    private fun Method.adjustTo(superClass: Scope, childClass: Scope, childMethod: Scope): Method {
        val superMethod = memberScope
        return Method(
            selfType?.adjustTo(superClass, childClass, superMethod, childMethod),
            hasExplicitSelfType, name,
            typeParameters.map { it.withType(it.type.adjustTo(superClass, childClass, superMethod, childMethod)) },
            valueParameters.map { it.withType(it.type.adjustTo(superClass, childClass, superMethod, childMethod)) },
            childMethod, returnType?.adjustTo(superClass, childClass, superMethod, childMethod),
            extraConditions, body, flags or Flags.OVERRIDE, origin
        )
    }

}