package dev.turboism.sdk.cubism.core;


/** Permission-checked metadata for the admitted Cubism Core runtime. */
public interface CoreRuntimeInfo {

    CoreVersion version();

    CoreCapabilities capabilities();

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
