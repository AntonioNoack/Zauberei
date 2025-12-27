package zauber

enum class Boolean {
    FALSE,
    TRUE;

    fun shortcutAnd(other: Boolean): Boolean = native("this && other")
    fun shortcutOr(other: Boolean): Boolean = native("this || other")
}

