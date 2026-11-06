package dev.turboism.gradle.internal.boundary;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Duplicate {@code import} declarations inside one Java source file.
 *
 * <p>Pure: lines in, messages out. A duplicate import is invisible in review and compiles fine, so
 * the gate reports the second and every later occurrence with the line of the first one. Only the
 * leading import block is scanned - the block ends at the first line that is neither an import, a
 * package declaration, an annotation nor a comment - so a repeated name in code or in a second
 * class body cannot be mistaken for a duplicate import.
 *
 * <p>Messages name the caller-supplied relative path and are returned in source order; the caller
 * owns the file walk and the final sorted report.
 */
public final class DuplicateImports {

    private static final Pattern IMPORT = Pattern.compile("^\\s*import\\s+(static\\s+)?([\\w.$*]+)\\s*;\\s*$");

    private DuplicateImports() {
    }

    /**
     * The duplicate import declarations of one file.
     *
     * @param relativePath the path messages name, relative to the project directory
     */
    public static List<String> duplicates(String relativePath, List<String> lines) {
        List<String> duplicates = new ArrayList<>();
        Map<String, Integer> firstImportLines = new LinkedHashMap<>();
        boolean importsOpen = true;
        boolean blockComment = false;
        for (int index = 0; index < lines.size(); index++) {
            if (!importsOpen) {
                break;
            }
            String line = lines.get(index);
            String trimmed = line.strip();
            if (blockComment) {
                if (trimmed.contains("*/")) {
                    blockComment = false;
                }
                continue;
            }
            if (trimmed.isEmpty() || trimmed.startsWith("//")) {
                continue;
            }
            if (trimmed.startsWith("/*")) {
                blockComment = !trimmed.contains("*/");
                continue;
            }
            Matcher match = IMPORT.matcher(line);
            if (!match.matches()) {
                if (!trimmed.startsWith("package ") && !trimmed.startsWith("@")) {
                    importsOpen = false;
                }
                continue;
            }
            String declaration = ((match.group(1) == null ? "" : match.group(1)) + match.group(2)).strip();
            int lineNumber = index + 1;
            Integer firstLine = firstImportLines.putIfAbsent(declaration, lineNumber);
            if (firstLine != null) {
                duplicates.add(relativePath + ":" + lineNumber + " repeats import " + declaration + "; (first at line "
                        + firstLine + ")");
            }
        }
        return duplicates;
    }
}
