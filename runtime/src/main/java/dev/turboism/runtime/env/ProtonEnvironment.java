package dev.turboism.runtime.env;

import java.util.List;
import java.util.Map;

/**
 * Environment-marker detection for a Windows JVM actually running under
 * Wine/Proton. The product ships Windows binaries only, so "Linux/Proton
 * default" means the Cubism JVM is a Wine process: Wine builds the Windows
 * process environment block from the Unix environment, so Wine- and
 * Proton-scoped variables propagate into {@code System.getenv()} — no native
 * interop needed.
 *
 * <p>Chosen over the alternatives deliberately: {@code HKCU\Software\Wine}
 * registry keys are unreachable without JNI/JNA, which this layer does not
 * carry, and an {@code ntdll.wine_get_version} export probe needs native
 * interop for the same reason. The managed launch path additionally exports
 * {@code TURBOISM_PROTON=1} so the product's only supported Proton entry
 * never depends on upstream variable naming.</p>
 */
public final class ProtonEnvironment {

    /** Deterministic marker exported by the managed Linux/Proton launch script. */
    public static final String MANAGED_MARKER = "TURBOISM_PROTON";

    private static final List<String> MARKERS = List.of(
        MANAGED_MARKER,
        // Wine propagates its own loader/prefix variables into the Windows env.
        "WINEPREFIX", "WINELOADER", "WINEDLLPATH", "WINEESYNC", "WINEFSYNC",
        // Proton/Steam runtime variables present for managed compatibility runs.
        "STEAM_COMPAT_DATA_PATH", "STEAM_COMPAT_CLIENT_INSTALL_PATH",
        "PROTON_LOG", "PROTON_LOG_DIR"
    );

    private ProtonEnvironment() { }

    /** Whether this JVM runs as a Wine/Proton process, from the real environment. */
    public static boolean underWineOrProton() {
        return underWineOrProton(System.getenv());
    }

    /**
     * Whether the given environment carries a Wine/Proton marker. Native
     * Windows environments never contain these names; a native-Linux JVM is
     * not a supported Cubism host and simply reports false unless it was
     * explicitly launched through a marker-bearing environment.
     */
    public static boolean underWineOrProton(final Map<String, String> environment) {
        for (final String marker : MARKERS) {
            final String value = environment.get(marker);
            if (value != null && !value.isEmpty()) return true;
        }
        return false;
    }
}
