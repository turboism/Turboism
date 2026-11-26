package dev.turboism.shell;

import dev.turboism.config.optimization.Optimization;
import dev.turboism.config.optimization.OptimizationCatalog;
import dev.turboism.config.optimization.OptimizationPreset;
import dev.turboism.internal.core.OptimizationSettingsService;
import dev.turboism.sdk.i18n.PluginLocalization;
import dev.turboism.sdk.ui.settings.SettingsBinding;
import dev.turboism.sdk.ui.settings.SettingsContribution;
import dev.turboism.sdk.ui.settings.SettingsControl;
import dev.turboism.sdk.ui.settings.SettingsLink;
import dev.turboism.sdk.ui.settings.SettingsTab;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;

/**
 * Core-owned toggles for the declared native optimizations, rendered in the shared Performance tab.
 *
 * <p>Rows derive from {@link OptimizationCatalog#uiRows()} so sections stay concise while every
 * underlying {@code config.json} key persists independently for launcher and override compatibility.</p>
 */
final class OptimizationSettingsContribution {

    static final String PRESET_CONTRIBUTION_ID = "performance-preset";
    static final String PRESET_TOOLTIP_KEY = "settings.performance.preset.tooltip";
    static final String GROUP_TOGGLE_TOOLTIP_KEY = "settings.performance.section.toggle-all.tooltip";
    private static final String PRESET_LABEL_KEY = "settings.performance.preset";
    private static final int PRESET_INDEX = 48;
    private static final String PERFORMANCE_TAB_ID = "performance";
    private static final String PERFORMANCE_TAB_LABEL_KEY = "settings.tab.performance";
    private static final int PERFORMANCE_TAB_INDEX = 200;
    private static final String REFERENCE_OPEN_FAILED_KEY = "settings.optimization.reference-open-failed";
    private static final String SECTION_LABEL_PREFIX = "settings.performance.section.";
    private static final String SECTION_CONTRIBUTION_PREFIX = "performance.section.";

    /** Section header rows in render order, each pinned to the index its note renders at. */
    private static final List<Section> SECTIONS = List.of(
            new Section(OptimizationCatalog.SECTION_EDITING, 50),
            new Section(OptimizationCatalog.SECTION_RENDERING, 70),
            new Section(OptimizationCatalog.SECTION_ASSETS, 90),
            new Section(OptimizationCatalog.SECTION_JVM, 110),
            new Section(OptimizationCatalog.SECTION_LEGACY, 120));

    private OptimizationSettingsContribution() {}

    /** Binding exposing individual optimization flags behind a single or bundled checkbox row. */
    interface OptimizationToggleBinding extends SettingsBinding<Boolean> {
        List<Optimization> optimizations();

        boolean readOptimization(Optimization optimization);

        void writeOptimization(Optimization optimization, boolean enabled);
    }

    /**
     * Creates the preset selector at the top of the Performance tab from the persisted flags.
     *
     * @param i18n the shell localization the preset label and option texts resolve through
     * @param settings the service that reads the persisted flags to derive the initial preset
     * @return the preset choice contribution
     */
    static SettingsContribution createPreset(
            final PluginLocalization i18n, final OptimizationSettingsService settings) {
        Objects.requireNonNull(i18n, "i18n");
        Objects.requireNonNull(settings, "settings");
        final List<SettingsControl.Option> options = Arrays.stream(OptimizationPreset.values())
                .map(preset -> new SettingsControl.Option(preset.configValue(), i18n.text(preset.labelKey())))
                .toList();
        return new SettingsContribution(
                PRESET_CONTRIBUTION_ID,
                settingsTab(i18n),
                OptionalInt.of(PRESET_INDEX),
                new SettingsControl.Choice(
                        PRESET_CONTRIBUTION_ID,
                        i18n.text(PRESET_LABEL_KEY),
                        options,
                        SettingsBinding.of(
                                () -> OptimizationPreset.detect(settings).configValue(), ignored -> {})));
    }

    /**
     * Every Performance-tab row that renders native optimizations: the five section notes and the
     * consolidated toggle rows of {@link OptimizationCatalog#uiRows()}.
     *
     * @param i18n the shell localization the labels and tooltips resolve through
     * @param settings the service that reads and persists one preference per catalogue key
     * @return the contributions in render order, ready to be registered on the shell
     */
    static List<SettingsContribution> create(
            final PluginLocalization i18n, final OptimizationSettingsService settings) {
        Objects.requireNonNull(i18n, "i18n");
        Objects.requireNonNull(settings, "settings");
        final List<SettingsContribution> contributions = new ArrayList<>();
        for (final Section section : SECTIONS) contributions.add(createSection(i18n, section));
        for (final OptimizationCatalog.UiRow row : OptimizationCatalog.uiRows()) {
            contributions.add(create(i18n, settings, row));
        }
        return List.copyOf(contributions);
    }

    /**
     * Creates one consolidated or single toggle contribution from a declared {@link OptimizationCatalog.UiRow}.
     *
     * @param i18n the shell localization the label resolves through
     * @param settings the service that owns the persisted preferences for {@code row}
     * @param row the UI row declaration to render
     * @return the toggle contribution for that row
     */
    static SettingsContribution create(
            final PluginLocalization i18n,
            final OptimizationSettingsService settings,
            final OptimizationCatalog.UiRow row) {
        Objects.requireNonNull(i18n, "i18n");
        Objects.requireNonNull(settings, "settings");
        Objects.requireNonNull(row, "row");
        final String id = row.contributionId();
        return new SettingsContribution(
                id,
                settingsTab(i18n),
                OptionalInt.of(row.index()),
                new SettingsControl.Toggle(id, i18n.text(row.labelKey()), new RowBinding(settings, row.members())));
    }

    /**
     * One preference toggle, placed where its catalogue row declares it.
     *
     * @param i18n the shell localization the label resolves through
     * @param settings the service that owns the persisted preference for {@code optimization}
     * @param optimization the catalogue row to render
     * @return the toggle for that row
     */
    static SettingsContribution create(
            final PluginLocalization i18n,
            final OptimizationSettingsService settings,
            final Optimization optimization) {
        Objects.requireNonNull(i18n, "i18n");
        Objects.requireNonNull(settings, "settings");
        final Optimization.Placement placement = optimization
                .placement()
                .orElseThrow(
                        () -> new IllegalArgumentException(optimization.contributionId() + " renders no settings row"));
        final String id = optimization.contributionId();
        return new SettingsContribution(
                id,
                settingsTab(i18n),
                OptionalInt.of(placement.index()),
                new SettingsControl.Toggle(
                        id, i18n.text(optimization.labelKey()), new RowBinding(settings, List.of(optimization))));
    }

    /**
     * Resolves the optional external HTTPS reference link declared for {@code contributionId}.
     *
     * @param i18n the shell localization the link label and failure message resolve through
     * @param contributionId the settings contribution id of the toggle row
     * @return localized {@link SettingsLink} when the row or optimization declares a reference link
     */
    static Optional<SettingsLink> referenceLink(final PluginLocalization i18n, final String contributionId) {
        Objects.requireNonNull(i18n, "i18n");
        Objects.requireNonNull(contributionId, "contributionId");
        final Optional<Optimization.ReferenceLink> rowLink = OptimizationCatalog.uiRowByContributionId(contributionId)
                .flatMap(OptimizationCatalog.UiRow::referenceLink);
        final Optional<Optimization.ReferenceLink> resolved = rowLink.isPresent()
                ? rowLink
                : OptimizationCatalog.byContributionId(contributionId).flatMap(Optimization::referenceLink);
        return resolved.map(ref -> new SettingsLink(
                i18n.text(ref.labelKey()), ref.uri(), i18n.format(REFERENCE_OPEN_FAILED_KEY, ref.uri())));
    }

    /**
     * Section header transported as a note; the settings renderer turns it into a collapsible box.
     *
     * @param i18n the shell localization the section title resolves through
     * @param section the section to render
     * @return the section note
     */
    private static SettingsContribution createSection(final PluginLocalization i18n, final Section section) {
        final String contributionId = section.contributionId();
        return new SettingsContribution(
                contributionId,
                settingsTab(i18n),
                OptionalInt.of(section.index()),
                new SettingsControl.Note(contributionId, i18n.text(SECTION_LABEL_PREFIX + section.id())));
    }

    private static SettingsTab settingsTab(final PluginLocalization i18n) {
        return new SettingsTab(
                PERFORMANCE_TAB_ID, i18n.text(PERFORMANCE_TAB_LABEL_KEY), OptionalInt.of(PERFORMANCE_TAB_INDEX));
    }

    private static final class RowBinding implements OptimizationToggleBinding {
        private final OptimizationSettingsService settings;
        private final List<Optimization> optimizations;

        private RowBinding(final OptimizationSettingsService settings, final List<Optimization> optimizations) {
            this.settings = Objects.requireNonNull(settings, "settings");
            this.optimizations = List.copyOf(Objects.requireNonNull(optimizations, "optimizations"));
        }

        @Override
        public Boolean read() {
            for (final Optimization optimization : optimizations) {
                if (!settings.enabled(optimization.key())) {
                    return false;
                }
            }
            return true;
        }

        @Override
        public void write(final Boolean value) {
            final boolean target = Boolean.TRUE.equals(value);
            for (final Optimization optimization : optimizations) {
                settings.setEnabled(optimization.key(), target);
            }
        }

        @Override
        public List<Optimization> optimizations() {
            return optimizations;
        }

        @Override
        public boolean readOptimization(final Optimization optimization) {
            return settings.enabled(optimization.key());
        }

        @Override
        public void writeOptimization(final Optimization optimization, final boolean enabled) {
            settings.setEnabled(optimization.key(), enabled);
        }
    }

    /** One section header: the catalogue section it opens and the index its note renders at. */
    private record Section(String id, int index) {
        private Section {
            Objects.requireNonNull(id, "id");
            if (!OptimizationCatalog.SECTIONS.contains(id)) {
                throw new IllegalArgumentException("Unknown performance section: " + id);
            }
        }

        /** Contribution id the renderer keys the collapsible box and its tooltip by. */
        private String contributionId() {
            return SECTION_CONTRIBUTION_PREFIX + id;
        }
    }
}
