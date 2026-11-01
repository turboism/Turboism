package dev.turboism.plugin.acp;

import static org.junit.jupiter.api.Assertions.*;

import java.awt.Font;
import java.util.List;
import javax.swing.text.DefaultStyledDocument;
import javax.swing.text.SimpleAttributeSet;
import javax.swing.text.StyleConstants;
import org.junit.jupiter.api.Test;

final class AcpMessageRendererTest {
    @Test
    void markdownParagraphsUseSpacingWithoutRenderingEmptyRows() throws Exception {
        final var document = render("你好！\n\n让我检查工具：\n\n1. 第一项\n2. 第二项\n\n");
        final String text = document.getText(0, document.getLength());
        assertEquals("你好！\n让我检查工具：\n1. 第一项\n2. 第二项\n", text);
        final float paragraphGap = StyleConstants.getSpaceAbove(
                document.getParagraphElement(text.indexOf("让我")).getAttributes());
        final float listGap = StyleConstants.getSpaceAbove(
                document.getParagraphElement(text.indexOf("2.")).getAttributes());
        assertTrue(paragraphGap > listGap);
        assertTrue(paragraphGap <= 6F);
    }

    @Test
    void blankLinesInsideFencedCodeArePreserved() throws Exception {
        final var document = render("```text\nfirst\n\nsecond\n```\n");
        assertEquals("first\n\nsecond\n", document.getText(0, document.getLength()));
    }

    @Test
    void rendersHeadingsEmphasisListsAndCodeWithoutMarkdownMarkers() throws Exception {
        final DefaultStyledDocument document = render("# 结果\n**完成**，*注意* `model_id`\n- 第一项\n2. 第二项");
        final String text = document.getText(0, document.getLength());
        assertEquals("结果\n完成，注意 model_id\n• 第一项\n2. 第二项\n", text);
        assertTrue(StyleConstants.isBold(document.getCharacterElement(0).getAttributes()));
        assertTrue(StyleConstants.isBold(
                document.getCharacterElement(text.indexOf("完成")).getAttributes()));
        assertTrue(StyleConstants.isItalic(
                document.getCharacterElement(text.indexOf("注意")).getAttributes()));
        assertEquals(
                Font.MONOSPACED,
                StyleConstants.getFontFamily(
                        document.getCharacterElement(text.indexOf("model_id")).getAttributes()));
    }

    @Test
    void fencedCodePreservesWhitespaceHtmlAndMarkdownLiterally() throws Exception {
        final DefaultStyledDocument document = render("```java\n  if (a < b) {\n    **literal**;\n  }\n```\n普通文本");
        final String text = document.getText(0, document.getLength());
        assertEquals("  if (a < b) {\n    **literal**;\n  }\n普通文本\n", text);
        assertEquals(
                Font.MONOSPACED,
                StyleConstants.getFontFamily(document.getCharacterElement(0).getAttributes()));
        assertFalse(StyleConstants.isBold(
                document.getCharacterElement(text.indexOf("literal")).getAttributes()));
        assertNotEquals(
                Font.MONOSPACED,
                StyleConstants.getFontFamily(
                        document.getCharacterElement(text.indexOf("普通文本")).getAttributes()));
    }

    @Test
    void unfinishedAndTildeFencesRemainCodeUntilMatchingClosingFence() throws Exception {
        final String markdown = "~~~text\r\n  <img src=\"file:///private\">\r\n```\r\n**still code**";
        final DefaultStyledDocument document = render(markdown);
        assertEquals(
                "  <img src=\"file:///private\">\n```\n**still code**\n", document.getText(0, document.getLength()));
        assertEquals(
                Font.MONOSPACED,
                StyleConstants.getFontFamily(document.getCharacterElement(0).getAttributes()));
    }

    @Test
    void plainUserMessagesAndUnmatchedDelimitersStayLiteral() throws Exception {
        final DefaultStyledDocument plain = new DefaultStyledDocument();
        AcpMessageRenderer.insert(plain, 0, "**保留原文** <b>hi</b>", new SimpleAttributeSet(), false);
        assertEquals("**保留原文** <b>hi</b>\n", plain.getText(0, plain.getLength()));
        final DefaultStyledDocument incomplete = render("**尚未闭合");
        assertEquals("**尚未闭合\n", incomplete.getText(0, incomplete.getLength()));
    }

    @Test
    void incrementalStreamRendersLikeAFullRender() throws Exception {
        final String markdown =
                "# 结果\n**完成**，*注意* `model_id`\n\n```java\n  int x = 1;\n\n  int y = 2;\n```\n- 第一项\n2. 第二项\n最后一行";
        final DefaultStyledDocument streamed = stream(markdown, 7);
        final DefaultStyledDocument rendered = render(markdown);
        assertEquals(rendered.getText(0, rendered.getLength()), streamed.getText(0, streamed.getLength()));
        final String text = streamed.getText(0, streamed.getLength());
        assertTrue(StyleConstants.isBold(
                streamed.getCharacterElement(text.indexOf("完成")).getAttributes()));
        assertEquals(
                Font.MONOSPACED,
                StyleConstants.getFontFamily(
                        streamed.getCharacterElement(text.indexOf("int y")).getAttributes()));
        assertFalse(StyleConstants.isBold(
                streamed.getCharacterElement(text.indexOf("int y")).getAttributes()));
    }

    @Test
    void incrementalStreamKeepsUnfinishedFenceAndCarriageReturnBoundaries() throws Exception {
        final StringBuilder source = new StringBuilder();
        final DefaultStyledDocument document = new DefaultStyledDocument();
        final SimpleAttributeSet base = base();
        AcpMessageRenderer.StreamRender state = null;
        for (String chunk : List.of("```text\r", "\n  first\r\n", "\r\nsecond\n```\r", "\n正常文本")) {
            source.append(chunk);
            state = appendChunk(document, state, source, base);
        }
        final DefaultStyledDocument rendered = render(source.toString());
        assertEquals(rendered.getText(0, rendered.getLength()), document.getText(0, document.getLength()));
        assertEquals(
                Font.MONOSPACED,
                StyleConstants.getFontFamily(document.getCharacterElement(0).getAttributes()));
    }

    @Test
    void largeStreamedResponseStaysWithinAnInsertBudget() throws Exception {
        // Reproduces the per-chunk full re-render: every chunk used to reparse and re-render
        // the whole message, so inserted characters grew quadratically with the response.
        final StringBuilder source = new StringBuilder();
        final CountingDocument document = new CountingDocument();
        final SimpleAttributeSet base = base();
        AcpMessageRenderer.StreamRender state = null;
        final long started = System.nanoTime();
        for (int chunk = 0; chunk < 400; chunk++) {
            source.append("第").append(chunk).append("行：**粗体**与 `code` 文本，用于模拟流式响应。\n");
            state = appendChunk(document, state, source, base);
        }
        final long elapsedMillis = (System.nanoTime() - started) / 1_000_000L;
        final DefaultStyledDocument expected = render(source.toString());
        assertEquals(expected.getText(0, expected.getLength()), text(document));
        assertTrue(
                document.inserted <= source.length() * 3L,
                "inserted " + document.inserted + " characters for a " + source.length() + " character response");
        assertTrue(elapsedMillis < 2500L, "streamed render took " + elapsedMillis + "ms");
    }

    private static String text(final CountingDocument document) throws Exception {
        return document.getText(0, document.getLength());
    }

    private static AcpMessageRenderer.StreamRender appendChunk(
            final DefaultStyledDocument document,
            final AcpMessageRenderer.StreamRender state,
            final CharSequence source,
            final SimpleAttributeSet base)
            throws Exception {
        if (state == null) {
            return AcpMessageRenderer.insert(document, 0, source.toString(), base, true);
        }
        assertTrue(AcpMessageRenderer.append(document, state, source, base, true, java.util.Map.of()));
        return state;
    }

    private static DefaultStyledDocument stream(final String markdown, final int chunkSize) throws Exception {
        final DefaultStyledDocument document = new DefaultStyledDocument();
        final SimpleAttributeSet base = base();
        AcpMessageRenderer.StreamRender state = null;
        for (int end = chunkSize; end < markdown.length() + chunkSize; end += chunkSize) {
            final String prefix = markdown.substring(0, Math.min(markdown.length(), end));
            state = appendChunk(document, state, prefix, base);
        }
        return document;
    }

    private static SimpleAttributeSet base() {
        final SimpleAttributeSet base = new SimpleAttributeSet();
        StyleConstants.setFontFamily(base, Font.SANS_SERIF);
        return base;
    }

    /** StyledDocument that counts inserted characters so render cost is observable offline. */
    private static final class CountingDocument extends DefaultStyledDocument {
        private long inserted;

        @Override
        public void insertString(final int offset, final String text, final javax.swing.text.AttributeSet attributes)
                throws javax.swing.text.BadLocationException {
            inserted += text.length();
            super.insertString(offset, text, attributes);
        }
    }

    private static DefaultStyledDocument render(final String markdown) throws Exception {
        final DefaultStyledDocument document = new DefaultStyledDocument();
        final SimpleAttributeSet base = new SimpleAttributeSet();
        StyleConstants.setFontFamily(base, Font.SANS_SERIF);
        AcpMessageRenderer.insert(document, 0, markdown, base, true);
        return document;
    }
}
