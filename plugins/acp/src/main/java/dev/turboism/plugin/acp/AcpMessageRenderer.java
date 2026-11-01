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
    private static final Pattern IMAGE = Pattern.compile("^!\\[[^\\]]*\\]\\((.+)\\)$");

    private AcpMessageRenderer() {}

    static StreamRender insert(
            final StyledDocument document,
            final int offset,
            final String text,
            final SimpleAttributeSet base,
            final boolean markdown)
            throws BadLocationException {
        return insert(document, offset, text, base, markdown, java.util.Map.of());
    }

    /**
     * Renders {@code text} at {@code offset} and returns the incremental position of the
     * trailing line, which {@link #append} re-renders when the message grows.
     */
    static StreamRender insert(
            final StyledDocument document,
            final int offset,
            final String text,
            final SimpleAttributeSet base,
            final boolean markdown,
            final java.util.Map<String, javax.swing.ImageIcon> images)
            throws BadLocationException {
        final Cursor cursor = new Cursor(markdown, null, false);
        final Writer writer = new Writer(document, offset);
        final int committedEnd = committedBoundary(text, 0);
        renderRange(document, writer, text, cursor, 0, committedEnd, base, images);
        final StreamRender state = new StreamRender(markdown);
        state.fence = cursor.fence;
        state.paragraphBreak = cursor.paragraphBreak;
        state.committedSource = committedEnd;
        state.committedFingerprint = boundaryFingerprint(text, committedEnd);
        state.tailDocumentStart = writer.offset;
        final int tailStart = writer.offset;
        renderTail(
                document,
                writer,
                text,
                new Cursor(markdown, cursor.fence, cursor.paragraphBreak),
                committedEnd,
                base,
                images);
        state.tailLength = writer.offset - tailStart;
        return state;
    }

    /**
     * Re-renders only the trailing line of a growing message and appends the lines that
     * completed since the previous call. The result equals a full {@link #insert} of the
     * whole source; the work is bounded by the new chunk plus one trailing line.
     *
     * @return {@code false} when the recorded position no longer matches the source or the
     *         document, and the caller must fall back to a full render
     */
    static boolean append(
            final StyledDocument document,
            final StreamRender state,
            final CharSequence source,
            final SimpleAttributeSet base,
            final boolean markdown,
            final java.util.Map<String, javax.swing.ImageIcon> images)
            throws BadLocationException {
        if (state == null || state.markdown != markdown) return false;
        if (source.length() < state.committedSource) return false;
        if (boundaryFingerprint(source, state.committedSource) != state.committedFingerprint) return false;
        if (state.tailDocumentStart < 0
                || state.tailDocumentStart + state.tailLength > document.getLength()) {
            return false;
        }
        document.remove(state.tailDocumentStart, state.tailLength);
        final Writer writer = new Writer(document, state.tailDocumentStart);
        final Cursor cursor = new Cursor(markdown, state.fence, state.paragraphBreak);
        final int committedEnd = committedBoundary(source, state.committedSource);
        renderRange(document, writer, source, cursor, state.committedSource, committedEnd, base, images);
        state.fence = cursor.fence;
        state.paragraphBreak = cursor.paragraphBreak;
        state.committedSource = committedEnd;
        state.committedFingerprint = boundaryFingerprint(source, committedEnd);
        state.tailLength = 0;
        final int tailStart = writer.offset;
        state.tailDocumentStart = tailStart;
        renderTail(
                document,
                writer,
                source,
                new Cursor(markdown, state.fence, state.paragraphBreak),
                committedEnd,
                base,
                images);
        state.tailLength = writer.offset - tailStart;
        return true;
    }

    /**
     * Index after the last complete line of {@code source}, i.e. after the last newline at or
     * after {@code from}. The trailing line is re-rendered from its start on every chunk, so
     * a line break that arrives inside the tail never commits half of itself.
     */
    private static int committedBoundary(final CharSequence source, final int from) {
        for (int index = source.length() - 1; index >= from; index--) {
            if (source.charAt(index) == '\n') {
                return index + 1;
            }
        }
        return from;
    }

    private static int boundaryFingerprint(final CharSequence source, final int boundary) {
        int hash = boundary;
        final int from = Math.max(0, boundary - 64);
        for (int index = from; index < boundary; index++) {
            hash = 31 * hash + source.charAt(index);
        }
        return hash;
    }

    private static void renderRange(
            final StyledDocument document,
            final Writer writer,
            final CharSequence source,
            final Cursor cursor,
            final int from,
            final int to,
            final SimpleAttributeSet base,
            final java.util.Map<String, javax.swing.ImageIcon> images)
            throws BadLocationException {
        int index = from;
        while (index < to) {
            final int lineBreak = nextLineBreak(source, index, to, cursor.markdown);
            final int lineEnd = lineBreak < 0 ? to : lineBreak;
            renderLine(document, writer, source.subSequence(index, lineEnd).toString(), cursor, base, images);
            if (lineBreak < 0) return;
            index = skipLineBreak(source, lineBreak);
        }
    }

    /**
     * Renders the text after the last complete line. The trailing line is deliberately
     * rendered with a copy of the cursor so that committing it later cannot double-apply
     * its fence or paragraph state.
     */
    private static void renderTail(
            final StyledDocument document,
            final Writer writer,
            final CharSequence source,
            final Cursor cursor,
            final int from,
            final SimpleAttributeSet base,
            final java.util.Map<String, javax.swing.ImageIcon> images)
            throws BadLocationException {
        if (from >= source.length()) {
            // The complete source already ended with a line break; the tail is the empty
            // line after it, which renders as a single newline in both modes.
            renderLine(document, writer, "", cursor, base, images);
            return;
        }
        int index = from;
        while (index < source.length()) {
            final int lineBreak = nextLineBreak(source, index, source.length(), cursor.markdown);
            final int lineEnd = lineBreak < 0 ? source.length() : lineBreak;
            renderLine(document, writer, source.subSequence(index, lineEnd).toString(), cursor, base, images);
            if (lineBreak < 0) return;
            index = skipLineBreak(source, lineBreak);
        }
    }

    private static int nextLineBreak(
            final CharSequence source, final int from, final int to, final boolean carriageReturn) {
        for (int index = from; index < to; index++) {
            final char current = source.charAt(index);
            if (current == '\n' || (carriageReturn && current == '\r')) {
                return index;
            }
        }
        return -1;
    }

    private static int skipLineBreak(final CharSequence source, final int lineBreak) {
        if (source.charAt(lineBreak) == '\r'
                && lineBreak + 1 < source.length()
                && source.charAt(lineBreak + 1) == '\n') {
            return lineBreak + 2;
        }
        return lineBreak + 1;
    }

    private static void renderLine(
            final StyledDocument document,
            final Writer writer,
            final String line,
            final Cursor cursor,
            final SimpleAttributeSet base,
            final java.util.Map<String, javax.swing.ImageIcon> images)
            throws BadLocationException {
        if (cursor.markdown) {
            final Matcher marker = FENCE.matcher(line);
            if (marker.matches()
                    && (cursor.fence == null
                            || (marker.group(1).charAt(0) == cursor.fence.charAt(0)
                                    && marker.group(1).length() >= cursor.fence.length()
                                    && marker.group(2).isBlank()))) {
                cursor.fence = cursor.fence == null ? marker.group(1) : null;
                return;
            }
            if (cursor.fence == null && line.isBlank()) {
                cursor.paragraphBreak = true;
                return;
            }
        }
        final SimpleAttributeSet style = new SimpleAttributeSet(base);
        final int start = writer.offset;
        if (cursor.markdown && cursor.fence != null) {
            codeStyle(style);
            writer.append(line + "\n", style);
        } else if (cursor.markdown) {
            final Matcher image = IMAGE.matcher(line);
            if (image.matches() && images.containsKey(image.group(1))) {
                final SimpleAttributeSet imageStyle = new SimpleAttributeSet(base);
                StyleConstants.setIcon(imageStyle, images.get(image.group(1)));
                writer.append(" ", imageStyle);
                writer.append("\n", base);
                return;
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
        } else {
            writer.append(line + "\n", style);
        }
        final boolean fenced = cursor.markdown && cursor.fence != null;
        final SimpleAttributeSet paragraph = new SimpleAttributeSet();
        StyleConstants.setSpaceAbove(paragraph, !fenced && cursor.paragraphBreak ? 6F : 0F);
        StyleConstants.setSpaceBelow(paragraph, fenced ? 0F : 2F);
        StyleConstants.setLeftIndent(paragraph, fenced ? 12F : 4F);
        StyleConstants.setRightIndent(paragraph, 8F);
        document.setParagraphAttributes(start, writer.offset - start, paragraph, false);
        cursor.paragraphBreak = false;
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

    /** Line-rendering state that must survive a stream chunk boundary. */
    private static final class Cursor {
        private final boolean markdown;
        private String fence;
        private boolean paragraphBreak;

        private Cursor(final boolean markdown, final String fence, final boolean paragraphBreak) {
            this.markdown = markdown;
            this.fence = fence;
            this.paragraphBreak = paragraphBreak;
        }
    }

    /**
     * Incremental position of one streamed message: the raw source offset already covered by
     * complete lines, the fence and paragraph state at that boundary, and the document range
     * of the trailing line that the next chunk replaces.
     */
    static final class StreamRender {
        private final boolean markdown;
        private int committedSource;
        private int committedFingerprint;
        private String fence;
        private boolean paragraphBreak;
        private int tailDocumentStart = -1;
        private int tailLength;

        private StreamRender(final boolean markdown) {
            this.markdown = markdown;
        }

        int tailDocumentStart() {
            return tailDocumentStart;
        }

        int tailLength() {
            return tailLength;
        }

        void shift(final int delta) {
            if (delta != 0 && tailDocumentStart >= 0) {
                tailDocumentStart += delta;
            }
        }
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
