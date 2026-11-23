package dev.turboism.plugin.acp;

import java.awt.Font;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.swing.ImageIcon;
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
    private static final Pattern HORIZONTAL_RULE = Pattern.compile("^ {0,3}(?:([-*_])(?: *\\1){2,}) *$");
    private static final Pattern TABLE_SEPARATOR_CELL = Pattern.compile("^:?-{1,}:?$");

    private static final String ESCAPABLE_CHARS = "\\`*_{}[]()#+-.!>";
    private static final String INLINE_DELIMITERS = "\\`*_";
    private static final int HEADING_BASE_FONT_SIZE = 22;
    private static final int FINGERPRINT_WINDOW = 64;
    private static final int HASH_MULTIPLIER = 31;

    private static final float PARAGRAPH_SPACE_ABOVE = 6F;
    private static final float PARAGRAPH_SPACE_BELOW = 2F;
    private static final float COMPACT_SPACE_BELOW = 0F;
    private static final float BASE_LEFT_INDENT = 4F;
    private static final float FENCED_LEFT_INDENT = 12F;
    private static final float BASE_RIGHT_INDENT = 8F;

    private static final int MAX_TABLE_DISPLAY_WIDTH = 76;
    private static final int MIN_COLUMN_WIDTH = 1;
    private static final int MIN_WRAPPED_COLUMN_WIDTH = 10;
    private static final int CELL_HORIZONTAL_PADDING = 2;
    private static final int BORDER_WIDTH = 1;
    private static final int CELL_OVERHEAD = CELL_HORIZONTAL_PADDING + BORDER_WIDTH;
    private static final int HORIZONTAL_RULE_WIDTH = 40;
    private static final int FULLWIDTH_ASCII_START = 0xFF01;
    private static final int FULLWIDTH_ASCII_END = 0xFF60;
    private static final int FULLWIDTH_SYMBOL_START = 0xFFE0;
    private static final int FULLWIDTH_SYMBOL_END = 0xFFE6;
    private static final int EMOJI_RANGE_START = 0x1F300;
    private static final int EMOJI_RANGE_END = 0x1FAFF;
    private static final int MISC_SYMBOL_START = 0x2600;
    private static final int MISC_SYMBOL_END = 0x27BF;
    private static final int WIDE_CHAR_COLUMNS = 2;
    private static final int NARROW_CHAR_COLUMNS = 1;

    private static final String BOX_TOP_LEFT = "┌";
    private static final String BOX_TOP_TEE = "┬";
    private static final String BOX_TOP_RIGHT = "┐";
    private static final String BOX_MID_LEFT = "├";
    private static final String BOX_MID_CROSS = "┼";
    private static final String BOX_MID_RIGHT = "┤";
    private static final String BOX_BOT_LEFT = "└";
    private static final String BOX_BOT_TEE = "┴";
    private static final String BOX_BOT_RIGHT = "┘";
    private static final String BOX_HORIZONTAL = "─";
    private static final String BOX_VERTICAL = "│";
    private static final Set<Character.UnicodeBlock> WIDE_UNICODE_BLOCKS = Set.of(
            Character.UnicodeBlock.CJK_SYMBOLS_AND_PUNCTUATION,
            Character.UnicodeBlock.CJK_COMPATIBILITY,
            Character.UnicodeBlock.CJK_COMPATIBILITY_FORMS,
            Character.UnicodeBlock.CJK_COMPATIBILITY_IDEOGRAPHS,
            Character.UnicodeBlock.CJK_RADICALS_SUPPLEMENT,
            Character.UnicodeBlock.KANGXI_RADICALS,
            Character.UnicodeBlock.ENCLOSED_CJK_LETTERS_AND_MONTHS);

    private AcpMessageRenderer() {}

    static StreamRender insert(
            final StyledDocument document,
            final int offset,
            final String text,
            final SimpleAttributeSet base,
            final boolean markdown)
            throws BadLocationException {
        return insert(document, offset, text, base, markdown, Map.of());
    }

    /**
     * Renders {@code text} at {@code offset} and returns the incremental position of the
     * trailing uncommitted block, which {@link #append} re-renders when the message grows.
     */
    static StreamRender insert(
            final StyledDocument document,
            final int offset,
            final String text,
            final SimpleAttributeSet base,
            final boolean markdown,
            final Map<String, ImageIcon> images)
            throws BadLocationException {
        final Cursor cursor = new Cursor(markdown, null, false);
        final Writer writer = new Writer(document, offset);
        final int committedEnd = committedBoundary(text, 0, markdown, cursor.fence);
        renderSequence(document, writer, text, cursor, 0, committedEnd, base, images);
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
     * Re-renders only the trailing uncommitted block of a growing message and commits newly
     * completed lines or closed tables. The result matches a full {@link #insert}.
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
            final Map<String, ImageIcon> images)
            throws BadLocationException {
        if (state == null || state.markdown != markdown) return false;
        if (source.length() < state.committedSource) return false;
        if (boundaryFingerprint(source, state.committedSource) != state.committedFingerprint) return false;
        if (state.tailDocumentStart < 0 || state.tailDocumentStart + state.tailLength > document.getLength()) {
            return false;
        }
        document.remove(state.tailDocumentStart, state.tailLength);
        final Writer writer = new Writer(document, state.tailDocumentStart);
        final Cursor cursor = new Cursor(markdown, state.fence, state.paragraphBreak);
        final int committedEnd = committedBoundary(source, state.committedSource, markdown, cursor.fence);
        renderSequence(document, writer, source, cursor, state.committedSource, committedEnd, base, images);
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
     * Index after the last complete line or closed table block at or after {@code from}.
     * Candidate or active pipe-table blocks remain uncommitted until a non-table line closes them.
     */
    private static int committedBoundary(
            final CharSequence source, final int from, final boolean markdown, final String initialFence) {
        final int limit = lastNewlineBoundary(source, from);
        if (!markdown || limit == from) {
            return limit;
        }
        String fence = initialFence;
        int safeCommit = from;
        int candidateTableStart = -1;
        int candidateColumns = 0;
        boolean inTable = false;
        int index = from;
        while (index < limit) {
            final int lineBreak = nextLineBreak(source, index, limit, true);
            final int lineEnd = lineBreak < 0 ? limit : lineBreak;
            final int nextIndex = lineBreak < 0 ? limit : skipLineBreak(source, lineBreak);
            final String line = source.subSequence(index, lineEnd).toString();
            final String nextFence = toggleFence(fence, line);
            if (!Objects.equals(fence, nextFence) || nextFence != null) {
                fence = nextFence;
                candidateTableStart = -1;
                candidateColumns = 0;
                inTable = false;
                safeCommit = nextIndex;
                index = nextIndex;
                continue;
            }
            if (inTable) {
                if (isTableContinuationRow(line)) {
                    index = nextIndex;
                    continue;
                }
                inTable = false;
                candidateTableStart = -1;
                candidateColumns = 0;
                safeCommit = index;
            }
            if (candidateTableStart >= 0) {
                if (parseTableAlignments(line, candidateColumns) != null) {
                    inTable = true;
                    index = nextIndex;
                    continue;
                }
                candidateTableStart = -1;
                candidateColumns = 0;
                safeCommit = index;
            }
            final List<String> headerCells = isPotentialTableRow(line) ? splitTableCells(line) : List.of();
            if (!headerCells.isEmpty()) {
                candidateTableStart = index;
                candidateColumns = headerCells.size();
            } else {
                safeCommit = nextIndex;
            }
            index = nextIndex;
        }
        return safeCommit;
    }

    private static int lastNewlineBoundary(final CharSequence source, final int from) {
        for (int index = source.length() - 1; index >= from; index--) {
            if (source.charAt(index) == '\n') {
                return index + 1;
            }
        }
        return from;
    }

    private static int boundaryFingerprint(final CharSequence source, final int boundary) {
        int hash = boundary;
        final int from = Math.max(0, boundary - FINGERPRINT_WINDOW);
        for (int index = from; index < boundary; index++) {
            hash = HASH_MULTIPLIER * hash + source.charAt(index);
        }
        return hash;
    }

    private static void renderTail(
            final StyledDocument document,
            final Writer writer,
            final CharSequence source,
            final Cursor cursor,
            final int from,
            final SimpleAttributeSet base,
            final Map<String, ImageIcon> images)
            throws BadLocationException {
        if (from >= source.length()) {
            // The source ended with a newline; render the trailing empty line uniformly.
            renderLine(document, writer, "", cursor, base, images);
            return;
        }
        renderSequence(document, writer, source, cursor, from, source.length(), base, images);
    }

    private static void renderSequence(
            final StyledDocument document,
            final Writer writer,
            final CharSequence source,
            final Cursor cursor,
            final int from,
            final int to,
            final SimpleAttributeSet base,
            final Map<String, ImageIcon> images)
            throws BadLocationException {
        int index = from;
        while (index < to) {
            final int lineBreak = nextLineBreak(source, index, to, cursor.markdown);
            final int lineEnd = lineBreak < 0 ? to : lineBreak;
            final int nextIndex = lineBreak < 0 ? -1 : skipLineBreak(source, lineBreak);
            final String line = source.subSequence(index, lineEnd).toString();
            if (cursor.markdown
                    && cursor.fence == null
                    && nextIndex >= 0
                    && nextIndex < to
                    && isPotentialTableRow(line)) {
                final List<String> headerCells = splitTableCells(line);
                if (!headerCells.isEmpty()) {
                    final int secondBreak = nextLineBreak(source, nextIndex, to, true);
                    final int secondEnd = secondBreak < 0 ? to : secondBreak;
                    final String secondLine =
                            source.subSequence(nextIndex, secondEnd).toString();
                    final List<Alignment> alignments = parseTableAlignments(secondLine, headerCells.size());
                    if (alignments != null) {
                        final List<List<String>> rows = new ArrayList<>();
                        int tableCursor = secondBreak < 0 ? to : skipLineBreak(source, secondBreak);
                        while (tableCursor < to) {
                            final int rowBreak = nextLineBreak(source, tableCursor, to, true);
                            final int rowEnd = rowBreak < 0 ? to : rowBreak;
                            final String rowLine =
                                    source.subSequence(tableCursor, rowEnd).toString();
                            if (!isTableContinuationRow(rowLine)) {
                                break;
                            }
                            rows.add(normalizeRowCells(splitTableCells(rowLine), headerCells.size()));
                            if (rowBreak < 0) {
                                tableCursor = to;
                                break;
                            }
                            tableCursor = skipLineBreak(source, rowBreak);
                        }
                        renderTable(document, writer, cursor, headerCells, alignments, rows, base);
                        if (tableCursor >= to) return;
                        index = tableCursor;
                        continue;
                    }
                }
            }
            renderLine(document, writer, line, cursor, base, images);
            if (nextIndex < 0) return;
            index = nextIndex;
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

    private static String toggleFence(final String currentFence, final String line) {
        final Matcher marker = FENCE.matcher(line);
        if (!marker.matches()) {
            return currentFence;
        }
        if (currentFence == null) {
            return marker.group(1);
        }
        if (marker.group(1).charAt(0) == currentFence.charAt(0)
                && marker.group(1).length() >= currentFence.length()
                && marker.group(2).isBlank()) {
            return null;
        }
        return currentFence;
    }

    private static void renderLine(
            final StyledDocument document,
            final Writer writer,
            final String line,
            final Cursor cursor,
            final SimpleAttributeSet base,
            final Map<String, ImageIcon> images)
            throws BadLocationException {
        if (cursor.markdown) {
            final String nextFence = toggleFence(cursor.fence, line);
            if (!Objects.equals(cursor.fence, nextFence)) {
                cursor.fence = nextFence;
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
                StyleConstants.setFontSize(
                        style, HEADING_BASE_FONT_SIZE - heading.group(1).length());
                inline(writer, heading.group(2), style);
            } else if (HORIZONTAL_RULE.matcher(line).matches()) {
                StyleConstants.setForeground(style, AcpChatTheme.muted());
                writer.append(BOX_HORIZONTAL.repeat(HORIZONTAL_RULE_WIDTH), style);
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
        applyParagraphSpacing(
                document,
                start,
                writer.offset - start,
                !fenced && cursor.paragraphBreak ? PARAGRAPH_SPACE_ABOVE : 0F,
                fenced ? COMPACT_SPACE_BELOW : PARAGRAPH_SPACE_BELOW,
                fenced ? FENCED_LEFT_INDENT : BASE_LEFT_INDENT);
        cursor.paragraphBreak = false;
    }

    private static void applyParagraphSpacing(
            final StyledDocument document,
            final int start,
            final int length,
            final float spaceAbove,
            final float spaceBelow,
            final float leftIndent) {
        final SimpleAttributeSet paragraph = new SimpleAttributeSet();
        StyleConstants.setSpaceAbove(paragraph, spaceAbove);
        StyleConstants.setSpaceBelow(paragraph, spaceBelow);
        StyleConstants.setLeftIndent(paragraph, leftIndent);
        StyleConstants.setRightIndent(paragraph, BASE_RIGHT_INDENT);
        document.setParagraphAttributes(start, length, paragraph, false);
    }

    private static void renderTable(
            final StyledDocument document,
            final Writer writer,
            final Cursor cursor,
            final List<String> headerCells,
            final List<Alignment> alignments,
            final List<List<String>> bodyRows,
            final SimpleAttributeSet base)
            throws BadLocationException {
        final int columnCount = headerCells.size();
        final List<List<InlineSpan>> parsedHeader = parseRowSpans(headerCells);
        final List<List<List<InlineSpan>>> parsedBody = new ArrayList<>(bodyRows.size());
        for (List<String> row : bodyRows) {
            parsedBody.add(parseRowSpans(row));
        }
        final int[] columnWidths = computeColumnWidths(columnCount, parsedHeader, parsedBody);
        final List<List<WrappedLine>> wrappedHeader = wrapRow(parsedHeader, columnWidths);
        final List<List<List<WrappedLine>>> wrappedBody = new ArrayList<>(parsedBody.size());
        boolean anyBodyRowWrapped = false;
        for (List<List<InlineSpan>> row : parsedBody) {
            final List<List<WrappedLine>> wrapped = wrapRow(row, columnWidths);
            if (maxCellHeight(wrapped) > 1) {
                anyBodyRowWrapped = true;
            }
            wrappedBody.add(wrapped);
        }

        final SimpleAttributeSet borderStyle = tableBorderStyle(base);
        final SimpleAttributeSet cellBase = new SimpleAttributeSet(base);
        StyleConstants.setFontFamily(cellBase, Font.MONOSPACED);

        final float firstLineSpaceAbove = cursor.paragraphBreak ? PARAGRAPH_SPACE_ABOVE : 0F;
        appendBorderLine(
                document,
                writer,
                borderLine(BOX_TOP_LEFT, BOX_TOP_TEE, BOX_TOP_RIGHT, columnWidths),
                borderStyle,
                firstLineSpaceAbove,
                COMPACT_SPACE_BELOW);
        appendTableVisualRow(document, writer, wrappedHeader, alignments, columnWidths, cellBase, borderStyle, true);
        appendBorderLine(
                document,
                writer,
                borderLine(BOX_MID_LEFT, BOX_MID_CROSS, BOX_MID_RIGHT, columnWidths),
                borderStyle,
                0F,
                COMPACT_SPACE_BELOW);
        for (int rowIndex = 0; rowIndex < wrappedBody.size(); rowIndex++) {
            appendTableVisualRow(
                    document,
                    writer,
                    wrappedBody.get(rowIndex),
                    alignments,
                    columnWidths,
                    cellBase,
                    borderStyle,
                    false);
            if (anyBodyRowWrapped && rowIndex < wrappedBody.size() - 1) {
                appendBorderLine(
                        document,
                        writer,
                        borderLine(BOX_MID_LEFT, BOX_MID_CROSS, BOX_MID_RIGHT, columnWidths),
                        borderStyle,
                        0F,
                        COMPACT_SPACE_BELOW);
            }
        }
        appendBorderLine(
                document,
                writer,
                borderLine(BOX_BOT_LEFT, BOX_BOT_TEE, BOX_BOT_RIGHT, columnWidths),
                borderStyle,
                0F,
                PARAGRAPH_SPACE_BELOW);
        cursor.paragraphBreak = false;
    }

    private static SimpleAttributeSet tableBorderStyle(final SimpleAttributeSet base) {
        final SimpleAttributeSet border = new SimpleAttributeSet(base);
        StyleConstants.setFontFamily(border, Font.MONOSPACED);
        StyleConstants.setForeground(border, AcpChatTheme.muted());
        StyleConstants.setBold(border, false);
        StyleConstants.setItalic(border, false);
        return border;
    }

    private static void appendBorderLine(
            final StyledDocument document,
            final Writer writer,
            final String line,
            final SimpleAttributeSet borderStyle,
            final float spaceAbove,
            final float spaceBelow)
            throws BadLocationException {
        final int start = writer.offset;
        writer.append(line + "\n", borderStyle);
        applyParagraphSpacing(document, start, writer.offset - start, spaceAbove, spaceBelow, BASE_LEFT_INDENT);
    }

    private static void appendTableVisualRow(
            final StyledDocument document,
            final Writer writer,
            final List<List<WrappedLine>> rowCells,
            final List<Alignment> alignments,
            final int[] columnWidths,
            final SimpleAttributeSet cellBase,
            final SimpleAttributeSet borderStyle,
            final boolean header)
            throws BadLocationException {
        final int height = maxCellHeight(rowCells);
        for (int lineIndex = 0; lineIndex < height; lineIndex++) {
            final int start = writer.offset;
            for (int col = 0; col < columnWidths.length; col++) {
                writer.append(BOX_VERTICAL + " ", borderStyle);
                final List<WrappedLine> cellLines = rowCells.get(col);
                final WrappedLine cellLine =
                        lineIndex < cellLines.size() ? cellLines.get(lineIndex) : new WrappedLine(List.of(), 0);
                final int remaining = Math.max(0, columnWidths[col] - cellLine.displayWidth());
                final Alignment alignment = alignments.get(col);
                final int leftPad =
                        switch (alignment) {
                            case LEFT -> 0;
                            case RIGHT -> remaining;
                            case CENTER -> remaining / 2;
                        };
                final int rightPad = remaining - leftPad;
                if (leftPad > 0) {
                    writer.append(" ".repeat(leftPad), cellBase);
                }
                for (InlineSpan span : cellLine.spans()) {
                    final SimpleAttributeSet spanStyle = spanAttributeSet(cellBase, span, header);
                    writer.append(span.text(), spanStyle);
                }
                if (rightPad > 0) {
                    writer.append(" ".repeat(rightPad), cellBase);
                }
                writer.append(" ", cellBase);
            }
            writer.append(BOX_VERTICAL + "\n", borderStyle);
            applyParagraphSpacing(document, start, writer.offset - start, 0F, COMPACT_SPACE_BELOW, BASE_LEFT_INDENT);
        }
    }

    private static SimpleAttributeSet spanAttributeSet(
            final SimpleAttributeSet base, final InlineSpan span, final boolean defaultBold) {
        final SimpleAttributeSet style = new SimpleAttributeSet(base);
        if (span.code()) {
            codeStyle(style);
            if (defaultBold) {
                StyleConstants.setBold(style, true);
            }
            return style;
        }
        if (defaultBold || span.bold()) {
            StyleConstants.setBold(style, true);
        }
        if (span.italic()) {
            StyleConstants.setItalic(style, true);
        }
        return style;
    }

    private static String borderLine(
            final String left, final String join, final String right, final int[] columnWidths) {
        final StringBuilder line = new StringBuilder(left);
        for (int col = 0; col < columnWidths.length; col++) {
            if (col > 0) {
                line.append(join);
            }
            line.append(BOX_HORIZONTAL.repeat(columnWidths[col] + CELL_HORIZONTAL_PADDING));
        }
        return line.append(right).toString();
    }

    private static int[] computeColumnWidths(
            final int columnCount, final List<List<InlineSpan>> header, final List<List<List<InlineSpan>>> body) {
        final int[] natural = new int[columnCount];
        for (int col = 0; col < columnCount; col++) {
            natural[col] = Math.max(MIN_COLUMN_WIDTH, spansDisplayWidth(header.get(col)));
        }
        for (List<List<InlineSpan>> row : body) {
            for (int col = 0; col < columnCount; col++) {
                natural[col] = Math.max(natural[col], spansDisplayWidth(row.get(col)));
            }
        }
        final int[] widths = natural.clone();
        final int budget = Math.max(
                columnCount * MIN_COLUMN_WIDTH, MAX_TABLE_DISPLAY_WIDTH - (columnCount * CELL_OVERHEAD + BORDER_WIDTH));
        int total = 0;
        for (int width : widths) {
            total += width;
        }
        while (total > budget) {
            int candidate = -1;
            for (int col = 0; col < columnCount; col++) {
                final int floor = Math.min(natural[col], MIN_WRAPPED_COLUMN_WIDTH);
                if (widths[col] > floor && (candidate < 0 || widths[col] > widths[candidate])) {
                    candidate = col;
                }
            }
            if (candidate < 0) {
                break;
            }
            widths[candidate]--;
            total--;
        }
        return widths;
    }

    private static List<List<InlineSpan>> parseRowSpans(final List<String> cells) {
        final List<List<InlineSpan>> parsed = new ArrayList<>(cells.size());
        for (String cell : cells) {
            final List<InlineSpan> spans = new ArrayList<>();
            parseInline(cell, false, false, spans);
            parsed.add(spans);
        }
        return parsed;
    }

    private static List<List<WrappedLine>> wrapRow(final List<List<InlineSpan>> row, final int[] columnWidths) {
        final List<List<WrappedLine>> wrapped = new ArrayList<>(row.size());
        for (int col = 0; col < row.size(); col++) {
            wrapped.add(wrapCell(row.get(col), columnWidths[col]));
        }
        return wrapped;
    }

    private static int maxCellHeight(final List<List<WrappedLine>> row) {
        int max = 1;
        for (List<WrappedLine> cell : row) {
            if (cell.size() > max) {
                max = cell.size();
            }
        }
        return max;
    }

    private static List<WrappedLine> wrapCell(final List<InlineSpan> spans, final int maxWidth) {
        if (spans.isEmpty()) {
            return List.of(new WrappedLine(List.of(), 0));
        }
        final List<WrappedLine> lines = new ArrayList<>();
        final List<StyledUnit> current = new ArrayList<>();
        int currentWidth = 0;
        int lastSpaceIndex = -1;
        for (InlineSpan span : spans) {
            final String text = span.text();
            for (int offset = 0; offset < text.length(); ) {
                final int codePoint = text.codePointAt(offset);
                final int charCount = Character.charCount(codePoint);
                final String symbol = text.substring(offset, offset + charCount);
                final int unitWidth = codePointDisplayWidth(codePoint);
                offset += charCount;
                if (currentWidth + unitWidth > maxWidth && currentWidth > 0) {
                    if (lastSpaceIndex > 0) {
                        lines.add(collapseUnits(current.subList(0, lastSpaceIndex)));
                        final List<StyledUnit> remainder =
                                new ArrayList<>(current.subList(lastSpaceIndex + 1, current.size()));
                        current.clear();
                        current.addAll(remainder);
                        currentWidth = unitsWidth(current);
                        lastSpaceIndex = lastIndexOfSpace(current);
                    } else {
                        lines.add(collapseUnits(current));
                        current.clear();
                        currentWidth = 0;
                        lastSpaceIndex = -1;
                    }
                    if (" ".equals(symbol) && current.isEmpty()) {
                        continue;
                    }
                }
                if (" ".equals(symbol)) {
                    lastSpaceIndex = current.size();
                }
                current.add(new StyledUnit(symbol, unitWidth, span.bold(), span.italic(), span.code()));
                currentWidth += unitWidth;
            }
        }
        if (!current.isEmpty() || lines.isEmpty()) {
            lines.add(collapseUnits(current));
        }
        return lines;
    }

    private static int lastIndexOfSpace(final List<StyledUnit> units) {
        for (int index = units.size() - 1; index >= 0; index--) {
            if (" ".equals(units.get(index).symbol())) {
                return index;
            }
        }
        return -1;
    }

    private static int unitsWidth(final List<StyledUnit> units) {
        int width = 0;
        for (StyledUnit unit : units) {
            width += unit.width();
        }
        return width;
    }

    private static WrappedLine collapseUnits(final List<StyledUnit> units) {
        if (units.isEmpty()) {
            return new WrappedLine(List.of(), 0);
        }
        final List<InlineSpan> spans = new ArrayList<>();
        final StringBuilder buffer = new StringBuilder();
        boolean bold = units.get(0).bold();
        boolean italic = units.get(0).italic();
        boolean code = units.get(0).code();
        int width = 0;
        for (StyledUnit unit : units) {
            if (unit.bold() != bold || unit.italic() != italic || unit.code() != code) {
                spans.add(new InlineSpan(buffer.toString(), bold, italic, code));
                buffer.setLength(0);
                bold = unit.bold();
                italic = unit.italic();
                code = unit.code();
            }
            buffer.append(unit.symbol());
            width += unit.width();
        }
        if (!buffer.isEmpty()) {
            spans.add(new InlineSpan(buffer.toString(), bold, italic, code));
        }
        return new WrappedLine(spans, width);
    }

    private static int spansDisplayWidth(final List<InlineSpan> spans) {
        int width = 0;
        for (InlineSpan span : spans) {
            final String text = span.text();
            for (int offset = 0; offset < text.length(); ) {
                final int codePoint = text.codePointAt(offset);
                width += codePointDisplayWidth(codePoint);
                offset += Character.charCount(codePoint);
            }
        }
        return width;
    }

    static int codePointDisplayWidth(final int codePoint) {
        final int type = Character.getType(codePoint);
        if (type == Character.NON_SPACING_MARK
                || type == Character.ENCLOSING_MARK
                || type == Character.COMBINING_SPACING_MARK
                || type == Character.FORMAT
                || Character.isISOControl(codePoint)) {
            return 0;
        }
        final Character.UnicodeScript script = Character.UnicodeScript.of(codePoint);
        if (script == Character.UnicodeScript.HAN
                || script == Character.UnicodeScript.HIRAGANA
                || script == Character.UnicodeScript.KATAKANA
                || script == Character.UnicodeScript.HANGUL
                || script == Character.UnicodeScript.BOPOMOFO
                || Character.isIdeographic(codePoint)) {
            return WIDE_CHAR_COLUMNS;
        }
        final Character.UnicodeBlock block = Character.UnicodeBlock.of(codePoint);
        if ((block != null && WIDE_UNICODE_BLOCKS.contains(block))
                || (codePoint >= FULLWIDTH_ASCII_START && codePoint <= FULLWIDTH_ASCII_END)
                || (codePoint >= FULLWIDTH_SYMBOL_START && codePoint <= FULLWIDTH_SYMBOL_END)
                || (codePoint >= EMOJI_RANGE_START && codePoint <= EMOJI_RANGE_END)
                || (codePoint >= MISC_SYMBOL_START && codePoint <= MISC_SYMBOL_END)) {
            return WIDE_CHAR_COLUMNS;
        }
        return NARROW_CHAR_COLUMNS;
    }

    private static boolean isPotentialTableRow(final String line) {
        return !line.isBlank()
                && !FENCE.matcher(line).matches()
                && !HEADING.matcher(line).matches()
                && !LIST.matcher(line).matches()
                && !HORIZONTAL_RULE.matcher(line).matches()
                && !line.startsWith("> ")
                && hasUnescapedPipeOutsideCode(line);
    }

    private static boolean isTableContinuationRow(final String line) {
        return isPotentialTableRow(line);
    }

    private static boolean hasUnescapedPipeOutsideCode(final String line) {
        boolean inCode = false;
        int tickLen = 0;
        for (int index = 0; index < line.length(); index++) {
            final char current = line.charAt(index);
            if (current == '\\' && !inCode && index + 1 < line.length()) {
                index++;
                continue;
            }
            if (current == '`') {
                int end = index + 1;
                while (end < line.length() && line.charAt(end) == '`') end++;
                final int run = end - index;
                if (!inCode) {
                    inCode = true;
                    tickLen = run;
                } else if (run == tickLen) {
                    inCode = false;
                }
                index = end - 1;
                continue;
            }
            if (!inCode && current == '|') {
                return true;
            }
        }
        return false;
    }

    private static List<String> splitTableCells(final String rawLine) {
        final String trimmed = rawLine.trim();
        if (trimmed.isEmpty()) {
            return List.of();
        }
        final List<String> cells = new ArrayList<>();
        final StringBuilder current = new StringBuilder();
        boolean inCode = false;
        int tickLen = 0;
        int start = trimmed.charAt(0) == '|' ? 1 : 0;
        for (int index = start; index < trimmed.length(); index++) {
            final char ch = trimmed.charAt(index);
            if (ch == '\\' && !inCode && index + 1 < trimmed.length() && trimmed.charAt(index + 1) == '|') {
                current.append('|');
                index++;
                continue;
            }
            if (ch == '`') {
                int end = index + 1;
                while (end < trimmed.length() && trimmed.charAt(end) == '`') end++;
                final int run = end - index;
                if (!inCode) {
                    inCode = true;
                    tickLen = run;
                } else if (run == tickLen) {
                    inCode = false;
                }
                current.append(trimmed, index, end);
                index = end - 1;
                continue;
            }
            if (!inCode && ch == '|') {
                cells.add(current.toString().trim());
                current.setLength(0);
                continue;
            }
            current.append(ch);
        }
        final String trailing = current.toString().trim();
        if (!trailing.isEmpty() || !endsWithUnescapedPipe(trimmed)) {
            cells.add(trailing);
        }
        return cells;
    }

    private static boolean endsWithUnescapedPipe(final String trimmed) {
        if (trimmed.isEmpty() || trimmed.charAt(trimmed.length() - 1) != '|') {
            return false;
        }
        int backslashes = 0;
        for (int index = trimmed.length() - 2; index >= 0 && trimmed.charAt(index) == '\\'; index--) {
            backslashes++;
        }
        return backslashes % 2 == 0;
    }

    private static List<Alignment> parseTableAlignments(final String separatorLine, final int expectedColumns) {
        if (expectedColumns <= 0 || !hasUnescapedPipeOutsideCode(separatorLine)) {
            return null;
        }
        final List<String> cells = splitTableCells(separatorLine);
        if (cells.size() != expectedColumns) {
            return null;
        }
        final List<Alignment> alignments = new ArrayList<>(expectedColumns);
        for (String cell : cells) {
            if (!TABLE_SEPARATOR_CELL.matcher(cell).matches()) {
                return null;
            }
            final boolean left = cell.startsWith(":");
            final boolean right = cell.endsWith(":");
            if (left && right) {
                alignments.add(Alignment.CENTER);
            } else if (right) {
                alignments.add(Alignment.RIGHT);
            } else {
                alignments.add(Alignment.LEFT);
            }
        }
        return alignments;
    }

    private static List<String> normalizeRowCells(final List<String> rawCells, final int columnCount) {
        final List<String> normalized = new ArrayList<>(columnCount);
        for (int col = 0; col < columnCount; col++) {
            normalized.add(col < rawCells.size() ? rawCells.get(col) : "");
        }
        return normalized;
    }

    private static void inline(final Writer writer, final String text, final SimpleAttributeSet base)
            throws BadLocationException {
        final List<InlineSpan> spans = new ArrayList<>();
        parseInline(text, false, false, spans);
        for (InlineSpan span : spans) {
            writer.append(span.text(), spanAttributeSet(base, span, false));
        }
    }

    private static void parseInline(
            final String text, final boolean bold, final boolean italic, final List<InlineSpan> out) {
        int cursor = 0;
        while (cursor < text.length()) {
            final char current = text.charAt(cursor);
            if (current == '\\'
                    && cursor + 1 < text.length()
                    && ESCAPABLE_CHARS.indexOf(text.charAt(cursor + 1)) >= 0) {
                out.add(new InlineSpan(text.substring(cursor + 1, cursor + 2), bold, italic, false));
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
                    final String content = text.substring(cursor + delimiter.length(), end);
                    if (current == '`') {
                        out.add(new InlineSpan(content, bold, italic, true));
                    } else if (delimiter.length() == 2) {
                        parseInline(content, true, italic, out);
                    } else {
                        parseInline(content, bold, true, out);
                    }
                    cursor = end + delimiter.length();
                    continue;
                }
            }
            // Unmatched delimiters remain visible until a later stream chunk closes them.
            int next = cursor + 1;
            while (next < text.length() && INLINE_DELIMITERS.indexOf(text.charAt(next)) < 0) next++;
            out.add(new InlineSpan(text.substring(cursor, next), bold, italic, false));
            cursor = next;
        }
    }

    private static void codeStyle(final SimpleAttributeSet style) {
        StyleConstants.setFontFamily(style, Font.MONOSPACED);
        StyleConstants.setBackground(style, AcpChatTheme.codeBackground());
        StyleConstants.setItalic(style, false);
        StyleConstants.setBold(style, false);
    }

    private enum Alignment {
        LEFT,
        CENTER,
        RIGHT
    }

    private record InlineSpan(String text, boolean bold, boolean italic, boolean code) {}

    private record WrappedLine(List<InlineSpan> spans, int displayWidth) {}

    private record StyledUnit(String symbol, int width, boolean bold, boolean italic, boolean code) {}

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
     * committed lines, the fence and paragraph state at that boundary, and the document range
     * of the trailing uncommitted block that the next chunk replaces.
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
