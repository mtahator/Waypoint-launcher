package app.olauncher.features.math.domain

/**
 * A list of lines, like Desmos: `a = 2` (slider), `f(x) = x^2`, `y = a sin(x)`, `x = 3`,
 * `x^2 + y^2 = 9`, `(1, 2)`, or a plain `2^10` that just shows its value.
 */
class MathProgram(lines: List<String>) {

    enum class Kind { EMPTY, VALUE, VARIABLE, FUNCTION, GRAPH_Y, GRAPH_X, IMPLICIT, POINT, REGION_Y, REGION_X, REGION, ERROR }

    data class Line(
        val text: String,
        val kind: Kind,
        val value: Double? = null,
        /** `a = 2.5`: a literal the UI shows as a slider. */
        val sliderName: String? = null,
        val error: String? = null,
        /** Undefined names the UI can offer as sliders. */
        val missing: List<String> = emptyList(),
        /** y = f(x) for GRAPH_Y; x = g(y) for GRAPH_X (called with y). */
        val curve: ((Double) -> Double)? = null,
        val implicit: Fn2? = null,
        val point: Pair<Double, Double>? = null,
        /** Inequalities: +1 shades above (REGION_Y) / right (REGION_X), −1 below / left. */
        val side: Int = 0,
        /** `<` / `>`: the boundary is dashed (not included). */
        val strict: Boolean = false,
    ) {
        val plottable get() = curve != null || implicit != null || point != null
        val isRegion get() = kind == Kind.REGION_Y || kind == Kind.REGION_X || kind == Kind.REGION
    }

    val lines: List<Line>
    val variables: Map<String, Double>

    init {
        val userFns = lines.mapNotNull { FN_DEF.find(it)?.groupValues?.get(1) }
            .filter { it != "x" && it != "y" }.toSet()
        val parser = MathParser(userFns)
        val parsed: List<Result<Statement>?> = lines.map { t ->
            if (t.isBlank()) null else runCatching { parser.parseStatement(t) }
        }

        // Function definitions: f(x) = …
        val functions = mutableMapOf<String, UserFunction>()
        parsed.forEach { r ->
            val s = r?.getOrNull() ?: return@forEach
            val lhs = s.lhs
            if (s.relation == "=" && lhs is Expr.Call && lhs.name in userFns && lhs.args.all { it is Expr.Var } && s.rhs != null)
                functions[lhs.name] = UserFunction(lhs.args.map { (it as Expr.Var).name }, s.rhs)
        }

        // Variables: a = …, resolved in dependency order
        val assignments = mutableMapOf<Int, Pair<String, Expr>>()
        parsed.forEachIndexed { i, r ->
            val s = r?.getOrNull() ?: return@forEachIndexed
            val lhs = s.lhs
            if (s.relation == "=" && lhs is Expr.Var && lhs.name != "x" && lhs.name != "y" && s.rhs != null &&
                !s.rhs.variables().any { it == "x" || it == "y" }
            ) assignments[i] = lhs.name to s.rhs
        }
        val values = mutableMapOf<String, Double>()
        var progress = true
        while (progress) {
            progress = false
            for ((_, a) in assignments) {
                if (a.first in values) continue
                val v = runCatching { Compiler(values, functions, emptySet()).compile(a.second)(0.0, 0.0) }.getOrNull()
                if (v != null) { values[a.first] = v; progress = true }
            }
        }
        variables = values

        this.lines = lines.mapIndexed { i, text ->
            val r = parsed[i] ?: return@mapIndexed Line(text, Kind.EMPTY)
            val s = r.getOrElse { e -> return@mapIndexed Line(text, Kind.ERROR, error = e.message ?: "can't read this") }
            runCatching { classify(text, s, assignments[i], functions, values) }
                .getOrElse { e ->
                    if (e is UndefinedVariable) Line(text, Kind.ERROR, error = "undefined: ${e.names.joinToString()}",
                        missing = e.names.filter { it.length <= 3 && it != "x" && it != "y" }.sorted())
                    else Line(text, Kind.ERROR, error = e.message ?: "can't read this")
                }
        }
    }

    private fun classify(
        text: String, s: Statement, assignment: Pair<String, Expr>?,
        functions: Map<String, UserFunction>, values: Map<String, Double>,
    ): Line {
        val c = Compiler(values, functions)
        val lhs = s.lhs
        val rhs = s.rhs
        if (s.relation != null && s.relation != "=") return inequality(text, s, c)

        if (assignment != null) {
            val name = assignment.first
            val v = values[name] ?: run { Compiler(values, functions, emptySet()).compile(assignment.second); null }
            val literal = assignment.second.let { it is Expr.Num || (it is Expr.Neg && it.arg is Expr.Num) }
            return Line(text, Kind.VARIABLE, value = v, sliderName = if (literal) name else null)
        }
        if (rhs != null && lhs is Expr.Call && lhs.name in functions) {
            // check the body compiles (params bound) so undefined names show up
            val uf = functions.getValue(lhs.name)
            Compiler(values, functions, uf.params.toSet()).compile(uf.body)
            return Line(text, Kind.FUNCTION)
        }
        if (rhs == null) {
            if (lhs is Expr.Tuple) return point(text, lhs, c)
            val vars = lhs.variables()
            val f = c.compile(lhs)
            return when {
                "y" in vars -> throw IllegalArgumentException("add \"= …\" to plot an equation in y")
                "x" in vars -> Line(text, Kind.GRAPH_Y, curve = { x -> f(x, 0.0) })
                else -> Line(text, Kind.VALUE, value = f(0.0, 0.0))
            }
        }
        if (lhs is Expr.Var && lhs.name == "y" && "y" !in rhs.variables()) {
            val f = c.compile(rhs); return Line(text, Kind.GRAPH_Y, curve = { x -> f(x, 0.0) })
        }
        if (rhs is Expr.Var && rhs.name == "y" && "y" !in lhs.variables()) {
            val f = c.compile(lhs); return Line(text, Kind.GRAPH_Y, curve = { x -> f(x, 0.0) })
        }
        if (lhs is Expr.Var && lhs.name == "x" && "x" !in rhs.variables()) {
            val g = c.compile(rhs); return Line(text, Kind.GRAPH_X, curve = { y -> g(0.0, y) })
        }
        val vars = lhs.variables() + rhs.variables()
        if ("x" !in vars && "y" !in vars) {
            val a = c.compile(lhs)(0.0, 0.0); val b = c.compile(rhs)(0.0, 0.0)
            return Line(text, Kind.VALUE, value = a - b, error = if (kotlin.math.abs(a - b) < 1e-9) null else "not equal")
        }
        val l = c.compile(lhs); val r = c.compile(rhs)
        return Line(text, Kind.IMPLICIT, implicit = { x, y -> l(x, y) - r(x, y) })
    }

    /**
     * `y > f(x)`, `f(x) <= y`, `x < g(y)` shade one side of a curve; anything else (`x^2 + y^2 < 9`)
     * shades where F(x, y) = lhs − rhs has the right sign ([Line.implicit] is positive inside).
     */
    private fun inequality(text: String, s: Statement, c: Compiler): Line {
        var lhs = s.lhs
        var rhs = s.rhs!!
        var rel = s.relation!!
        val strict = rel == "<" || rel == ">"
        // put y (or x) on the left
        if ((rhs is Expr.Var && (rhs.name == "y" || rhs.name == "x")) && !(lhs is Expr.Var && (lhs.name == "y" || lhs.name == "x"))) {
            val t = lhs; lhs = rhs; rhs = t
            rel = when (rel) { "<" -> ">"; ">" -> "<"; "<=" -> ">="; else -> "<=" }
        }
        val greater = rel.startsWith(">")
        if (lhs is Expr.Var && lhs.name == "y" && "y" !in rhs.variables()) {
            val f = c.compile(rhs)
            return Line(text, Kind.REGION_Y, curve = { x -> f(x, 0.0) }, side = if (greater) 1 else -1, strict = strict)
        }
        if (lhs is Expr.Var && lhs.name == "x" && "x" !in rhs.variables()) {
            val g = c.compile(rhs)
            return Line(text, Kind.REGION_X, curve = { y -> g(0.0, y) }, side = if (greater) 1 else -1, strict = strict)
        }
        val vars = lhs.variables() + rhs.variables()
        val l = c.compile(lhs); val r = c.compile(rhs)
        if ("x" !in vars && "y" !in vars) {
            val a = l(0.0, 0.0); val b = r(0.0, 0.0)
            val ok = if (greater) (if (strict) a > b else a >= b) else (if (strict) a < b else a <= b)
            return Line(text, Kind.VALUE, value = if (ok) 1.0 else 0.0, error = if (ok) null else "false")
        }
        val sign = if (greater) 1.0 else -1.0
        return Line(text, Kind.REGION, implicit = { x, y -> sign * (l(x, y) - r(x, y)) }, strict = strict)
    }

    private fun point(text: String, t: Expr.Tuple, c: Compiler): Line {
        require(t.items.size == 2) { "a point needs two coordinates" }
        val px = c.compile(t.items[0])(0.0, 0.0)
        val py = c.compile(t.items[1])(0.0, 0.0)
        return Line(text, Kind.POINT, point = px to py)
    }

    companion object {
        private val FN_DEF = Regex("""^\s*([a-zA-Z](?:_\w+)?)\s*\(\s*[a-zA-Z](?:\s*,\s*[a-zA-Z])*\s*\)\s*=""")
    }
}
