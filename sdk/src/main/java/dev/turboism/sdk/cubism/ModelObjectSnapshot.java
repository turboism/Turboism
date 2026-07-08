package dev.turboism.sdk.cubism;

public sealed interface ModelObjectSnapshot permits ParameterSnapshot, ArtMeshSnapshot, DeformerSnapshot {
    String id();

    String name();
}
