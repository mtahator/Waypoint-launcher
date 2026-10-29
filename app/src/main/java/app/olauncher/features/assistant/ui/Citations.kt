package app.olauncher.features.assistant.ui

import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextPaint
import android.text.style.ClickableSpan
import android.text.style.RelativeSizeSpan
import android.text.style.SuperscriptSpan
import android.view.View
import app.olauncher.features.websearch.domain.Source
import app.olauncher.features.websearch.domain.WebSearch

/** Turns [n] in an answer into small red tappable citations that open source n. */
object Citations {
    fun link(text: CharSequence, sources: List<Source>, color: Int, open: (String) -> Unit): CharSequence {
        if (sources.isEmpty()) return text
        val out = SpannableStringBuilder(text)
        for (m in WebSearch.CITATION.findAll(text).toList().asReversed()) {
            val n = m.groupValues[1].toInt()
            val src = sources.getOrNull(n - 1) ?: continue
            val start = m.range.first; val end = m.range.last + 1
            out.replace(start, end, "$n")
            out.setSpan(object : ClickableSpan() {
                override fun onClick(widget: View) = open(src.url)
                override fun updateDrawState(ds: TextPaint) { ds.color = color; ds.isUnderlineText = false; ds.isFakeBoldText = true }
            }, start, start + "$n".length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            out.setSpan(SuperscriptSpan(), start, start + "$n".length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            out.setSpan(RelativeSizeSpan(0.72f), start, start + "$n".length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        return out
    }
}
