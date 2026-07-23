package dev.turboism.adapter.host;

import dev.turboism.sdk.cubism.model.CubismModel;
import dev.turboism.sdk.cubism.model.CubismModelAccess;

/** Fail-closed model access used when no verified host connection owns a Core model backend. */
final class UnavailableCubismModelAccess implements CubismModelAccess {

    static final UnavailableCubismModelAccess INSTANCE = new UnavailableCubismModelAccess();

    private UnavailableCubismModelAccess() {
    }

    @Override
    public CubismModel active() {
        throw new IllegalStateException("No verified active Cubism Core model is available.");
    }
}
