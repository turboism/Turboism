package dev.turboism.plugin.uitheme.service;

import dev.turboism.plugin.uitheme.b1.domain.BuiltinThemeCatalog;
import dev.turboism.plugin.uitheme.b1.domain.LegacyThemePaletteResolver;
import dev.turboism.plugin.uitheme.b1.domain.ThemePackageCodec;
import dev.turboism.plugin.uitheme.b1.domain.ThemePackageData;
import dev.turboism.plugin.uitheme.b1.domain.ThemePackageEntry;
import dev.turboism.sdk.appearance.AppearanceApplyResult;
import dev.turboism.sdk.appearance.AppearanceService;
import dev.turboism.sdk.i18n.PluginLocalization;
import dev.turboism.sdk.ui.StatusNotification;
import dev.turboism.sdk.ui.UiHostCapabilityService;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Objects;

/** Applies one reviewed built-in package through the semantic appearance SDK. */
public final class BuiltinThemeAppearanceService {

    public static final String DEFAULT_THEME_ID = "turboism.nord";

    private final ClassLoader classLoader;
    private final AppearanceService appearance;
    private final UiHostCapabilityService uiHost;
    private final PluginLocalization localization;

    public BuiltinThemeAppearanceService(
        final ClassLoader classLoader,
        final AppearanceService appearance,
        final UiHostCapabilityService uiHost,
        final PluginLocalization localization
    ) {
        this.classLoader = Objects.requireNonNull(classLoader, "classLoader");
        this.appearance = Objects.requireNonNull(appearance, "appearance");
        this.uiHost = Objects.requireNonNull(uiHost, "uiHost");
        this.localization = Objects.requireNonNull(localization, "localization");
    }

    /**
     * Applies the shipped default theme ({@value #DEFAULT_THEME_ID}) and posts a status notification
     * naming the outcome.
     *
     * <p>Blocks on the appearance service: it reads the current revision, applies against it, and waits
     * for both stages. {@code APPLIED} and {@code NO_CHANGE} notify at {@code INFO}; every other
     * outcome notifies at {@code WARNING} rather than throwing, so a host that refuses the appearance
     * leaves the plugin running.
     *
     * @throws IllegalStateException if the built-in default package is missing or does not decode
     */
    public void applyDefault() {
        final ThemePackageData theme = load(DEFAULT_THEME_ID);
        final long revision = appearance.current().toCompletableFuture().join().revision();
        final AppearanceApplyResult result = appearance.apply(
            LegacyThemePaletteResolver.resolve(theme, revision)
        ).toCompletableFuture().join();
        final boolean applied = result.outcome() == AppearanceApplyResult.Outcome.APPLIED
            || result.outcome() == AppearanceApplyResult.Outcome.NO_CHANGE;
        uiHost.notifyStatus(new StatusNotification(
            "ui-theme.appearance.apply." + result.outcome().name().toLowerCase(java.util.Locale.ROOT),
            applied ? "INFO" : "WARNING",
            applied
                ? localization.format("theme.selection.applied", theme.metadata().name())
                : localization.text("theme.selection.failed")
        ));
    }

    /**
     * Loads and decodes one reviewed built-in theme from the plugin's own resources.
     *
     * <p>Reads only from the classloader given at construction; it never touches user storage, so the
     * result is the shipped package and not a user's edited copy.
     *
     * @param themeId the built-in catalog id
     * @return the decoded package
     * @throws IllegalArgumentException if no built-in theme has that id
     * @throws IllegalStateException if a resource of the package is missing, unreadable, or the decoded
     *     package is invalid
     */
    public ThemePackageData load(final String themeId) {
        final BuiltinThemeCatalog.Entry entry = BuiltinThemeCatalog.entries().stream()
            .filter(candidate -> candidate.id().equals(themeId))
            .findFirst()
            .orElseThrow(() -> new IllegalArgumentException("Unknown built-in theme: " + themeId));
        final String root = "themes/" + entry.resourceDirectory() + "/";
        final var decoded = ThemePackageCodec.decode(List.of(
            resource(root + ThemePackageCodec.THEME_PROPERTIES),
            resource(root + ThemePackageCodec.COLORS_PROPERTIES)
        ));
        if (!decoded.valid()) {
            throw new IllegalStateException("Built-in theme is invalid: " + decoded.issues());
        }
        return decoded.theme().orElseThrow();
    }

    private ThemePackageEntry resource(final String name) {
        try (InputStream input = classLoader.getResourceAsStream(name)) {
            if (input == null) {
                throw new IllegalStateException("Missing built-in theme resource: " + name);
            }
            return new ThemePackageEntry(name.substring(name.lastIndexOf('/') + 1), input.readAllBytes());
        } catch (IOException exception) {
            throw new IllegalStateException("Could not read built-in theme resource: " + name, exception);
        }
    }
}
