package dev.turboism.plugin.core;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Safe Markdown-subset renderer for plugin README content shown in Swing HTML. */
final class PluginReadmeRenderer {
    private static final Pattern LINK = Pattern.compile("\\[([^]\\r\\n]+)]\\((https?://[^)\\s]+)\\)");
    private static final Pattern CODE = Pattern.compile("`([^`\\r\\n]+)`");
    private static final Pattern STRONG = Pattern.compile("\\*\\*([^*\\r\\n]+)\\*\\*");
    private static final Pattern EMPHASIS = Pattern.compile("(?<!\\*)\\*([^*\\r\\n]+)\\*(?!\\*)");

    private PluginReadmeRenderer() { }

    static String render(final String markdown) {
        final String normalized = markdown == null ? "" : markdown.replace("\r\n", "\n").replace('\r', '\n');
        final String source = withoutFrontMatter(normalized);
        final StringBuilder html = new StringBuilder("<html><head><meta charset=\"UTF-8\"><style>")
            .append("body{font-family:sans-serif;margin:12px;color:#202124;background:#ffffff;}")
            .append("h1{font-size:22px;margin:12px 0 8px;}h2{font-size:18px;margin:12px 0 6px;}")
            .append("h3{font-size:15px;margin:10px 0 5px;}p{margin:6px 0;}li{margin:2px 0;}")
            .append("pre{background:#f3f4f6;padding:8px;}code{font-family:monospace;background:#f3f4f6;}")
            .append("table{border-collapse:collapse;margin:8px 0;}th,td{border:1px solid #d0d5dd;padding:4px 7px;text-align:left;}")
            .append("th{background:#f3f4f6;}blockquote{color:#5f6368;margin-left:12px;}a{color:#155dfc;}")
            .append("</style></head><body>");
        final List<String> paragraph = new ArrayList<>();
        final List<String> table = new ArrayList<>();
        boolean unordered = false;
        boolean ordered = false;
        boolean fenced = false;
        final StringBuilder code = new StringBuilder();
        for (String line : source.split("\n", -1)) {
            if (line.trim().startsWith("```")) {
                flushParagraph(html, paragraph);
                flushTable(html, table);
                if (fenced) {
                    html.append("<pre><code>").append(escape(code.toString())).append("</code></pre>");
                    code.setLength(0);
                } else {
                    if (unordered) { html.append("</ul>"); unordered = false; }
                    if (ordered) { html.append("</ol>"); ordered = false; }
                }
                fenced = !fenced;
                continue;
            }
            if (fenced) {
                if (!code.isEmpty()) code.append('\n');
                code.append(line);
                continue;
            }
            final String trimmed = line.trim();
            if (trimmed.isEmpty()) {
                flushParagraph(html, paragraph);
                flushTable(html, table);
                if (unordered) { html.append("</ul>"); unordered = false; }
                if (ordered) { html.append("</ol>"); ordered = false; }
                continue;
            }
            if (trimmed.startsWith("|") && trimmed.endsWith("|")) {
                flushParagraph(html, paragraph);
                if (unordered) { html.append("</ul>"); unordered = false; }
                if (ordered) { html.append("</ol>"); ordered = false; }
                table.add(trimmed);
                continue;
            }
            flushTable(html, table);
            final int heading = headingLevel(trimmed);
            if (heading > 0) {
                flushParagraph(html, paragraph);
                if (unordered) { html.append("</ul>"); unordered = false; }
                if (ordered) { html.append("</ol>"); ordered = false; }
                html.append("<h").append(heading).append('>')
                    .append(inline(trimmed.substring(heading + 1)))
                    .append("</h").append(heading).append('>');
                continue;
            }
            if (trimmed.matches("[-*_](?:\\s*[-*_]){2,}")) {
                flushParagraph(html, paragraph);
                html.append("<hr>");
                continue;
            }
            if (trimmed.startsWith("- ") || trimmed.startsWith("* ") || trimmed.startsWith("+ ")) {
                flushParagraph(html, paragraph);
                if (ordered) { html.append("</ol>"); ordered = false; }
                if (!unordered) { html.append("<ul>"); unordered = true; }
                html.append("<li>").append(inline(trimmed.substring(2))).append("</li>");
                continue;
            }
            final Matcher numbered = Pattern.compile("^\\d+[.)]\\s+(.+)$").matcher(trimmed);
            if (numbered.matches()) {
                flushParagraph(html, paragraph);
                if (unordered) { html.append("</ul>"); unordered = false; }
                if (!ordered) { html.append("<ol>"); ordered = true; }
                html.append("<li>").append(inline(numbered.group(1))).append("</li>");
                continue;
            }
            if (trimmed.startsWith("> ")) {
                flushParagraph(html, paragraph);
                html.append("<blockquote>").append(inline(trimmed.substring(2))).append("</blockquote>");
                continue;
            }
            paragraph.add(trimmed);
        }
        if (fenced) html.append("<pre><code>").append(escape(code.toString())).append("</code></pre>");
        flushParagraph(html, paragraph);
        flushTable(html, table);
        if (unordered) html.append("</ul>");
        if (ordered) html.append("</ol>");
        return html.append("</body></html>").toString();
    }

    private static String withoutFrontMatter(final String markdown) {
        if (!markdown.startsWith("---\n")) return markdown;
        final int end = markdown.indexOf("\n---\n", 4);
        return end < 0 ? markdown : markdown.substring(end + 5);
    }

    private static void flushParagraph(final StringBuilder html, final List<String> lines) {
        if (lines.isEmpty()) return;
        html.append("<p>").append(inline(String.join(" ", lines))).append("</p>");
        lines.clear();
    }

    private static void flushTable(final StringBuilder html, final List<String> lines) {
        if (lines.isEmpty()) return;
        if (lines.size() < 2 || !tableDivider(lines.get(1))) {
            lines.forEach(line -> html.append("<p>").append(inline(line)).append("</p>"));
            lines.clear();
            return;
        }
        html.append("<table><thead>");
        appendTableRow(html, lines.get(0), "th");
        html.append("</thead><tbody>");
        for (int index = 2; index < lines.size(); index++) appendTableRow(html, lines.get(index), "td");
        html.append("</tbody></table>");
        lines.clear();
    }

    private static boolean tableDivider(final String line) {
        final List<String> cells = tableCells(line);
        return !cells.isEmpty() && cells.stream().allMatch(cell -> cell.matches(":?-{3,}:?"));
    }

    private static void appendTableRow(final StringBuilder html, final String line, final String cellTag) {
        html.append("<tr>");
        for (String cell : tableCells(line)) {
            html.append('<').append(cellTag).append('>')
                .append(inline(cell))
                .append("</").append(cellTag).append('>');
        }
        html.append("</tr>");
    }

    private static List<String> tableCells(final String line) {
        final String content = line.substring(1, line.length() - 1);
        return java.util.Arrays.stream(content.split("\\|", -1))
            .map(String::trim)
            .toList();
    }

    private static int headingLevel(final String line) {
        int level = 0;
        while (level < line.length() && level < 3 && line.charAt(level) == '#') level++;
        return level > 0 && line.length() > level && line.charAt(level) == ' ' ? level : 0;
    }

    private static String inline(final String value) {
        final Matcher links = LINK.matcher(value);
        final StringBuilder result = new StringBuilder();
        int offset = 0;
        while (links.find()) {
            result.append(formatInlineText(value.substring(offset, links.start())));
            result.append("<a href=\"").append(escapeAttribute(links.group(2))).append("\">")
                .append(formatInlineText(links.group(1))).append("</a>");
            offset = links.end();
        }
        result.append(formatInlineText(value.substring(offset)));
        return result.toString();
    }

    private static String formatInlineText(final String value) {
        String result = escape(value);
        result = replace(CODE, result, match -> "<code>" + match.group(1) + "</code>");
        result = replace(STRONG, result, match -> "<strong>" + match.group(1) + "</strong>");
        return replace(EMPHASIS, result, match -> "<em>" + match.group(1) + "</em>");
    }

    private static String replace(
        final Pattern pattern,
        final String input,
        final java.util.function.Function<Matcher, String> replacement
    ) {
        final Matcher matcher = pattern.matcher(input);
        final StringBuffer output = new StringBuffer();
        while (matcher.find()) matcher.appendReplacement(output, Matcher.quoteReplacement(replacement.apply(matcher)));
        matcher.appendTail(output);
        return output.toString();
    }

    private static String escapeAttribute(final String value) {
        return escape(value);
    }

    private static String escape(final String value) {
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
            .replace("\"", "&quot;").replace("'", "&#39;");
    }
}
