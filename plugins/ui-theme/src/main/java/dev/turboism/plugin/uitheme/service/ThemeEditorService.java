package dev.turboism.plugin.uitheme.service;

import dev.turboism.plugin.uitheme.b1.domain.LegacyThemePaletteResolver;
import dev.turboism.plugin.uitheme.b1.domain.ThemeBase;
import dev.turboism.plugin.uitheme.b1.domain.ThemeIcons;
import dev.turboism.plugin.uitheme.b1.domain.ThemePackageData;
import dev.turboism.plugin.uitheme.b1.domain.ThemePackageMetadata;
import dev.turboism.plugin.uitheme.b1.domain.ThemePaletteGenerator;
import dev.turboism.sdk.appearance.AppearanceApplyResult;
import dev.turboism.sdk.appearance.AppearanceService;
import dev.turboism.sdk.i18n.PluginLocalization;
import dev.turboism.sdk.plugin.PluginLogger;
import dev.turboism.sdk.ui.FormDialogField;
import dev.turboism.sdk.ui.FormDialogRequest;
import dev.turboism.sdk.ui.FormFieldKind;
import dev.turboism.sdk.ui.StatusNotification;
import dev.turboism.sdk.ui.UiHostCapabilityService;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;

/** Theme generator/editor window: metadata plus semantic color slots. */
public final class ThemeEditorService {

    private static final String DIALOG_ID = "ui-theme.editor";
    private static final Pattern SLUG = Pattern.compile("[a-z0-9][a-z0-9-]{0,63}");

    private static final String F_SLUG = "slug";
    private static final String F_NAME = "name";
    private static final String F_AUTHOR = "author";
    private static final String F_VERSION = "version";
    private static final String F_URL = "url";
    private static final String F_BASE = "base";

    private static final Map<String, String> SLOT_KEYS = Map.ofEntries(
        Map.entry("accent", "CubismCommon.blue"),
        Map.entry("background", "CubismCommon.background"),
        Map.entry("surface", "CubismCommon.surface"),
        Map.entry("inputBackground", "CubismCommon.inputBackground"),
        Map.entry("foreground", "CubismCommon.foreground"),
        Map.entry("mutedForeground", "CubismCommon.mutedForeground"),
        Map.entry("selectionBackground", "CubismCommon.selectionBackground"),
        Map.entry("selectionForeground", "CubismCommon.selectionForeground"),
        Map.entry("border", "CubismCommon.border"),
        Map.entry("glViewportBackground", "CubismCommon.gl.viewArea.background")
    );
    private static final List<String> SLOT_ORDER = ThemePaletteGenerator.slotOrder();

    private final UiHostCapabilityService uiHost;
    private final ThemePackageRepository repository;
    private final AppearanceService appearance;
    private final PluginLocalization localization;
    private final PluginLogger logger;
    private final java.util.concurrent.atomic.AtomicBoolean dialogOpen =
        new java.util.concurrent.atomic.AtomicBoolean();
    private volatile Runnable backToManager = () -> { };
    private volatile Runnable onSaved = () -> { };

    public ThemeEditorService(
        final UiHostCapabilityService uiHost,
        final ThemePackageRepository repository,
        final AppearanceService appearance,
        final PluginLocalization localization,
        final PluginLogger logger
    ) {
        this.uiHost = Objects.requireNonNull(uiHost, "uiHost");
        this.repository = Objects.requireNonNull(repository, "repository");
        this.appearance = Objects.requireNonNull(appearance, "appearance");
        this.localization = Objects.requireNonNull(localization, "localization");
        this.logger = Objects.requireNonNull(logger, "logger");
    }

    /** Reopens the theme manager window when the editor is dismissed. */
    public void setBackToManager(final Runnable backToManager) {
        this.backToManager = Objects.requireNonNull(backToManager, "backToManager");
    }

    /** Refreshes external theme caches after a successful save. */
    public void setOnSaved(final Runnable onSaved) {
        this.onSaved = Objects.requireNonNull(onSaved, "onSaved");
    }

    /** Opens the generator for a brand new theme. */
    public void openNew() {
        openEditor(Optional.empty());
    }

    /** Opens the generator pre-filled with an existing theme. */
    public void openEdit(final ThemePackageData theme) {
        Objects.requireNonNull(theme, "theme");
        openEditor(Optional.of(theme));
    }

    private void openEditor(final Optional<ThemePackageData> existing) {
        if (!dialogOpen.compareAndSet(false, true)) {
            return;
        }
        final List<FormDialogField> fields = new ArrayList<>();
        final String baseDefault = existing.map(theme -> theme.metadata().base().name().toLowerCase(java.util.Locale.ROOT))
            .orElseGet(() -> uiHost.currentColorMode() == dev.turboism.sdk.ui.UiHostColorMode.DARK ? "dark" : "light");
        fields.add(field(F_SLUG, "theme.gen.themeSlug",
            existing.map(theme -> slugOf(theme.metadata().id())).orElse("")));
        fields.add(field(F_NAME, "theme.gen.displayName",
            existing.map(theme -> theme.metadata().name()).orElse("")));
        fields.add(field(F_AUTHOR, "theme.gen.displayAuthor",
            existing.map(theme -> theme.metadata().author()).orElse("")));
        fields.add(field(F_VERSION, "theme.detail.version",
            existing.map(theme -> theme.metadata().version() == null || theme.metadata().version().isBlank()
                ? "1.0.0" : theme.metadata().version()).orElse("1.0.0")));
        fields.add(field(F_URL, "theme.detail.url",
            existing.map(theme -> theme.metadata().url() == null ? "" : theme.metadata().url()).orElse("")));
        fields.add(new FormDialogField(
            F_BASE,
            localization.text("theme.gen.base"),
            baseDefault,
            FormFieldKind.SELECT,
            java.util.List.of("light", "dark", "any")
        ));
        final Map<String, String> colors = existing
            .map(theme -> theme.colors())
            .orElseGet(() -> ThemePaletteGenerator.fallbackDefaults(
                baseDefault.equals("dark") ? ThemeBase.DARK : ThemeBase.LIGHT
            ));
        for (String slot : SLOT_ORDER) {
            final String key = SLOT_KEYS.getOrDefault(slot, slot);
            final String value = colors.get(key);
            final String fallback = ThemePaletteGenerator.fallbackDefaults(
                baseDefault.equals("dark") ? ThemeBase.DARK : ThemeBase.LIGHT
            ).getOrDefault(key, "#000000");
            fields.add(new FormDialogField(
                slot,
                localization.text("theme.slot." + slot),
                value == null ? fallback : value,
                FormFieldKind.COLOR
            ));
        }
        uiHost.openFormDialog(
            new FormDialogRequest(
                DIALOG_ID,
                localization.text("theme.gen.title"),
                fields,
                localization.text("theme.gen.saveApply"),
                localization.text("theme.gen.cancel")
            ),
            (accepted, actionId, values) -> {
                dialogOpen.set(false);
                if (!accepted) {
                    backToManager.run();
                    return;
                }
                final Thread thread = new Thread(
                    () -> save(existing, values),
                    "ui-theme-editor-op"
                );
                thread.setDaemon(true);
                thread.start();
            }
        );
    }

    private void save(
        final Optional<ThemePackageData> existing,
        final Map<String, String> values
    ) {
        try {
            saveInternal(existing, values);
        } catch (RuntimeException failure) {
            logger.warn("THEME_EDITOR_SAVE_FAILED " + failure);
            notify("ui-theme.editor.save-failed", "WARNING",
                localization.text("theme.gen.saveFailed") + failure.getMessage());
        }
    }

    private void saveInternal(
        final Optional<ThemePackageData> existing,
        final Map<String, String> values
    ) {
        final String slug = values.getOrDefault(F_SLUG, "").trim();
        final String name = values.getOrDefault(F_NAME, "").trim();
        final String author = values.getOrDefault(F_AUTHOR, "").trim();
        final String version = values.getOrDefault(F_VERSION, "1.0.0").trim();
        final String url = values.getOrDefault(F_URL, "").trim();
        final String baseText = values.getOrDefault(F_BASE, "light").trim();
        if (!SLUG.matcher(slug).matches() || name.isEmpty()) {
            notify("ui-theme.editor.invalid", "WARNING", localization.text("theme.gen.invalidSlug"));
            return;
        }
        final ThemeBase base = switch (baseText) {
            case "dark" -> ThemeBase.DARK;
            case "any" -> ThemeBase.ANY;
            default -> ThemeBase.LIGHT;
        };
        final LinkedHashMap<String, String> colors = new LinkedHashMap<>();
        for (String slot : SLOT_ORDER) {
            final String key = SLOT_KEYS.getOrDefault(slot, slot);
            final String value = values.get(slot);
            if (value != null && value.matches("#[0-9A-Fa-f]{6}")) {
                colors.put(key, value.toUpperCase(java.util.Locale.ROOT));
            }
        }
        if (colors.isEmpty()) {
            notify("ui-theme.editor.invalid", "WARNING", localization.text("theme.gen.saveFailed"));
            return;
        }
        final String id = existing.isPresent() && existing.orElseThrow().metadata().id().startsWith("turboism.")
            ? "turboism." + slug
            : slug;
        final ThemePackageMetadata metadata = new ThemePackageMetadata(
            id,
            name,
            "",
            author,
            url,
            version,
            null,
            base,
            ThemeIcons.LIGHT,
            false
        );
        final ThemePackageData theme = new ThemePackageData(metadata, colors, Map.of(), null, null);
        final ThemePackageRepository.SaveResult saved = repository.save(theme, existing.isPresent());
        if (saved.outcome() == ThemePackageRepository.SaveOutcome.CONFLICT) {
            notify("ui-theme.editor.conflict", "WARNING", localization.text("theme.package.importConflict"));
            return;
        }
        if (saved.outcome() != ThemePackageRepository.SaveOutcome.SAVED) {
            notify("ui-theme.editor.save-failed", "WARNING", localization.text("theme.gen.saveFailed") + saved.outcome());
            return;
        }
        onSaved.run();
        // Save-and-apply: commit the selection through the same production path.
        final long revision = appearance.current().toCompletableFuture().join().revision();
        final AppearanceApplyResult applied = appearance.apply(
            LegacyThemePaletteResolver.resolve(theme, revision)
        ).toCompletableFuture().join();
        if (applied.outcome() == AppearanceApplyResult.Outcome.APPLIED
            || applied.outcome() == AppearanceApplyResult.Outcome.NO_CHANGE) {
            refreshOffCanvas();
            notify(
                "ui-theme.editor.saved-applied",
                "INFO",
                localization.format("theme.selection.applied", name)
            );
        } else {
            notify("ui-theme.editor.saved", "INFO", localization.format("theme.package.imported", name));
        }
    }

    private static String slugOf(final String id) {
        if (id == null) {
            return "";
        }
        return id.startsWith("turboism.") ? id.substring("turboism.".length()) : id;
    }

    private FormDialogField field(final String id, final String labelKey, final String value) {
        return new FormDialogField(id, localization.text(labelKey), value, FormFieldKind.TEXT);
    }

    private void refreshOffCanvas() {
        try {
            uiHost.refreshOffCanvasAppearance();
        } catch (RuntimeException ignored) {
            // Off-canvas refresh is best-effort; an unsupported host must not fail the save.
        }
    }

    private void notify(final String id, final String level, final String message) {
        uiHost.notifyStatus(new StatusNotification(id, level, message));
    }
}
