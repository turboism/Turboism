package dev.turboism.sdk.cubism.core;


/** Permission-checked MOC byte inspection service. */
public interface MocInspector {

    /** Returns the newest MOC format version the runtime understands. */
    MocVersion latestVersion();

    /**
     * Inspects one MOC payload.
     *
     * @param data the MOC bytes to inspect
     * @return the parsed MOC metadata
     */
    MocInfo inspect(MocData data);
}
