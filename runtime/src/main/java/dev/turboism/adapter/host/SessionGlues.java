package dev.turboism.adapter.host;

import dev.turboism.sdk.cubism.id.ArtMeshId;
import dev.turboism.sdk.cubism.model.Glue;
import dev.turboism.sdk.cubism.model.GlueId;
import dev.turboism.sdk.cubism.model.Glues;
import java.util.List;
import java.util.Objects;

final class SessionGlues implements Glues {
    private final DynamicCubismModelAccess host;
    private final long generation;
    private final Glues delegate;

    SessionGlues(final DynamicCubismModelAccess host, final long generation, final Glues delegate) {
        this.host = Objects.requireNonNull(host, "host");
        this.generation = generation;
        this.delegate = Objects.requireNonNull(delegate, "delegate");
    }

    @Override
    public List<Glue> all() {
        return host.guarded(
                generation,
                () -> delegate.all().stream()
                        .map(value -> (Glue) new SessionGlue(host, generation, value))
                        .toList());
    }

    @Override
    public Glue find(final GlueId id) {
        return host.guarded(generation, () -> new SessionGlue(host, generation, delegate.find(id)));
    }

    @Override
    public java.util.Optional<String> providerVersion() {
        return host.guarded(generation, delegate::providerVersion);
    }
}

final class SessionGlue implements Glue {
    private final DynamicCubismModelAccess host;
    private final long generation;
    private final Glue delegate;

    SessionGlue(final DynamicCubismModelAccess host, final long generation, final Glue delegate) {
        this.host = Objects.requireNonNull(host, "host");
        this.generation = generation;
        this.delegate = Objects.requireNonNull(delegate, "delegate");
    }

    @Override
    public GlueId id() {
        return host.guarded(generation, delegate::id);
    }

    @Override
    public int index() {
        return host.guarded(generation, delegate::index);
    }

    @Override
    public ArtMeshId drawableAId() {
        return host.guarded(generation, delegate::drawableAId);
    }

    @Override
    public ArtMeshId drawableBId() {
        return host.guarded(generation, delegate::drawableBId);
    }

    @Override
    public List<dev.turboism.sdk.cubism.id.ParameterId> parameterIds() {
        return host.guarded(generation, delegate::parameterIds);
    }

    @Override
    public int drawableA() {
        return host.guarded(generation, delegate::drawableA);
    }

    @Override
    public int drawableB() {
        return host.guarded(generation, delegate::drawableB);
    }

    @Override
    public dev.turboism.sdk.cubism.model.IntSequence parameters() {
        return new SessionIntSequence(host, generation, host.guarded(generation, delegate::parameters));
    }

    @Override
    public String name() {
        return host.guarded(generation, delegate::name);
    }

    @Override
    public float intensity() {
        return host.guarded(generation, delegate::intensity);
    }

    @Override
    public void setName(final String name) {
        host.guardedVoid(generation, () -> delegate.setName(name));
    }

    @Override
    public void setId(final GlueId id) {
        host.guardedVoid(generation, () -> delegate.setId(id));
    }

    @Override
    public void setIntensity(final float intensity) {
        host.guardedVoid(generation, () -> delegate.setIntensity(intensity));
    }

    @Override
    public void setDrawableA(final ArtMeshId id) {
        host.guardedVoid(generation, () -> delegate.setDrawableA(id));
    }

    @Override
    public void setDrawableB(final ArtMeshId id) {
        host.guardedVoid(generation, () -> delegate.setDrawableB(id));
    }
}
