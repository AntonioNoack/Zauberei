package me.anno.zauberei.typeresolution.members

import me.anno.zauberei.astbuilder.Method
import me.anno.zauberei.astbuilder.TokenListIndex.resolveOrigin
import me.anno.zauberei.astbuilder.expression.Expression
import me.anno.zauberei.typeresolution.ResolutionContext
import me.anno.zauberei.typeresolution.TypeResolution.getSelfType
import me.anno.zauberei.typeresolution.TypeResolution.langScope
import me.anno.zauberei.typeresolution.TypeResolution.resolveType
import me.anno.zauberei.typeresolution.ValueParameter
import me.anno.zauberei.types.Scope
import me.anno.zauberei.types.Type
import me.anno.zauberei.types.impl.ClassType

object MethodResolver : MemberResolver<Method, ResolvedMethod>() {

    override fun findMemberInScope(
        scope: Scope?, name: String,

        returnType: Type?, // sometimes, we know what to expect from the return type
        selfType: Type?, // if inside Companion/Object/Class/Interface, this is defined; else null

        typeParameters: List<Type>?,
        valueParameters: List<ValueParameter>
    ): ResolvedMethod? {
        scope ?: return null
        val scopeSelfType = getSelfType(scope)
        for (method in scope.methods) {
            if (method.name != name) continue
            if (method.typeParameters.isNotEmpty()) {
                println("Given $method on $selfType, with target $returnType, can we deduct any generics from that?")
            }
            val methodReturnType = if (returnType != null) {
                getMethodReturnType(scopeSelfType, method)
            } else method.returnType // no resolution invoked (fast-path)
            val match = findMemberMatch(
                method, methodReturnType, returnType,
                selfType, typeParameters, valueParameters
            )
            if (match != null) return match
        }
        return null
    }

    fun getMethodReturnType(scopeSelfType: Type?, method: Method): Type? {
        if (method.returnType == null) {
            if (false) println("Resolving ${method.innerScope}.type by ${method.body}")
            val context = ResolutionContext(
                method.innerScope,
                method.selfType ?: scopeSelfType,
                false, null
            )
            method.returnType = resolveType(context, method.body!!)
        }
        return method.returnType
    }

    fun findMemberMatch(
        method: Method,
        methodReturnType: Type?,

        returnType: Type?, // sometimes, we know what to expect from the return type
        selfType: Type?, // if inside Companion/Object/Class/Interface, this is defined; else null

        typeParameters: List<Type>?,
        valueParameters: List<ValueParameter>
    ): ResolvedMethod? {
        val generics = findGenericsForMatch(
            method.selfType, selfType,
            methodReturnType, returnType,
            method.typeParameters, typeParameters,
            method.valueParameters, valueParameters
        ) ?: return null
        val selfType = selfType ?: method.selfType
        val context = ResolutionContext(
            method.innerScope, selfType,
            false, returnType
        )
        val ownerTypes = (selfType as? ClassType)?.typeParameters ?: emptyList()
        return ResolvedMethod(ownerTypes, method, generics, context)
    }

    fun resolveCallType(
        context: ResolutionContext,
        expr: Expression,
        name: String,
        constructor: ResolvedCallable<*>?,
        typeParameters: List<Type>?,
        valueParameters: List<ValueParameter>,
    ): Type {
        val targetType = context.targetType
        val selfType = context.selfType
        val m = MethodResolver
        val method = constructor
            ?: m.findMemberInHierarchy(context.selfScope, name, targetType, selfType, typeParameters, valueParameters)
            ?: m.findMemberInFile(context.codeScope, name, targetType, selfType, typeParameters, valueParameters)
            ?: m.findMemberInFile(langScope, name, targetType, selfType, typeParameters, valueParameters)
        val f = FieldResolver
        val field = null1()
            ?: f.findMemberInHierarchy(context.selfScope, name, selfType, targetType, typeParameters, valueParameters)
            ?: f.findMemberInFile(context.codeScope, name, selfType, targetType, typeParameters, valueParameters)
            ?: f.findMemberInFile(langScope, name, selfType, targetType, typeParameters, valueParameters)
        val candidates =
            listOfNotNull(method?.getTypeFromCall(), field?.getTypeFromCall())
        if (candidates.isEmpty()) {
            val selfScope = context.selfScope
            val codeScope = context.codeScope
            println("self-scope methods[${selfScope?.pathStr}.'$name']: ${selfScope?.methods?.filter { it.name == name }}")
            println("code-scope methods[${codeScope.pathStr}.'$name']: ${codeScope.methods.filter { it.name == name }}")
            println("lang-scope methods[${langScope.pathStr}.'$name']: ${langScope.methods.filter { it.name == name }}")
            throw IllegalStateException(
                "Could not resolve method ${selfScope?.pathStr}.'$name'<$typeParameters>($valueParameters) " +
                        "in ${resolveOrigin(expr.origin)}, scopes: ${codeScope.pathStr}"
            )
        }
        if (candidates.size > 1) throw IllegalStateException("Cannot have both a method and a type with the same name '$name': $candidates")
        return candidates.first()
    }

    fun null1(): ResolvedField? {
        return null
    }
}