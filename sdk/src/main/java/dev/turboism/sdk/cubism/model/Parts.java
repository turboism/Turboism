package dev.turboism.sdk.cubism.model;

import dev.turboism.sdk.PreviewApi;
import java.util.List;
import java.util.NoSuchElementException;

/** Parts in one Cubism model. */
@PreviewApi
public interface Parts {

    List<Part> all();

    /** @throws NoSuchElementException when the ID is absent */
    Part find(PartId id);
}
