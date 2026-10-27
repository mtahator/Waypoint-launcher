package app.olauncher.features.automation.domain

import java.util.regex.Pattern
import java.util.regex.PatternSyntaxException

/**
 * Guarded regular expressions for user-written TextMatches conditions:
 * bounded pattern length, a static check rejecting nested quantifiers (catastrophic backtracking),
 * bounded input, and a character-access budget that aborts runaway matching.
 */
object SafeRegex {
    const val MAX_PATTERN_LENGTH = 200
    const val MAX_INPUT_LENGTH = 2_000
    private const val ACCESS_BUDGET = 200_000

    // A group containing a quantifier, itself followed by a quantifier: (a+)+, (\w*)*, (x{1,9})+ ...
    private val NESTED_QUANTIFIER = Regex("""\((?:[^()\\]|\\.)*[+*}](?:[^()\\]|\\.)*\)\s*[+*{]""")

    sealed interface Check {
        data object Ok : Check
        data object TooLong : Check
        data object Dangerous : Check
        data object Invalid : Check
    }

    fun check(pattern: String): Check {
        if (pattern.isEmpty()) return Check.Invalid
        if (pattern.length > MAX_PATTERN_LENGTH) return Check.TooLong
        if (NESTED_QUANTIFIER.containsMatchIn(pattern)) return Check.Dangerous
        return try {
            Pattern.compile(pattern)
            Check.Ok
        } catch (_: PatternSyntaxException) {
            Check.Invalid
        }
    }

    /** False for unsafe patterns, invalid patterns, or matches that exceed the work budget. */
    fun find(pattern: String, input: String, ignoreCase: Boolean): Boolean {
        if (check(pattern) != Check.Ok) return false
        val flags = if (ignoreCase) Pattern.CASE_INSENSITIVE or Pattern.UNICODE_CASE else 0
        return try {
            Pattern.compile(pattern, flags).matcher(BudgetedCharSequence(input.take(MAX_INPUT_LENGTH))).find()
        } catch (_: BudgetExceeded) {
            false
        } catch (_: StackOverflowError) {
            false // java.util.regex recurses on some alternations; deep recursion is treated as no match
        }
    }

    private class BudgetExceeded : RuntimeException() {
        override fun fillInStackTrace(): Throwable = this
    }

    private class BudgetedCharSequence(
        private val inner: CharSequence,
        private val budget: IntArray = intArrayOf(ACCESS_BUDGET),
    ) : CharSequence {
        override val length get() = inner.length
        override fun get(index: Int): Char {
            if (--budget[0] < 0) throw BudgetExceeded()
            return inner[index]
        }

        override fun subSequence(startIndex: Int, endIndex: Int): CharSequence =
            BudgetedCharSequence(inner.subSequence(startIndex, endIndex), budget)

        override fun toString() = inner.toString()
    }
}
