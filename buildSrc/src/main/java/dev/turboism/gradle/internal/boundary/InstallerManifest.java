package dev.turboism.gradle.internal.boundary;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.gradle.api.GradleException;

/**
 * Installer metadata that must fail closed: JSON Unicode escapes and the release-plugin manifest.
 *
 * <p>Both rules run at configuration time in {@code installer.gradle.kts}, so neither takes a task,
 * a provider or a project: the caller passes the bytes, the source label and the set of known
 * project paths, which keeps them usable outside a task action and testable in-process.
 *
 * <p>Messages are part of the interface: they name the source label the caller supplied, so the
 * operator still sees which file or fixture was rejected.
 */
public final class InstallerManifest {

    private static final Pattern ASCII_HEX = Pattern.compile("^[0-9A-Fa-f]{4}$");

    private static final Pattern PLUGIN_ENTRY = Pattern.compile("^:plugins:[a-z0-9-]+$");

    private InstallerManifest() {
    }

    /**
     * Rejects a JSON Unicode escape that is not written with ASCII hexadecimal digits, and returns
     * the decoded text.
     *
     * <p>A fullwidth or Arabic-Indic digit in {@code \}{@code uXXXX} is accepted by a lenient parser
     * and produces a different id than the ASCII spelling, so the installer would stage a plugin
     * under an id nothing else matches. An odd run of backslashes starts an escape; an even run is
     * an escaped backslash and stays literal.
     *
     * @param bytes the file contents, decoded strictly as UTF-8
     * @param source the file path or fixture name messages name
     * @throws GradleException when the bytes are not UTF-8 or an escape is truncated or non-ASCII
     */
    public static String rejectNonAsciiJsonUnicodeEscapes(byte[] bytes, String source) {
        String text;
        try {
            text = StandardCharsets.UTF_8
                    .newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes))
                    .toString();
        } catch (CharacterCodingException failure) {
            throw new GradleException(source + ": plugin.json is not valid UTF-8", failure);
        }
        int offset = 0;
        while (offset < text.length()) {
            if (text.charAt(offset) != '\\') {
                offset++;
                continue;
            }
            int slashCount = 1;
            while (offset + slashCount < text.length() && text.charAt(offset + slashCount) == '\\') {
                slashCount++;
            }
            int marker = offset + slashCount;
            if (slashCount % 2 == 1 && marker < text.length() && text.charAt(marker) == 'u') {
                if (marker + 4 >= text.length()) {
                    throw new GradleException(source + ": truncated JSON Unicode escape");
                }
                String hex = text.substring(marker + 1, marker + 5);
                if (!ASCII_HEX.matcher(hex).matches()) {
                    throw new GradleException(source + ": JSON Unicode escape must use ASCII hexadecimal digits");
                }
            }
            offset += slashCount;
        }
        return text;
    }

    /**
     * The release-plugin allowlist, in file order.
     *
     * <p>{@code packaging/release-plugins.txt} is the sole authority for which plugins ship; there
     * is no independent blocklist anywhere, because the public-exclusion modules are simply absent
     * from the manifest. Parsing is fail-closed: a missing file, a blank or comment line, a
     * non-plugin entry, a duplicate, an unsorted order or an unknown project aborts the build.
     *
     * @param file the manifest to read
     * @param knownProjects every project path in the build, so a renamed module cannot linger here
     * @throws GradleException naming the first offending entry
     */
    public static List<String> parseReleasePluginManifest(File file, Set<String> knownProjects) {
        if (!file.isFile()) {
            throw new GradleException("release-plugins.txt missing: " + file.getPath());
        }
        List<String> lines = readLines(file);
        List<String> invalid = lines.stream()
                .filter(entry -> entry.isEmpty() || entry.startsWith("#"))
                .toList();
        if (!invalid.isEmpty()) {
            throw new GradleException("release-plugins.txt forbids blank/comment lines: '" + invalid.get(0) + "'");
        }
        List<String> malformed =
                lines.stream().filter(entry -> !PLUGIN_ENTRY.matcher(entry).matches()).toList();
        if (!malformed.isEmpty()) {
            throw new GradleException("release-plugins.txt contains a non-plugin entry: '" + malformed.get(0) + "'");
        }
        Map<String, Long> counts =
                lines.stream().collect(Collectors.groupingBy(Function.identity(), Collectors.counting()));
        List<String> duplicates = lines.stream()
                .filter(entry -> counts.get(entry) > 1)
                .distinct()
                .toList();
        if (!duplicates.isEmpty()) {
            throw new GradleException("release-plugins.txt contains duplicates: '" + duplicates.get(0) + "'");
        }
        List<String> sorted = new ArrayList<>(lines);
        sorted.sort(String::compareTo);
        if (!sorted.equals(lines)) {
            throw new GradleException("release-plugins.txt entries are not ASCII-sorted");
        }
        List<String> unknown =
                lines.stream().filter(entry -> !knownProjects.contains(entry)).toList();
        if (!unknown.isEmpty()) {
            throw new GradleException("release-plugins.txt references unknown project: '" + unknown.get(0) + "'");
        }
        return lines;
    }

    private static List<String> readLines(File file) {
        try {
            return Files.readAllLines(file.toPath(), StandardCharsets.UTF_8).stream()
                    .map(String::strip)
                    .toList();
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        }
    }
}
