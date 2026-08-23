package dev.turboism.ui.settings;

import dev.turboism.sdk.plugin.Registration;
import dev.turboism.sdk.ui.settings.SettingsContribution;
import dev.turboism.sdk.ui.settings.SettingsContributionSource;
import dev.turboism.sdk.ui.settings.SettingsSnapshot;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.OptionalInt;

/** Process-owned aggregate of every active plugin's declarative settings contributions. */
public final class SettingsContributionStore implements SettingsContributionSource {
    private static final Comparator<Entry> ENTRY_ORDER = Comparator
        .comparingInt((Entry value) -> value.contribution().index().orElse(Integer.MAX_VALUE))
        .thenComparing(Entry::pluginId)
        .thenComparing(value -> value.contribution().id());

    private final Map<String, Entry> entries = new LinkedHashMap<>();

    Registration register(final String pluginId, final SettingsContribution contribution) {
        final String owner = requireText(pluginId, "pluginId");
        final SettingsContribution value = Objects.requireNonNull(contribution, "contribution");
        final String identity = owner + ':' + value.id();
        synchronized (this) {
            if (entries.containsKey(identity)) {
                throw new IllegalArgumentException("duplicate settings contribution: " + identity);
            }
            entries.put(identity, new Entry(identity, owner, value));
        }
        return new Registration() {
            private boolean closed;

            @Override
            public void close() {
                synchronized (SettingsContributionStore.this) {
                    if (closed) return;
                    closed = true;
                    entries.remove(identity);
                }
            }
        };
    }

    public synchronized void clear() {
        entries.clear();
    }

    @Override
    public synchronized List<SettingsSnapshot.Tab> snapshot() {
        final Map<String, List<Entry>> byTab = new LinkedHashMap<>();
        for (Entry entry : entries.values()) {
            byTab.computeIfAbsent(entry.contribution().tab().id(), ignored -> new ArrayList<>())
                .add(entry);
        }
        final List<TabGroup> groups = new ArrayList<>();
        for (Map.Entry<String, List<Entry>> grouped : byTab.entrySet()) {
            final List<Entry> sortedEntries = grouped.getValue().stream().sorted(ENTRY_ORDER).toList();
            final Entry metadata = sortedEntries.stream().min(tabMetadataOrder()).orElseThrow();
            groups.add(new TabGroup(
                grouped.getKey(),
                metadata.contribution().tab().title(),
                tabIndex(sortedEntries),
                sortedEntries
            ));
        }
        groups.sort(Comparator
            .comparingInt((TabGroup value) -> value.index().orElse(Integer.MAX_VALUE))
            .thenComparing(TabGroup::id));
        return groups.stream().map(group -> new SettingsSnapshot.Tab(
            group.id(),
            group.title(),
            group.index(),
            group.entries().stream()
                .map(value -> new SettingsSnapshot.Entry(
                    value.pluginId(), value.contribution()
                ))
                .toList()
        )).toList();
    }

    private static OptionalInt tabIndex(final List<Entry> entries) {
        return entries.stream()
            .map(value -> value.contribution().tab().index())
            .filter(OptionalInt::isPresent)
            .mapToInt(OptionalInt::getAsInt)
            .min();
    }

    private static Comparator<Entry> tabMetadataOrder() {
        return Comparator
            .comparing((Entry value) -> value.contribution().tab().index().isEmpty())
            .thenComparingInt(value -> value.contribution().tab().index().orElse(Integer.MAX_VALUE))
            .thenComparing(Entry::pluginId)
            .thenComparing(value -> value.contribution().id());
    }

    private static String requireText(final String value, final String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) throw new IllegalArgumentException(name + " must not be blank");
        return value;
    }

    private record Entry(String identity, String pluginId, SettingsContribution contribution) {
    }

    private record TabGroup(
        String id,
        String title,
        OptionalInt index,
        List<Entry> entries
    ) {
    }
}
