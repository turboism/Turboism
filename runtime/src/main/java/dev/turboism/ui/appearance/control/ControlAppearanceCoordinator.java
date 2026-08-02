package dev.turboism.ui.appearance.control;

import dev.turboism.sdk.ui.appearance.ControlAppearanceContribution;
import dev.turboism.sdk.ui.appearance.ControlAppearanceStyle;
import dev.turboism.sdk.ui.appearance.ControlAppearanceTarget;

import java.awt.Component;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/** Runtime-owned snapshot of transient styles and live parameter-row labels. */
public final class ControlAppearanceCoordinator implements AutoCloseable {
    private final Map<Key, StoredContribution> contributions = new ConcurrentHashMap<>();
    private volatile long hostGeneration;
    private final java.util.concurrent.CopyOnWriteArrayList<Runnable> listeners =
        new java.util.concurrent.CopyOnWriteArrayList<>();
    private final List<StoredParameterControlBinding> parameterControls = new ArrayList<>();

    void put(final String pluginId, final long generation, final ControlAppearanceContribution contribution) {
        contributions.put(new Key(pluginId, generation, contribution.id()), new StoredContribution(contribution));
        changed();
    }

    public synchronized void replaceHostGeneration(final long generation) {
        if (generation < 0) {
            throw new IllegalArgumentException("generation must not be negative");
        }
        if (hostGeneration != generation) {
            parameterControls.clear();
        }
        hostGeneration = generation;
        changed();
    }

    public synchronized void clearHostGeneration() {
        hostGeneration = 0;
        parameterControls.clear();
        changed();
    }

    public long hostGeneration() {
        return hostGeneration;
    }

    void remove(
        final String pluginId,
        final long generation,
        final String id,
        final ControlAppearanceContribution expected
    ) {
        contributions.computeIfPresent(new Key(pluginId, generation, id), (key, stored) ->
            stored.contribution() == expected ? null : stored
        );
        changed();
    }

    public void removePlugin(final String pluginId, final long generation) {
        contributions.keySet().removeIf(key -> key.pluginId().equals(pluginId)
            && key.generation() == generation);
        changed();
    }

    synchronized void bindParameterControl(
        final boolean folder,
        final String id,
        final Component label
    ) {
        final String value = java.util.Objects.requireNonNull(id, "id");
        final Component component = java.util.Objects.requireNonNull(label, "label");
        parameterControls.removeIf(binding -> binding.label().get() == null
            || binding.label().get() == component);
        parameterControls.add(new StoredParameterControlBinding(folder, value, new WeakReference<>(component)));
    }

    synchronized void unbindParameterControl(final Component label) {
        parameterControls.removeIf(binding -> binding.label().get() == null
            || binding.label().get() == label);
    }

    /** Exact native parameter/folder IDs paired with their live Swing labels. */
    public synchronized List<ParameterControlBinding> parameterControlBindings() {
        final List<ParameterControlBinding> live = new ArrayList<>(parameterControls.size());
        final java.util.Iterator<StoredParameterControlBinding> iterator = parameterControls.iterator();
        while (iterator.hasNext()) {
            final StoredParameterControlBinding binding = iterator.next();
            final Component label = binding.label().get();
            if (label == null) {
                iterator.remove();
            } else {
                live.add(new ParameterControlBinding(binding.folder(), binding.id(), label));
            }
        }
        return List.copyOf(live);
    }

    @Override
    public void close() {
        clearHostGeneration();
        contributions.clear();
        changed();
        listeners.clear();
    }

    public Optional<ControlAppearanceStyle> parameterLabel(final String parameterId) {
        return style(ControlAppearanceTarget.ParameterLabel.class, parameterId);
    }

    public Optional<ControlAppearanceStyle> parameterFolder(final String groupId) {
        return style(ControlAppearanceTarget.ParameterFolder.class, groupId);
    }

    public Optional<ControlAppearanceStyle> deformerLabel(final String deformerId) {
        return style(ControlAppearanceTarget.DeformerLabel.class, deformerId);
    }

    public Optional<ControlAppearanceStyle> deformerControlRow(final String deformerId) {
        return style(ControlAppearanceTarget.DeformerControlRow.class, deformerId);
    }

    public Optional<ControlAppearanceStyle> partLabel(final String partId) {
        return style(ControlAppearanceTarget.PartLabel.class, partId);
    }

    public Optional<ControlAppearanceStyle> partFolder(final String partId) {
        return style(ControlAppearanceTarget.PartFolder.class, partId);
    }


    public AutoCloseable onChange(final Runnable listener) {
        final Runnable value = java.util.Objects.requireNonNull(listener, "listener");
        listeners.add(value);
        return () -> listeners.remove(value);
    }

    private void changed() {
        listeners.forEach(Runnable::run);
    }

    private Optional<ControlAppearanceStyle> style(
        final Class<? extends ControlAppearanceTarget> targetType,
        final String targetId
    ) {
        return contributions.entrySet().stream()
            .sorted(java.util.Comparator.comparing(
                (Map.Entry<Key, StoredContribution> entry) -> entry.getKey().pluginId()
            )
                .thenComparingLong(entry -> entry.getKey().generation())
                .thenComparing(entry -> entry.getKey().id()))
            .map(Map.Entry::getValue)
            .map(StoredContribution::contribution)
            .filter(value -> targetType.isInstance(value.target()) && targetId(value.target()).equals(targetId))
            .map(ControlAppearanceContribution::style)
            .findFirst();
    }

    private static String targetId(final ControlAppearanceTarget target) {
        if (target instanceof ControlAppearanceTarget.ParameterLabel value) return value.id().value();
        if (target instanceof ControlAppearanceTarget.ParameterFolder value) return value.id().value();
        if (target instanceof ControlAppearanceTarget.DeformerLabel value) return value.id().value();
        if (target instanceof ControlAppearanceTarget.DeformerControlRow value) return value.id().value();
        if (target instanceof ControlAppearanceTarget.PartLabel value) return value.id().value();
        if (target instanceof ControlAppearanceTarget.PartFolder value) return value.id().value();
        throw new IllegalArgumentException("unsupported control appearance target: " + target.getClass().getName());
    }

    private record Key(String pluginId, long generation, String id) {
    }

    private record StoredContribution(ControlAppearanceContribution contribution) {
    }

    public record ParameterControlBinding(boolean folder, String id, Component label) {
        public ParameterControlBinding {
            id = java.util.Objects.requireNonNull(id, "id");
            label = java.util.Objects.requireNonNull(label, "label");
        }
    }

    private record StoredParameterControlBinding(
        boolean folder,
        String id,
        WeakReference<Component> label
    ) {
    }
}
