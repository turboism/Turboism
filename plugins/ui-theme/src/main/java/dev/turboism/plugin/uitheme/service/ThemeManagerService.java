package dev.turboism.plugin.uitheme.service;

import dev.turboism.plugin.uitheme.b1.domain.BuiltinThemeCatalog;
import dev.turboism.plugin.uitheme.b1.domain.ThemeBase;
import dev.turboism.plugin.uitheme.b1.domain.ThemePackageData;
import dev.turboism.plugin.uitheme.b1.domain.ThemePackageMetadata;
import dev.turboism.sdk.plugin.PluginLogger;
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

    public ThemeManagerService(
        final UiHostCapabilityService uiHost,
        final BuiltinThemeAppearanceService builtins,
        final ThemePackageRepository repository,
        final ThemePackageTransferService transfer,
        final ThemeSelectionService selection,
        final ThemeSelectionConfig selectionConfig,
        final PluginLogger logger
    ) {
        this.uiHost = Objects.requireNonNull(uiHost, "uiHost");
        this.builtins = Objects.requireNonNull(builtins, "builtins");
        this.repository = Objects.requireNonNull(repository, "repository");
        this.transfer = Objects.requireNonNull(transfer, "transfer");
        this.selection = Objects.requireNonNull(selection, "selection");
        this.selectionConfig = Objects.requireNonNull(selectionConfig, "selectionConfig");
        this.logger = Objects.requireNonNull(logger, "logger");
    }

    public void open() {
        final List<ThemePackageData> themes = themes();
        if (themes.isEmpty()) {
            notify("ui-theme.manager.empty", "WARNING", "No valid theme packages are available.");
            return;
        }
        final List<ChoiceDialogOption> options = themes.stream().map(this::option).toList();
        final Optional<String> selected = selectionConfig.selectedThemeId()
            .filter(id -> options.stream().anyMatch(option -> option.id().equals(id)));
        final Optional<String> chosen = uiHost.choose(new ChoiceDialogRequest(
            DIALOG_ID,
            "Theme Manager",
            "Choose a theme package. Applying changes Cubism's semantic colors; package metadata and optional UI capabilities remain grouped under the same theme.",
            options,
            selected,
            "Apply",
            "Cancel"
        ));
        chosen.flatMap(id -> themes.stream().filter(theme -> theme.metadata().id().equals(id)).findFirst())
            .ifPresent(this::apply);
    }

    public void importPackage() {
        final ThemePackageTransferService.ImportResult imported = transfer.importPackage();
        if (imported.outcome() != ThemePackageTransferService.ImportOutcome.IMPORTED) {
            notify(
                "ui-theme.package.import." + imported.outcome().name().toLowerCase(java.util.Locale.ROOT),
                imported.outcome() == ThemePackageTransferService.ImportOutcome.CANCELED ? "INFO" : "WARNING",
                "Theme import result: " + imported.outcome()
            );
            return;
        }
        final ThemePackageData theme = imported.theme().orElseThrow();
        final ThemePackageRepository.SaveResult saved = repository.save(theme, false);
        if (saved.outcome() == ThemePackageRepository.SaveOutcome.CONFLICT) {
            notify("ui-theme.package.import.conflict", "WARNING", "A theme with this id already exists.");
            return;
        }
        notify(
            "ui-theme.package.import." + saved.outcome().name().toLowerCase(java.util.Locale.ROOT),
            saved.outcome() == ThemePackageRepository.SaveOutcome.SAVED ? "INFO" : "WARNING",
            "Theme package " + theme.metadata().name() + ": " + saved.outcome()
        );
    }

    public void exportSelected() {
        final Optional<ThemePackageData> selected = selectionConfig.selectedThemeId().flatMap(this::find);
        if (selected.isEmpty()) {
            notify("ui-theme.package.export.no-selection", "WARNING", "Select a theme before exporting it.");
            return;
        }
        final ThemePackageTransferService.ExportResult exported = transfer.exportPackage(selected.orElseThrow());
        notify(
            "ui-theme.package.export." + exported.outcome().name().toLowerCase(java.util.Locale.ROOT),
            exported.outcome() == ThemePackageTransferService.ExportOutcome.EXPORTED ? "INFO" : "WARNING",
            "Theme export result: " + exported.outcome()
        );
    }

    public void deleteSelected() {
        final Optional<String> selected = selectionConfig.selectedThemeId();
        if (selected.isEmpty()) {
            notify("ui-theme.package.delete.no-selection", "WARNING", "No selected theme can be deleted.");
            return;
        }
        final String id = selected.orElseThrow();
        if (BuiltinThemeCatalog.isReviewedBuiltin(id)) {
            notify("ui-theme.package.delete.builtin", "WARNING", "Built-in themes cannot be deleted.");
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
            }
        );
        notify(
            "ui-theme.package.delete." + result.outcome().name().toLowerCase(java.util.Locale.ROOT),
            result.outcome() == ThemeSelectionService.SelectionOutcome.DELETED ? "INFO" : "WARNING",
            "Theme delete result: " + result.outcome()
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
        return BuiltinThemeCatalog.visibleEntries().stream()
            .filter(entry -> entry.id().equals(id))
            .findFirst()
            .map(entry -> builtins.load(entry.id()))
            .or(() -> repository.find(id));
    }

    private void apply(final ThemePackageData theme) {
        final ThemeSelectionService.SelectionResult result = selection.select(theme);
        notify(
            "ui-theme.selection." + result.outcome().name().toLowerCase(java.util.Locale.ROOT),
            result.outcome() == ThemeSelectionService.SelectionOutcome.SELECTED ? "INFO" : "WARNING",
            "Theme selection result: " + result.outcome()
        );
    }

    private ChoiceDialogOption option(final ThemePackageData theme) {
        final ThemePackageMetadata metadata = theme.metadata();
        final StringBuilder detail = new StringBuilder();
        append(detail, "Name", metadata.name());
        append(detail, "ID", metadata.id());
        append(detail, "Version", metadata.version());
        append(detail, "Base", base(metadata.base()));
        append(detail, "Description", metadata.description());
        append(detail, "Author", metadata.author());
        append(detail, "URL", metadata.url());
        append(detail, "Icons", metadata.icons().name().toLowerCase(java.util.Locale.ROOT));
        append(detail, "Package", metadata.builtIn() ? "Built-in" : "Imported");
        return new ChoiceDialogOption(metadata.id(), metadata.name(), detail.toString(), true);
    }

    private static String base(final ThemeBase base) {
        return switch (base) {
            case LIGHT -> "Light";
            case DARK -> "Dark";
            case ANY -> "Any";
        };
    }

    private static void append(final StringBuilder target, final String label, final String value) {
        if (value != null && !value.isBlank()) {
            if (!target.isEmpty()) {
                target.append('\n');
            }
            target.append(label).append(": ").append(value);
        }
    }

    private void notify(final String id, final String level, final String message) {
        uiHost.notifyStatus(new StatusNotification(id, level, message));
    }
}
