package dev.turboism.shell;

import dev.turboism.config.optimization.Optimization;
import dev.turboism.config.optimization.OptimizationCatalog;
import dev.turboism.config.optimization.OptimizationPreset;
import dev.turboism.sdk.ui.settings.SettingsControl;
import dev.turboism.ui.panel.CollapsibleSection;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;

/**
 * Coordinates the Performance tab preset selector, bundled or individual optimization checkboxes,
 * and collapsible section group checkboxes while enforcing {@link OptimizationCatalog} exclusions.
 */
final class PerformanceToggleCoordinator {
    private static final String GROUP_TOGGLE_SUFFIX = ".toggle";

    private JComboBox<SettingsControl.Option> presetCombo;
    private String[] presetAccepted;
    private boolean[] presetChanging;
    private final Map<String, MemberTarget> optimizationTargets = new LinkedHashMap<>();
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
            final List<Optimization> optimizations,
            final JCheckBox checkbox,
            final SettingsControl.Toggle control,
            final Map<Optimization, Boolean> initialMembers,
            final boolean[] accepted,
            final boolean[] changing,
            final List<ToggleEntry> sectionToggles) {
        final ToggleEntry entry =
                new ToggleEntry(controlId, optimizations, checkbox, control, initialMembers, accepted, changing);
        allToggles.add(entry);
        for (final Optimization optimization : optimizations) {
            optimizationTargets.put(optimization.key(), new MemberTarget(entry, optimization));
        }
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
            for (final ToggleEntry entry : allToggles) {
                if (entry.optimizations().isEmpty()) {
                    continue;
                }
                for (final Optimization optimization : entry.optimizations()) {
                    final boolean target = preset.targetFlag(optimization).orElseThrow();
                    entry.setMemberState(optimization, target);
                }
                entry.syncCheckBox();
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
            final MemberTarget target = optimizationTargets.get(key);
            return target != null && target.entry().memberState(target.optimization());
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

        final boolean allOn = toggleable.stream().allMatch(ToggleEntry::allAccepted);
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
        final List<ToggleEntry> candidates = new ArrayList<>();
        for (final ToggleEntry entry : toggleable) {
            if (!entry.allAccepted()) {
                candidates.add(entry);
            }
        }
        // Evaluate default-ON entries before opt-in toggles so same-group exclusions keep defaults.
        candidates.sort(Comparator.comparing(PerformanceToggleCoordinator::isDefaultEnabled)
                .reversed());

        final List<ToggleEntry> compatible = new ArrayList<>();
        for (final ToggleEntry candidate : candidates) {
            if (hasAnyConflict(candidate, active)) {
                continue;
            }
            if (!candidate
                    .control()
                    .validator()
                    .validate(candidate.allAccepted(), true)
                    .accepted()) {
                continue;
            }
            active.addAll(candidate.optimizations());
            compatible.add(candidate);
        }
        return compatible;
    }

    private static boolean hasAnyConflict(final ToggleEntry candidate, final Set<Optimization> active) {
        for (final Optimization optimization : candidate.optimizations()) {
            if (!candidate.memberState(optimization) && conflictsWithAny(optimization, active)) {
                return true;
            }
        }
        return false;
    }

    private Set<Optimization> activeOptimizations() {
        final Set<Optimization> active = new LinkedHashSet<>();
        for (final ToggleEntry entry : allToggles) {
            for (final Optimization optimization : entry.optimizations()) {
                if (entry.memberState(optimization)) {
                    active.add(optimization);
                }
            }
        }
        return active;
    }

    private static boolean setAll(final List<ToggleEntry> entries, final boolean target) {
        boolean optimizationChanged = false;
        for (final ToggleEntry entry : entries) {
            if (entry.setSelected(target) && !entry.optimizations().isEmpty()) {
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
        final boolean allSelected = !inspected.isEmpty() && inspected.stream().allMatch(ToggleEntry::allAccepted);
        final boolean anySelected = inspected.stream().anyMatch(ToggleEntry::anyAccepted);
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
        if (entry.optimizations().isEmpty()) {
            return true;
        }
        for (final Optimization optimization : entry.optimizations()) {
            if (!OptimizationCatalog.isDefaultEnabled(optimization)) {
                return false;
            }
        }
        return true;
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

    private record MemberTarget(ToggleEntry entry, Optimization optimization) {}

    static final class ToggleEntry {
        private final String id;
        private final List<Optimization> optimizations;
        private final JCheckBox checkbox;
        private final SettingsControl.Toggle control;
        private final Map<Optimization, Boolean> memberStates;
        private final boolean[] accepted;
        private final boolean[] changing;

        private ToggleEntry(
                final String id,
                final List<Optimization> optimizations,
                final JCheckBox checkbox,
                final SettingsControl.Toggle control,
                final Map<Optimization, Boolean> initialMembers,
                final boolean[] accepted,
                final boolean[] changing) {
            this.id = id;
            this.optimizations = List.copyOf(optimizations);
            this.checkbox = checkbox;
            this.control = control;
            this.memberStates = new LinkedHashMap<>(initialMembers);
            this.accepted = accepted;
            this.changing = changing;
        }

        String id() {
            return id;
        }

        List<Optimization> optimizations() {
            return optimizations;
        }

        JCheckBox checkbox() {
            return checkbox;
        }

        SettingsControl.Toggle control() {
            return control;
        }

        boolean memberState(final Optimization optimization) {
            return Boolean.TRUE.equals(memberStates.get(optimization));
        }

        boolean allAccepted() {
            if (optimizations.isEmpty()) {
                return accepted[0];
            }
            for (final Optimization optimization : optimizations) {
                if (!memberState(optimization)) {
                    return false;
                }
            }
            return true;
        }

        boolean anyAccepted() {
            if (optimizations.isEmpty()) {
                return accepted[0];
            }
            for (final Optimization optimization : optimizations) {
                if (memberState(optimization)) {
                    return true;
                }
            }
            return false;
        }

        void setMemberState(final Optimization optimization, final boolean target) {
            memberStates.put(optimization, target);
            accepted[0] = allAccepted();
        }

        void applyUniformState(final boolean target) {
            accepted[0] = target;
            for (final Optimization optimization : optimizations) {
                memberStates.put(optimization, target);
            }
            syncCheckBox();
        }

        boolean setSelected(final boolean target) {
            if (!checkbox.isEnabled()) {
                return false;
            }
            final boolean alreadyAtTarget = target ? allAccepted() : !anyAccepted();
            if (alreadyAtTarget) {
                syncCheckBox();
                return false;
            }
            if (!control.validator().validate(allAccepted(), target).accepted()) {
                return false;
            }
            applyUniformState(target);
            return true;
        }

        void syncCheckBox() {
            final boolean all = allAccepted();
            final boolean any = anyAccepted();
            changing[0] = true;
            try {
                if (optimizations.size() > 1) {
                    CollapsibleSection.updateGroupCheckBoxState(checkbox, all, any);
                } else {
                    checkbox.setSelected(all);
                }
            } finally {
                changing[0] = false;
            }
        }
    }
}
