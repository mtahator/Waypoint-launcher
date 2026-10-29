package app.olauncher.features.math.domain

/** Expression tree. Only numbers are ever computed from it; nothing is executed. */
sealed class Expr {
    data class Num(val value: Double) : Expr()
    data class Var(val name: String) : Expr()
    data class Neg(val arg: Expr) : Expr()
    data class Bin(val op: Char, val left: Expr, val right: Expr) : Expr()
    data class Call(val name: String, val args: List<Expr>) : Expr()
    data class Postfix(val op: Char, val arg: Expr) : Expr() // '!' factorial, '%' percent, '°' degrees
    data class Tuple(val items: List<Expr>) : Expr() // (x, y) point

    /** Free variable names used anywhere in this tree. */
    fun variables(into: MutableSet<String> = mutableSetOf()): Set<String> {
        when (this) {
            is Var -> into += name
            is Neg -> arg.variables(into)
            is Bin -> { left.variables(into); right.variables(into) }
            is Call -> args.forEach { it.variables(into) }
            is Postfix -> arg.variables(into)
            is Tuple -> items.forEach { it.variables(into) }
            is Num -> Unit
        }
        return into
    }

    fun calls(into: MutableSet<String> = mutableSetOf()): Set<String> {
        when (this) {
            is Call -> { into += name; args.forEach { it.calls(into) } }
            is Neg -> arg.calls(into)
            is Bin -> { left.calls(into); right.calls(into) }
            is Postfix -> arg.calls(into)
            is Tuple -> items.forEach { it.calls(into) }
            else -> Unit
        }
        return into
    }
}

/** One line: `expr`, or `lhs = rhs` (assignment, function definition or equation). */
data class Statement(val lhs: Expr, val rhs: Expr?, val relation: String? = null)

class ParseException(message: String, val position: Int) : Exception(message)

/**
 * Desmos-like syntax: implicit multiplication (`2x`, `3sin x`, `(x+1)(x-1)`, `pi r^2`), `^` and `**`,
 * `|x|`, `x!`, `50%`, `30°`, `√x`, `sin x` without parentheses, `a_1` subscripts, `(x, y)` points,
 * Unicode × ÷ − · ² ³ π and Persian/Arabic digits. Multi-letter words split into known function/
 * constant names, otherwise into single-letter variables (`xy` = x·y).
 */
class MathParser(private val userFunctions: Set<String> = emptySet()) {

    fun parseStatement(input: String): Statement {
        tokens = tokenize(normalize(input))
        pos = 0
        if (tokens.isEmpty()) throw ParseException("empty", 0)
        val lhs = expression()
        var relation: String? = null
        var rhs: Expr? = null
        if (peek()?.type == T.REL) {
            relation = next().text
            rhs = expression()
        }
        if (pos < tokens.size) throw ParseException("unexpected '${tokens[pos].text}'", tokens[pos].at)
        return Statement(lhs, rhs, relation)
    }

    fun parseExpression(input: String): Expr {
        val s = parseStatement(input)
        if (s.rhs != null) throw ParseException("unexpected '='", 0)
        return s.lhs
    }

    // ---- Tokens ----

    private enum class T { NUM, ID, OP, LP, RP, COMMA, BAR, REL }
    private class Tok(val type: T, val text: String, val at: Int, val value: Double = 0.0)

    private var tokens: List<Tok> = emptyList()
    private var pos = 0

    private fun tokenize(s: String): List<Tok> {
        val out = ArrayList<Tok>()
        var i = 0
        while (i < s.length) {
            val c = s[i]
            when {
                c.isWhitespace() -> i++
                c.isDigit() || (c == '.' && i + 1 < s.length && s[i + 1].isDigit()) -> {
                    val start = i
                    while (i < s.length && (s[i].isDigit() || s[i] == '.')) i++
                    // scientific notation 1.5e3 / 2e-4 (only when an exponent digit follows)
                    if (i + 1 < s.length && (s[i] == 'e' || s[i] == 'E')) {
                        var j = i + 1
                        if (j < s.length && (s[j] == '+' || s[j] == '-')) j++
                        if (j < s.length && s[j].isDigit()) {
                            i = j
                            while (i < s.length && s[i].isDigit()) i++
                        }
                    }
                    val text = s.substring(start, i)
                    val v = text.toDoubleOrNull() ?: throw ParseException("bad number '$text'", start)
                    out += Tok(T.NUM, text, start, v)
                }
                c.isLetter() -> {
                    val start = i
                    while (i < s.length && s[i].isLetter()) i++
                    var word = s.substring(start, i)
                    // subscript: a_1, v_0, x_max
                    if (i < s.length && s[i] == '_') {
                        var j = i + 1
                        while (j < s.length && s[j].isLetterOrDigit()) j++
                        if (j > i + 1 && word.length == 1) { word = s.substring(start, j); i = j }
                    }
                    splitWord(word, start).forEach { out += it }
                }
                c == '(' || c == '[' -> { out += Tok(T.LP, "(", i); i++ }
                c == ')' || c == ']' -> { out += Tok(T.RP, ")", i); i++ }
                c == ',' -> { out += Tok(T.COMMA, ",", i); i++ }
                c == '|' -> { out += Tok(T.BAR, "|", i); i++ }
                c == '<' || c == '>' || c == '=' || c == '≤' || c == '≥' -> {
                    val two = if (i + 1 < s.length && s[i + 1] == '=' && c != '=') "$c=" else "$c"
                    out += Tok(T.REL, two.replace("≤", "<=").replace("≥", ">="), i); i += two.length
                }
                c in "+-*/^!%°√" -> { out += Tok(T.OP, c.toString(), i); i++ }
                else -> throw ParseException("unexpected '$c'", i)
            }
        }
        return out
    }

    private fun splitWord(word: String, at: Int): List<Tok> {
        if (word.contains('_') || word.length == 1) return listOf(Tok(T.ID, word, at))
        val lower = word.lowercase()
        if (lower in Builtins.FUNCTIONS || lower in Builtins.CONSTANTS) return listOf(Tok(T.ID, lower, at))
        val out = ArrayList<Tok>()
        var i = 0
        while (i < word.length) {
            val match = Builtins.WORDS.firstOrNull { lower.startsWith(it, i) }
            if (match != null) { out += Tok(T.ID, match, at + i); i += match.length }
            else { out += Tok(T.ID, word[i].toString(), at + i); i++ }
        }
        return out
    }

    // ---- Grammar ----

    private fun peek(): Tok? = tokens.getOrNull(pos)
    private fun next(): Tok = tokens.getOrNull(pos++) ?: throw ParseException("incomplete", tokens.lastOrNull()?.at ?: 0)
    private fun isOp(t: Tok?, op: String) = t != null && t.type == T.OP && t.text == op

    private fun expression(): Expr {
        var left = term()
        while (true) {
            val t = peek()
            left = when {
                isOp(t, "+") -> { pos++; Expr.Bin('+', left, term()) }
                isOp(t, "-") -> { pos++; Expr.Bin('-', left, term()) }
                else -> return left
            }
        }
    }

    private fun term(): Expr {
        var left = unary()
        while (true) {
            val t = peek()
            left = when {
                isOp(t, "*") -> { pos++; Expr.Bin('*', left, unary()) }
                isOp(t, "/") -> { pos++; Expr.Bin('/', left, unary()) }
                startsFactor(t) -> Expr.Bin('*', left, power()) // implicit: 2x, 3(x+1), x sin x
                else -> return left
            }
        }
    }

    /** A token that can begin an implicitly multiplied factor. `|` only when not closing an |abs|. */
    private fun startsFactor(t: Tok?): Boolean = when (t?.type) {
        T.NUM, T.ID, T.LP -> true
        T.OP -> t.text == "√"
        T.BAR -> absDepth == 0
        else -> false
    }

    private fun unary(): Expr {
        val t = peek()
        return when {
            isOp(t, "-") -> { pos++; Expr.Neg(unary()) }
            isOp(t, "+") -> { pos++; unary() }
            else -> power()
        }
    }

    private fun power(): Expr {
        val base = postfix(primary())
        return if (isOp(peek(), "^")) { pos++; Expr.Bin('^', base, unary()) } else base
    }

    private fun postfix(e: Expr): Expr {
        var r = e
        while (true) {
            val t = peek()
            r = when {
                isOp(t, "!") -> { pos++; Expr.Postfix('!', r) }
                isOp(t, "%") -> { pos++; Expr.Postfix('%', r) }
                isOp(t, "°") -> { pos++; Expr.Postfix('°', r) }
                else -> return r
            }
        }
    }

    private var absDepth = 0

    private fun primary(): Expr {
        val t = next()
        return when (t.type) {
            T.NUM -> Expr.Num(t.value)
            T.LP -> {
                val first = expression()
                if (peek()?.type == T.COMMA) {
                    val items = mutableListOf(first)
                    while (peek()?.type == T.COMMA) { pos++; items += expression() }
                    expect(T.RP)
                    Expr.Tuple(items)
                } else { expect(T.RP); first }
            }
            T.BAR -> {
                absDepth++
                val inner = expression()
                absDepth--
                expect(T.BAR)
                Expr.Call("abs", listOf(inner))
            }
            T.OP -> if (t.text == "√") Expr.Call("sqrt", listOf(postfix(primary())))
                else throw ParseException("unexpected '${t.text}'", t.at)
            T.ID -> identifier(t)
            else -> throw ParseException(if (t.type == T.RP) "unbalanced ')'" else "unexpected '${t.text}'", t.at)
        }
    }

    private fun identifier(t: Tok): Expr {
        val name = t.text
        val isFn = name in Builtins.FUNCTIONS || name in userFunctions
        if (!isFn) return Expr.Var(name)
        // sin^2 x  →  (sin x)^2
        var powerOf: Expr? = null
        if (isOp(peek(), "^") && name in Builtins.FUNCTIONS) { pos++; powerOf = postfix(primary()) }
        val args: List<Expr> = if (peek()?.type == T.LP) {
            pos++
            val list = mutableListOf(expression())
            while (peek()?.type == T.COMMA) { pos++; list += expression() }
            expect(T.RP)
            list
        } else if (name in userFunctions && name !in Builtins.FUNCTIONS) {
            return Expr.Var(name) // f without parentheses is a plain variable
        } else {
            // sin x, sin 2x, sin x cos x: the argument is a product of powers without + or −
            var arg = power()
            while (true) {
                val p = peek() ?: break
                val plain = p.type == T.NUM || (p.type == T.ID && p.text !in Builtins.FUNCTIONS && p.text !in userFunctions)
                if (!plain) break
                arg = Expr.Bin('*', arg, power())
            }
            listOf(arg)
        }
        val call = Expr.Call(name, args)
        return if (powerOf != null) Expr.Bin('^', call, powerOf) else call
    }

    private fun expect(type: T) {
        val t = peek()
        if (t?.type != type) throw ParseException(if (type == T.RP) "missing ')'" else "expected $type", t?.at ?: (tokens.lastOrNull()?.at ?: 0))
        pos++
    }

    companion object {
        fun normalize(s: String): String = buildString {
            for (c in s) when (c) {
                in '۰'..'۹' -> append('0' + (c - '۰'))
                in '٠'..'٩' -> append('0' + (c - '٠'))
                '٫' -> append('.')
                '×', '·', '⋅', '∙' -> append('*')
                '÷' -> append('/')
                '−', '–', '—' -> append('-')
                '²' -> append("^2")
                '³' -> append("^3")
                'π' -> append(" pi ")
                'τ' -> append(" tau ")
                'θ' -> append('t')
                '∞' -> append(" inf ")
                '٪' -> append('%')
                else -> append(c)
            }
        }.replace("**", "^")
    }
}
