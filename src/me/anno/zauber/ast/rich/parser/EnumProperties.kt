package me.anno.zauber.ast.rich.parser

import me.anno.langserver.VSCodeType
import me.anno.zauber.ast.rich.Flags
import me.anno.zauber.ast.rich.expression.ExpressionList
import me.anno.zauber.ast.rich.expression.constants.NumberExpression
import me.anno.zauber.ast.rich.expression.constants.StringExpression
import me.anno.zauber.ast.rich.expression.unresolved.AssignmentExpression
import me.anno.zauber.ast.rich.expression.unresolved.ConstructorExpression
import me.anno.zauber.ast.rich.expression.unresolved.FieldExpression
import me.anno.zauber.ast.rich.member.Constructor
import me.anno.zauber.ast.rich.parameter.NamedParameter
import me.anno.zauber.scope.Scope
import me.anno.zauber.scope.ScopeType
import me.anno.zauber.tokenizer.TokenType
import me.anno.zauber.typeresolution.CallWithNames.createArrayOfExpr
import me.anno.zauber.typeresolution.ResolutionContext
import me.anno.zauber.types.Types
import me.anno.zauber.types.impl.ClassType

fun ZauberASTBuilderBase.readEnumBody(): Int {

    val origin0 = origin(i)
    var endIndex = tokens.findToken(i, ";")
    if (endIndex < 0) endIndex = tokens.size
    val enumScope = currPackage

    val companionScope = enumScope.getOrPutCompanion()

    // val needsPrimaryConstructor = companionScope.primaryConstructorScope == null
    val primaryConstructorScope = companionScope.getOrCreatePrimaryConstructorScope()
    primaryConstructorScope.selfAsConstructor = Constructor(
        emptyList(), primaryConstructorScope,
        null, ExpressionList(ArrayList(), primaryConstructorScope, origin0),
        Flags.NONE, origin0
    )

    push(endIndex) {
        var ordinal = 0
        while (i < tokens.size) {
            // read enum value
            readAnnotations()

            val origin = origin(i)
            val name = consumeName(VSCodeType.ENUM_MEMBER, 0)

            val typeParameters = readTypeParameters(null)
            val valueParameters = if (tokens.equals(i, TokenType.OPEN_CALL)) {
                readValueParameters()
            } else emptyList()

            val keywords = packFlags()
            val entryScope = readClassBody(name, Flags.NONE, ScopeType.ENUM_ENTRY_CLASS)
            val integerValue = if (consumeIf("=")) readExpression() else null

            // todo add name and id as parameters
            val extraValueParameters = listOf(
                NamedParameter(integerValue ?: NumberExpression(ordinal.toString(), companionScope, origin)),
                NamedParameter(StringExpression(name, companionScope, origin)),
            )

            val initialValue = ConstructorExpression(
                enumScope, typeParameters,
                extraValueParameters + valueParameters,
                null, enumScope, origin
            )
            val valueType =
                if (enumScope.typeParameters.isNotEmpty()) null // we need to resolve them
                else enumScope.typeWithArgs
            val field = companionScope.addField(
                null, false, isMutable = false, null,
                name, valueType, initialValue, keywords, origin
            )
            entryScope.objectField = field

            val fieldExpr = FieldExpression(field, primaryConstructorScope, origin)
            primaryConstructorScope.code.add(AssignmentExpression(fieldExpr, initialValue))

            ordinal++
            readComma()
        }
    }

    createEnumProperties(companionScope, enumScope, origin0)
    return endIndex
}

fun createEnumProperties(companionScope: Scope, enumScope: Scope, origin: Long) {

    companionScope.setEmptyTypeParams()

    val constructorScope = companionScope.getOrCreatePrimaryConstructorScope()
    val listType = ClassType(Types.List.clazz, listOf(enumScope.typeWithArgs), origin)
    val entryValues = enumScope.enumEntries.map { entryScope ->
        val field = entryScope.objectField!!
        FieldExpression(field, constructorScope, origin)
    }
    val context = ResolutionContext(enumScope, null, false, null)
    val initialValue = createArrayOfExpr(context, enumScope.typeWithArgs, entryValues, constructorScope, origin)

    val entriesField = constructorScope.addField(
        null, false, isMutable = false, null,
        "entries", listType,
        initialValue, Flags.SYNTHETIC, origin
    )

    val entriesFieldExpr = FieldExpression(entriesField, constructorScope, origin)
    constructorScope.code.add(AssignmentExpression(entriesFieldExpr, initialValue))
}
