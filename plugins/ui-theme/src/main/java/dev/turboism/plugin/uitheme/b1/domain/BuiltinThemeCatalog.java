package dev.turboism.plugin.uitheme.b1.domain;

import java.util.List;

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

    public static List<Entry> entries() {
        return ENTRIES;
    }

    public static List<Entry> visibleEntries() {
        return ENTRIES.stream().filter(Entry::visible).toList();
    }

    public static boolean isReviewedBuiltin(final String id) {
        return ENTRIES.stream().anyMatch(entry -> entry.id().equals(id));
    }

    public record Entry(String id, String resourceDirectory, boolean visible) {
    }
}
