package dev.turboism.sdk.cubism.model;

import dev.turboism.sdk.PreviewApi;
import dev.turboism.sdk.cubism.id.ArtMeshId;
import dev.turboism.sdk.cubism.id.ParameterId;

import java.util.List;

/** One Cubism Glue relation. */
@PreviewApi
public interface Glue {

    GlueId id();

    default int index() { throw unavailable("Glue index"); }

    int drawableA();

    int drawableB();

    IntSequence parameters();

    default ArtMeshId drawableAId() { throw unavailable("Glue drawable A"); }

    default ArtMeshId drawableBId() { throw unavailable("Glue drawable B"); }

    default List<ParameterId> parameterIds() { throw unavailable("Glue parameters"); }

    private static UnsupportedOperationException unavailable(final String feature) {
        return new UnsupportedOperationException(feature + " is unavailable.");
    }
}
