package dev.turboism.plugin.uitheme.service;

import dev.turboism.plugin.uitheme.b1.domain.LegacyThemePaletteResolver;
import dev.turboism.plugin.uitheme.b1.domain.ThemePackageData;
import dev.turboism.sdk.appearance.AppearanceApplyResult;
import dev.turboism.sdk.appearance.AppearanceService;
import dev.turboism.sdk.appearance.AppearanceRestoreResult;

import java.util.Objects;
import java.util.Optional;

/** Commits a selected package only after its semantic appearance was accepted by the host. */
public final class ThemeSelectionService {

    private final AppearanceService appearance;
    private final SelectionStore selections;

    public ThemeSelectionService(
        final AppearanceService appearance,
        final SelectionStore selections
    ) {
        this.appearance = Objects.requireNonNull(appearance, "appearance");
        this.selections = Objects.requireNonNull(selections, "selections");
    }

    public SelectionResult select(final ThemePackageData theme) {
        Objects.requireNonNull(theme, "theme");
        final long revision = appearance.current().toCompletableFuture().join().revision();
        final AppearanceApplyResult applied = appearance.apply(
            LegacyThemePaletteResolver.resolve(theme, revision)
        ).toCompletableFuture().join();
        if (applied.outcome() == AppearanceApplyResult.Outcome.APPLIED
            || applied.outcome() == AppearanceApplyResult.Outcome.NO_CHANGE) {
            selections.saveSelectedThemeId(theme.metadata().id());
            return new SelectionResult(SelectionOutcome.SELECTED, applied.diagnosticId());
        }
        return new SelectionResult(SelectionOutcome.APPLY_FAILED, applied.diagnosticId());
    }

    /** Restores the host's native appearance and clears the persisted selection. */
    public SelectionResult restoreNative() {
        final AppearanceRestoreResult restored = appearance.restoreOwnedAppearance()
            .toCompletableFuture().join();
        if (restored.outcome() == AppearanceRestoreResult.Outcome.RESTORED
            || restored.outcome() == AppearanceRestoreResult.Outcome.NO_OWNED_OVERRIDE) {
            selections.clearSelectedThemeId();
            return new SelectionResult(
                SelectionOutcome.RESTORED_NATIVE,
                restored.diagnosticId()
            );
        }
        return new SelectionResult(SelectionOutcome.RESTORE_FAILED, restored.diagnosticId());
    }

    public SelectionResult restoreIfSelectionIsMissing(final ThemeLookup themes) {
        Objects.requireNonNull(themes, "themes");
        final Optional<String> selectedId = selections.selectedThemeId();
        if (selectedId.isEmpty() || themes.find(selectedId.orElseThrow()).isPresent()) {
            return new SelectionResult(SelectionOutcome.NO_CHANGE, Optional.empty());
        }
        final AppearanceRestoreResult restored = appearance.restoreOwnedAppearance()
            .toCompletableFuture().join();
        if (restored.outcome() == AppearanceRestoreResult.Outcome.RESTORED
            || restored.outcome() == AppearanceRestoreResult.Outcome.NO_OWNED_OVERRIDE) {
            selections.clearSelectedThemeId();
            return new SelectionResult(
                SelectionOutcome.INVALID_SELECTION_CLEARED,
                restored.diagnosticId()
            );
        }
        return new SelectionResult(SelectionOutcome.RESTORE_FAILED, restored.diagnosticId());
    }

    public SelectionResult delete(final String themeId, final ThemeDelete delete) {
        if (themeId == null || themeId.isBlank()) {
            throw new IllegalArgumentException("themeId must not be blank");
        }
        Objects.requireNonNull(delete, "delete");
        if (selections.selectedThemeId().filter(themeId::equals).isPresent()) {
            final AppearanceRestoreResult restored = appearance.restoreOwnedAppearance()
                .toCompletableFuture().join();
            if (restored.outcome() != AppearanceRestoreResult.Outcome.RESTORED
                && restored.outcome() != AppearanceRestoreResult.Outcome.NO_OWNED_OVERRIDE) {
                return new SelectionResult(SelectionOutcome.RESTORE_FAILED, restored.diagnosticId());
            }
            delete.delete(themeId);
            selections.clearSelectedThemeId();
            return new SelectionResult(SelectionOutcome.DELETED, restored.diagnosticId());
        }
        delete.delete(themeId);
        return new SelectionResult(SelectionOutcome.DELETED, Optional.empty());
    }

    public enum SelectionOutcome {
        SELECTED,
        APPLY_FAILED,
        INVALID_SELECTION_CLEARED,
        RESTORED_NATIVE,
        RESTORE_FAILED,
        NO_CHANGE,
        DELETED
    }

    public record SelectionResult(
        SelectionOutcome outcome,
        Optional<String> diagnosticId
    ) {
        public SelectionResult {
            outcome = Objects.requireNonNull(outcome, "outcome");
            diagnosticId = Objects.requireNonNull(diagnosticId, "diagnosticId");
        }
    }

    @FunctionalInterface
    public interface ThemeLookup {
        Optional<ThemePackageData> find(String themeId);
    }

    @FunctionalInterface
    public interface ThemeDelete {
        void delete(String themeId);
    }

    public interface SelectionStore {
        Optional<String> selectedThemeId();

        void saveSelectedThemeId(String themeId);


        void clearSelectedThemeId();
    }
}
