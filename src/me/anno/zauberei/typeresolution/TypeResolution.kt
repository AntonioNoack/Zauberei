package me.anno.zauberei.typeresolution

import me.anno.zauberei.astbuilder.expression.*
import me.anno.zauberei.astbuilder.expression.constants.NumberExpression
import me.anno.zauberei.astbuilder.expression.constants.StringExpression
import me.anno.zauberei.astbuilder.flow.IfElseBranch
import me.anno.zauberei.astbuilder.flow.ReturnExpression
import me.anno.zauberei.astbuilder.flow.ThrowExpression
import me.anno.zauberei.types.Field
import me.anno.zauberei.types.NullableType
import me.anno.zauberei.types.Scope
import me.anno.zauberei.types.Type
import me.anno.zauberei.types.Types.NullableAnyType
import me.anno.zauberei.types.Types.ThrowableType
import me.anno.zauberei.types.Types.TypelessType
import me.anno.zauberei.types.Types.UnitType

object TypeResolution {

    val knownUnitType = KnownType(UnitType)
    val knownAnyNullableType = KnownType(NullableAnyType)
    val typeless = KnownType(TypelessType)
    val throwableType = KnownType(ThrowableType)

    fun resolveTypesAndNames(root: Scope) {
        forEachScope(root, ::collectConstraints)
    }

    val subTypeConstrains = ArrayList<SubtypeConstraint>()
    val nonNullConstraints = ArrayList<NonNullConstraint>()

    fun setSubType(parentType: ResolvingType, childType: ResolvingType) {
        if (parentType == childType || parentType == knownAnyNullableType) return
        subTypeConstrains.add(SubtypeConstraint(parentType, childType, -1))
    }

    fun setSameType(type1: ResolvingType, type2: ResolvingType) {
        if (type1 == type2) return
        setSubType(type1, type2)
        setSubType(type2, type1)
    }

    fun setNonNull(nullableType: ResolvingType, nonNullType: ResolvingType) {
        nonNullConstraints.add(NonNullConstraint(nullableType, nonNullType, -1))
    }

    // todo resolve all unclear types:
    //  all variables, all expressions, all method calls (because their return type is necessary to find the types)

    var nextTypeId = 0
    fun nextUnknownType(): ToBeResolvedType {
        return ToBeResolvedType(nextTypeId++)
    }

    fun nextType(knownType: Type?): ResolvingType {
        return if (knownType != null) KnownType(knownType)
        else nextUnknownType()
    }

    fun tryGetNonNullType(type: ResolvingType): ResolvingType {
        return if (type is KnownType && type.type is NullableType) {
            KnownType(type.type.base)
        } else {
            type
        }
    }

    fun collectExprConstraints(expr: Expression?, targetType: ResolvingType, functionReturnType: ResolvingType?) {
        expr ?: return

        val givenType = expr.resolvedTypeI ?: nextUnknownType()
        expr.resolvedTypeI = givenType
        setSubType(targetType, givenType)

        when (expr) {
            is CallExpression -> {
                if (expr.typeParameters == null) {
                    // todo set condition for unknown type param list...
                } else {
                    // todo connect the type parameters with the values, when they are used as the values...
                }
                // todo we must have candidates...
                //  we can use them to restrict the type
                for (param in expr.valueParameters) {
                    collectExprConstraints(param, knownAnyNullableType, functionReturnType)
                }
            }
            is StringExpression, is NumberExpression -> {}
            is ExpressionList -> {
                val list = expr.list
                for (i in 0 until list.size - 1) {
                    collectExprConstraints(list[i], knownAnyNullableType, functionReturnType)
                }
                val lastExpr = list.lastOrNull()
                collectExprConstraints(lastExpr, targetType, functionReturnType)
            }
            is PostfixExpression -> {
                expr.variable
                expr.variable.resolvedTypeI
                expr.resolvedTypeI
                when (expr.type) {
                    PostfixMode.INCREMENT, PostfixMode.DECREMENT -> {
                        // post-fix and contained variable will have the same type
                        if (expr.variable.resolvedTypeI == null) {
                            expr.variable.resolvedTypeI = expr.resolvedTypeI
                            collectExprConstraints(expr.variable, targetType, functionReturnType)
                        } else {
                            collectExprConstraints(expr.variable, targetType, functionReturnType)
                            setSameType(expr.resolvedTypeI!!, expr.variable.resolvedTypeI!!)
                        }
                    }
                    PostfixMode.ASSERT_NON_NULL -> {
                        // is this good enough???
                        collectExprConstraints(expr.variable, tryGetNonNullType(targetType), functionReturnType)
                        setNonNull(expr.variable.resolvedTypeI!!, expr.resolvedTypeI!!)
                    }
                }
            }
            is IfElseBranch -> {
                if (expr.ifFalse != null) {

                    collectExprConstraints(expr.ifTrue, knownAnyNullableType, functionReturnType)
                    collectExprConstraints(expr.ifFalse, knownAnyNullableType, functionReturnType)

                    val type = nextUnknownType()
                    expr.resolvedTypeI = type

                    setSubType(type, expr.ifTrue.resolvedTypeI!!)
                    setSubType(type, expr.ifFalse.resolvedTypeI!!)
                } else {
                    // todo (type -> typeless) = type is just discarded... typeless is kind of like Any?
                    collectExprConstraints(expr.ifTrue, typeless, functionReturnType)
                    expr.resolvedTypeI = typeless
                }
            }
            is ReturnExpression -> {
                collectExprConstraints(expr.value, functionReturnType!!, functionReturnType)
                expr.resolvedTypeI = typeless
            }
            is ThrowExpression -> {
                collectExprConstraints(expr.thrown, throwableType, functionReturnType)
                expr.resolvedTypeI = typeless
            }
            else -> {
                IllegalStateException("Create constraints for ${expr.javaClass} == $targetType ($expr)")
                    .printStackTrace()
            }
        }
    }

    fun collectFieldConstrains(field: Field) {
        // field itself
        val valueType = nextType(field.valueType ?: field.initialValue?.resolvedType)
        field.valueType1 = valueType

        collectExprConstraints(
            field.initialValue, valueType,
            null /* null, because return cannot be used in val x = 5 */
        )
    }

    fun collectConstraints(scope: Scope) {

        // if is class, we can define super-class constraints... but do we need them???
        //  should be all resolved already by name

        for (field in scope.fields) {
            collectFieldConstrains(field)
            collectExprConstraints(field.getterExpr, field.valueType1, field.valueType1)
            // todo constraints for setter variable...
            collectExprConstraints(field.setterExpr, knownUnitType, knownUnitType)
        }
        for (init in scope.initialization) {
            init.resolvedType = UnitType
            init.resolvedTypeI = knownUnitType
            collectExprConstraints(init, knownUnitType, knownUnitType)
        }
        for (function in scope.functions) {
            function.resolvedType = UnitType
            function.resolvedTypeI = knownUnitType

            for (param in function.typeParameters) {
                // todo do we need any constraints here?
            }

            // constraints for parameters & resolving initial values
            for (param in function.valueParameters) {
                val paramType = KnownType(param.type)
                param.typeI = paramType
                collectExprConstraints(
                    param.initialValue, paramType,
                    null /* null, because you cannot return from inside initialValues of parameters */
                )
            }

            val returnType = nextType(function.returnType ?: function.body?.resolvedType)
            function.returnTypeI = returnType
            collectExprConstraints(function.body, returnType, returnType)
        }
    }

    fun forEachScope(scope: Scope, callback: (Scope) -> Unit) {
        callback(scope)
        for (child in scope.children) {
            forEachScope(child, callback)
        }
    }
}