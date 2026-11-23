package dev.turboism.shell;

import dev.turboism.config.PerformanceSettingsConfig;
import dev.turboism.config.optimization.Optimization;
import dev.turboism.config.optimization.OptimizationCatalog;
import dev.turboism.config.optimization.OptimizationPreset;
import dev.turboism.sdk.ui.settings.SettingsControl;
import dev.turboism.ui.panel.CollapsibleSection;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;

/**
 * Coordinates the Performance tab preset selector, individual optimization checkboxes, and
 * collapsible section group checkboxes while enforcing {@link OptimizationCatalog} exclusions.
 */
final class PerformanceToggleCoordinator {
    private static final String GROUP_TOGGLE_SUFFIX = ".toggle";

    private JComboBox<SettingsControl.Option> presetCombo;
    private String[] presetAccepted;
    private boolean[] presetChanging;
    private final Map<String, ToggleEntry> optimizationToggles = new LinkedHashMap<>();
    private final List<ToggleEntry> allToggles = new ArrayList<>();
    private final List<GroupBinding> groups = new ArrayList<>();
    private boolean applyingBatch;

    void registerPreset(
            final JComboBox<SettingsControl.Option> combo, final String[] accepted, final boolean[] changing) {
        this.presetCombo = combo;
        this.presetAccepted = accepted;
        this.presetChanging = changing;
    }

    ToggleEntry registerToggle(
            final String controlId,
            final Optional<Optimization> optimization,
            final JCheckBox checkbox,
            final SettingsControl.Toggle control,
            final boolean[] accepted,
            final boolean[] changing,
            final List<ToggleEntry> sectionToggles) {
        final ToggleEntry entry = new ToggleEntry(controlId, optimization, checkbox, control, accepted, changing);
        allToggles.add(entry);
        optimization.ifPresent(value -> optimizationToggles.put(value.key(), entry));
        if (sectionToggles != null) {
            sectionToggles.add(entry);
        }
        return entry;
    }

    JCheckBox createGroupCheckBox(
            final String sectionId, final String title, final String tooltip, final List<ToggleEntry> sectionToggles) {
        Objects.requireNonNull(sectionId, "sectionId");
        Objects.requireNonNull(title, "title");
        if (sectionToggles == null || sectionToggles.isEmpty()) {
            return null;
        }
        final JCheckBox headerCheckBox = CollapsibleSection.createGroupCheckBox();
        headerCheckBox.setName(sectionId + GROUP_TOGGLE_SUFFIX);
        headerCheckBox.getAccessibleContext().setAccessibleName(title);
        if (tooltip != null && !tooltip.isBlank()) {
            headerCheckBox.setToolTipText(tooltip);
        }
        final GroupBinding group = new GroupBinding(headerCheckBox, List.copyOf(sectionToggles));
        groups.add(group);
        headerCheckBox.addActionListener(ignored -> onGroupHeaderClicked(group));
        syncGroupState(group);
        return headerCheckBox;
    }

    void onPresetSelected(final String configValue) {
        if (presetChanging != null && presetChanging[0]) {
            return;
        }
        final OptimizationPreset preset = OptimizationPreset.fromConfigValue(configValue);
        if (!preset.appliesFlags()) {
            return;
        }
        applyingBatch = true;
        try {
            for (final Map.Entry<String, ToggleEntry> entry : optimizationToggles.entrySet()) {
                final Optimization optimization = OptimizationCatalog.requireByKey(entry.getKey());
                final boolean target = preset.targetFlag(optimization).orElseThrow();
                entry.getValue().setSelected(target);
            }
        } finally {
            applyingBatch = false;
        }
        syncGroupStates();
    }

    void onToggleChanged(final boolean isOptimization) {
        if (applyingBatch) {
            return;
        }
        if (isOptimization) {
            syncPresetSelection();
        }
        syncGroupStates();
    }

    void syncGroupStates() {
        for (final GroupBinding group : groups) {
            syncGroupState(group);
        }
    }

    private void syncPresetSelection() {
        if (presetCombo == null) {
            return;
        }
        final OptimizationPreset detected = OptimizationPreset.detect(key -> {
            final ToggleEntry entry = optimizationToggles.get(key);
            return entry != null && entry.accepted()[0];
        });
        final String nextValue = detected.configValue();
        presetAccepted[0] = nextValue;
        presetChanging[0] = true;
        try {
            selectOption(presetCombo, nextValue);
        } finally {
            presetChanging[0] = false;
        }
    }

    private void onGroupHeaderClicked(final GroupBinding group) {
        if (applyingBatch) {
            return;
        }
        final List<ToggleEntry> toggleable = group.members().stream()
                .filter(entry -> entry.checkbox().isEnabled())
                .toList();
        if (toggleable.isEmpty()) {
            syncGroupState(group);
            return;
        }

        final boolean allOn = toggleable.stream().allMatch(entry -> entry.accepted()[0]);
        boolean optimizationChanged = false;
        applyingBatch = true;
        try {
            if (allOn) {
                optimizationChanged = setAll(toggleable, false);
            } else {
                final List<ToggleEntry> compatible = compatibleCandidatesToEnable(toggleable);
                if (!compatible.isEmpty()) {
                    optimizationChanged = setAll(compatible, true);
                } else {
                    // All conflict-free toggles are already active, so a click turns the group off.
                    optimizationChanged = setAll(toggleable, false);
                }
            }
        } finally {
            applyingBatch = false;
        }
        if (optimizationChanged) {
            syncPresetSelection();
        }
        syncGroupStates();
    }

    private List<ToggleEntry> compatibleCandidatesToEnable(final List<ToggleEntry> toggleable) {
        final Set<Optimization> active = activeOptimizations();
        final List<ToggleEntry> unchecked = new ArrayList<>();
        for (final ToggleEntry entry : toggleable) {
            if (!entry.accepted()[0]) {
                unchecked.add(entry);
            }
        }
        // Evaluate default-ON entries before opt-in toggles so same-group exclusions keep defaults.
        unchecked.sort(Comparator.comparing(PerformanceToggleCoordinator::isDefaultEnabled)
                .reversed());

        final List<ToggleEntry> candidates = new ArrayList<>();
        for (final ToggleEntry candidate : unchecked) {
            if (candidate.optimization().isPresent()) {
                final Optimization opt = candidate.optimization().get();
                if (conflictsWithAny(opt, active)) {
                    continue;
                }
                if (!candidate.control().validator().validate(false, true).accepted()) {
                    continue;
                }
                active.add(opt);
                candidates.add(candidate);
            } else if (candidate.control().validator().validate(false, true).accepted()) {
                candidates.add(candidate);
            }
        }
        return candidates;
    }

    private Set<Optimization> activeOptimizations() {
        final Set<Optimization> active = new LinkedHashSet<>();
        for (final ToggleEntry entry : allToggles) {
            if (entry.accepted()[0] && entry.optimization().isPresent()) {
                active.add(entry.optimization().get());
            }
        }
        return active;
    }

    private static boolean setAll(final List<ToggleEntry> entries, final boolean target) {
        boolean optimizationChanged = false;
        for (final ToggleEntry entry : entries) {
            if (entry.setSelected(target) && entry.optimization().isPresent()) {
                optimizationChanged = true;
            }
        }
        return optimizationChanged;
    }

    private static void syncGroupState(final GroupBinding group) {
        final List<ToggleEntry> enabledMembers = group.members().stream()
                .filter(entry -> entry.checkbox().isEnabled())
                .toList();
        group.headerCheckBox().setEnabled(!enabledMembers.isEmpty());
        final List<ToggleEntry> inspected = enabledMembers.isEmpty() ? group.members() : enabledMembers;
        int selectedCount = 0;
        for (final ToggleEntry entry : inspected) {
            if (entry.accepted()[0]) {
                selectedCount++;
            }
        }
        final boolean allSelected = !inspected.isEmpty() && selectedCount == inspected.size();
        final boolean anySelected = selectedCount > 0;
        CollapsibleSection.updateGroupCheckBoxState(group.headerCheckBox(), allSelected, anySelected);
    }

    private static boolean conflictsWithAny(final Optimization candidate, final Set<Optimization> active) {
        for (final Optimization existing : active) {
            if (conflicts(candidate, existing)) {
                return true;
            }
        }
        return false;
    }

    static boolean conflicts(final Optimization first, final Optimization second) {
        if (first.equals(second)) {
            return false;
        }
        return declaresConflict(first, second) || declaresConflict(second, first);
    }

    private static boolean declaresConflict(final Optimization source, final Optimization target) {
        for (final Optimization.Relation relation : source.relations()) {
            if (relation.kind() == Optimization.Relation.Kind.CONFLICTS
                    && relation.target().equals(target)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isDefaultEnabled(final ToggleEntry entry) {
        if (entry.optimization().isEmpty()) {
            return true;
        }
        final String key = entry.optimization().get().key();
        return Arrays.stream(PerformanceSettingsConfig.Field.values())
                .filter(field -> field.key().equals(key))
                .findFirst()
                .map(field -> Boolean.parseBoolean(field.defaultValue(false)))
                .orElse(false);
    }

    private static void selectOption(final JComboBox<SettingsControl.Option> combo, final String value) {
        for (int index = 0; index < combo.getItemCount(); index++) {
            if (combo.getItemAt(index).value().equals(value)) {
                combo.setSelectedIndex(index);
                return;
            }
        }
        throw new IllegalArgumentException("unknown settings choice: " + value);
    }

    private record GroupBinding(JCheckBox headerCheckBox, List<ToggleEntry> members) {}

    record ToggleEntry(
            String id,
            Optional<Optimization> optimization,
            JCheckBox checkbox,
            SettingsControl.Toggle control,
            boolean[] accepted,
            boolean[] changing) {
        boolean setSelected(final boolean target) {
            if (!checkbox.isEnabled()) {
                return false;
            }
            if (accepted[0] == target) {
                if (checkbox.isSelected() != target) {
                    applyCheckboxSelection(target);
                }
                return false;
            }
            if (!control.validator().validate(accepted[0], target).accepted()) {
                return false;
            }
            accepted[0] = target;
            applyCheckboxSelection(target);
            return true;
        }

        private void applyCheckboxSelection(final boolean target) {
            if (checkbox.isSelected() == target) {
                return;
            }
            changing[0] = true;
            try {
                checkbox.setSelected(target);
            } finally {
                changing[0] = false;
            }
        }
    }
}
