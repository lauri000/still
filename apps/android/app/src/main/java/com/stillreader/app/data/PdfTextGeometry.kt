package com.stillreader.app.data

import android.graphics.RectF
import com.stillreader.app.core.PdfTextRepair
import io.legere.pdfiumandroid.PdfTextPage
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** PDFium character indices are not always string offsets (e.g. unmapped glyphs).
 * Fail closed when they disagree; never use guessed geometry to change spelling. */
internal class PdfTextGeometry(private val page: PdfTextPage, private val raw: String) {
    private val aligned = page.textPageCountChars() == raw.length
    private fun box(index: Int): RectF? {
        if (!aligned || index !in raw.indices || raw[index].isSurrogate()) return null
        if (page.textPageGetUnicode(index) != raw[index]) return null
        return page.textPageGetCharBox(index)
    }
    private fun sameLine(a: RectF, b: RectF, scale: Double): Boolean =
        b.left >= a.left && (abs(a.bottom - b.bottom) < scale * .35 ||
            abs(a.height()) > abs(b.height()) * 1.6 && abs(a.top - b.top) < scale * .2)

    fun input(height: Double): PdfTextRepair.Input {
        val removed = mutableSetOf<Int>()
        for (match in Regex("(?:ffi|ffl|ff|fi|fl|ft|st) (?=\\p{Ll})").findAll(raw)) {
            val i = match.range.last
            val a = box(i - 2) ?: continue; val b = box(i - 1) ?: continue
            val space = box(i) ?: continue; val c = box(i + 1) ?: continue
            // Some producers encode a 1pt font and scale it with the text matrix;
            // FPDFText_GetFontSize alone then understates its visible size.
            val scale = max(page.getFontSize(i - 1), max(abs(b.height()), abs(c.height())).toDouble())
            // PDFium expands a ligature to letters with identical ink bounds. A zero-width
            // generated space after that glyph is a font-metric artefact, not a word break.
            if (scale > 0 && a == b && abs(space.width()) < .1 && sameLine(b, c, scale) &&
                c.left - b.right < scale * .18) removed.add(i)
        }
        val first = raw.indexOfFirst { !it.isWhitespace() }
        val last = raw.indexOfLast { !it.isWhitespace() }
        val firstBox = box(first); val lastBox = box(last)
        return PdfTextRepair.Input(raw, removed,
            topMargin = firstBox != null && min(firstBox.top, firstBox.bottom) > height * .88,
            bottomMargin = lastBox != null && max(lastBox.top, lastBox.bottom) < height * .12)
    }

    fun boundary(offset: Int, split: Boolean): Boolean {
        val left = box(offset - 1) ?: return false
        var rightOffset = offset
        if (!split) while (rightOffset < raw.length && raw[rightOffset].isWhitespace()) rightOffset++
        val right = box(rightOffset) ?: return false
        val scale = max(page.getFontSize(offset - 1), max(abs(left.height()), abs(right.height())).toDouble())
        if (scale <= 0 || !sameLine(left, right, scale)) return false
        val gap = right.left - left.right
        return if (split) {
            val previous = box(offset - 2)
            val following = box(offset + 1)
            val neighboringGap = max(previous?.let { left.left - it.right } ?: 0f,
                following?.let { it.left - right.right } ?: 0f)
            gap > max(.8, scale * .16) && gap > neighboringGap * 2.5
        } else {
            val space = box(offset) ?: return false
            val overlapsNext = space.right > right.left + scale * .1
            (abs(space.width()) < .1 || overlapsNext) && gap < scale * .15
        }
    }
}
