package dev.turboism.adapter.cubism.lifecycle;

import dev.turboism.sdk.cubism.model.Part;
import dev.turboism.sdk.cubism.model.PartId;

import java.util.Objects;

/** Immutable, host-detached Part projection safe to retain from an event callback. */
final class DetachedPart implements Part {
    private final PartId id;
    private final String name;
    private final float opacity;
    private final int parentIndex;

    private DetachedPart(
        final PartId id,
        final String name,
        final float opacity,
        final int parentIndex
    ) {
        this.id = id;
        this.name = name;
        this.opacity = opacity;
        this.parentIndex = parentIndex;
    }

    static DetachedPart capture(
        final Part part,
        final String name,
        final float opacity
    ) {
        final Part source = Objects.requireNonNull(part, "part");
        return new DetachedPart(
            source.id(),
            Objects.requireNonNull(name, "name"),
            opacity,
            source.parentIndex()
        );
    }

    @Override public PartId id() { return id; }
    @Override public String name() { return name; }
    @Override public float getOpacity() { return opacity; }
    @Override public int parentIndex() { return parentIndex; }
    @Override public void setName(final String name) { throw detached(); }
    @Override public void setOpacity(final float opacity) { throw detached(); }

    private static UnsupportedOperationException detached() {
        return new UnsupportedOperationException("Event Part snapshots are read-only and host-detached.");
    }
}
