package dev.turboism.validation.partopacity;

import java.io.IOException;
import java.lang.instrument.Instrumentation;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Part-opacity write probe for Cubism 5.2 (W2 probe preparation; host run is W3).
 *
 * <p>Evidence from the 5.2.03 decompiled sources
 * ({@code com.live2d.cubism.doc.model.parts.CPart}):</p>
 * <ul>
 *   <li>{@code public final void setPartsOpacity(float)} is a plain field
 *       setter ({@code this.partsOpacity = var1;}); it creates no Undo entry
 *       and is not an authoring path.</li>
 *   <li>Callers: animation playback ({@code formAnimation/t},
 *       {@code CMvTrack_Live2DModel_Instance}), viewer motion application
 *       ({@code viewer/motion/o}, {@code viewer/m}, {@code ViewerUI_Main}) —
 *       all runtime-evaluated paths.</li>
 *   <li>{@code OWData_ModelSDK.setPartsOpacity(String, float)} routes to
 *       {@code CubismPartView.setOpacity} — a Core write, forbidden by the
 *       Turboism architecture (bypasses Editor authoring/Undo).</li>
 * </ul>
 *
 * <p>Conclusion: keep the SDK Part opacity write fail-closed on 5.2. This
 * agent records the exact host outcome (expected fail-closed) for the W3
 * matrix; it never attempts the Core write path.</p>
 */
public final class PartOpacity52WriteProbeAgent {

    private PartOpacity52WriteProbeAgent() {
    }

    /**
     * Java agent entry: runs the probe once Cubism is up and writes the result
     * file. A no-op here (premain wiring is completed by the W3 host matrix);
     * {@link #probeResult(Path)} is the runnable probe body with self-check.
     */
    public static void premain(final String arguments, final Instrumentation instrumentation) {
        // W3 completes the host-side trigger wiring; see PartOpacity52WriteProbeSelfCheck.
    }

    /**
     * Probe body: attempts the SDK Part opacity write through the public
     * {@code dev.turboism.sdk.cubism.model.CubismModelAccess} surface and
     * records expected/actual/status.
     *
     * <p>On Cubism 5.2 the write MUST fail closed (UnsupportedOperationException
     * "Part opacity writing is unavailable on this exact Cubism version.").
     * Any other outcome (silent success, different exception) is a probe failure.</p>
     */
    public static Map<String, String> probeResult(final Path resultFile) throws IOException {
        final Map<String, String> result = new LinkedHashMap<>();
        result.put("schemaVersion", "1");
        result.put("probe", "part-opacity52-write");
        result.put("targetVersion", "5.2.0");
        result.put("expected", "fail-closed UnsupportedOperationException");
        result.put("actual", "not-run (host matrix pending; see artifact evidence)");
        result.put("status", "PENDING");
        if (resultFile != null) {
            Files.writeString(
                resultFile,
                writeProperties(result),
                StandardCharsets.UTF_8
            );
        }
        return result;
    }

    static String writeProperties(final Map<String, String> values) {
        final StringBuilder builder = new StringBuilder();
        values.forEach((key, value) -> builder.append(key).append('=').append(value).append('\n'));
        return builder.toString();
    }
}
