package dev.turboism.sdk.cubism.model;

import dev.turboism.sdk.PreviewApi;
import java.util.List;
import java.util.NoSuchElementException;

/** Glue relations in one Cubism model. */
@PreviewApi
public interface Glues {

    List<Glue> all();

    /** @throws NoSuchElementException when the ID is absent */
    Glue find(GlueId id);
}
