package dev.turboism.runtime.env;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/**
 * Detection for a Windows JVM actually running under Wine/Proton. The product
 * ships Windows binaries only, so "Linux/Proton default" means the Cubism JVM
 * is a Wine process.
 *
 * <p>Prefer Wine filesystem facts: winecfg.exe in the Windows system32 or
 * the Linux proc self stat/maps files through Wine's Z: Unix-root mapping.
 * The exact managed-launch marker TURBOISM_PROTON=1 is an explicit fallback
 * when a prefix removes Z: or Wine utilities. Ambient WINE, STEAM_COMPAT and PROTON environment
 * variables are not detection signals.</p>
 *
 * <p>This is a conservative no-JNI heuristic, not tamper-proof attestation:
 * a deliberately copied winecfg or emulated proc tree could imitate it;
 * unusual unmanaged prefixes without these facts default off. start.exe is
 * not used as a signal on its own. Failed filesystem probes default off.</p>
 *
 * <p>Rejected alternatives: ambient {@code WINEPREFIX}/{@code STEAM_COMPAT_*}/
 * {@code PROTON_*} variables are user-settable on a native Windows box and so
 * cannot distinguish "running under Wine" from "inherits a Wine-shaped env"
 * (a real cross-check found false positives). {@code HKCU\Software\Wine}
 * registry keys and the {@code ntdll.wine_get_version} export are unreachable
 * without JNI/JNA, which this layer does not carry. {@code os.version} cannot
 * help: Wine deliberately reports a Windows version.</p>
 */
public final class ProtonEnvironment {

    /** Deterministic marker exported by the managed Linux/Proton launch script. */
    public static final String MANAGED_MARKER = "TURBOISM_PROTON";

    private ProtonEnvironment() {}

    /** Whether this JVM runs as a Wine/Proton process, probing the real host. */
    public static boolean underWineOrProton() {
        return underWineOrProton(System.getenv());
    }

    /**
     * Whether the given environment plus host filesystem facts indicate a
     * Wine/Proton process. A native-Linux JVM is not a supported Cubism host
     * and reports false unless explicitly launched under the managed marker.
     */
    public static boolean underWineOrProton(final Map<String, String> environment) {
        try {
            if (wineFilesystemFacts(systemRoot(environment), Path.of("Z:\\proc\\self"))) {
                return true;
            }
        } catch (java.nio.file.InvalidPathException | SecurityException unavailable) {
            // Environment path values cannot break runtime startup.
        }
        return "1".equals(environment.get(MANAGED_MARKER));
    }

    /** Wine utility or Linux proc facts through the Wine drive mapping. */
    static boolean wineFilesystemFacts(final Path systemRoot, final Path zProcSelf) {
        try {
            return Files.isRegularFile(systemRoot.resolve("system32").resolve("winecfg.exe"))
                    || (Files.isDirectory(zProcSelf)
                            && Files.isRegularFile(zProcSelf.resolve("stat"))
                            && Files.isRegularFile(zProcSelf.resolve("maps")));
        } catch (SecurityException unavailable) {
            return false;
        }
    }

    private static Path systemRoot(final Map<String, String> environment) {
        for (final String name : new String[] {"SystemRoot", "windir"}) {
            final String value = environment.get(name);
            if (value != null && !value.isBlank()) {
                return Path.of(value);
            }
        }
        return Path.of("C:\\windows");
    }
}
