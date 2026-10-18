package dev.turboism.plugin.acp;

import java.awt.Font;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.swing.text.BadLocationException;
import javax.swing.text.SimpleAttributeSet;
import javax.swing.text.StyleConstants;
import javax.swing.text.StyledDocument;

/** Renders the common ACP Markdown subset into native Swing text, without interpreting HTML. */
final class AcpMessageRenderer {
    private static final Pattern HEADING = Pattern.compile("^(#{1,6})\\s+(.+)$");
    private static final Pattern LIST = Pattern.compile("^(\\s*)(?:[-+*]|(\\d+)[.)])\\s+(.*)$");
    private static final Pattern FENCE = Pattern.compile("^ {0,3}(`{3,}|~{3,})(.*)$");

    private AcpMessageRenderer() {}

    static void insert(
            final StyledDocument document,
            final int offset,
            final String text,
            final SimpleAttributeSet base,
            final boolean markdown)
            throws BadLocationException {
        insert(document, offset, text, base, markdown, java.util.Map.of());
    }

    static void insert(
            final StyledDocument document,
            final int offset,
            final String text,
            final SimpleAttributeSet base,
            final boolean markdown,
            final java.util.Map<String, javax.swing.ImageIcon> images)
            throws BadLocationException {
        final Writer writer = new Writer(document, offset);
        if (!markdown) {
            writer.append(text + "\n", base);
            return;
        }
        String fence = null;
        for (String line : text.replace("\r\n", "\n").replace('\r', '\n').split("\n", -1)) {
            final Matcher marker = FENCE.matcher(line);
            if (marker.matches()
                    && (fence == null
                            || (marker.group(1).charAt(0) == fence.charAt(0)
                                    && marker.group(1).length() >= fence.length()
                                    && marker.group(2).isBlank()))) {
                fence = fence == null ? marker.group(1) : null;
                continue;
            }
            final SimpleAttributeSet style = new SimpleAttributeSet(base);
            final int start = writer.offset;
            if (fence != null) {
                codeStyle(style);
                writer.append(line + "\n", style);
            } else {
                final Matcher image =
                        Pattern.compile("^!\\[[^\\]]*\\]\\((.+)\\)$").matcher(line);
                if (image.matches() && images.containsKey(image.group(1))) {
                    final SimpleAttributeSet imageStyle = new SimpleAttributeSet(base);
                    StyleConstants.setIcon(imageStyle, images.get(image.group(1)));
                    writer.append(" ", imageStyle);
                    writer.append("\n", base);
                    continue;
                }
                final Matcher heading = HEADING.matcher(line);
                final Matcher list = LIST.matcher(line);
                if (heading.matches()) {
                    StyleConstants.setBold(style, true);
                    StyleConstants.setFontSize(style, 22 - heading.group(1).length());
                    inline(writer, heading.group(2), style);
                } else if (list.matches()) {
                    writer.append(list.group(1) + (list.group(2) == null ? "• " : list.group(2) + ". "), style);
                    inline(writer, list.group(3), style);
                } else if (line.startsWith("> ")) {
                    StyleConstants.setItalic(style, true);
                    writer.append("│ ", style);
                    inline(writer, line.substring(2), style);
                } else {
                    inline(writer, line, style);
                }
                writer.append("\n", style);
            }
            final SimpleAttributeSet paragraph = new SimpleAttributeSet();
            StyleConstants.setSpaceAbove(paragraph, 2F);
            StyleConstants.setSpaceBelow(paragraph, 4F);
            StyleConstants.setLeftIndent(paragraph, fence == null ? 4F : 12F);
            StyleConstants.setRightIndent(paragraph, 8F);
            document.setParagraphAttributes(start, writer.offset - start, paragraph, false);
        }
    }

    private static void inline(final Writer writer, final String text, final SimpleAttributeSet base)
            throws BadLocationException {
        int cursor = 0;
        while (cursor < text.length()) {
            final char current = text.charAt(cursor);
            if (current == '\\'
                    && cursor + 1 < text.length()
                    && "\\`*_{}[]()#+-.!>".indexOf(text.charAt(cursor + 1)) >= 0) {
                writer.append(text.substring(cursor + 1, cursor + 2), base);
                cursor += 2;
                continue;
            }
            final String delimiter;
            if (current == '`') {
                int end = cursor + 1;
                while (end < text.length() && text.charAt(end) == '`') end++;
                delimiter = text.substring(cursor, end);
            } else if (text.startsWith("**", cursor) || text.startsWith("__", cursor)) {
                delimiter = text.substring(cursor, cursor + 2);
            } else if (current == '*') {
                delimiter = "*";
            } else {
                delimiter = null;
            }
            if (delimiter != null) {
                final int end = text.indexOf(delimiter, cursor + delimiter.length());
                if (end > cursor + delimiter.length()) {
                    final SimpleAttributeSet style = new SimpleAttributeSet(base);
                    final String content = text.substring(cursor + delimiter.length(), end);
                    if (current == '`') {
                        codeStyle(style);
                        writer.append(content, style);
                    } else {
                        if (delimiter.length() == 2) StyleConstants.setBold(style, true);
                        else StyleConstants.setItalic(style, true);
                        inline(writer, content, style);
                    }
                    cursor = end + delimiter.length();
                    continue;
                }
            }
            // Unmatched delimiters remain visible until a later stream chunk closes them.
            int next = cursor + 1;
            while (next < text.length() && "\\`*_".indexOf(text.charAt(next)) < 0) next++;
            writer.append(text.substring(cursor, next), base);
            cursor = next;
        }
    }

    private static void codeStyle(final SimpleAttributeSet style) {
        StyleConstants.setFontFamily(style, Font.MONOSPACED);
        StyleConstants.setBackground(style, AcpChatTheme.surface());
        StyleConstants.setItalic(style, false);
        StyleConstants.setBold(style, false);
    }

    private static final class Writer {
        private final StyledDocument document;
        private int offset;

        private Writer(final StyledDocument document, final int offset) {
            this.document = document;
            this.offset = offset;
        }

        private void append(final String text, final SimpleAttributeSet style) throws BadLocationException {
            document.insertString(offset, text, style);
            offset += text.length();
        }
    }
}
