package app.olauncher.features.assistant.ui

import android.graphics.Typeface
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.BackgroundColorSpan
import android.text.style.LeadingMarginSpan
import android.text.style.RelativeSizeSpan
import android.text.style.StyleSpan
import android.text.style.TypefaceSpan

/**
 * Just enough Markdown for chat replies: ``` code blocks, `inline code`, **bold**, *italic*, # headings
 * and - / 1. lists. Unknown syntax stays as plain text; nothing is ever interpreted as a link or HTML.
 */
object Markdown {

    fun render(text: String, codeBackground: Int, density: Float): CharSequence {
        val out = SpannableStringBuilder()
        val parts = text.split("```")
        parts.forEachIndexed { i, part ->
            if (i % 2 == 1) {
                // fenced code: drop the language tag on the first line
                val code = part.substringAfter('\n', part).trimEnd('\n')
                if (out.isNotEmpty() && out.last() != '\n') out.append('\n')
                val start = out.length
                out.append(code)
                out.setSpan(TypefaceSpan("monospace"), start, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                out.setSpan(BackgroundColorSpan(codeBackground), start, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                out.setSpan(RelativeSizeSpan(0.88f), start, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                out.append('\n')
            } else {
                blocks(out, part, codeBackground, density)
            }
        }
        while (out.isNotEmpty() && out.last() == '\n') out.delete(out.length - 1, out.length)
        return out
    }

    private val HEADING = Regex("""^(#{1,6})\s+(.*)$""")
    private val BULLET = Regex("""^(\s*)[-*•]\s+(.*)$""")
    private val NUMBERED = Regex("""^(\s*)(\d+)[.)]\s+(.*)$""")

    private fun blocks(out: SpannableStringBuilder, text: String, codeBg: Int, density: Float) {
        val lines = text.split('\n')
        lines.forEachIndexed { idx, line ->
            val lineStart = out.length
            HEADING.matchEntire(line)?.let { m ->
                inline(out, m.groupValues[2], codeBg)
                out.setSpan(StyleSpan(Typeface.BOLD), lineStart, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                out.setSpan(RelativeSizeSpan(if (m.groupValues[1].length <= 2) 1.18f else 1.08f), lineStart, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            } ?: BULLET.matchEntire(line)?.let { m ->
                val indent = (m.groupValues[1].length / 2 + 1) * (14 * density).toInt()
                out.append("•  ")
                inline(out, m.groupValues[2], codeBg)
                out.setSpan(LeadingMarginSpan.Standard(indent - (10 * density).toInt(), indent + (6 * density).toInt()),
                    lineStart, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            } ?: NUMBERED.matchEntire(line)?.let { m ->
                val indent = (m.groupValues[1].length / 2 + 1) * (14 * density).toInt()
                out.append("${m.groupValues[2]}.  ")
                inline(out, m.groupValues[3], codeBg)
                out.setSpan(LeadingMarginSpan.Standard(indent - (10 * density).toInt(), indent + (10 * density).toInt()),
                    lineStart, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            } ?: inline(out, line, codeBg)
            if (idx < lines.size - 1) out.append('\n')
        }
    }

    /** `code`, **bold**, __bold__, *italic*, _italic_ inside one line. */
    private fun inline(out: SpannableStringBuilder, line: String, codeBg: Int) {
        var i = 0
        while (i < line.length) {
            val c = line[i]
            when {
                c == '`' -> {
                    val end = line.indexOf('`', i + 1)
                    if (end > i) {
                        val s = out.length
                        out.append(line, i + 1, end)
                        out.setSpan(TypefaceSpan("monospace"), s, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                        out.setSpan(BackgroundColorSpan(codeBg), s, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                        i = end + 1; continue
                    }
                }
                (c == '*' || c == '_') && line.startsWith("$c$c", i) -> {
                    val end = line.indexOf("$c$c", i + 2)
                    if (end > i + 2) {
                        val s = out.length
                        inline(out, line.substring(i + 2, end), codeBg)
                        out.setSpan(StyleSpan(Typeface.BOLD), s, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                        i = end + 2; continue
                    }
                }
                (c == '*' || c == '_') && i + 1 < line.length && !line[i + 1].isWhitespace() -> {
                    val end = line.indexOf(c, i + 1)
                    val wordy = c == '_' && i > 0 && line[i - 1].isLetterOrDigit() // snake_case stays
                    if (end > i + 1 && !wordy && !line[end - 1].isWhitespace()) {
                        val s = out.length
                        out.append(line, i + 1, end)
                        out.setSpan(StyleSpan(Typeface.ITALIC), s, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                        i = end + 1; continue
                    }
                }
            }
            out.append(c)
            i++
        }
    }
}
