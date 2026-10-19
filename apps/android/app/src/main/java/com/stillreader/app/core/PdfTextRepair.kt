package com.stillreader.app.core

import java.util.Locale
import kotlin.math.abs

/** Deterministic import-time repair. Offsets and provenance are UTF-16, as in Book. */
object PdfTextRepair {
    const val VERSION = 1
    data class Input(val text: String, val ligatureSpaces: Set<Int> = emptySet(),
        val topMargin: Boolean = false, val bottomMargin: Boolean = false, val enabled: Boolean = true)
    data class Edit(val start: Int, val end: Int, val replacement: String)
    data class Result(val pages: List<MappedText>) {
        fun relocate(page: Int, offset: Int): Pair<Int, Int> {
            var bestPage = page; var bestOffset = 0; var distance = Long.MAX_VALUE
            pages.forEachIndexed { p, text -> text.origins.forEachIndexed { i, origin ->
                if ((origin ushr 32).toInt() == page) {
                    val delta = abs((origin and 0xffffffffL) - offset.toLong())
                    if (delta < distance) { distance = delta; bestPage = p; bestOffset = i }
                }
            } }
            return bestPage to bestOffset
        }
    }
    class MappedText(var text: String, var origins: LongArray) {
        fun apply(edits: List<Edit>) {
            if (edits.isEmpty()) return
            val ordered = edits.sortedBy { it.start }
            val size = text.length + ordered.sumOf { it.replacement.length - (it.end - it.start) }
            val result = StringBuilder(size); val mapped = LongArray(size)
            var cursor = 0; var out = 0
            for (edit in ordered) {
                require(edit.start >= cursor && edit.end in edit.start..text.length)
                result.append(text, cursor, edit.start)
                origins.copyInto(mapped, out, cursor, edit.start); out += edit.start - cursor
                result.append(edit.replacement)
                val origin = origins.getOrElse(edit.start) { origins.lastOrNull() ?: 0L }
                repeat(edit.replacement.length) { mapped[out++] = origin }
                cursor = edit.end
            }
            result.append(text, cursor, text.length)
            origins.copyInto(mapped, out, cursor, origins.size)
            text = result.toString(); origins = mapped
        }
        fun replace(regex: Regex, transform: (MatchResult) -> String) =
            apply(regex.findAll(text).map { Edit(it.range.first, it.range.last + 1, transform(it)) }.toList())
    }
    private val letters = Regex("[\\p{L}\\p{M}]+")
    private val words = Regex("[\\p{L}\\p{M}]+(?:[-’'][\\p{L}\\p{M}]+)*")
    private val lineHyphen = Regex("(?<![\\p{L}\\p{M}])([\\p{L}\\p{M}]+)[-‐][\\t ]*\\n[\\t ]*([\\p{L}\\p{M}]+)")
    private val beforeFragment = Regex("[-‐]\\s*$")
    private val afterFragment = Regex("^[-‐]\\s*\\n")
    private val compoundPrefixes = setOf("self", "well", "ill", "all", "half", "quasi", "ex", "non")
    private val ligatures = mapOf('ﬀ' to "ff", 'ﬁ' to "fi", 'ﬂ' to "fl", 'ﬃ' to "ffi", 'ﬄ' to "ffl", 'ﬅ' to "st", 'ﬆ' to "st")
    private fun lower(text: String) = text.lowercase(Locale.ROOT)

    fun eligible(page: BookPage) = !page.isEdited && (page.textProcessingVersion ?: 0) < VERSION

    fun applyTo(book: Book, result: Result, enabled: List<Boolean>): Book {
        require(book.pages.size == result.pages.size && enabled.size == book.pages.size)
        fun relocate(position: ReadingPosition?): ReadingPosition? {
            position ?: return null
            val index = book.pages.indexOfFirst { it.id == position.pageID }
            if (index < 0 || !enabled[index]) return position
            val (page, offset) = result.relocate(index, position.utf16Offset)
            return position.copy(pageID = book.pages[page].id, utf16Offset = offset)
        }
        return book.copy(position = relocate(book.position), readingStart = relocate(book.readingStart),
            pages = book.pages.mapIndexed { index, page ->
                if (!enabled[index]) page else page.copy(text = result.pages[index].text,
                    processedText = result.pages[index].text, textProcessingVersion = VERSION,
                    isReviewed = page.isReviewed && page.text == result.pages[index].text)
            })
    }

    /** evidence is asked only about plausible word boundaries, with ORIGINAL page offsets. */
    fun repair(inputs: List<Input>, englishWords: Set<String> = emptySet(), checkpoint: () -> Unit = {},
               evidence: (Int, Int, Boolean) -> Boolean = { _, _, _ -> false }): Result {
        val pages = inputs.mapIndexed { index, input ->
            checkpoint()
            MappedText(input.text, LongArray(input.text.length) { (index.toLong() shl 32) or it.toLong() }).also { page ->
                if (input.enabled) {
                    page.apply(input.ligatureSpaces.filter { it in input.text.indices && input.text[it] == ' ' }.map { Edit(it, it + 1, "") })
                    page.replace(Regex("\\u00AD[\\t ]*(?:\\r?\\n[\\t ]*)?")) { "" }
                    page.replace(Regex("\\r\\n?|[ﬀ-ﬆ]|\\uFFFE|\\u0000|\\uFEFF|\\u00A0")) {
                        when (it.value) {
                            "\r\n", "\r" -> "\n"
                            "\uFFFE" -> "-\n" // PDFium's discretionary-hyphen marker; compounds still need the hyphen.
                            "\u00AD", "\u0000", "\uFEFF" -> ""
                            "\u00A0" -> " "
                            else -> ligatures[it.value[0]] ?: it.value
                        }
                    }
                }
            }
        }
        removeFurniture(inputs, pages)
        val vocabulary = HashMap<String, Int>()
        for ((pageIndex, page) in pages.withIndex()) {
            checkpoint()
            for (match in words.findAll(page.text)) {
                // Fragments of a wrapped word are not evidence for standalone words.
                val before = page.text.substring((match.range.first - 3).coerceAtLeast(0), match.range.first)
                val after = page.text.substring(match.range.last + 1, (match.range.last + 4).coerceAtMost(page.text.length))
                if (beforeFragment.containsMatchIn(before) || afterFragment.containsMatchIn(after)) continue
                if (match.range.first == 0 && pageIndex > 0 && pages[pageIndex - 1].text.trimEnd().endsWith('-')) continue
                val word = lower(match.value)
                vocabulary[word] = (vocabulary[word] ?: 0) + 1
            }
        }
        fun count(word: String) = vocabulary[lower(word)] ?: 0
        val commonEnglish = listOf("the", "and", "of", "to", "is", "that", "with").sumOf { count(it) }
        val useEnglish = commonEnglish >= maxOf(5, vocabulary.values.sum() / 50)
        fun known(word: String) = count(word) > 0 || useEnglish && lower(word) in englishWords
        fun inflected(word: String): Boolean {
            if (!useEnglish) return false
            val value = lower(word)
            val roots = mutableSetOf<String>()
            for (suffix in listOf("ing", "ed", "ly", "ness", "ful", "less", "s")) {
                if (value.endsWith(suffix) && value.length > suffix.length + 3) {
                    val stem = value.dropLast(suffix.length)
                    roots.add(stem)
                    if (suffix == "ing" || suffix == "ed") {
                        roots.add(stem + "e")
                        if (stem.length >= 2 && stem.last() == stem[stem.lastIndex - 1]) roots.add(stem.dropLast(1))
                    }
                }
            }
            return roots.any(::known)
        }
        fun separator(left: String, right: String): String {
            if (count("$left-$right") > 0) return "-"
            if (count(left + right) > 0) return ""
            if (known("$left-$right")) return "-"
            if (known(left + right) || inflected(left + right)) return ""
            if (lower(left) in compoundPrefixes || right.firstOrNull()?.isUpperCase() == true && !left.all(Char::isUpperCase)) return "-"
            if (known(left) && known(right)) return "-"
            return ""
        }
        for ((index, page) in pages.withIndex()) {
            checkpoint()
            if (!inputs[index].enabled) continue
            val splits = mutableListOf<Edit>()
            for (word in letters.findAll(page.text)) {
                if (word.value.length !in 4..64 || count(word.value) > 1 || useEnglish && lower(word.value) in englishWords) continue
                val candidates = (1..word.value.length - 2).filter { split ->
                    if (split == 1 && word.value[0] !in "IaA") return@filter false
                    count(word.value.substring(0, split)) >= 2 && count(word.value.substring(split)) >= 2
                }
                // A unique, well-supported segmentation plus a real visual word gap.
                val supported = candidates.filter { split ->
                    val at = word.range.first + split
                    val source = (page.origins[at] and 0xffffffffL).toInt()
                    evidence(index, source, true)
                }
                if (supported.size == 1) {
                    val at = word.range.first + supported.single(); splits.add(Edit(at, at, " "))
                }
            }
            page.apply(splits)
            // Recover a spurious space only if the intact word occurs elsewhere AND geometry agrees.
            val joins = mutableListOf<Edit>()
            val tokens = letters.findAll(page.text).toList()
            for ((left, right) in tokens.zipWithNext()) {
                val start = left.range.last + 1; val end = right.range.first
                val gap = page.text.substring(start, end)
                val dropCap = gap == "\n" && left.value.length == 1 && left.value[0].isUpperCase()
                if (gap != " " && !dropCap || count(left.value + right.value) < 2) continue
                val source = (page.origins[start] and 0xffffffffL).toInt()
                if (evidence(index, source, false)) joins.add(Edit(start, end, ""))
            }
            page.apply(joins)
            // Replace only the separator; retain exact source positions for both word fragments.
            while (true) {
                checkpoint()
                val edits = lineHyphen.findAll(page.text).map { match ->
                    val left = match.groups[1]!!; val right = match.groups[2]!!
                    Edit(left.range.last + 1, right.range.first, separator(left.value, right.value))
                }.toList()
                if (edits.isEmpty()) break
                page.apply(edits)
            }
            page.replace(Regex("(?<=\\p{N})[-‐][\\t ]*\\n[\\t ]*(?=\\p{L})")) { "-" }
        }
        // A word may straddle a physical page. It belongs to its starting page; provenance
        // for the carried suffix keeps a saved position on the following page attached to it.
        for (index in 0 until pages.lastIndex) {
            if (!inputs[index].enabled || !inputs[index + 1].enabled) continue
            val previous = pages[index]; val next = pages[index + 1]
            val tail = Regex("(?<![\\p{L}\\p{M}])([\\p{L}\\p{M}]+)[-‐][\\t ]*\\n?\\s*$").find(previous.text) ?: continue
            val head = Regex("^\\s*([\\p{Ll}\\p{M}]+)").find(next.text) ?: continue
            val left = tail.groups[1]!!; val right = head.groups[1]!!
            val sep = separator(left.value, right.value)
            val suffixOrigins = next.origins.copyOfRange(right.range.first, right.range.last + 1)
            previous.apply(listOf(Edit(left.range.last + 1, previous.text.length, sep)))
            previous.text += right.value
            previous.origins += suffixOrigins
            next.apply(listOf(Edit(0, right.range.last + 1, "")))
        }
        for ((index, page) in pages.withIndex()) if (inputs[index].enabled) {
            page.replace(Regex("[\\t ]*\\n[\\t ]*")) { "\n" }
            page.replace(Regex("^[\\s\\p{Z}]+|[\\s\\p{Z}]+$")) { "" }
        }
        return Result(pages)
    }

    private fun removeFurniture(inputs: List<Input>, pages: List<MappedText>) {
        fun key(line: String): String? {
            val value = line.trim()
            if (value.length > 100 || Regex("(?i)^(chapter|part|book|section)\\s+[\\divxlcdm]+$").matches(value)) return null
            if (Regex("^[0-9]+$").matches(value)) return "#"
            if (!Regex("^(?:[0-9]+|[ivxlcdm]+)\\s+.+|.+\\s+(?:[0-9]+|[ivxlcdm]+)$").matches(value)) return null
            return value.replace(Regex("^(?:[0-9]+|[ivxlcdm]+)\\s+|\\s+(?:[0-9]+|[ivxlcdm]+)$"), "#")
        }
        val top = HashMap<String, Int>(); val bottom = HashMap<String, Int>()
        for ((index, page) in pages.withIndex()) {
            if (!inputs[index].enabled || page.text.count { it == '\n' } < 4) continue
            if (inputs[index].topMargin) key(page.text.substringBefore('\n'))?.let { top[it] = (top[it] ?: 0) + 1 }
            if (inputs[index].bottomMargin) key(page.text.trimEnd().substringAfterLast('\n'))?.let { bottom[it] = (bottom[it] ?: 0) + 1 }
        }
        for ((index, page) in pages.withIndex()) {
            if (!inputs[index].enabled || page.text.count { it == '\n' } < 4) continue
            val edits = mutableListOf<Edit>()
            val first = page.text.substringBefore('\n')
            if (inputs[index].topMargin && (top[key(first)] ?: 0) >= 3) edits.add(Edit(0, first.length + 1, ""))
            val end = page.text.trimEnd().length; val start = page.text.lastIndexOf('\n', end - 1) + 1
            if (inputs[index].bottomMargin && (bottom[key(page.text.substring(start, end))] ?: 0) >= 3) edits.add(Edit(start, page.text.length, ""))
            page.apply(edits)
        }
    }
}
