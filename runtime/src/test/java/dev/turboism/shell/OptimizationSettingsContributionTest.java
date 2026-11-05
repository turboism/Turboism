package dev.turboism.shell;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.turboism.config.PerformanceSettingsConfig.Field;
import dev.turboism.config.optimization.Optimization;
import dev.turboism.config.optimization.OptimizationCatalog;
import dev.turboism.internal.core.OptimizationSettingsService;
import dev.turboism.sdk.i18n.PluginLocalization;
import dev.turboism.sdk.ui.settings.SettingsContribution;
import dev.turboism.sdk.ui.settings.SettingsControl;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * The Performance page renders one toggle per declared optimization instead of one hand-written
 * factory per optimization, so these are the contract tests for that derivation: every persisted
 * catalogue row produces its reviewed row, and the toggle persists exactly that row's key.
 */
class OptimizationSettingsContributionTest {

    /** Records what the page asked the service for, keyed like the real service is. */
    private static class RecordingSettings implements OptimizationSettingsService {
        private final Map<String, Boolean> stored = new LinkedHashMap<>();

        RecordingSettings(final boolean initial) {
            for (final Optimization optimization : OptimizationCatalog.persisted()) {
                stored.put(optimization.key(), initial);
            }
        }

        @Override
        public boolean enabled(final String key) {
            return stored.getOrDefault(key, false);
        }

        @Override
        public void setEnabled(final String key, final boolean enabled) {
            stored.put(key, enabled);
        }
    }

    static Stream<Optimization> persisted() {
        return OptimizationCatalog.persisted().stream();
    }

    @ParameterizedTest
    @MethodSource("persisted")
    void everyPersistedEntryRendersItsReviewedToggle(final Optimization optimization) {
        final RecordingSettings settings = new RecordingSettings(false);
        final SettingsContribution contribution =
                OptimizationSettingsContribution.create(localization(), settings, optimization);

        assertEquals(optimization.contributionId(), contribution.id());
        assertEquals("performance", contribution.tab().id());
        assertEquals(
                OptionalInt.of(optimization.placement().orElseThrow().index()),
                contribution.index(),
                optimization.contributionId() + " renders at its catalogue index");

        final SettingsControl.Toggle toggle = assertInstanceOf(SettingsControl.Toggle.class, contribution.control());
        assertEquals(optimization.contributionId(), toggle.id());
        assertEquals(optimization.labelKey(), toggle.label(), "the label is the catalogue key");
        assertEquals(Boolean.FALSE, toggle.binding().read(), "every optimization starts off");

        toggle.binding().write(true);
        assertTrue(settings.enabled(optimization.key()), "writing the toggle persists that catalogue key");
        assertEquals(Boolean.TRUE, toggle.binding().read(), "the toggle reads back the persisted value");
        for (final Optimization other : OptimizationCatalog.persisted()) {
            if (other.equals(optimization)) continue;
            assertFalse(
                    settings.enabled(other.key()),
                    "writing one toggle must not touch " + other.key() + " and must not reach any hook");
        }

        toggle.binding().write(false);
        assertFalse(settings.enabled(optimization.key()), "an explicit opt-out is persisted too");
    }

    @ParameterizedTest
    @MethodSource("persisted")
    void everyPersistedEntryKeepsItsDeclaredTooltip(final Optimization optimization) {
        assertEquals(
                Optional.of(optimization.tooltipKey()),
                CubismJvmSettingsContribution.tooltipKey(optimization.contributionId()),
                "the renderer finds the tooltip of " + optimization.contributionId());
    }

    @ParameterizedTest
    @MethodSource("persisted")
    void aFailedSaveReachesTheCallerInsteadOfPretendingSuccess(final Optimization optimization) {
        final OptimizationSettingsService refusing = new RecordingSettings(false) {
            @Override
            public void setEnabled(final String key, final boolean enabled) {
                throw new IllegalStateException("simulated storage failure");
            }
        };
        final SettingsControl.Toggle toggle = assertInstanceOf(
                SettingsControl.Toggle.class,
                OptimizationSettingsContribution.create(localization(), refusing, optimization)
                        .control());

        assertThrows(IllegalStateException.class, () -> toggle.binding().write(true));
        assertFalse(refusing.enabled(optimization.key()), "the preference stayed off");
    }

    @Test
    void thePageIsTheSixSectionNotesPlusEveryPlacedRow() {
        final List<SettingsContribution> contributions =
                OptimizationSettingsContribution.create(localization(), new RecordingSettings(false));

        assertEquals(
                OptimizationCatalog.SECTIONS.size()
                        + OptimizationCatalog.placed().size(),
                contributions.size());
        assertEquals(
                OptimizationCatalog.SECTIONS.stream()
                        .map(section -> "performance.section." + section)
                        .toList(),
                contributions.subList(0, OptimizationCatalog.SECTIONS.size()).stream()
                        .map(SettingsContribution::id)
                        .toList(),
                "the six section notes come first, in catalogue section order");
        assertEquals(
                OptimizationCatalog.placed().stream()
                        .map(Optimization::contributionId)
                        .toList(),
                contributions.subList(OptimizationCatalog.SECTIONS.size(), contributions.size()).stream()
                        .map(SettingsContribution::id)
                        .toList(),
                "the rows follow in settings-layout order");
    }

    @Test
    void sectionNotesOpenTheirSectionAtThePinnedIndex() {
        final Map<String, SettingsContribution> notes = new java.util.LinkedHashMap<>();
        for (final SettingsContribution contribution :
                OptimizationSettingsContribution.create(localization(), new RecordingSettings(false))) {
            if (contribution.control() instanceof SettingsControl.Note) notes.put(contribution.id(), contribution);
        }

        assertEquals(
                List.of(
                        "performance.section.heavy-model",
                        "performance.section.model",
                        "performance.section.rendering",
                        "performance.section.upload",
                        "performance.section.mesh",
                        "performance.section.jvm"),
                List.copyOf(notes.keySet()));
        assertEquals(
                List.of(60, 70, 80, 90, 100, 110),
                notes.values().stream().map(note -> note.index().orElseThrow()).toList(),
                "the section notes keep the indexes the reviewed layout pins");
        for (final SettingsContribution note : notes.values()) {
            assertEquals("performance", note.tab().id());
        }
    }

    @Test
    void everyRenderedRowIsPlacedBetweenItsOwnSectionAndTheNextOne() {
        final List<SettingsContribution> contributions =
                OptimizationSettingsContribution.create(localization(), new RecordingSettings(false));
        final Map<String, Integer> noteIndex = new LinkedHashMap<>();
        for (final SettingsContribution contribution : contributions) {
            if (contribution.control() instanceof SettingsControl.Note) {
                noteIndex.put(contribution.id(), contribution.index().orElseThrow());
            }
        }
        final List<Integer> noteIndexes = List.copyOf(noteIndex.values());
        for (final Optimization optimization : OptimizationCatalog.placed()) {
            final int section = OptimizationCatalog.SECTIONS.indexOf(
                    optimization.placement().orElseThrow().section());
            final int index = optimization.placement().orElseThrow().index();
            assertTrue(
                    index > noteIndexes.get(section),
                    optimization.contributionId() + " renders below the header of its own section");
            if (section + 1 < noteIndexes.size()) {
                assertTrue(
                        index < noteIndexes.get(section + 1),
                        optimization.contributionId() + " renders above the next section header");
            }
        }
    }

    @Test
    void unavailableServiceReportsEveryRowOffAndRefusesEveryWrite() {
        final OptimizationSettingsService settings = OptimizationSettingsService.unavailable();
        assertFalse(settings.isAvailable());
        for (final Optimization optimization : OptimizationCatalog.persisted()) {
            assertFalse(settings.enabled(optimization.key()), optimization.key() + " reads as off");
            assertThrows(
                    IllegalStateException.class,
                    () -> settings.setEnabled(optimization.key(), true),
                    optimization.key() + " refuses to persist");
        }
    }

    @Test
    void everyPersistedRowKeepsItsConfigKey() {
        for (final Optimization optimization : OptimizationCatalog.placed()) {
            assertEquals(
                    optimization.key(),
                    Field.forKey(optimization.key()).key(),
                    optimization.contributionId() + " keeps the config key its toggle persists");
        }
    }

    private static PluginLocalization localization() {
        return new PluginLocalization() {
            @Override
            public String text(final String key) {
                return key;
            }

            @Override
            public String format(final String key, final Object... arguments) {
                return key;
            }

            @Override
            public boolean contains(final String key) {
                return true;
            }

            @Override
            public Locale locale() {
                return Locale.ENGLISH;
            }
        };
    }
}
