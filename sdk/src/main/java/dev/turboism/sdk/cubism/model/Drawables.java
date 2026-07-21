package dev.turboism.sdk.cubism.model;

import dev.turboism.sdk.PreviewApi;
import dev.turboism.sdk.cubism.id.ArtMeshId;
import java.util.List;
import java.util.NoSuchElementException;

/** Drawables in one Cubism model. */
@PreviewApi
public interface Drawables {

    List<Drawable> all();

    /** @throws NoSuchElementException when the ID is absent */
    Drawable find(ArtMeshId id);
}
