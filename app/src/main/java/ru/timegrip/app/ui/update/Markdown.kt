package ru.timegrip.app.ui.update

/**
 * The part of Markdown that GitHub release notes use: headings, bullet and
 * numbered lists, quotes, fenced code, rules and paragraphs, with bold,
 * italic, inline code and links inside them. Anything else stays plain text.
 */
sealed interface MdBlock {
    data class Heading(val level: Int, val text: List<MdSpan>) : MdBlock
    data class Paragraph(val text: List<MdSpan>) : MdBlock
    /** [marker] is null for a bullet and the number for a numbered item. */
    data class ListItem(val depth: Int, val marker: Int?, val text: List<MdSpan>) : MdBlock
    data class Quote(val text: List<MdSpan>) : MdBlock
    data class Code(val text: String) : MdBlock
    data object Rule : MdBlock
}

data class MdSpan(
    val text: String,
    val bold: Boolean = false,
    val italic: Boolean = false,
    val code: Boolean = false,
    val url: String? = null,
)

private val HEADING = Regex("""^(#{1,6})\s+(.*?)\s*#*\s*$""")
private val BULLET = Regex("""^(\s*)[-*+]\s+(.*)$""")
private val NUMBERED = Regex("""^(\s*)(\d+)[.)]\s+(.*)$""")
private val RULE = Regex("""^\s*([-*_])(\s*\1){2,}\s*$""")
private val FENCE = Regex("""^\s*(```|~~~)""")
private val BARE_URL = Regex("""https?://[^\s<>()]+""")

fun parseMarkdown(source: String): List<MdBlock> {
    val blocks = mutableListOf<MdBlock>()
    val lines = source.replace("\r\n", "\n").split('\n')
    // Lines of the block being gathered: a paragraph, list item or quote runs on until a blank line.
    val pending = StringBuilder()
    var pendingBlock: ((String) -> MdBlock)? = null
    var inQuote = false

    fun flush() {
        pendingBlock?.let { blocks += it(pending.toString().trim()) }
        pending.clear()
        pendingBlock = null
        inQuote = false
    }

    fun start(text: String, make: (String) -> MdBlock) {
        flush()
        pending.append(text)
        pendingBlock = make
    }

    var i = 0
    while (i < lines.size) {
        val line = lines[i]
        val fence = FENCE.find(line)
        when {
            fence != null -> {
                flush()
                val close = fence.groupValues[1]
                val code = mutableListOf<String>()
                i++
                while (i < lines.size && !lines[i].trimStart().startsWith(close)) code += lines[i++]
                blocks += MdBlock.Code(code.joinToString("\n"))
            }
            line.isBlank() -> flush()
            RULE.matches(line) -> {
                flush()
                blocks += MdBlock.Rule
            }
            HEADING.matches(line) -> {
                flush()
                val (hashes, text) = HEADING.find(line)!!.destructured
                blocks += MdBlock.Heading(hashes.length, parseInline(text))
            }
            BULLET.matches(line) -> {
                val (indent, text) = BULLET.find(line)!!.destructured
                start(text) { MdBlock.ListItem(depth(indent), null, parseInline(it)) }
            }
            NUMBERED.matches(line) -> {
                val (indent, number, text) = NUMBERED.find(line)!!.destructured
                start(text) { MdBlock.ListItem(depth(indent), number.toInt(), parseInline(it)) }
            }
            line.trimStart().startsWith(">") -> {
                val text = line.trimStart().removePrefix(">").trim()
                if (inQuote) {
                    pending.append(' ').append(text)
                } else {
                    start(text) { MdBlock.Quote(parseInline(it)) }
                    inQuote = true
                }
            }
            // A plain line continues the paragraph, list item or quote above it.
            pendingBlock != null -> pending.append(' ').append(line.trim())
            else -> start(line.trim()) { MdBlock.Paragraph(parseInline(it)) }
        }
        i++
    }
    flush()
    return blocks
}

/** Two spaces (or a tab) of indentation per nesting level, as GitHub writes nested lists. */
private fun depth(indent: String): Int = indent.replace("\t", "  ").length / 2

/** Bold, italic, inline code, `[text](url)` links and bare URLs; a backslash escapes the next character. */
fun parseInline(text: String): List<MdSpan> {
    val spans = mutableListOf<MdSpan>()
    parseInto(text, MdSpan(""), spans)
    // Neighbours with the same style read as one run.
    return spans.fold(mutableListOf()) { merged, span ->
        val last = merged.lastOrNull()
        if (last != null && last.copy(text = "") == span.copy(text = "")) {
            merged[merged.lastIndex] = last.copy(text = last.text + span.text)
        } else {
            merged += span
        }
        merged
    }
}

private fun parseInto(text: String, style: MdSpan, out: MutableList<MdSpan>) {
    val plain = StringBuilder()
    fun emit(span: MdSpan) {
        if (plain.isNotEmpty()) {
            out += style.copy(text = plain.toString())
            plain.clear()
        }
        if (span.text.isNotEmpty()) out += span
    }

    var i = 0
    while (i < text.length) {
        val c = text[i]
        when {
            c == '\\' && i + 1 < text.length -> {
                plain.append(text[i + 1])
                i += 2
                continue
            }
            c == '`' -> {
                val end = text.indexOf('`', i + 1)
                if (end > i) {
                    emit(style.copy(text = text.substring(i + 1, end), code = true))
                    i = end + 1
                    continue
                }
            }
            c == '[' -> {
                val close = text.indexOf("](", i + 1)
                val end = if (close > i) text.indexOf(')', close + 2) else -1
                if (close > i && end > close) {
                    emit(MdSpan(""))
                    parseInto(text.substring(i + 1, close), style.copy(url = text.substring(close + 2, end).trim()), out)
                    i = end + 1
                    continue
                }
            }
            (c == '*' || c == '_') && text.startsWith("$c$c", i) -> {
                val end = text.indexOf("$c$c", i + 2)
                if (end > i + 2) {
                    emit(MdSpan(""))
                    parseInto(text.substring(i + 2, end), style.copy(bold = true), out)
                    i = end + 2
                    continue
                }
            }
            (c == '*' || c == '_') && opensEmphasis(text, i) -> {
                val end = closingEmphasis(text, i)
                if (end > i + 1) {
                    emit(MdSpan(""))
                    parseInto(text.substring(i + 1, end), style.copy(italic = true), out)
                    i = end + 1
                    continue
                }
            }
            style.url == null && (c == 'h') -> {
                val url = BARE_URL.matchAt(text, i)?.value?.trimEnd('.', ',', ';', ':', '!', '?')
                if (url != null) {
                    emit(style.copy(text = url, url = url))
                    i += url.length
                    continue
                }
            }
        }
        plain.append(c)
        i++
    }
    emit(MdSpan(""))
}

/** `_` only emphasises at a word edge, so snake_case names stay as they are. */
private fun opensEmphasis(text: String, i: Int): Boolean {
    val next = text.getOrNull(i + 1) ?: return false
    if (next.isWhitespace()) return false
    return text[i] == '*' || text.getOrNull(i - 1)?.isLetterOrDigit() != true
}

private fun closingEmphasis(text: String, open: Int): Int {
    val c = text[open]
    var j = open + 1
    while (true) {
        j = text.indexOf(c, j)
        if (j < 0) return -1
        val closes = !text[j - 1].isWhitespace() && (c == '*' || text.getOrNull(j + 1)?.isLetterOrDigit() != true)
        if (closes) return j
        j++
    }
}
