package me.anno.zauber.expansion

import me.anno.zauber.ast.rich.Flags.IMPLICIT
import me.anno.zauber.ast.rich.Flags.hasFlag
import me.anno.zauber.logging.LogManager
import me.anno.zauber.scope.Scope
import me.anno.zauber.scope.ScopeInit
import me.anno.zauber.scope.ScopeInitType
import me.anno.zauber.types.Specialization
import me.anno.zauber.types.impl.ClassType

object ImplicitCasts {

    private val LOGGER = LogManager.getLogger(ImplicitCasts::class)

    val conversionMethodRegistrator = ScopeInit(ScopeInitType.CONVERSION_METHODS) { scope: Scope ->
        registerConversionMethods(scope)
    }

    private fun registerConversionMethods(scope: Scope) {

        // todo we can support implicit conversion methods :)
        //  they would need to be unique somehow...

        val methods = scope.methods
        for (i in methods.indices) {
            val method = methods[i]
            if (method.flags.hasFlag(IMPLICIT)) {
                if (!method.valueParameters.isEmpty()) {
                    LOGGER.warn("Conversion method $method must not have any value parameters")
                    continue
                }

                val targetType = method.resolveReturnType(Specialization.noSpecialization)
                if (targetType is ClassType) {
                    scope.implicitCastMethods[targetType] = method
                } else {
                    LOGGER.warn("$targetType is not a valid return type for a conversion method")
                }
            }
        }
    }
}