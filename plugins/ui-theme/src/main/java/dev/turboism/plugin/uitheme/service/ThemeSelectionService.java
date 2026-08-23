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

    /**
     * Applies the package's appearance and persists the selection only if the host accepted it.
     *
     * <p>Ordering is the guarantee: a rejected apply leaves the persisted selection untouched, so the
     * store never names a theme that is not actually in force. {@code NO_CHANGE} counts as acceptance.
     * Blocks on the appearance service.
     *
     * @param theme the package to make current
     * @return {@code SELECTED} with the selection persisted, or {@code APPLY_FAILED} with nothing
     *     changed, carrying the host's diagnostic id in either case
     * @throws NullPointerException if {@code theme} is {@code null}
     */
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

    /**
     * Clears a persisted selection that names a package which no longer exists, restoring the native
     * appearance first.
     *
     * <p>Fails closed: if the restore is refused, the selection is deliberately left in place and
     * {@code RESTORE_FAILED} is returned rather than clearing state the host did not release. A present
     * or absent-but-valid selection is {@code NO_CHANGE}.
     *
     * @param themes lookup used to decide whether the selected id still resolves to a package
     * @return {@code NO_CHANGE}, {@code INVALID_SELECTION_CLEARED}, or {@code RESTORE_FAILED}
     * @throws NullPointerException if {@code themes} is {@code null}
     */
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

    /**
     * Deletes one package, restoring the native appearance first when that package is the one currently
     * selected.
     *
     * <p>Order matters: when the target is selected, the appearance is released before the delete runs
     * and the selection is cleared after, so a refused restore ({@code RESTORE_FAILED}) leaves the
     * package on disk and still applied. Deleting a package that is not selected touches neither the
     * appearance nor the store.
     *
     * @param themeId the package to delete, must not be blank
     * @param delete the deletion action to run; exceptions it throws propagate to the caller
     * @return {@code DELETED}, or {@code RESTORE_FAILED} with nothing deleted
     * @throws IllegalArgumentException if {@code themeId} is null or blank
     * @throws NullPointerException if {@code delete} is {@code null}
     */
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

    /**
     * Outcome of a selection, restore or delete attempt.
     *
     * @param outcome what happened, including the failure modes that changed nothing
     * @param diagnosticId the appearance host's diagnostic reference when it supplied one
     */
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
