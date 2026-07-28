package dev.turboism.sdk.cubism.model;

import dev.turboism.sdk.PreviewApi;
import dev.turboism.sdk.cubism.id.DeformerId;

import java.util.List;
import java.util.NoSuchElementException;

/** Warp Deformers in one Cubism model. */
@PreviewApi
public interface WarpDeformers {

    List<WarpDeformer> all();

    /** @throws NoSuchElementException when the ID is absent */
    WarpDeformer find(DeformerId id);
}
