package dev.turboism.sdk.cubism.model;

import dev.turboism.sdk.PreviewApi;

import java.util.List;

/** Read-only evaluated auto-Yure state of the active model. */
@PreviewApi
public interface AutoYure {

    /** Returns every evaluated auto-Yure binding in stable model order. */
    List<AutoYureBinding> bindings();
}
