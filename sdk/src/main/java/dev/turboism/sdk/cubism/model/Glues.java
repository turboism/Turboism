package dev.turboism.sdk.cubism.model;

import java.util.List;
import java.util.NoSuchElementException;

/** Glue relations in one Cubism model. */
public interface Glues {

    List<Glue> all();

    /** @throws NoSuchElementException when the ID is absent */
    Glue find(GlueId id);
}
