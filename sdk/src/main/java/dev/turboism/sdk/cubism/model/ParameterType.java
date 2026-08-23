package dev.turboism.sdk.cubism.model;


/** Version-neutral semantic type of one Cubism parameter. */
public enum ParameterType {
    /** The active backend cannot safely determine the parameter type. */
    UNKNOWN,

    /** A regular Cubism parameter. */
    NORMAL,

    /**
     * A blend-shape parameter.
     *
     * <p>This corresponds to Editor {@code MORPH_TARGET} and Core
     * {@code BLEND_SHAPE}.</p>
     */
    BLEND_SHAPE
}
