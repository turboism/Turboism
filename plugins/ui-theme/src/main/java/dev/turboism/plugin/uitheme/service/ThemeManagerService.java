package dev.turboism.plugin.uitheme.service;

import dev.turboism.plugin.uitheme.b1.domain.BuiltinThemeCatalog;
import dev.turboism.plugin.uitheme.b1.domain.ThemeBase;
import dev.turboism.plugin.uitheme.b1.domain.ThemePackageData;
import dev.turboism.plugin.uitheme.b1.domain.ThemePackageMetadata;
import dev.turboism.sdk.i18n.PluginLocalization;
import dev.turboism.sdk.plugin.PluginLogger;
import dev.turboism.sdk.ui.ChoiceDialogDetailRow;
import dev.turboism.sdk.ui.ChoiceDialogOption;
import dev.turboism.sdk.ui.ChoiceDialogRequest;
import dev.turboism.sdk.ui.StatusNotification;
import dev.turboism.sdk.ui.UiHostCapabilityService;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Unified theme selection window and package workflow. */
public final class ThemeManagerService {

    private static final String DIALOG_ID = "ui-theme.manager";

    private final UiHostCapabilityService uiHost;
    private final BuiltinThemeAppearanceService builtins;
    private final ThemePackageRepository repository;
    private final ThemePackageTransferService transfer;
    private final ThemeSelectionService selection;
    private final ThemeSelectionConfig selectionConfig;
    private final PluginLogger logger;
    private final PluginLocalization localization;
    private final ThemeEditorService editor;

    public ThemeManagerService(
        final UiHostCapabilityService uiHost,
        final BuiltinThemeAppearanceService builtins,
        final ThemePackageRepository repository,
        final ThemePackageTransferService transfer,
        final ThemeSelectionService selection,
        final ThemeSelectionConfig selectionConfig,
        final PluginLogger logger,
        final PluginLocalization localization,
        final ThemeEditorService editor
    ) {
        this.uiHost = Objects.requireNonNull(uiHost, "uiHost");
        this.builtins = Objects.requireNonNull(builtins, "builtins");
        this.repository = Objects.requireNonNull(repository, "repository");
        this.transfer = Objects.requireNonNull(transfer, "transfer");
        this.selection = Objects.requireNonNull(selection, "selection");
        this.selectionConfig = Objects.requireNonNull(selectionConfig, "selectionConfig");
        this.logger = Objects.requireNonNull(logger, "logger");
        this.localization = Objects.requireNonNull(localization, "localization");
        this.editor = Objects.requireNonNull(editor, "editor");
    }

    private static final String ACTION_IMPORT = "import";
    private static final String ACTION_EXPORT = "export";
    private static final String ACTION_DELETE = "delete";
    private static final String ACTION_OPEN_DIR = "open-dir";
    private static final String ACTION_NEW_THEME = "new-theme";
    private static final String ACTION_EDIT_THEME = "edit-theme";

    private final java.util.concurrent.atomic.AtomicBoolean dialogOpen =
        new java.util.concurrent.atomic.AtomicBoolean();
    private volatile java.util.List<ThemePackageData> cachedThemes = java.util.List.of();

    /** Rebuilds the theme list off the action path; called on enable and after package changes. */
    public void refreshCache() {
        cachedThemes = themes();
    }

    /** Opens the non-blocking theme manager window. The window owns all theme workflows. */
    public void open() {
        if (!dialogOpen.compareAndSet(false, true)) {
            return;
        }
        final List<ThemePackageData> themes = compatibleThemes(cachedThemes);
        if (themes.isEmpty()) {
            dialogOpen.set(false);
            notify(
                "ui-theme.manager.empty",
                "WARNING",
                localization.text("theme.manager.empty")
            );
            return;
        }
        final List<ChoiceDialogOption> options = themes.stream().map(this::option).toList();
        final Optional<String> selected = selectionConfig.selectedThemeId()
            .filter(id -> options.stream().anyMatch(option -> option.id().equals(id)));
        uiHost.openChoiceDialog(
            new ChoiceDialogRequest(
                DIALOG_ID,
                localization.text("theme.manager.title"),
                localization.text("theme.notice"),
                options,
                selected,
                localization.text("theme.button.apply"),
                localization.text("theme.button.close"),
                List.of(
                    new dev.turboism.sdk.ui.ChoiceDialogAction(ACTION_NEW_THEME, localization.text("theme.button.newTheme")),
                    new dev.turboism.sdk.ui.ChoiceDialogAction(ACTION_EDIT_THEME, localization.text("theme.button.editTheme")),
                    new dev.turboism.sdk.ui.ChoiceDialogAction(ACTION_OPEN_DIR, localization.text("theme.button.openDir")),
                    new dev.turboism.sdk.ui.ChoiceDialogAction(ACTION_IMPORT, localization.text("theme.button.import"))
                ),
                Optional.of(this::refreshOptions),
                localization.text("theme.button.reload")
            ),
            this::handleResult
        );
    }

    /** Filters the cached themes to those compatible with the active Cubism color mode. */
    private List<ThemePackageData> compatibleThemes(final List<ThemePackageData> themes) {
        final dev.turboism.sdk.ui.UiHostColorMode mode = uiHost.currentColorMode();
        return themes.stream()
            .filter(theme -> compatible(theme.metadata().base(), mode))
            .toList();
    }

    private static boolean compatible(final ThemeBase base, final dev.turboism.sdk.ui.UiHostColorMode mode) {
        return switch (base) {
            case ANY -> true;
            case LIGHT -> mode == dev.turboism.sdk.ui.UiHostColorMode.LIGHT;
            case DARK -> mode == dev.turboism.sdk.ui.UiHostColorMode.DARK;
        };
    }

    /** Rebuilds options in-place for the window reload button. */
    private List<ChoiceDialogOption> refreshOptions() {
        refreshCache();
        return compatibleThemes(cachedThemes).stream().map(this::option).toList();
    }

    private void handleResult(final String optionId, final String actionId) {
        dialogOpen.set(false);
        if (optionId == null && actionId == null) {
            return;
        }
        final Runnable work;
        if (actionId == null || actionId.isBlank()) {
            work = () -> find(optionId).ifPresent(this::apply);
        } else if (ACTION_IMPORT.equals(actionId)) {
            work = this::importPackage;
        } else if (ACTION_EXPORT.equals(actionId)) {
            work = () -> exportSelected(optionId);
        } else if (ACTION_DELETE.equals(actionId)) {
            work = () -> deleteSelected(optionId);
        } else if (ACTION_OPEN_DIR.equals(actionId)) {
            work = this::openThemeDirectory;
        } else if (ACTION_NEW_THEME.equals(actionId)) {
            work = editor::openNew;
        } else if (ACTION_EDIT_THEME.equals(actionId)) {
            work = () -> find(optionId).ifPresent(editor::openEdit);
        } else {
            return;
        }
        final Thread thread = new Thread(work, "ui-theme-manager-op");
        thread.setDaemon(true);
        thread.start();
    }

    public void importPackage() {
        final ThemePackageTransferService.ImportResult imported = transfer.importPackage();
        if (imported.outcome() != ThemePackageTransferService.ImportOutcome.IMPORTED) {
            notify(
                "ui-theme.package.import.canceled",
                "INFO",
                localization.text("theme.package.importCanceled")
            );
            return;
        }
        final ThemePackageData theme = imported.theme().orElseThrow();
        final ThemePackageRepository.SaveResult saved = repository.save(theme, false);
        refreshCache();
        if (saved.outcome() == ThemePackageRepository.SaveOutcome.CONFLICT) {
            notify("ui-theme.package.import.conflict", "WARNING", localization.text("theme.package.importConflict"));
            return;
        }
        notify(
            "ui-theme.package.import.saved",
            saved.outcome() == ThemePackageRepository.SaveOutcome.SAVED ? "INFO" : "WARNING",
            localization.format("theme.package.imported", theme.metadata().name())
        );
    }

    public void exportSelected(final String windowOptionId) {
        final Optional<ThemePackageData> selected =
            Optional.ofNullable(windowOptionId)
                .flatMap(this::find)
                .or(() -> selectionConfig.selectedThemeId().flatMap(this::find));
        if (selected.isEmpty()) {
            notify("ui-theme.package.export.no-selection", "WARNING", localization.text("theme.package.exportNoSelection"));
            return;
        }
        final ThemePackageTransferService.ExportResult exported = transfer.exportPackage(selected.orElseThrow());
        notify(
            "ui-theme.package.export." + exported.outcome().name().toLowerCase(java.util.Locale.ROOT),
            exported.outcome() == ThemePackageTransferService.ExportOutcome.EXPORTED ? "INFO" : "WARNING",
            localization.format("theme.package.exported", selected.orElseThrow().metadata().name())
        );
    }

    public void deleteSelected(final String windowOptionId) {
        final Optional<String> selected =
            Optional.ofNullable(windowOptionId)
                .or(() -> selectionConfig.selectedThemeId());
        if (selected.isEmpty()) {
            notify("ui-theme.package.delete.no-selection", "WARNING", localization.text("theme.package.deleteNoSelection"));
            return;
        }
        final String id = selected.orElseThrow();
        if (BuiltinThemeCatalog.isReviewedBuiltin(id)) {
            notify("ui-theme.package.delete.builtin", "WARNING", localization.text("theme.package.deleteBuiltin"));
            return;
        }
        final ThemeSelectionService.SelectionResult result = selection.delete(
            id,
            themeId -> {
                final ThemePackageRepository.DeleteResult deleted = repository.delete(themeId);
                if (deleted.outcome() != ThemePackageRepository.DeleteOutcome.DELETED
                    && deleted.outcome() != ThemePackageRepository.DeleteOutcome.NOT_FOUND) {
                    throw new IllegalStateException("theme package delete failed");
                }
                refreshCache();
            }
        );
        notify(
            "ui-theme.package.delete." + result.outcome().name().toLowerCase(java.util.Locale.ROOT),
            result.outcome() == ThemeSelectionService.SelectionOutcome.DELETED ? "INFO" : "WARNING",
            localization.format("theme.package.deleted", id)
        );
    }

    public void restorePersistedSelection() {
        final ThemeSelectionService.SelectionResult validity = selection.restoreIfSelectionIsMissing(this::find);
        if (validity.outcome() == ThemeSelectionService.SelectionOutcome.RESTORE_FAILED) {
            logger.warn("Persisted theme validation failed closed");
            return;
        }
        selectionConfig.selectedThemeId().flatMap(this::find).ifPresent(this::apply);
    }

    private List<ThemePackageData> themes() {
        final ArrayList<ThemePackageData> themes = new ArrayList<>();
        BuiltinThemeCatalog.visibleEntries().stream()
            .map(entry -> builtins.load(entry.id()))
            .forEach(themes::add);
        themes.addAll(repository.list());
        return themes.stream()
            .collect(java.util.stream.Collectors.toMap(
                theme -> theme.metadata().id(),
                theme -> theme,
                (first, ignored) -> first,
                java.util.LinkedHashMap::new
            ))
            .values().stream()
            .sorted(Comparator.comparing(theme -> theme.metadata().name(), String.CASE_INSENSITIVE_ORDER))
            .toList();
    }

    private Optional<ThemePackageData> find(final String id) {
        // A saved package overrides a built-in with the same id (editing a
        // built-in theme persists the edited copy), so consult the repository
        // first and fall back to the immutable built-in catalog.
        return repository.find(id)
            .or(() -> BuiltinThemeCatalog.visibleEntries().stream()
                .filter(entry -> entry.id().equals(id))
                .findFirst()
                .map(entry -> builtins.load(entry.id())));
    }

    private void apply(final ThemePackageData theme) {
        final ThemeSelectionService.SelectionResult result = selection.select(theme);
        refreshOffCanvas();
        notify(
            "ui-theme.selection." + result.outcome().name().toLowerCase(java.util.Locale.ROOT),
            result.outcome() == ThemeSelectionService.SelectionOutcome.SELECTED ? "INFO" : "WARNING",
            result.outcome() == ThemeSelectionService.SelectionOutcome.SELECTED
                ? localization.format("theme.selection.applied", theme.metadata().name())
                : localization.text("theme.selection.failed")
        );
    }

    private void refreshOffCanvas() {
        try {
            uiHost.refreshOffCanvasAppearance();
        } catch (RuntimeException ignored) {
            // Off-canvas refresh is best-effort; an unsupported host must not fail the apply.
        }
    }

    /** Opens the plugin theme storage directory in the host file manager. */
    private void openThemeDirectory() {
        uiHost.openDirectory(new dev.turboism.sdk.storage.StoragePath(
            dev.turboism.sdk.storage.StorageRoot.DATA,
            "themes"
        ));
    }

    private ChoiceDialogOption option(final ThemePackageData theme) {
        final ThemePackageMetadata metadata = theme.metadata();
        final List<ChoiceDialogDetailRow> rows = new ArrayList<>();
        rows.add(new ChoiceDialogDetailRow(
            localization.text("theme.detail.name"), metadata.name(), ""));
        rows.add(new ChoiceDialogDetailRow(
            localization.text("theme.detail.id"), metadata.id(), ""));
        rows.add(new ChoiceDialogDetailRow(
            localization.text("theme.detail.version"),
            metadata.version() == null || metadata.version().isBlank() ? "-" : metadata.version(),
            ""));
        rows.add(new ChoiceDialogDetailRow(
            localization.text("theme.detail.base"), base(metadata.base()), ""));
        rows.add(new ChoiceDialogDetailRow(
            localization.text("theme.detail.description"),
            metadata.description() == null || metadata.description().isBlank() ? "-" : metadata.description(),
            ""));
        rows.add(new ChoiceDialogDetailRow(
            localization.text("theme.detail.author"),
            metadata.author() == null || metadata.author().isBlank() ? "-" : metadata.author(),
            ""));
        rows.add(new ChoiceDialogDetailRow(
            localization.text("theme.detail.url"),
            metadata.url() == null || metadata.url().isBlank() ? "-" : metadata.url(),
            metadata.url() == null ? "" : metadata.url()));
        return new ChoiceDialogOption(metadata.id(), metadata.name(), "", true, rows);
    }

    private String base(final ThemeBase base) {
        return switch (base) {
            case LIGHT -> localization.text("theme.base.light");
            case DARK -> localization.text("theme.base.dark");
            case ANY -> localization.text("theme.base.any");
        };
    }

    private void notify(final String id, final String level, final String message) {
        uiHost.notifyStatus(new StatusNotification(id, level, message));
    }
}
