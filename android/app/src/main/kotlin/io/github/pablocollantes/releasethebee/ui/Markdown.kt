package io.github.pablocollantes.releasethebee.ui

import android.graphics.Typeface
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.BulletSpan
import android.text.style.RelativeSizeSpan
import android.text.style.StyleSpan
import android.text.style.TypefaceSpan

/**
 * Just enough Markdown for an LLM summary: headings, bullet lists, **bold**, *italic*
 * and `code`. Anything else shows as written.
 */
object Markdown {
    private val HEADING = Regex("^#{1,6}\\s+")
    private val BULLET = Regex("^\\s*[-*•]\\s+")
    private val INLINE = Regex("\\*\\*(.+?)\\*\\*|`([^`]+)`|(?<![*\\w])\\*(?![\\s*])(.+?)(?<!\\s)\\*(?!\\w)")

    fun render(src: String, ui: Ui): CharSequence {
        val out = SpannableStringBuilder()
        val lines = src.replace("\r\n", "\n").trim().split("\n")
        lines.forEachIndexed { i, raw ->
            val line = raw.trimEnd()
            val start = out.length
            when {
                HEADING.containsMatchIn(line) -> {
                    inline(out, line.replaceFirst(HEADING, ""))
                    out.setSpan(StyleSpan(Typeface.BOLD), start, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    out.setSpan(RelativeSizeSpan(1.08f), start, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
                BULLET.containsMatchIn(line) -> inline(out, line.replaceFirst(BULLET, ""))
                else -> inline(out, line)
            }
            if (i < lines.lastIndex) out.append('\n')
            // A paragraph span must end on a paragraph boundary: after the newline, or at the end.
            if (BULLET.containsMatchIn(line)) {
                out.setSpan(BulletSpan(ui.dp(10), ui.accent, ui.dp(3)), start, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
        }
        return out
    }

    private fun inline(out: SpannableStringBuilder, text: String) {
        var last = 0
        for (m in INLINE.findAll(text)) {
            out.append(text, last, m.range.first)
            val s = out.length
            val (content, span) = when {
                m.groups[1] != null -> m.groupValues[1] to StyleSpan(Typeface.BOLD)
                m.groups[2] != null -> m.groupValues[2] to TypefaceSpan("monospace")
                else -> m.groupValues[3] to StyleSpan(Typeface.ITALIC)
            }
            out.append(content)
            out.setSpan(span, s, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            last = m.range.last + 1
        }
        out.append(text, last, text.length)
    }
}
