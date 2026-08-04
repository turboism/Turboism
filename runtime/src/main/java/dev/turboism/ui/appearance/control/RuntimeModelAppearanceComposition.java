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

    public PaletteAppearanceCoordinator coordinator() {
        return coordinator;
    }

    public long modelGeneration() {
        return modelGeneration.getAsLong();
    }

    public long hostGeneration() {
        return hostGeneration.getAsLong();
    }

    public long providerGeneration() {
        return providerGeneration.getAsLong();
    }

    public NativeLabelColorAuthoring nativeLabelColorAuthoring() {
        return nativeLabelColorAuthoring;
    }

    public boolean available() {
        return available;
    }
}
