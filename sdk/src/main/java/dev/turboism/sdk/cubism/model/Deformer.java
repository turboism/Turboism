package dev.turboism.sdk.cubism.model;

import dev.turboism.sdk.PreviewApi;
import dev.turboism.sdk.cubism.id.DeformerId;

/** One Cubism deformer. */
@PreviewApi
public interface Deformer {

    DeformerId id();

    int parentDeformerIndex();

    IntSequence parameters();
}
