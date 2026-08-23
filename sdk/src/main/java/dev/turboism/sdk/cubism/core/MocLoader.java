package dev.turboism.sdk.cubism.core;


/**
 * Permission-checked loader for plugin-owned Cubism Core models built from MOC bytes.
 *
 * <p>This is the read-only projection of the owned-Moc workflow: the plugin supplies
 * {@code .moc3} bytes, and the runtime builds a Core model through the verified Core
 * public API (both 5.2.03 and 5.3.02). All evaluated reads are immutable adapter-owned
 * copies; no Core write member ({@code setValue}/{@code setOpacity}) is exposed.</p>
 *
 * <p>Fail-closed: without an admitted host Core runtime the loader is unavailable and
 * {@link #load} throws {@link UnsupportedOperationException}.</p>
 */
public interface MocLoader {

    /**
     * Loads MOC bytes into an owned model source.
     *
     * <p>The bytes are copied defensively and diagnosed for version and consistency
     * before any Core instance is created.</p>
     *
     * @param data non-empty MOC bytes
     * @return owned MOC handle; {@code close()} releases the Core instance
     * @throws UnsupportedOperationException when no verified Core runtime is admitted
     * @throws IllegalArgumentException when the bytes exceed the runtime byte quota
     */
    OwnedMoc load(MocData data);
}
