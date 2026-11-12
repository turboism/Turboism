package dev.turboism.config;

import dev.turboism.internal.core.GuidePreferences;
import java.nio.file.Path;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * {@link GuidePreferences} backed by the {@code guide} section of the canonical global
 * {@code config.json}.
 *
 * <p>The section is optional: an absent section or field reads as
 * {@link #DEFAULT_SHOW_ON_STARTUP}, so a fresh install and an upgrade from a build that predates
 * the guide both open it once. The dismissal is stored only after it happened, and a document that
 * already records {@code false} is never rewritten.</p>
 */
public final class RuntimeGuidePreferences implements GuidePreferences {

    /** Whether a document that does not record the choice yet still opens the guide. */
    public static final boolean DEFAULT_SHOW_ON_STARTUP = true;

    static final String GUIDE_FIELD = "guide";
    static final String SHOW_ON_STARTUP_FIELD = "showOnStartup";

    private final RuntimeConfigRepository config;

    public RuntimeGuidePreferences(final Path turboismHome, final Consumer<String> diagnostic) {
        this(new RuntimeConfigRepository(turboismHome, diagnostic));
    }

    RuntimeGuidePreferences(final RuntimeConfigRepository config) {
        this.config = Objects.requireNonNull(config, "config");
    }

    @Override
    public boolean shouldShowGuide() {
        return config.read().path(GUIDE_FIELD).path(SHOW_ON_STARTUP_FIELD).asBoolean(DEFAULT_SHOW_ON_STARTUP);
    }

    @Override
    public void markGuideSeen() {
        if (!shouldShowGuide()) return;
        config.update(root -> {
            root.withObject(GUIDE_FIELD).put(SHOW_ON_STARTUP_FIELD, false);
            return root;
        });
    }
}
