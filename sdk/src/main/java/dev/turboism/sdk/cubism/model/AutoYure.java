package dev.turboism.sdk.cubism.model;


import java.util.List;

/** Read-only evaluated auto-Yure state of the active model. */
public interface AutoYure {

    /** Returns every evaluated auto-Yure binding in stable model order. */
    List<AutoYureBinding> bindings();
}
