package dev.turboism.sdk.cubism.model;

import dev.turboism.sdk.cubism.id.DeformerId;

import java.util.List;
import java.util.NoSuchElementException;

/** Rotation Deformers in one Cubism model. */
public interface RotationDeformers {

    /** Returns every Rotation Deformer in the model, in host order. */
    List<RotationDeformer> all();

    /** @throws NoSuchElementException when the ID is absent */
    RotationDeformer find(DeformerId id);
}
