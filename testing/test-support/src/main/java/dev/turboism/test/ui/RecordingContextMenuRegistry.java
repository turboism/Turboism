package dev.turboism.test.ui;

import dev.turboism.sdk.plugin.Registration;
import dev.turboism.sdk.ui.context.ContextMenuRegistry;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Recording test fake for {@link ContextMenuRegistry}.
 */
public class RecordingContextMenuRegistry implements ContextMenuRegistry {

    private final List<ContextMenuContribution> contributions = new CopyOnWriteArrayList<>();

    @Override
    public Registration contribute(final ContextMenuContribution contribution) {
        Objects.requireNonNull(contribution, "contribution");
        contributions.add(contribution);
        return () -> contributions.remove(contribution);
    }

    public List<ContextMenuContribution> contributions() {
        return List.copyOf(contributions);
    }
}
