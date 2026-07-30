package dev.turboism.sdk.cubism.model;

import dev.turboism.sdk.PreviewApi;
import dev.turboism.sdk.cubism.id.DeformerId;

import java.util.List;
import java.util.NoSuchElementException;

/** Rotation Deformers in one Cubism model. */
@PreviewApi
public interface RotationDeformers {

    List<RotationDeformer> all();

    /** @throws NoSuchElementException when the ID is absent */
    RotationDeformer find(DeformerId id);
}
