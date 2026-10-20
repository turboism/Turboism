package dev.turboism.plugin.acp;

import static org.junit.jupiter.api.Assertions.*;

import java.awt.Font;
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

    private static DefaultStyledDocument render(final String markdown) throws Exception {
        final DefaultStyledDocument document = new DefaultStyledDocument();
        final SimpleAttributeSet base = new SimpleAttributeSet();
        StyleConstants.setFontFamily(base, Font.SANS_SERIF);
        AcpMessageRenderer.insert(document, 0, markdown, base, true);
        return document;
    }
}
