package me.anno.zauberei.astbuilder.expression

import me.anno.zauberei.astbuilder.NamedParameter
import me.anno.zauberei.astbuilder.TokenListIndex.resolveOrigin
import me.anno.zauberei.typeresolution.ResolutionContext
import me.anno.zauberei.typeresolution.TypeResolution.findConstructor
import me.anno.zauberei.typeresolution.TypeResolution.findMethod
import me.anno.zauberei.typeresolution.TypeResolution.langScope
import me.anno.zauberei.typeresolution.TypeResolution.resolveCallType
import me.anno.zauberei.typeresolution.TypeResolution.resolveValueParameters
import me.anno.zauberei.types.Type

/**
 * Calls base<typeParams>(valueParams)
 * */
class CallExpression(
    val base: Expression,
    val typeParameters: List<Type>?,
    val valueParameters: List<NamedParameter>,
    origin: Int
) : Expression(base.scope, origin) {

    override fun forEachExpr(callback: (Expression) -> Unit) {
        callback(base)
        for (i in valueParameters.indices) {
            callback(valueParameters[i].value)
        }
    }

    override fun toString(): String {
        return if (typeParameters != null && typeParameters.isEmpty()) {
            "($base)($valueParameters)"
        } else "($base)<${typeParameters ?: "?"}>($valueParameters)"
    }

    override fun clone() = CallExpression(
        base.clone(), typeParameters,
        valueParameters.map { NamedParameter(it.name, it.value.clone()) }, origin
    )

    override fun hasLambdaOrUnknownGenericsType(): Boolean {
        return typeParameters == null ||
                base.hasLambdaOrUnknownGenericsType() ||
                valueParameters.any { it.value.hasLambdaOrUnknownGenericsType() }
    }

    override fun resolveType(context: ResolutionContext): Type {
        val typeParameters = typeParameters
        val valueParameters = resolveValueParameters(context, valueParameters)
        println("Resolving call: ${base}<${typeParameters ?: "?"}>($valueParameters)")
        // todo base can be a constructor, field or a method
        // todo find the best matching candidate...
        when (base) {
            is NamedCallExpression if base.name == "." -> {
                TODO("Find method/field ${base}($valueParameters)")
            }
            is NameExpression -> {
                val name = base.name
                println("Find call '$name' with nameAsImport=null")
                // findConstructor(selfScope, false, name, typeParameters, valueParameters)
                val constructor = findConstructor(context.codeScope, true, name, typeParameters, valueParameters)
                        ?: findConstructor(langScope, false, name, typeParameters, valueParameters)
                return resolveCallType(
                    context, this, name, constructor,
                    typeParameters, valueParameters
                )
            }
            is ImportedExpression -> {
                val name = base.nameAsImport.name
                println("Find call '$name' with nameAsImport=${base.nameAsImport}")
                // findConstructor(selfScope, false, name, typeParameters, valueParameters)
                val constructor =
                    findConstructor(base.nameAsImport, false, name, typeParameters, valueParameters)
                       // ?: findConstructor(context.codeScope, true, name, typeParameters, valueParameters)
                       // ?: findConstructor(langScope, false, name, typeParameters, valueParameters)
                        ?: findMethod(
                            base.nameAsImport.parent, false, name,
                            context.targetType,
                            base.nameAsImport.parent?.typeWithoutArgs,
                            typeParameters, valueParameters
                        )
                return resolveCallType(
                    context, this, name, constructor,
                    typeParameters, valueParameters
                )
            }
            else -> throw IllegalStateException(
                "Resolve field/method for ${base.javaClass} ($base) " +
                        "in ${resolveOrigin(origin)}"
            )
        }
    }
}