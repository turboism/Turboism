package dev.turboism.sdk.cubism.psd;

/**
 * Opaque save-version token issued for a runtime-owned PSD edit file.
 *
 * <p>The runtime must validate token identity, owning plugin, file and model session against its
 * registry. Implementing this interface or copying a token reference does not grant authority.
 * No path, file contents or native host object is exposed. A token represents a stable staged
 * input, not the continuously changing external editor file.</p>
 */
public interface PsdFileRevision {
}
