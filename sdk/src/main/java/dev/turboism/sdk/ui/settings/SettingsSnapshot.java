package dev.turboism.sdk.ui.settings;

import dev.turboism.sdk.PreviewApi;

import java.util.List;
import java.util.Objects;
import java.util.OptionalInt;

/** Immutable runtime aggregate consumed by the shared settings window renderer. */
@PreviewApi
public final class SettingsSnapshot {
    private SettingsSnapshot() {
    }

    public record Entry(String pluginId, SettingsContribution contribution) {
        public Entry {
            pluginId = Objects.requireNonNull(pluginId, "pluginId");
            contribution = Objects.requireNonNull(contribution, "contribution");
        }
    }

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
