package me.anno.zauber

import me.anno.utils.ResetThreadLocal.Companion.threadLocal
import me.anno.utils.StdlibLoader
import me.anno.zauber.scope.Scope
import me.anno.zauber.scope.ScopeInitType
import me.anno.zauber.scope.ScopeType

// todo make variable capture by lambdas explicit:
//  mark mutable fields as captured;
//  mutable fields then need some sort of wrapper in the method

// todo at compile-time define types???
// todo collect field names & visibility flags at collectNames-time? would allow for immediate name resolution for first names of chains
// todo make any field const-able; if a field is const:
//  - it must be computable from just that expression
//  - and other const values
//  - comptime exact maths?
//  - allow file IO?
//  - allow method calls
//  - execute with specializations ofc

// todo expand macros:
//   compile-time if
//   compile-time loop (duplicating instructions)
//   compile-time type replacements??? e.g. float -> double

// todo like Zig, just import .h/.hpp files, and use their types and functions

object Zauber {
    const val STDLIB_NAME = "zauber"
    val root by threadLocal {
        Scope("ROOT").apply {
            scopeType = ScopeType.PACKAGE
            setEmptyTypeParams()

            // ensure zauber is a package
            getOrPut(STDLIB_NAME, ScopeType.PACKAGE).apply {
                setEmptyTypeParams()
            }

            addInitPart(ScopeInitType.DISCOVER_MEMBERS) {
                StdlibLoader.loadCode("src/BaseTypes.kt")
                StdlibLoader.loadCode("src/Numbers.kt")
                StdlibLoader.loadCode("src/Strings.kt")
                StdlibLoader.loadCode("src/Println.kt")
                StdlibLoader.loadCode("src/IO.kt")
                StdlibLoader.loadCode("src/Collections.kt")
                StdlibLoader.loadCode("src/Ranges.kt")
                StdlibLoader.loadCode("src/Exceptions.kt")
                StdlibLoader.loadCode("src/Reflection.kt")
                StdlibLoader.loadCode("src/Lambdas.kt")
                StdlibLoader.loadCode("src/Macros.kt")
            }
        }
    }
}