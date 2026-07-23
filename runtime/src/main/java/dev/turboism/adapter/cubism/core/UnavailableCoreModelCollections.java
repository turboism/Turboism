package dev.turboism.adapter.cubism.core;

import dev.turboism.sdk.cubism.id.ArtMeshId;
import dev.turboism.sdk.cubism.id.DeformerId;
import dev.turboism.sdk.cubism.model.Deformer;
import dev.turboism.sdk.cubism.model.Deformers;
import dev.turboism.sdk.cubism.model.Drawable;
import dev.turboism.sdk.cubism.model.Drawables;
import dev.turboism.sdk.cubism.model.Glue;
import dev.turboism.sdk.cubism.model.GlueId;
import dev.turboism.sdk.cubism.model.Glues;
import dev.turboism.sdk.cubism.model.Part;
import dev.turboism.sdk.cubism.model.PartId;
import dev.turboism.sdk.cubism.model.Parts;

import java.util.List;

/** Core families whose structural projections are not installed in the current provider slice. */
final class UnavailableCoreModelCollections {

    private static final String MESSAGE =
        "This Cubism Core model family is unavailable in the installed Runtime projection.";

    static final Parts PARTS = new Parts() {
        @Override
        public List<Part> all() {
            throw unavailable();
        }

        @Override
        public Part find(final PartId id) {
            throw unavailable();
        }
    };

    static final Drawables DRAWABLES = new Drawables() {
        @Override
        public List<Drawable> all() {
            throw unavailable();
        }

        @Override
        public Drawable find(final ArtMeshId id) {
            throw unavailable();
        }
    };

    static final Deformers DEFORMERS = new Deformers() {
        @Override
        public List<Deformer> all() {
            throw unavailable();
        }

        @Override
        public Deformer find(final DeformerId id) {
            throw unavailable();
        }
    };

    static final Glues GLUES = new Glues() {
        @Override
        public List<Glue> all() {
            throw unavailable();
        }

        @Override
        public Glue find(final GlueId id) {
            throw unavailable();
        }
    };

    private UnavailableCoreModelCollections() {
    }

    private static UnsupportedOperationException unavailable() {
        return new UnsupportedOperationException(MESSAGE);
    }
}
