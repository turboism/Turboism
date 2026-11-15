package dev.turboism.shell;

import dev.turboism.config.optimization.Optimization;
import dev.turboism.config.optimization.OptimizationCatalog;
import dev.turboism.config.optimization.OptimizationPreset;
import dev.turboism.internal.core.OptimizationSettingsService;
import dev.turboism.sdk.i18n.PluginLocalization;
import dev.turboism.sdk.ui.settings.SettingsBinding;
import dev.turboism.sdk.ui.settings.SettingsContribution;
import dev.turboism.sdk.ui.settings.SettingsControl;
import dev.turboism.sdk.ui.settings.SettingsTab;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.OptionalInt;

/**
 * Core-owned toggles for the declared native optimizations, rendered in the shared Performance tab.
 *
 * <p>The rows are derived from {@link OptimizationCatalog#placed()} instead of being listed here,
 * so the page order, the row ids, the label and tooltip keys and the persisted preference all come
 * from the one declaration of each optimization. Unchanged-frame reuse and incremental updates
 * require explicit opt-in: every row starts from the persisted value and stays off until the user
 * ticks it, and a running hook keeps the startup configuration so a dependent optimization cannot
 * enter a partially switched state.</p>
 */
final class OptimizationSettingsContribution {

    static final String PRESET_CONTRIBUTION_ID = "performance-preset";
    static final String PRESET_TOOLTIP_KEY = "settings.performance.preset.tooltip";
    private static final String PRESET_LABEL_KEY = "settings.performance.preset";
    private static final int PRESET_INDEX = 59;
    private static final String PERFORMANCE_TAB_ID = "performance";
    private static final String PERFORMANCE_TAB_LABEL_KEY = "settings.tab.performance";
    private static final int PERFORMANCE_TAB_INDEX = 200;

    /** Section header rows in render order, each pinned to the index its note renders at. */
    private static final List<Section> SECTIONS = List.of(
            new Section("heavy-model", 60),
            new Section("model", 71),
            new Section("rendering", 80),
            new Section("upload", 90),
            new Section("mesh", 100),
            new Section("jvm", 110));

    private OptimizationSettingsContribution() {}

    /**
     * Creates the preset selector at the top of the Performance tab from the 26 persisted flags.
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
     * Every Performance-tab row that renders a native optimization: the six section notes and the
     * toggles of {@link OptimizationCatalog#placed()}.
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
        for (final Optimization optimization : OptimizationCatalog.placed()) {
            contributions.add(create(i18n, settings, optimization));
        }
        return List.copyOf(contributions);
    }

    /**
     * One preference toggle, placed where its catalogue row declares it.
     *
     * <p>The toggle persists through {@link OptimizationSettingsService#setEnabled(String, boolean)}
     * when the dialog is confirmed, so a storage failure propagates instead of being reported as a
     * successful change.</p>
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
        final String key = optimization.key();
        return new SettingsContribution(
                id,
                settingsTab(i18n),
                OptionalInt.of(placement.index()),
                new SettingsControl.Toggle(
                        id,
                        i18n.text(optimization.labelKey()),
                        SettingsBinding.of(() -> settings.enabled(key), value -> settings.setEnabled(key, value))));
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
                new SettingsControl.Note(contributionId, i18n.text("settings.performance.section." + section.id())));
    }

    private static SettingsTab settingsTab(final PluginLocalization i18n) {
        return new SettingsTab(
                PERFORMANCE_TAB_ID, i18n.text(PERFORMANCE_TAB_LABEL_KEY), OptionalInt.of(PERFORMANCE_TAB_INDEX));
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
            return "performance.section." + id;
        }
    }
}
