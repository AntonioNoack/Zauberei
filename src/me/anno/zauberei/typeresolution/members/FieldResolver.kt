package me.anno.zauberei.typeresolution.members

import me.anno.zauberei.astbuilder.Field
import me.anno.zauberei.astbuilder.TokenListIndex.resolveOrigin
import me.anno.zauberei.typeresolution.ResolutionContext
import me.anno.zauberei.typeresolution.TypeResolution.getSelfType
import me.anno.zauberei.typeresolution.TypeResolution.langScope
import me.anno.zauberei.typeresolution.TypeResolution.resolveType
import me.anno.zauberei.typeresolution.ValueParameter
import me.anno.zauberei.types.Scope
import me.anno.zauberei.types.Type

object FieldResolver : MemberResolver<Field, ResolvedField>() {

    override fun findMemberInScope(
        scope: Scope?, name: String,

        returnType: Type?, // sometimes, we know what to expect from the return type
        selfType: Type?, // if inside Companion/Object/Class/Interface, this is defined; else null

        typeParameters: List<Type>?,
        valueParameters: List<ValueParameter>
    ): ResolvedField? {
        scope ?: return null
        val scopeSelfType = getSelfType(scope)
        for (field in scope.fields) {
            if (field.name != name) continue
            if (field.typeParameters.isNotEmpty()) {
                println("Given $field on $selfType, with target $returnType, can we deduct any generics from that?")
            }
            val valueType = if (returnType != null) {
                getFieldReturnType(scopeSelfType, field)
            } else field.valueType // no resolution invoked (fast-path)
            val match = findMemberMatch(
                field, valueType,
                returnType, selfType,
                typeParameters, valueParameters
            )
            if (match != null) return match
        }
        return null
    }

    private fun getFieldReturnType(scopeSelfType: Type?, field: Field): Type? {
        if (field.valueType == null) {
            val expr = (field.initialValue ?: field.getterExpr)!!
            println("Resolving valueType($field), initial/getter: $expr")
            val context = ResolutionContext(
                field.declaredScope,//.innerScope,
                field.selfType ?: scopeSelfType,
                false, null
            )
            field.valueType = resolveType(context, expr)
        }
        return field.valueType
    }

    fun findMemberMatch(
        field: Field,
        valueType: Type?,

        returnType: Type?, // sometimes, we know what to expect from the return type
        selfType: Type?, // if inside Companion/Object/Class/Interface, this is defined; else null

        typeParameters: List<Type>?,
        valueParameters: List<ValueParameter>
    ): ResolvedField? {
        check(valueParameters.isEmpty())

        val generics = findGenericsForMatch(
            field.selfType, selfType,
            valueType, returnType,
            field.selfTypeTypeParams, typeParameters,
            emptyList(), emptyList()
        ) ?: return null
        return ResolvedField(generics, field, emptyList())
    }

    fun resolveFieldType(
        context: ResolutionContext,
        name: String,
        typeParameters: List<Type>?,
        origin: Int
    ): Type {
        println("typeParams: $typeParameters")
        val field = resolveField(context, name, typeParameters)
        if (field == null) {
            val selfScope = context.selfScope
            val codeScope = context.codeScope
            throw IllegalStateException(
                "Could not resolve field ${selfScope?.pathStr}.'$name'<$typeParameters> " +
                        "in ${resolveOrigin(origin)}, scopes: ${codeScope.pathStr}"
            )
        }
        return field.getValueType()
    }

    fun resolveField(
        context: ResolutionContext,
        name: String,
        typeParameters: List<Type>?, // if provided, typically not the case (I've never seen it)
    ): ResolvedField? {
        val returnType = context.targetType
        val selfType = context.selfType
        println("typeParams for field '$name': $typeParameters, selfType: $selfType")
        val valueParameters = emptyList<ValueParameter>()
        val field = MethodResolver.null1()
            ?: findMemberInHierarchy(context.selfScope, name, returnType, selfType, typeParameters, valueParameters)
            ?: findMemberInFile(context.codeScope, name, returnType, selfType, typeParameters, valueParameters)
            ?: findMemberInFile(langScope, name, returnType, selfType, typeParameters, valueParameters)
        return field
    }

}