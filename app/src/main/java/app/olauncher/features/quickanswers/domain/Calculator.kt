package app.olauncher.features.quickanswers.domain

import app.olauncher.features.math.domain.Builtins
import app.olauncher.features.math.domain.Compiler
import app.olauncher.features.math.domain.Expr
import app.olauncher.features.math.domain.MathParser

/**
 * One-line arithmetic for the drawer search, on top of the graphing engine: `12×3+4`, `sin(30°)`,
 * `ln 2`, `5!`, `2^10`, `√2`, `nCr(5,2)`. Only numbers are computed; nothing is executed.
 */
object Calculator {

    private const val OPERATORS = "+-*/^%!√|×÷−°²³("

    /**
     * The value, or null. Unless [forced] (the `=` prefix), plain numbers, words and dates aren't treated
     * as questions: there must be an operator or a function call, and no unknown names.
     */
    fun evaluate(input: String, forced: Boolean = false): Double? {
        val src = prepare(input)
        if (src.isBlank()) return null
        if (!forced) {
            if (src.none { it in OPERATORS }) return null
            if (src.all { it.isDigit() || it == '.' || it == '-' || it == ' ' }) return null // "-5", "2026-10-28"
        }
        val expr = runCatching { MathParser().parseExpression(src) }.getOrNull() ?: return null
        if (expr is Expr.Tuple) return null
        val vars = expr.variables() - Builtins.CONSTANTS.keys
        if (vars.isNotEmpty()) return null
        return runCatching { Compiler().compile(expr)(0.0, 0.0) }.getOrNull()?.takeIf { it.isFinite() }
    }

    /** "12x3" means 12×3 in a search box (x between digits), and Persian digits become ASCII. */
    fun prepare(input: String): String =
        normalizeDigits(input.trim()).replace(Regex("""(?<=\d)\s*[xX]\s*(?=\d)"""), "*").replace(Regex("""(?<=\d),(?=\d{3}(?!\d))"""), "")

    /** Persian/Arabic digits and decimal separator → ASCII; everything else unchanged. */
    fun normalizeDigits(s: String): String = buildString {
        for (c in s) when (c) {
            in '۰'..'۹' -> append('0' + (c - '۰'))
            in '٠'..'٩' -> append('0' + (c - '٠'))
            '٫' -> append('.')
            else -> append(c)
        }
    }

    /** Shows an expression the way it's read: × ÷ instead of * /. */
    fun display(input: String): String = prepare(input).replace(" ", "").replace('*', '×').replace('/', '÷')
}
