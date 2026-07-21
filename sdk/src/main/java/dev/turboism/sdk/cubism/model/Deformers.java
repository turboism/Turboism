package dev.turboism.sdk.cubism.model;

import dev.turboism.sdk.PreviewApi;
import dev.turboism.sdk.cubism.id.DeformerId;
import java.util.List;
import java.util.NoSuchElementException;

/** Deformers in one Cubism model. */
@PreviewApi
public interface Deformers {

    List<Deformer> all();

    /** @throws NoSuchElementException when the ID is absent */
    Deformer find(DeformerId id);
}
