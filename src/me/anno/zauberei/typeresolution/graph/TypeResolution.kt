package me.anno.zauberei.typeresolution.graph

import me.anno.zauberei.astbuilder.expression.*
import me.anno.zauberei.astbuilder.expression.constants.SpecialValueExpression
import me.anno.zauberei.astbuilder.expression.constants.NumberExpression
import me.anno.zauberei.astbuilder.expression.constants.StringExpression
import me.anno.zauberei.astbuilder.flow.*
import me.anno.zauberei.types.*
import me.anno.zauberei.types.Types.AnyClassType
import me.anno.zauberei.types.Types.AnyIterableType
import me.anno.zauberei.types.Types.AnyType
import me.anno.zauberei.types.Types.BooleanType
import me.anno.zauberei.types.Types.IntType
import me.anno.zauberei.types.Types.NothingType
import me.anno.zauberei.types.Types.NullableAnyType
import me.anno.zauberei.types.Types.ThrowableType
import me.anno.zauberei.types.Types.TypelessType
import me.anno.zauberei.types.Types.UnitType

// todo for generic (all) functions,
//  we could pre-determine from the type how many valid options there are,
//  e.g. most functions won't have generic types

object TypeResolution {

    val knownUnitType = KnownType(UnitType)
    val knownAnyNullableType = KnownType(NullableAnyType)
    val knownAnyType = KnownType(AnyType)
    val typeless = KnownType(TypelessType)
    val throwableType = KnownType(ThrowableType)
    val anyIterableType = KnownType(AnyIterableType)
    val booleanType = KnownType(BooleanType)
    val intType = KnownType(IntType)
    val anyClassType = KnownType(AnyClassType)
    val nothingType = KnownType(NothingType)

    fun resolveTypesAndNames(root: Scope) {
        forEachScope(root, ::collectConstraints)
    }

    fun forEachScope(scope: Scope, callback: (Scope) -> Unit) {
        callback(scope)
        for (child in scope.children) {
            forEachScope(child, callback)
        }
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
        return if (type is KnownType && type.type is UnionType && NullType in type.type.types) {
            KnownType(UnionType(type.type.types - NullType))
        } else type
    }

    fun collectExprConstraints(expr: Expression?, targetType: ResolvingType, functionReturnType: ResolvingType?) {
        expr ?: return

        // todo skip this, if the expression is typeless, anyway
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
                    collectExprConstraints(param.value, knownAnyNullableType, functionReturnType)
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
                expr.base
                expr.base.resolvedTypeI
                expr.resolvedTypeI
                when (expr.type) {
                    PostfixType.INCREMENT, PostfixType.DECREMENT -> {
                        // post-fix and contained variable will have the same type
                        if (expr.base.resolvedTypeI == null) {
                            expr.base.resolvedTypeI = expr.resolvedTypeI
                            collectExprConstraints(expr.base, targetType, functionReturnType)
                        } else {
                            collectExprConstraints(expr.base, targetType, functionReturnType)
                            setSameType(expr.resolvedTypeI!!, expr.base.resolvedTypeI!!)
                        }
                    }
                    PostfixType.ASSERT_NON_NULL -> {
                        // is this good enough???
                        collectExprConstraints(expr.base, tryGetNonNullType(targetType), functionReturnType)
                        setNonNull(expr.base.resolvedTypeI!!, expr.resolvedTypeI!!)
                    }
                }
            }
            is IfElseBranch -> {
                if (expr.elseBranch != null) {

                    collectExprConstraints(expr.ifBranch, knownAnyNullableType, functionReturnType)
                    collectExprConstraints(expr.elseBranch, knownAnyNullableType, functionReturnType)
                    collectExprConstraints(expr.condition, booleanType, functionReturnType)

                    val type = nextUnknownType()
                    expr.resolvedTypeI = type

                    setSubType(type, expr.ifBranch.resolvedTypeI!!)
                    setSubType(type, expr.elseBranch.resolvedTypeI!!)
                } else {
                    // todo (type -> typeless) = type is just discarded... typeless is kind of like Any?
                    collectExprConstraints(expr.ifBranch, typeless, functionReturnType)
                    expr.resolvedTypeI = typeless
                }
            }
            is ForLoop -> {
                // todo if variableType is defined, use that for the generics
                collectExprConstraints(expr.iterable, anyIterableType, functionReturnType)
                collectExprConstraints(expr.body, typeless, functionReturnType)
                expr.resolvedTypeI = typeless
            }
            is NamedCallExpression -> {
                collectExprConstraints(expr.base, knownAnyType, functionReturnType)
                // todo add constraints for type parameters
                // todo add constraints for finding a valid method or field for calling
            }
            is ReturnExpression -> {
                collectExprConstraints(expr.value, functionReturnType!!, functionReturnType)
                expr.resolvedTypeI = nothingType
            }
            is ThrowExpression -> {
                collectExprConstraints(expr.thrown, throwableType, functionReturnType)
                expr.resolvedTypeI = nothingType
            }
            is AssignmentExpression -> {
                // todo actually, it is the type of expr.variableName... (might be a getter-chain though)
                collectExprConstraints(expr.newValue, knownAnyNullableType, functionReturnType)
                expr.resolvedTypeI = typeless
            }
            is VariableExpression -> {
                // todo conditions...
            }
            is BinaryTypeOp -> {
                // todo conditions...
            }
            is SpecialValueExpression -> {
                // todo conditions...
            }
            is WhileLoop -> {
                collectExprConstraints(expr.condition, booleanType, functionReturnType)
                collectExprConstraints(expr.body, typeless, functionReturnType)
            }
            is CompareOp -> {
                collectExprConstraints(expr.value, intType, functionReturnType)
            }
            is AssignIfMutableExpr -> {
                // todo this is complicated...
            }
            is LambdaExpression -> {
                // todo this is really complicated, too...
            }
            is PrefixExpression -> {
                // todo this is like AssignIfMutableExpr - really complicated...
            }
            is GetClassFromTypeExpression -> {
                setSubType(givenType, anyClassType)
            }
            is CheckEqualsOp -> {
                // left and right may be of the same type, but we have no hard constraints...
                collectExprConstraints(expr.left, knownAnyNullableType, functionReturnType)
                collectExprConstraints(expr.right, knownAnyNullableType, functionReturnType)
                // return type will be Boolean
                setSubType(givenType, booleanType)
            }
            is DoubleColonPrefix -> {
                // todo type is some lambda/callable, but we don't know more than that...
                // todo body must be analysed, and it might return something, too,
                //  but we don't know what...
            }
            is BreakExpression, is ContinueExpression -> {
                expr.resolvedTypeI = nothingType
            }
            else -> {
                IllegalStateException("Create constraints for ${expr.javaClass} == $targetType ($expr)")
                    .printStackTrace()
            }
        }
    }

    fun collectFieldConstrains(scope: Scope, field: Field) {
        // field itself
        val valueType = nextType(field.valueType ?: field.initialValue?.resolvedType)
        field.valueType1 = valueType

        val func = findFunction(scope)
        collectExprConstraints(
            field.initialValue, valueType,
            func?.functionReturnType /* null, because return cannot be used in val x = 5 */
        )
    }

    fun findFunction(scope: Scope): Scope? {
        var scope = scope
        while (true) {
            if (scope.scopeType == ScopeType.METHOD) return scope
            scope = scope.parent ?: return null
        }
    }

    fun collectConstraints(scope: Scope) {

        // if is class, we can define super-class constraints... but do we need them???
        //  should be all resolved already by name

        for (field in scope.fields) {
            collectFieldConstrains(scope, field)
            collectExprConstraints(field.getterExpr, field.valueType1, field.valueType1)
            // todo constraints for setter variable...
            collectExprConstraints(field.setterExpr, knownUnitType, knownUnitType)
        }
        for (init in scope.code) {
            init.resolvedType = UnitType
            init.resolvedTypeI = knownUnitType
            collectExprConstraints(init, knownUnitType, knownUnitType)
        }
        for (function in scope.methods) {
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
}