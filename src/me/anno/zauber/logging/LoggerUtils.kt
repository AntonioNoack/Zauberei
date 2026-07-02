package me.anno.zauber.logging

object LoggerUtils {
    fun disableCompileLoggers() {
        LogManager.disable(
            "TypeResolution,ASTSimplifier,MemberResolver,Inheritance,FindMemberMatch," +
                    "CallExpression,SuperCallExpression,CallWithNames,FieldMethodResolver," +
                    "MethodResolver,ResolvedMethod," +
                    "ConstructorResolver," +
                    "ResolvedField,Field,FieldResolver,FieldExpression," +
                    "SimpleGetField,SimpleSetField,SimpleGetClassField,SimpleSetClassField,SimpleCall," +
                    "LambdaExpression,DataClassGenerator," +
                    "UnderdefinedValueParameter," +
                    "Runtime,BuildCommand,CodeReconstruction," +
                    "SimpleMethodCall,MethodOverrides"
        )
    }
}