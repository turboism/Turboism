package dev.turboism.sdk.ui.settings;


import java.util.List;
import java.util.Objects;
import java.util.OptionalInt;

/** Immutable runtime aggregate consumed by the shared settings window renderer. */
public final class SettingsSnapshot {
    private SettingsSnapshot() {
    }

    /** One plugin-owned contribution in the rendered aggregate. */
    public record Entry(String pluginId, SettingsContribution contribution) {
        public Entry {
            pluginId = Objects.requireNonNull(pluginId, "pluginId");
            contribution = Objects.requireNonNull(contribution, "contribution");
        }
    }

    /** One normalized settings tab and its ordered contributions. */
    public record Tab(
        String id,
        String title,
        OptionalInt index,
        List<Entry> contributions
    ) {
        public Tab {
            id = Objects.requireNonNull(id, "id");
            title = Objects.requireNonNull(title, "title");
            index = Objects.requireNonNull(index, "index");
            contributions = List.copyOf(Objects.requireNonNull(contributions, "contributions"));
        }

        public Tab(
            final String id,
            final String title,
            final List<Entry> contributions
        ) {
            this(id, title, OptionalInt.empty(), contributions);
        }
    }
}
