package dev.turboism.plugin.commandpalette;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.turboism.sdk.cubism.command.EditorCommand;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Matching and completion rules for {@link CommandMatcher}: subsequence semantics over
 * normalized text (separators and case ignored), hit indices in original coordinates,
 * best-match ordering and prefix completion.
 */
class CommandMatcherTest {

    // The command constant is only the row identity here; id/name are what matching reads.
    private static final List<CommandMatcher.Entry> ENTRIES = List.of(
            entry(EditorCommand.NEW_MODEL, "new.model", "New Model"),
            entry(EditorCommand.SAVE, "save", "Save"),
            entry(EditorCommand.OPEN_ABOUT, "save.as", "Save As"),
            entry(EditorCommand.UNDO, "undo", "Undo"));

    private static CommandMatcher.Entry entry(final EditorCommand command, final String id, final String name) {
        return CommandMatcher.Entry.command(command, id, name);
    }

    @Test
    void blankQueryMatchesNothing() {
        assertTrue(CommandMatcher.match("", ENTRIES).isEmpty());
        assertTrue(CommandMatcher.match("   ", ENTRIES).isEmpty());
        assertTrue(CommandMatcher.match("...", ENTRIES).isEmpty());
    }

    @Test
    void substringQueryMatchesNameAndId() {
        final List<CommandMatcher.Match> matches = CommandMatcher.match("save", ENTRIES);
        assertEquals(2, matches.size());
        assertEquals("save", matches.get(0).entry().id());
    }

    @Test
    void separatorInsensitiveQueryMatchesDottedId() {
        final List<CommandMatcher.Match> matches = CommandMatcher.match("newmodel", ENTRIES);
        assertEquals(1, matches.size());
        assertEquals(EditorCommand.NEW_MODEL, matches.get(0).entry().command());
        // Original-coordinate hits skip the dropped separator: n e w . m o d e l
        assertArrayEquals(new int[] {0, 1, 2, 4, 5, 6, 7, 8}, matches.get(0).idHits());
    }

    @Test
    void caseIsIgnored() {
        final List<CommandMatcher.Match> matches = CommandMatcher.match("UNDO", ENTRIES);
        assertEquals(1, matches.size());
        assertEquals(EditorCommand.UNDO, matches.get(0).entry().command());
    }

    @Test
    void subsequenceQueryMatchesAcrossWords() {
        final List<CommandMatcher.Match> matches = CommandMatcher.match("nm", ENTRIES);
        assertTrue(matches.stream().anyMatch(m -> m.entry().command() == EditorCommand.NEW_MODEL));
    }

    @Test
    void nonSubsequenceDoesNotMatch() {
        assertTrue(CommandMatcher.match("zzz", ENTRIES).isEmpty());
    }

    @Test
    void contiguousBeatsScattered() {
        final List<CommandMatcher.Entry> entries =
                List.of(entry(EditorCommand.SAVE, "save", "Save"), entry(EditorCommand.UNDO, "s.a.v.e", "S_A_V_E"));
        final List<CommandMatcher.Match> matches = CommandMatcher.match("save", entries);
        assertEquals("save", matches.get(0).entry().id());
    }

    @Test
    void completionPrefersNamePrefixThenId() {
        final List<CommandMatcher.Match> matches = CommandMatcher.match("new", ENTRIES);
        assertEquals("New Model", CommandMatcher.completion("new", matches.get(0)));

        final List<CommandMatcher.Match> idMatches = CommandMatcher.match("sav", ENTRIES);
        final String completion = CommandMatcher.completion("sav", idMatches.get(0));
        assertTrue(completion.equals("Save") || completion.equals("save"));
    }

    @Test
    void completionIsNullWithoutPrefix() {
        final List<CommandMatcher.Match> matches = CommandMatcher.match("nm", ENTRIES);
        assertNull(CommandMatcher.completion("nm", matches.get(0)));
    }
}
