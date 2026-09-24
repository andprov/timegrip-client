package ru.timegrip.app.ui.update

import org.junit.Assert.assertEquals
import org.junit.Test

class MarkdownTest {
    private fun plain(spans: List<MdSpan>) = spans.joinToString("") { it.text }

    @Test
    fun releaseNotesAsPublished() {
        // The 1.4.26 body as GitHub returns it.
        val blocks = parseMarkdown(
            "## What's Changed\r\n\r\n### Forms\r\n" +
                "- Validation errors now use the server's per-field details, so they are shown translated\r\n",
        )
        assertEquals(3, blocks.size)
        assertEquals(2, (blocks[0] as MdBlock.Heading).level)
        assertEquals("Forms", plain((blocks[1] as MdBlock.Heading).text))
        val item = blocks[2] as MdBlock.ListItem
        assertEquals(null, item.marker)
        assertEquals("Validation errors now use the server's per-field details, so they are shown translated", plain(item.text))
    }

    @Test
    fun listsNestAndContinue() {
        val blocks = parseMarkdown("- one\n  still one\n  - nested\n1. first\n2) second")
        assertEquals(
            listOf(0 to null, 1 to null, 0 to 1, 0 to 2),
            blocks.map { (it as MdBlock.ListItem).depth to it.marker },
        )
        assertEquals("one still one", plain((blocks[0] as MdBlock.ListItem).text))
    }

    @Test
    fun paragraphsQuotesRulesAndCode() {
        val blocks = parseMarkdown("line one\nline two\n\n> quoted\n> more\n\n---\n```\nval x = 1\n  y\n```\nafter")
        assertEquals("line one line two", plain((blocks[0] as MdBlock.Paragraph).text))
        assertEquals("quoted more", plain((blocks[1] as MdBlock.Quote).text))
        assertEquals(MdBlock.Rule, blocks[2])
        assertEquals("val x = 1\n  y", (blocks[3] as MdBlock.Code).text)
        assertEquals("after", plain((blocks[4] as MdBlock.Paragraph).text))
    }

    @Test
    fun inlineStyles() {
        val spans = parseInline("**Bold** and *it* or _it_, `code`, [link **x**](https://a.b/c) and snake_case_name")
        assertEquals(MdSpan("Bold", bold = true), spans[0])
        assertEquals(MdSpan("it", italic = true), spans[2])
        assertEquals(MdSpan("it", italic = true), spans[4])
        assertEquals(MdSpan("code", code = true), spans[6])
        assertEquals(MdSpan("link ", url = "https://a.b/c"), spans[8])
        assertEquals(MdSpan("x", bold = true, url = "https://a.b/c"), spans[9])
        assertEquals(" and snake_case_name", spans[10].text)
    }

    @Test
    fun bareUrlsBecomeLinksWithoutTrailingPunctuation() {
        val spans = parseInline("**Full Changelog**: https://github.com/andprov/timegrip-client/compare/v1.4.25...v1.4.26.")
        val link = spans.single { it.url != null }
        assertEquals("https://github.com/andprov/timegrip-client/compare/v1.4.25...v1.4.26", link.text)
        assertEquals(".", spans.last().text)
    }

    @Test
    fun unmatchedMarkersStayText() {
        assertEquals(listOf(MdSpan("2 * 3 and a_b and [x] \\")), parseInline("2 * 3 and a_b and [x] \\"))
        assertEquals(listOf(MdSpan("*not italic*")), parseInline("\\*not italic\\*"))
    }
}
