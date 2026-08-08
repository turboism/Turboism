package dev.turboism.plugin.uitheme.service;

import dev.turboism.plugin.uitheme.b1.domain.ThemeBase;
import dev.turboism.plugin.uitheme.b1.domain.ThemeIcons;
import dev.turboism.plugin.uitheme.b1.domain.ThemePackageData;
import dev.turboism.plugin.uitheme.b1.domain.ThemePackageMetadata;
import dev.turboism.sdk.appearance.AppearanceApplyResult;
import dev.turboism.sdk.appearance.AppearanceBase;
import dev.turboism.sdk.appearance.AppearanceRequest;
import dev.turboism.sdk.appearance.AppearanceRestoreResult;
import dev.turboism.sdk.appearance.AppearanceService;
import dev.turboism.sdk.appearance.AppearanceStatus;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class ThemeSelectionServiceTest {

    @Test
    void failedHostApplyDoesNotCommitTheSelectedTheme() {
        RecordingSelectionStore selections = new RecordingSelectionStore("turboism.slate");
        RecordingAppearanceService appearance = new RecordingAppearanceService(
            AppearanceApplyResult.Outcome.FAILED_RESTORED
        );
        ThemeSelectionService service = new ThemeSelectionService(appearance, selections);

        ThemeSelectionService.SelectionResult result = service.select(theme("turboism.nord"));

        assertEquals(ThemeSelectionService.SelectionOutcome.APPLY_FAILED, result.outcome());
        assertEquals("turboism.slate", selections.selectedThemeId().orElseThrow());
        assertEquals(0, selections.saveCount());
        assertEquals("turboism.nord", appearance.lastRequest().appearanceId());
        assertTrue(result.diagnosticId().isPresent());
    }

    @Test
    void successfulHostApplyCommitsTheSelectedTheme() {
        RecordingSelectionStore selections = new RecordingSelectionStore("turboism.slate");
        RecordingAppearanceService appearance = new RecordingAppearanceService(
            AppearanceApplyResult.Outcome.APPLIED
        );
        ThemeSelectionService service = new ThemeSelectionService(appearance, selections);

        ThemeSelectionService.SelectionResult result = service.select(theme("turboism.nord"));

        assertEquals(ThemeSelectionService.SelectionOutcome.SELECTED, result.outcome());
        assertEquals("turboism.nord", selections.selectedThemeId().orElseThrow());
        assertEquals(1, selections.saveCount());
    }

    @Test
    void missingPersistedThemeRestoresOwnedAppearanceAndClearsSelection() {
        RecordingSelectionStore selections = new RecordingSelectionStore("missing.theme");
        RecordingAppearanceService appearance = new RecordingAppearanceService(
            AppearanceApplyResult.Outcome.APPLIED,
            AppearanceRestoreResult.Outcome.RESTORED
        );
        ThemeSelectionService service = new ThemeSelectionService(appearance, selections);

        ThemeSelectionService.SelectionResult result = service.restoreIfSelectionIsMissing(
            themeId -> Optional.empty()
        );

        assertEquals(ThemeSelectionService.SelectionOutcome.INVALID_SELECTION_CLEARED, result.outcome());
        assertTrue(selections.selectedThemeId().isEmpty());
        assertEquals(1, selections.clearCount());
        assertEquals(1, appearance.restoreCount());
    }

    @Test
    void deletingTheSelectedThemeRestoresBeforeDeletingAndClearingSelection() {
        RecordingSelectionStore selections = new RecordingSelectionStore("turboism.nord");
        RecordingAppearanceService appearance = new RecordingAppearanceService(
            AppearanceApplyResult.Outcome.APPLIED,
            AppearanceRestoreResult.Outcome.RESTORED
        );
        ThemeSelectionService service = new ThemeSelectionService(appearance, selections);
        java.util.List<String> deleted = new java.util.ArrayList<>();

        ThemeSelectionService.SelectionResult result = service.delete(
            "turboism.nord",
            deleted::add
        );

        assertEquals(ThemeSelectionService.SelectionOutcome.DELETED, result.outcome());
        assertEquals(java.util.List.of("turboism.nord"), deleted);
        assertTrue(selections.selectedThemeId().isEmpty());
        assertEquals(1, appearance.restoreCount());
    }

    @Test
    void selectedThemeIsNotDeletedWhenRestoreFails() {
        RecordingSelectionStore selections = new RecordingSelectionStore("turboism.nord");
        RecordingAppearanceService appearance = new RecordingAppearanceService(
            AppearanceApplyResult.Outcome.APPLIED,
            AppearanceRestoreResult.Outcome.FAILED_RESTORE
        );
        ThemeSelectionService service = new ThemeSelectionService(appearance, selections);
        java.util.List<String> deleted = new java.util.ArrayList<>();

        ThemeSelectionService.SelectionResult result = service.delete(
            "turboism.nord",
            deleted::add
        );

        assertEquals(ThemeSelectionService.SelectionOutcome.RESTORE_FAILED, result.outcome());
        assertEquals(java.util.List.of(), deleted);
        assertEquals("turboism.nord", selections.selectedThemeId().orElseThrow());
        assertEquals(0, selections.clearCount());
    }

    private static ThemePackageData theme(final String id) {
        return new ThemePackageData(
            new ThemePackageMetadata(
                id,
                "Nord",
                "",
                "Turboism",
                "",
                "1",
                null,
                ThemeBase.DARK,
                ThemeIcons.LIGHT,
                true
            ),
            Map.of(
                "accent", "#88C0D0",
                "background", "#2E3440",
                "surface", "#3B4252",
                "input.background", "#434C5E",
                "foreground", "#ECEFF4",
                "foreground.muted", "#D8DEE9",
                "selection.background", "#4C566A",
                "selection.foreground", "#ECEFF4",
                "border", "#4C566A",
                "viewport.background", "#242933"
            ),
            Map.of(),
            "",
            ""
        );
    }

    private static final class RecordingSelectionStore implements ThemeSelectionService.SelectionStore {
        private String selectedThemeId;
        private int saveCount;
        private int clearCount;

        private RecordingSelectionStore(final String selectedThemeId) {
            this.selectedThemeId = selectedThemeId;
        }

        @Override
        public Optional<String> selectedThemeId() {
            return Optional.ofNullable(selectedThemeId);
        }

        @Override
        public void saveSelectedThemeId(final String themeId) {
            selectedThemeId = themeId;
            saveCount++;
        }

        @Override
        public void clearSelectedThemeId() {
            selectedThemeId = null;
            clearCount++;
        }

        int saveCount() {
            return saveCount;
        }

        int clearCount() {
            return clearCount;
        }
    }

    private static final class RecordingAppearanceService implements AppearanceService {
        private final AppearanceApplyResult.Outcome outcome;
        private final AppearanceRestoreResult.Outcome restoreOutcome;
        private AppearanceRequest lastRequest;
        private int restoreCount;
        private final AppearanceStatus status = new AppearanceStatus(
            AppearanceStatus.Availability.AVAILABLE,
            AppearanceStatus.Source.NATIVE,
            Optional.empty(),
            AppearanceBase.NATIVE,
            7,
            Optional.empty()
        );

        private RecordingAppearanceService(final AppearanceApplyResult.Outcome outcome) {
            this(outcome, AppearanceRestoreResult.Outcome.NO_OWNED_OVERRIDE);
        }

        private RecordingAppearanceService(
            final AppearanceApplyResult.Outcome outcome,
            final AppearanceRestoreResult.Outcome restoreOutcome
        ) {
            this.outcome = outcome;
            this.restoreOutcome = restoreOutcome;
        }

        AppearanceRequest lastRequest() {
            return lastRequest;
        }

        @Override
        public CompletionStage<AppearanceStatus> current() {
            return CompletableFuture.completedFuture(status);
        }

        @Override
        public CompletionStage<AppearanceApplyResult> apply(final AppearanceRequest request) {
            lastRequest = request;
            return CompletableFuture.completedFuture(new AppearanceApplyResult(
                outcome,
                status,
                Optional.of("appearance.apply.failed-restored")
            ));
        }

        @Override
        public CompletionStage<AppearanceRestoreResult> restoreOwnedAppearance() {
            restoreCount++;
            return CompletableFuture.completedFuture(new AppearanceRestoreResult(
                restoreOutcome,
                status,
                Optional.empty()
            ));
        }

        int restoreCount() {
            return restoreCount;
    }
    }
}
