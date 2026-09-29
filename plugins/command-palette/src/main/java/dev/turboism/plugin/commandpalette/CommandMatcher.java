package dev.turboism.plugin.commandpalette;

import dev.turboism.sdk.cubism.command.EditorCommand;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * Fuzzy query matching for the command palette.
 *
 * <p>The query and each candidate are normalized before comparison: case is folded with
 * {@link Locale#ROOT}, separator characters ({@code . _ -} and whitespace) are dropped, and
 * full-width ASCII letters are folded to their ASCII form, so {@code "newmodel"},
 * {@code "New Model"} and {@code "new.model"} all resolve to the same normalized text.
 * A candidate matches when the normalized query is a subsequence of the normalized field;
 * hits are reported as indices into the original (un-normalized) field text so the renderer
 * can highlight the characters the user actually sees.
 */
public final class CommandMatcher {

    private CommandMatcher() {}

    /** One palette row: the command, its stable SDK id and the localized display name. */
    public record Entry(EditorCommand command, String id, String name) {
        public Entry {
            Objects.requireNonNull(command, "command");
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(name, "name");
        }
    }

    /**
     * A matched row. {@code nameHits} / {@code idHits} hold ascending indices into
     * {@code entry.name()} / {@code entry.id()} for the characters the query matched;
     * the array is empty when that field did not match.
     */
    public record Match(Entry entry, int score, int[] nameHits, int[] idHits) {

        /** Returns the matched indices for the field the row is best described by. */
        public int[] hits() {
            return nameHits.length > 0 ? nameHits : idHits;
        }
    }

    /**
     * Returns the entries matching {@code query}, best first. A blank query matches
     * nothing — the dropdown only opens on real input.
     */
    public static List<Match> match(final String query, final List<Entry> entries) {
        Objects.requireNonNull(entries, "entries");
        final String normalizedQuery = normalize(query);
        if (normalizedQuery.isEmpty()) {
            return List.of();
        }
        final List<Match> matches = new ArrayList<>();
        for (final Entry entry : entries) {
            final int[] nameHits = hits(normalizedQuery, entry.name());
            final int[] idHits = hits(normalizedQuery, entry.id());
            if (nameHits == null && idHits == null) {
                continue;
            }
            final int score = Math.min(fieldScore(nameHits, entry.name()), fieldScore(idHits, entry.id()));
            matches.add(new Match(
                    entry, score, nameHits == null ? new int[0] : nameHits, idHits == null ? new int[0] : idHits));
        }
        matches.sort(Comparator.comparingInt(Match::score)
                .thenComparing(match -> match.entry().name(), String.CASE_INSENSITIVE_ORDER));
        return List.copyOf(matches);
    }

    /**
     * Returns the best completion text for the current input: the field (name preferred,
     * then id) of {@code match} whose raw text starts with {@code query} ignoring case,
     * or {@code null} when neither field prefix-matches.
     */
    public static String completion(final String query, final Match match) {
        if (match == null || query == null || query.isEmpty()) {
            return null;
        }
        if (startsWithIgnoreCase(match.entry().name(), query)) {
            return match.entry().name();
        }
        if (startsWithIgnoreCase(match.entry().id(), query)) {
            return match.entry().id();
        }
        return null;
    }

    private static boolean startsWithIgnoreCase(final String text, final String prefix) {
        return text.regionMatches(true, 0, prefix, 0, prefix.length());
    }

    /**
     * Greedy subsequence scan of the normalized query over the normalized candidate.
     * Earliest-possible indices keep contiguous runs contiguous; returns {@code null}
     * when the query is not a subsequence.
     */
    static int[] hits(final String normalizedQuery, final String text) {
        final int[] origin = originMap(text);
        final int[] result = new int[normalizedQuery.length()];
        int queryIndex = 0;
        for (int textIndex = 0; textIndex < origin.length && queryIndex < normalizedQuery.length(); textIndex++) {
            final char normalized = normalizeChar(text.charAt(origin[textIndex]));
            if (normalized == normalizedQuery.charAt(queryIndex)) {
                result[queryIndex++] = origin[textIndex];
            }
        }
        return queryIndex == normalizedQuery.length() ? result : null;
    }

    /**
     * Maps each kept (normalized) character index back to its index in {@code text}.
     * Dropped separator characters never appear in the map, so hit indices always point
     * at visible characters.
     */
    private static int[] originMap(final String text) {
        final int[] origin = new int[text.length()];
        int kept = 0;
        for (int index = 0; index < text.length(); index++) {
            if (!isSeparator(text.charAt(index))) {
                origin[kept++] = index;
            }
        }
        return java.util.Arrays.copyOf(origin, kept);
    }

    /**
     * Field ranking: contiguous substring hits score ahead of scattered subsequences;
     * earlier first hit, tighter span and shorter fields win inside a tier.
     */
    private static int fieldScore(final int[] hits, final String text) {
        if (hits == null || hits.length == 0) {
            return Integer.MAX_VALUE;
        }
        final int span = hits[hits.length - 1] - hits[0] + 1;
        final int scatter = span - hits.length;
        return scatter * 8 + hits[0] * 4 + text.length();
    }

    /** Normalizes {@code text} for comparison; never {@code null}. */
    static String normalize(final String text) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        final StringBuilder normalized = new StringBuilder(text.length());
        for (int index = 0; index < text.length(); index++) {
            final char character = text.charAt(index);
            if (isSeparator(character)) {
                continue;
            }
            normalized.append(normalizeChar(character));
        }
        return normalized.toString();
    }

    private static boolean isSeparator(final char character) {
        return character == '.'
                || character == '_'
                || character == '-'
                || character == '　'
                || Character.isWhitespace(character);
    }

    private static char normalizeChar(final char character) {
        // Full-width ASCII (Ａ-Ｚａ-ｚ０-９) folds onto ASCII before case folding.
        final char ascii = character >= 'Ａ' && character <= 'ｚ' ? (char) (character - ('Ａ' - 'A')) : character;
        return Character.toLowerCase(ascii);
    }
}
