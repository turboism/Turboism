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

    @Test
    void rendersMarkdownTablesWithBoxBordersInlineFormattingAndAlignment() throws Exception {
        final String markdown = """
                | 项 | 数值 | 参考基准 |
                |:---|---:|:---:|
                | **顶点** | `106,317` | 商用模型 |
                | 贴图 | 239.1 M | 严重超预算 |
                """;
        final DefaultStyledDocument document = render(markdown);
        final String text = document.getText(0, document.getLength());
        assertTrue(text.contains("┌"));
        assertTrue(text.contains("│ 项"));
        assertTrue(text.contains("├"));
        assertTrue(text.contains("└"));
        assertFalse(text.contains("|---"));
        assertFalse(text.contains("**顶点**"));
        assertFalse(text.contains("`106,317`"));
        assertTrue(StyleConstants.isBold(
                document.getCharacterElement(text.indexOf("参考基准")).getAttributes()));
        assertTrue(StyleConstants.isBold(
                document.getCharacterElement(text.indexOf("顶点")).getAttributes()));
        assertEquals(
                Font.MONOSPACED,
                StyleConstants.getFontFamily(
                        document.getCharacterElement(text.indexOf("106,317")).getAttributes()));
        final String[] lines = text.split("\n");
        final int headerCols = AcpMessageRenderer.codePointDisplayWidth('项') > 0 ? displayLength(lines[1]) : 0;
        for (int index = 0; index < lines.length; index++) {
            assertEquals(headerCols, displayLength(lines[index]), "line " + index + " misaligned: " + lines[index]);
        }
    }

    @Test
    void wrapsLongTableCellsWithinMaximumTableWidth() throws Exception {
        final String markdown = """
                | 项 | 数值 | 参考基准 |
                |---|---|---|
                | 顶点 / 三角面 | 106,317 / 164,194 | 商用模型常见 10k–40k 面，此为其 4–16 倍，需要大幅优化贴图与网格 |
                """;
        final DefaultStyledDocument document = render(markdown);
        final String text = document.getText(0, document.getLength());
        for (String line : text.split("\n")) {
            assertTrue(displayLength(line) <= 76, "line exceeded width budget: " + line);
        }
        assertTrue(text.contains("商用模型常见"));
        assertTrue(text.contains("需要大幅优化贴图与网格"));
    }

    @Test
    void incrementalStreamMatchesFullRenderAcrossTablesAndThematicBreaks() throws Exception {
        final String markdown = """
                # 客观规模

                ---

                | 项 | 数值 | 参考基准 |
                |---|---|---|
                | 参数 | 173 | 重型 |
                | 蒙版 | `255` | 深度 **0** |

                后续结论
                """;
        final DefaultStyledDocument streamed = stream(markdown, 5);
        final DefaultStyledDocument full = render(markdown);
        assertEquals(full.getText(0, full.getLength()), streamed.getText(0, streamed.getLength()));
        final String text = streamed.getText(0, streamed.getLength());
        assertTrue(StyleConstants.isBold(
                streamed.getCharacterElement(text.indexOf("0")).getAttributes()));
    }

    @Test
    void codeBackgroundAvoidsStarkWhiteSurfaceInLightTheme() throws Exception {
        final String[] keys = {"CubismCommon.background", "CubismCommon.surface", "CubismCommon.foreground"};
        final var previous = new java.util.HashMap<String, Object>();
        for (String key : keys) previous.put(key, javax.swing.UIManager.get(key));
        try {
            final java.awt.Color bg = new java.awt.Color(0xF2F2F2);
            final java.awt.Color whiteSurface = new java.awt.Color(0xFFFFFF);
            final java.awt.Color fg = new java.awt.Color(0x1E1E1E);
            javax.swing.UIManager.put("CubismCommon.background", bg);
            javax.swing.UIManager.put("CubismCommon.surface", whiteSurface);
            javax.swing.UIManager.put("CubismCommon.foreground", fg);
            final DefaultStyledDocument document = render("通道 `ParamEyeJellyPhyXL/XR` 说明");
            final String text = document.getText(0, document.getLength());
            final java.awt.Color codeBg =
                    StyleConstants.getBackground(document.getCharacterElement(text.indexOf("ParamEyeJellyPhyXL/XR"))
                            .getAttributes());
            assertNotEquals(whiteSurface, codeBg);
            assertNotEquals(bg, codeBg);
            assertEquals(AcpChatTheme.codeBackground(), codeBg);
        } finally {
            for (String key : keys) {
                if (previous.get(key) == null)
                    javax.swing.UIManager.getDefaults().remove(key);
                else javax.swing.UIManager.put(key, previous.get(key));
            }
        }
    }

    private static int displayLength(final String line) {
        int width = 0;
        for (int offset = 0; offset < line.length(); ) {
            final int cp = line.codePointAt(offset);
            width += AcpMessageRenderer.codePointDisplayWidth(cp);
            offset += Character.charCount(cp);
        }
        return width;
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
