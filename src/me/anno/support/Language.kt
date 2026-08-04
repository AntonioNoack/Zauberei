package me.anno.support

enum class Language {
    KOTLIN,
    ZAUBER,

    CSHARP,
    CPP, C,
    PYTHON,
    RUST,
    JAVA,
    TYPESCRIPT,

    LLVM_IR;

    val allowsDefaultsInParameterDeclaration: Boolean
        get() = this == KOTLIN || this == ZAUBER || this == CSHARP

    val allowsValuesAsTypes: Boolean
        get() = this == ZAUBER || this == TYPESCRIPT

    val hasSaveAssignments: Boolean
        get() = this == KOTLIN || this == ZAUBER || this == PYTHON

    val instanceOfName: String?
        get() = when (this) {
            CSHARP, JAVA -> "instanceof"
            PYTHON, ZAUBER, KOTLIN -> "is"
            else -> null
        }

    val notInstanceOfName: String?
        get() = when (this) {
            PYTHON, ZAUBER, KOTLIN -> "!is"
            else -> null
        }

    companion object {
        fun byFileName(fileName: String): Language {
            return when {
                fileName.endsWith(".kt") || fileName.endsWith(".kts") -> KOTLIN
                fileName.endsWith(".cpp") || fileName.endsWith(".hpp") -> CPP
                fileName.endsWith(".c") || fileName.endsWith(".h") -> C
                fileName.endsWith(".java") -> JAVA
                fileName.endsWith(".cs") -> CSHARP
                fileName.endsWith(".rs") -> RUST
                else -> ZAUBER
            }
        }
    }
}