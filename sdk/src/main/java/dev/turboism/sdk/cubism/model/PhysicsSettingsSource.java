package dev.turboism.sdk.cubism.model;


/** Read-only projection of one physics settings source document. */
public interface PhysicsSettingsSource {

    String id();

    String name();

    float totalAngle();

    int inputCount();

    int outputCount();

    int vertexCount();
}
