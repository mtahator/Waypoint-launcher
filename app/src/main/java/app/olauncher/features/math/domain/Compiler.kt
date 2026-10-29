package app.olauncher.features.math.domain

import kotlin.math.PI

/** f(x, y): every compiled expression takes both, most ignore one. */
typealias Fn2 = (Double, Double) -> Double

data class UserFunction(val params: List<String>, val body: Expr)

class UndefinedVariable(val names: Set<String>) : Exception("undefined: ${names.joinToString()}")

/**
 * Turns an [Expr] into a closure once, so plotting evaluates thousands of points without walking the
 * tree or looking up names. Variables are resolved at compile time; recompile when a slider moves.
 */
class Compiler(
    private val variables: Map<String, Double> = emptyMap(),
    private val functions: Map<String, UserFunction> = emptyMap(),
    /** Names bound per evaluation (normally x and y). */
    private val free: Set<String> = setOf("x", "y"),
) {
    fun compile(e: Expr): Fn2 {
        val missing = mutableSetOf<String>()
        val fn = build(e, emptyMap(), 0, missing)
        if (missing.isNotEmpty()) throw UndefinedVariable(missing)
        return fn
    }

    private fun build(e: Expr, locals: Map<String, Fn2>, depth: Int, missing: MutableSet<String>): Fn2 = when (e) {
        is Expr.Num -> { val v = e.value; { _, _ -> v } }
        is Expr.Var -> variable(e.name, locals, missing)
        is Expr.Neg -> { val a = build(e.arg, locals, depth, missing); { x, y -> -a(x, y) } }
        is Expr.Bin -> {
            val a = build(e.left, locals, depth, missing)
            val b = build(e.right, locals, depth, missing)
            when (e.op) {
                '+' -> { x, y -> a(x, y) + b(x, y) }
                '-' -> { x, y -> a(x, y) - b(x, y) }
                '*' -> { x, y -> a(x, y) * b(x, y) }
                '/' -> { x, y -> a(x, y) / b(x, y) }
                '^' -> { x, y -> Builtins.pow(a(x, y), b(x, y)) }
                else -> error("op ${e.op}")
            }
        }
        is Expr.Postfix -> {
            val a = build(e.arg, locals, depth, missing)
            when (e.op) {
                '!' -> { x, y -> Builtins.factorial(a(x, y)) }
                '%' -> { x, y -> a(x, y) / 100.0 }
                '°' -> { x, y -> a(x, y) * PI / 180.0 }
                else -> error("op ${e.op}")
            }
        }
        is Expr.Call -> call(e, locals, depth, missing)
        is Expr.Tuple -> throw IllegalArgumentException("a point isn't a number")
    }

    private fun variable(name: String, locals: Map<String, Fn2>, missing: MutableSet<String>): Fn2 {
        locals[name]?.let { return it }
        if (name in free) return if (name == "y") { _, y -> y } else { x, _ -> x }
        variables[name]?.let { v -> return { _, _ -> v } }
        Builtins.CONSTANTS[name]?.let { v -> return { _, _ -> v } }
        missing += name
        return { _, _ -> Double.NaN }
    }

    private fun call(e: Expr.Call, locals: Map<String, Fn2>, depth: Int, missing: MutableSet<String>): Fn2 {
        val args = e.args.map { build(it, locals, depth, missing) }
        functions[e.name]?.let { uf ->
            require(depth < 24) { "${e.name} calls itself" }
            require(uf.params.size == args.size) { "${e.name} needs ${uf.params.size} argument(s)" }
            return build(uf.body, uf.params.zip(args).toMap(), depth + 1, missing)
        }
        val unary = Builtins.unary(e.name)
        if (unary != null && args.size == 1) { val a = args[0]; return { x, y -> unary(a(x, y)) } }
        if (e.name !in Builtins.FUNCTIONS) { missing += e.name; return { _, _ -> Double.NaN } }
        return { x, y -> Builtins.call(e.name, args.map { it(x, y) }) }
    }
}
