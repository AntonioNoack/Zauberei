package me.anno.zauberei.astbuilder

import me.anno.zauberei.astbuilder.expression.Expression
import me.anno.zauberei.astbuilder.expression.ListExpression
import me.anno.zauberei.tokenizer.TokenList
import me.anno.zauberei.tokenizer.TokenType
import me.anno.zauberei.types.Package

class ASTBuilder(val tokens: TokenList, val root: Package) {

    companion object {
        private val classKeywords = listOf("enum", "private")
    }

    val imports = ArrayList<Package>()

    var currPackage = root

    fun buildAST(i0: Int): Pair<Expression, Int> {
        // todo parse until } or end
        // todo depending on context, accept different keywords
        var i = i0

        fun readPath(): Package {
            assert(tokens.equals(++i, TokenType.NAME))
            var path = root.getOrPut(tokens.getString(i++))
            while (tokens.equals(i, TokenType.SYMBOL, ".")) {
                assert(tokens.equals(++i, TokenType.NAME))
                path = path.getOrPut(tokens.getString(i++))
            }
            return path
        }

        while (i < tokens.size) {
            when {
                tokens.equals(i, TokenType.NAME, "package") -> currPackage = readPath()
                tokens.equals(i, TokenType.NAME, "import") -> imports.add(readPath())
                else -> throw NotImplementedError("Unknown token ${tokens.getType(i)}, ${tokens.getString(i)}")
            }
        }
        return ListExpression(emptyList()) to -1
    }
}