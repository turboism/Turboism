package dev.turboism.sdk.cubism.model;

import dev.turboism.sdk.PreviewApi;

/** Read-only projection of one physics settings source document. */
@PreviewApi
public interface PhysicsSettingsSource {

    String id();

    String name();

    float totalAngle();

    int inputCount();

    int outputCount();

    int vertexCount();
}
