package dev.turboism.sdk.cubism.core;


/** Permission-checked MOC byte inspection service. */
public interface MocInspector {

    MocVersion latestVersion();

    MocInfo inspect(MocData data);
}
