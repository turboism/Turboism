package dev.turboism.sdk.cubism.model;

import dev.turboism.sdk.PreviewApi;

import java.util.List;

/** Read-only physics settings document projection of the active model. */
@PreviewApi
public interface PhysicsSettings {

    float gravityX();

    float gravityY();

    float windX();

    float windY();

    /** Returns the settings FPS, or {@code null} when the host does not pin one. */
    Integer settingFps();

    /** Returns every physics settings source in stable model order. */
    List<PhysicsSettingsSource> sources();
}
