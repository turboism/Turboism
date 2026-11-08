package dev.turboism.plugin.uitheme.service;

import dev.turboism.plugin.uitheme.domain.LegacyThemePaletteResolver;
import dev.turboism.plugin.uitheme.domain.ThemePackageData;
import dev.turboism.sdk.appearance.AppearanceApplyResult;
import dev.turboism.sdk.appearance.AppearanceRestoreResult;
import dev.turboism.sdk.appearance.AppearanceService;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/**
 * Commits a selected package only after its semantic appearance was accepted by the host.
 *
 * <p>No method waits for the host: the runtime settles appearance and persistence work on the owning
 * plugin's worker, so waiting there can never be released and callers must compose instead.</p>
 */
public final class ThemeSelectionService {

    private final AppearanceService appearance;
    private final SelectionStore selections;

    public ThemeSelectionService(final AppearanceService appearance, final SelectionStore selections) {
        this.appearance = Objects.requireNonNull(appearance, "appearance");
        this.selections = Objects.requireNonNull(selections, "selections");
    }

    /**
     * Applies the package's appearance and persists the selection only if the host accepted it.
     *
     * <p>Ordering is the guarantee: a rejected apply leaves the persisted selection untouched, so the
     * store never names a theme that is not actually in force. {@code NO_CHANGE} counts as acceptance.
     *
     * @param theme the package to make current
     * @return a stage of {@code SELECTED} with the selection persisted, or {@code APPLY_FAILED} with
     *     nothing changed, carrying the host's diagnostic id in either case
     * @throws NullPointerException if {@code theme} is {@code null}
     */
    public CompletionStage<SelectionResult> select(final ThemePackageData theme) {
        Objects.requireNonNull(theme, "theme");
        return appearance
                .current()
                .thenCompose(status -> appearance.apply(LegacyThemePaletteResolver.resolve(theme, status.revision())))
                .thenCompose(applied -> accepted(applied)
                        ? selections
                                .saveSelectedThemeId(theme.metadata().id())
                                .thenApply(ignored ->
                                        new SelectionResult(SelectionOutcome.SELECTED, applied.diagnosticId()))
                        : CompletableFuture.completedFuture(
                                new SelectionResult(SelectionOutcome.APPLY_FAILED, applied.diagnosticId())));
    }

    /**
     * Restores the host's native appearance and clears the persisted selection.
     *
     * @return a stage of {@code RESTORED_NATIVE} once the host released its override and the store was
     *     cleared, or {@code RESTORE_FAILED} with the selection and the host left as they were
     */
    public CompletionStage<SelectionResult> restoreNative() {
        return appearance
                .restoreOwnedAppearance()
                .thenCompose(restored -> released(restored)
                        ? selections
                                .clearSelectedThemeId()
                                .thenApply(ignored ->
                                        new SelectionResult(SelectionOutcome.RESTORED_NATIVE, restored.diagnosticId()))
                        : CompletableFuture.completedFuture(
                                new SelectionResult(SelectionOutcome.RESTORE_FAILED, restored.diagnosticId())));
    }

    /**
     * @param result the host's answer to an apply request
     * @return {@code true} when the host reports the requested appearance as in force
     */
    public static boolean accepted(final AppearanceApplyResult result) {
        return result.outcome() == AppearanceApplyResult.Outcome.APPLIED
                || result.outcome() == AppearanceApplyResult.Outcome.NO_CHANGE;
    }

    private static boolean released(final AppearanceRestoreResult restored) {
        return restored.outcome() == AppearanceRestoreResult.Outcome.RESTORED
                || restored.outcome() == AppearanceRestoreResult.Outcome.NO_OWNED_OVERRIDE;
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
     * @return a stage of {@code NO_CHANGE}, {@code INVALID_SELECTION_CLEARED}, or
     *     {@code RESTORE_FAILED}
     * @throws NullPointerException if {@code themes} is {@code null}
     */
    public CompletionStage<SelectionResult> restoreIfSelectionIsMissing(final ThemeLookup themes) {
        Objects.requireNonNull(themes, "themes");
        final Optional<String> selectedId = selections.selectedThemeId();
        if (selectedId.isEmpty()) {
            return CompletableFuture.completedFuture(new SelectionResult(SelectionOutcome.NO_CHANGE, Optional.empty()));
        }
        return themes.find(selectedId.orElseThrow())
                .thenCompose(found -> found.isPresent()
                        ? CompletableFuture.completedFuture(
                                new SelectionResult(SelectionOutcome.NO_CHANGE, Optional.empty()))
                        : clearMissingSelection());
    }

    private CompletionStage<SelectionResult> clearMissingSelection() {
        return appearance
                .restoreOwnedAppearance()
                .thenCompose(restored -> released(restored)
                        ? selections
                                .clearSelectedThemeId()
                                .thenApply(ignored -> new SelectionResult(
                                        SelectionOutcome.INVALID_SELECTION_CLEARED, restored.diagnosticId()))
                        : CompletableFuture.completedFuture(
                                new SelectionResult(SelectionOutcome.RESTORE_FAILED, restored.diagnosticId())));
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
     * @param delete the deletion work to run; a failure it reports fails the returned stage
     * @return a stage of {@code DELETED}, or {@code RESTORE_FAILED} with nothing deleted
     * @throws IllegalArgumentException if {@code themeId} is null or blank
     * @throws NullPointerException if {@code delete} is {@code null}
     */
    public CompletionStage<SelectionResult> delete(final String themeId, final ThemeDelete delete) {
        if (themeId == null || themeId.isBlank()) {
            throw new IllegalArgumentException("themeId must not be blank");
        }
        Objects.requireNonNull(delete, "delete");
        if (selections.selectedThemeId().filter(themeId::equals).isEmpty()) {
            return delete.delete(themeId)
                    .thenApply(ignored -> new SelectionResult(SelectionOutcome.DELETED, Optional.empty()));
        }
        return appearance
                .restoreOwnedAppearance()
                .thenCompose(restored -> released(restored)
                        ? delete.delete(themeId)
                                .thenCompose(ignored -> selections.clearSelectedThemeId())
                                .thenApply(ignored ->
                                        new SelectionResult(SelectionOutcome.DELETED, restored.diagnosticId()))
                        : CompletableFuture.completedFuture(
                                new SelectionResult(SelectionOutcome.RESTORE_FAILED, restored.diagnosticId())));
    }

    /**
     * Outcome of a selection operation.
     *
     * <p>{@code SELECTED}, {@code RESTORED_NATIVE} and {@code DELETED} report completed
     * changes; {@code APPLY_FAILED} and {@code RESTORE_FAILED} mean the host refused and
     * nothing was persisted or deleted; {@code INVALID_SELECTION_CLEARED} means a selection
     * naming a missing package was removed after a successful restore; {@code NO_CHANGE} means
     * the persisted selection was already absent or still valid.</p>
     */
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
    public record SelectionResult(SelectionOutcome outcome, Optional<String> diagnosticId) {
        public SelectionResult {
            outcome = Objects.requireNonNull(outcome, "outcome");
            diagnosticId = Objects.requireNonNull(diagnosticId, "diagnosticId");
        }
    }

    /** Looks up whether a theme id still resolves to an installed package. */
    @FunctionalInterface
    public interface ThemeLookup {
        /**
         * @param themeId the package id to resolve
         * @return a stage of the package, or empty when no installed package has that id
         */
        CompletionStage<Optional<ThemePackageData>> find(String themeId);
    }

    /** Deletes one installed theme package; a failure is reported through the returned stage. */
    @FunctionalInterface
    public interface ThemeDelete {
        /**
         * Removes one package.
         *
         * @param themeId the package id to delete
         * @return a stage that completes once the package is gone
         */
        CompletionStage<?> delete(String themeId);
    }

    /**
     * The persisted record of which theme is currently selected.
     *
     * <p>Implemented by {@link ThemeSelectionConfig} over the plugin config registry; the
     * record survives restarts so a selection can be re-applied or validated later.</p>
     */
    public interface SelectionStore {
        /**
         * @return the persisted id of the selected theme, or empty when the native appearance
         *     is in force or nothing was persisted
         */
        Optional<String> selectedThemeId();

        /**
         * Persists {@code themeId} as the current selection.
         *
         * @param themeId the theme to record as selected
         * @return a stage that completes once the store holds the selection
         */
        CompletionStage<Void> saveSelectedThemeId(String themeId);

        /**
         * Removes the persisted selection, returning to the native-appearance state.
         *
         * @return a stage that completes once the store no longer holds a selection
         */
        CompletionStage<Void> clearSelectedThemeId();
    }
}
