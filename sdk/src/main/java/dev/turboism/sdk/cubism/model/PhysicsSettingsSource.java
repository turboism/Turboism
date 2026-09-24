package dev.turboism.sdk.cubism.model;


/** Read-only projection of one physics settings source document. */
public interface PhysicsSettingsSource {

    /** Returns the source document's identifier text. */
    String id();

    /** Returns the source document's display name. */
    String name();

    /** Returns the source's total pendulum angle, in degrees. */
    float totalAngle();

    /** Returns the number of input parameter bindings on this source. */
    int inputCount();

    /** Returns the number of output parameter bindings on this source. */
    int outputCount();

    /** Returns the number of pendulum vertices on this source. */
    int vertexCount();
}
