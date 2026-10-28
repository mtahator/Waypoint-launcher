package app.olauncher.features.quickanswers.domain

import kotlin.math.PI
import kotlin.math.E
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * Arithmetic for the drawer search (Spotlight-style). A small recursive-descent parser: numbers,
 * + − × ÷ ^ %, parentheses, unary minus, √/sqrt, π/pi, e. Persian/Arabic digits and × ÷ − are accepted.
 * It only ever computes numbers; nothing is executed.
 */
object Calculator {

    /** The value, or null if [input] isn't a complete expression with at least one operation. */
    fun evaluate(input: String): Double? {
        val src = normalize(input)
        if (src.isEmpty() || src.none { it in OPERATORS } && !src.contains("sqrt")) return null
        if (src.all { it.isDigit() || it == '.' || it == '-' }) return null // "-5", "2026-10" aren't questions
        return runCatching {
            val p = Parser(src)
            val v = p.expression()
            if (!p.atEnd()) null else v.takeIf { it.isFinite() }
        }.getOrNull()
    }

    private const val OPERATORS = "+-*/^%()√"

    /** Persian/Arabic digits and decimal separator → ASCII; everything else unchanged. */
    fun normalizeDigits(s: String): String = buildString {
        for (c in s) when (c) {
            in '۰'..'۹' -> append('0' + (c - '۰'))
            in '٠'..'٩' -> append('0' + (c - '٠'))
            '٫' -> append('.')
            else -> append(c)
        }
    }

    internal fun normalize(s: String): String = buildString {
        for (c in s.trim().lowercase()) when (c) {
            in '۰'..'۹' -> append('0' + (c - '۰'))
            in '٠'..'٩' -> append('0' + (c - '٠'))
            '×', 'x' -> append('*')
            '÷', ':' -> append('/')
            '−', '–' -> append('-')
            '٫' -> append('.')
            ',', '٬', ' ', '_' -> Unit
            'π' -> append("pi")
            else -> append(c)
        }
    }.replace("**", "^")

    private class Parser(private val s: String) {
        private var i = 0
        fun atEnd() = i >= s.length

        fun expression(): Double {
            var v = term()
            while (!atEnd()) {
                when (s[i]) {
                    '+' -> { i++; v += term() }
                    '-' -> { i++; v -= term() }
                    else -> return v
                }
            }
            return v
        }

        private fun term(): Double {
            var v = power()
            while (!atEnd()) {
                when (s[i]) {
                    '*' -> { i++; v *= power() }
                    '/' -> { i++; val d = power(); require(d != 0.0); v /= d }
                    else -> return v
                }
            }
            return v
        }

        private fun power(): Double {
            val base = unary()
            if (!atEnd() && s[i] == '^') { i++; return base.pow(power()) } // right-associative
            return base
        }

        private fun unary(): Double = when {
            atEnd() -> error("end")
            s[i] == '-' -> { i++; -unary() }
            s[i] == '+' -> { i++; unary() }
            else -> postfix(primary())
        }

        private fun postfix(v: Double): Double {
            var r = v
            while (!atEnd() && s[i] == '%') { i++; r /= 100.0 }
            return r
        }

        private fun primary(): Double {
            val c = s[i]
            return when {
                c == '(' -> { i++; val v = expression(); require(!atEnd() && s[i] == ')'); i++; v }
                c == '√' -> { i++; sqrtOf(unary()) }
                s.startsWith("sqrt", i) -> { i += 4; sqrtOf(unary()) }
                s.startsWith("pi", i) -> { i += 2; PI }
                c == 'e' && !s.startsWith("e", i + 1) -> { i++; E }
                c.isDigit() || c == '.' -> number()
                else -> error("unexpected")
            }
        }

        private fun sqrtOf(v: Double): Double { require(v >= 0); return sqrt(v) }

        private fun number(): Double {
            val start = i
            while (!atEnd() && (s[i].isDigit() || s[i] == '.')) i++
            return s.substring(start, i).toDouble()
        }
    }
}
