package dev.turboism.plugin.uitheme.service;

import dev.turboism.plugin.uitheme.b1.domain.BuiltinThemeCatalog;
import dev.turboism.plugin.uitheme.b1.domain.LegacyThemePaletteResolver;
import dev.turboism.plugin.uitheme.b1.domain.ThemePackageCodec;
import dev.turboism.plugin.uitheme.b1.domain.ThemePackageData;
import dev.turboism.plugin.uitheme.b1.domain.ThemePackageEntry;
import dev.turboism.sdk.appearance.AppearanceApplyResult;
import dev.turboism.sdk.appearance.AppearanceService;
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

    public BuiltinThemeAppearanceService(
        final ClassLoader classLoader,
        final AppearanceService appearance,
        final UiHostCapabilityService uiHost
    ) {
        this.classLoader = Objects.requireNonNull(classLoader, "classLoader");
        this.appearance = Objects.requireNonNull(appearance, "appearance");
        this.uiHost = Objects.requireNonNull(uiHost, "uiHost");
    }

    public void applyDefault() {
        final long revision = appearance.current().toCompletableFuture().join().revision();
        final AppearanceApplyResult result = appearance.apply(
            LegacyThemePaletteResolver.resolve(load(DEFAULT_THEME_ID), revision)
        ).toCompletableFuture().join();
        final String level = switch (result.outcome()) {
            case APPLIED, NO_CHANGE -> "INFO";
            default -> "WARNING";
        };
        uiHost.notifyStatus(new StatusNotification(
            "ui-theme.appearance.apply." + result.outcome().name().toLowerCase(java.util.Locale.ROOT),
            level,
            "Theme appearance result: " + result.outcome()
        ));
    }

    private ThemePackageData load(final String themeId) {
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
