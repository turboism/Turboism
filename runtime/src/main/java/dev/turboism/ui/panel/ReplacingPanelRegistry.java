package dev.turboism.ui.panel;

import dev.turboism.sdk.plugin.Registration;
import dev.turboism.ui.host.EdtDispatch;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;

/** Keeps one host surface alive across successive contributions with the same identity. */
final class ReplacingPanelRegistry {
    private static final String UPDATE_OPERATION = "viewport contribution update";
    private static final String CLOSE_OPERATION = "viewport contribution removal";
    private final Map<PanelKey, Entry> entries = new HashMap<>();

    Registration replace(
            final EmbeddedPanelContributionDescriptor descriptor,
            final Supplier<EmbeddedPanelHostOperations.PanelHandle> installer) {
        Objects.requireNonNull(descriptor, "descriptor");
        Objects.requireNonNull(installer, "installer");
        return EdtDispatch.call(UPDATE_OPERATION, () -> {
            final PanelKey key = new PanelKey(descriptor.pluginId(), descriptor.contributionId());
            Entry entry = entries.get(key);
            if (entry == null) {
                entry = new Entry(Objects.requireNonNull(installer.get(), "panel"));
                entries.put(key, entry);
            } else {
                entry.panel.updateContent(descriptor);
            }
            final Entry installed = entry;
            final Object generation = new Object();
            installed.generation = generation;
            return () -> EdtDispatch.runEventually(CLOSE_OPERATION, () -> {
                if (entries.get(key) == installed && installed.generation == generation) {
                    entries.remove(key);
                    installed.panel.close();
                }
            });
        });
    }

    private record PanelKey(String pluginId, String contributionId) {}

    private static final class Entry {
        private final EmbeddedPanelHostOperations.PanelHandle panel;
        private Object generation;

        private Entry(final EmbeddedPanelHostOperations.PanelHandle panel) {
            this.panel = panel;
        }
    }
}
