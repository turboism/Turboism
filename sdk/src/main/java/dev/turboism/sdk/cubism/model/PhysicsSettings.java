package dev.turboism.sdk.cubism.model;


import java.util.List;

/** Read-only physics settings document projection of the active model. */
public interface PhysicsSettings {

    /** Returns the horizontal gravity component of the physics settings. */
    float gravityX();

    /** Returns the vertical gravity component of the physics settings. */
    float gravityY();

    /** Returns the horizontal wind component of the physics settings. */
    float windX();

    /** Returns the vertical wind component of the physics settings. */
    float windY();

    /** Returns the settings FPS, or {@code null} when the host does not pin one. */
    Integer settingFps();

    /** Returns every physics settings source in stable model order. */
    List<PhysicsSettingsSource> sources();
}
