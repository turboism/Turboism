package dev.turboism.sdk.ui.appearance;

import dev.turboism.sdk.PreviewApi;
import dev.turboism.sdk.cubism.id.DeformerId;
import dev.turboism.sdk.cubism.id.ParameterGroupId;
import dev.turboism.sdk.cubism.id.ParameterId;
import dev.turboism.sdk.cubism.model.PartId;

import java.util.Objects;

/** Closed set of native Cubism controls eligible for bounded styling. */
@PreviewApi
public sealed interface ControlAppearanceTarget {

    record ParameterLabel(ParameterId id) implements ControlAppearanceTarget {
        public ParameterLabel { Objects.requireNonNull(id, "id"); }
    }

    record ParameterFolder(ParameterGroupId id) implements ControlAppearanceTarget {
        public ParameterFolder { Objects.requireNonNull(id, "id"); }
    }

    record DeformerLabel(DeformerId id) implements ControlAppearanceTarget {
        public DeformerLabel { Objects.requireNonNull(id, "id"); }
    }

    record DeformerControlRow(DeformerId id) implements ControlAppearanceTarget {
        public DeformerControlRow { Objects.requireNonNull(id, "id"); }
    }


    record PartLabel(PartId id) implements ControlAppearanceTarget {
        public PartLabel { Objects.requireNonNull(id, "id"); }
    }

    record PartFolder(PartId id) implements ControlAppearanceTarget {
        public PartFolder { Objects.requireNonNull(id, "id"); }
    }
}
