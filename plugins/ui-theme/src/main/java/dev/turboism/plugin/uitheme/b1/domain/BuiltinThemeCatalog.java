package dev.turboism.plugin.uitheme.b1.domain;

import java.util.List;

/**
 * The fixed set of themes shipped with the plugin, in the order they are offered.
 *
 * <p>Membership here is what makes a theme id "reviewed": ids outside this list are treated
 * as user-supplied. The two {@code __cubism_*__} entries name the host's own themes, which
 * are catalogued so their ids stay reviewed but are not offered for selection. Not
 * instantiable.
 */
public final class BuiltinThemeCatalog {

    private static final List<Entry> ENTRIES = List.of(
        new Entry("turboism.mint", "mint", true),
        new Entry("turboism.paper-yellow", "paper-yellow", true),
        new Entry("turboism.slate", "slate", true),
        new Entry("turboism.nord", "nord", true),
        new Entry("turboism.cherry-blossom", "cherry-blossom", true),
        new Entry("turboism.sky-blue", "sky-blue", true),
        new Entry("__cubism_light__", "cubism-light", false),
        new Entry("__cubism_dark__", "cubism-dark", false)
    );

    private BuiltinThemeCatalog() {
    }

    /**
     * @return every catalogued builtin, including the host's own themes that are not offered for
     *         selection; immutable and identical on every call
     */
    public static List<Entry> entries() {
        return ENTRIES;
    }

    /**
     * @return the builtins that should be offered to the user, excluding the host's own themes
     */
    public static List<Entry> visibleEntries() {
        return ENTRIES.stream().filter(Entry::visible).toList();
    }

    /**
     * Tests whether an id names a theme shipped and reviewed with the plugin, as opposed to one
     * loaded from a user package.
     *
     * @param id the theme id to test; a null id simply matches nothing
     * @return whether the id is exactly a catalogued builtin id
     */
    public static boolean isReviewedBuiltin(final String id) {
        return ENTRIES.stream().anyMatch(entry -> entry.id().equals(id));
    }

    /**
     * One catalogued builtin theme.
     *
     * @param id the theme's stable identifier, as stored in configuration
     * @param resourceDirectory the directory name its resources are loaded from
     * @param visible whether the theme is offered for selection; false for the host's own themes,
     *                which are catalogued only so their ids count as reviewed
     */
    public record Entry(String id, String resourceDirectory, boolean visible) {
    }
}
