package dev.turboism.test.menu;

import dev.turboism.sdk.menu.MenuRegistry;
import dev.turboism.sdk.plugin.Registration;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Recording test fake for {@link MenuRegistry}.
 */
public class RecordingMenuRegistry implements MenuRegistry {

    private final List<MenuContribution> contributions = new CopyOnWriteArrayList<>();

    @Override
    public Registration contribute(final MenuContribution contribution) {
        Objects.requireNonNull(contribution, "contribution");
        contributions.add(contribution);
        return () -> contributions.remove(contribution);
    }

    public List<MenuContribution> contributions() {
        return List.copyOf(contributions);
    }
}
