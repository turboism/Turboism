package dev.turboism.ui.appearance.control;

import dev.turboism.adapter.cubism.NativeLabelColorAuthoring;

import java.util.Objects;
import java.util.function.LongSupplier;

/** Narrow host-owned composition seam for model appearance projections. */
public final class RuntimeModelAppearanceComposition {

    private final PaletteAppearanceCoordinator coordinator;
    private final LongSupplier modelGeneration;
    private final LongSupplier hostGeneration;
    private final LongSupplier providerGeneration;
    private final NativeLabelColorAuthoring nativeLabelColorAuthoring;
    private final boolean available;

    public RuntimeModelAppearanceComposition(
        final PaletteAppearanceCoordinator coordinator,
        final LongSupplier modelGeneration,
        final LongSupplier hostGeneration,
        final LongSupplier providerGeneration,
        final NativeLabelColorAuthoring nativeLabelColorAuthoring
    ) {
        this(
            coordinator,
            modelGeneration,
            hostGeneration,
            providerGeneration,
            nativeLabelColorAuthoring,
            true
        );
    }

    private RuntimeModelAppearanceComposition(
        final PaletteAppearanceCoordinator coordinator,
        final LongSupplier modelGeneration,
        final LongSupplier hostGeneration,
        final LongSupplier providerGeneration,
        final NativeLabelColorAuthoring nativeLabelColorAuthoring,
        final boolean available
    ) {
        this.coordinator = Objects.requireNonNull(coordinator, "coordinator");
        this.modelGeneration = Objects.requireNonNull(modelGeneration, "modelGeneration");
        this.hostGeneration = Objects.requireNonNull(hostGeneration, "hostGeneration");
        this.providerGeneration = Objects.requireNonNull(providerGeneration, "providerGeneration");
        this.nativeLabelColorAuthoring = Objects.requireNonNull(
            nativeLabelColorAuthoring, "nativeLabelColorAuthoring"
        );
        this.available = available;
    }

    /**
     * @return a fully inert composition — a private coordinator nothing else shares, every generation
     *     reporting {@code 0} (a value no override can match), unavailable native colour authoring,
     *     and {@link #available()} {@code false}. Used where appearance support is absent, so callers
     *     get a working object rather than {@code null}.
     */
    public static RuntimeModelAppearanceComposition unavailable() {
        return new RuntimeModelAppearanceComposition(
            new PaletteAppearanceCoordinator(),
            () -> 0L,
            () -> 0L,
            () -> 0L,
            NativeLabelColorAuthoring.unavailable(),
            false
        );
    }

    /**
     * @return the coordinator that owns the transient palette overrides; on an unavailable
     *     composition this is a private instance no host UI is wired to
     */
    public PaletteAppearanceCoordinator coordinator() {
        return coordinator;
    }

    /**
     * @return the model's current incarnation counter, read live from the host on every call;
     *     {@code 0} on an unavailable composition
     */
    public long modelGeneration() {
        return modelGeneration.getAsLong();
    }

    /**
     * @return the host UI's current incarnation counter, read live on every call; {@code 0} on an
     *     unavailable composition
     */
    public long hostGeneration() {
        return hostGeneration.getAsLong();
    }

    /**
     * @return the appearance provider's current incarnation counter, read live on every call;
     *     {@code 0} on an unavailable composition
     */
    public long providerGeneration() {
        return providerGeneration.getAsLong();
    }

    /**
     * @return the seam for writing label colours into the native host UI; on an unavailable
     *     composition this is the no-op authoring that accepts calls and changes nothing
     */
    public NativeLabelColorAuthoring nativeLabelColorAuthoring() {
        return nativeLabelColorAuthoring;
    }

    /**
     * @return whether this composition is wired to a real host UI; {@code false} for the inert
     *     composition from {@link #unavailable()}, whose accessors all return safe placeholders
     */
    public boolean available() {
        return available;
    }
}
