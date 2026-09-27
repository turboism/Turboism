package dev.turboism.sdk.cubism.core;


/** Permission-checked metadata for the admitted Cubism Core runtime. */
public interface CoreRuntimeInfo {

    /** Returns the admitted Cubism Core runtime version. */
    CoreVersion version();

    /** Returns the feature set reported by the admitted Core runtime. */
    CoreCapabilities capabilities();

    /** Returns the permission-checked MOC byte inspection service. */
    MocInspector mocInspector();

    /**
     * Returns the owned-Moc loader (plugin-owned Core models built from MOC bytes).
     *
     * <p>Fail-closed: without an admitted host Core runtime this default is unavailable.
     * The runtime backend overrides it with the verified loader.</p>
     */
    default MocLoader mocLoader() {
        throw new UnsupportedOperationException("Owned MOC loading is unavailable.");
    }
}
