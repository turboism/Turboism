package dev.turboism.validation.hostlocale;

import dev.turboism.sdk.plugin.PluginContext;
import dev.turboism.sdk.plugin.TurboismPlugin;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Task-local exerciser for the {@code hostLocale()} SDK API on an exact Cubism
 * host. It only uses the public SDK ({@code uiHost().hostLocale()}) plus plain
 * JDK APIs; it never imports or reflects {@code com.live2d.*} types.
 *
     * <p>The assertion is the contract of the API itself: the returned Locale must
     * equal the effective UI language — the host JVM {@code user.language}/
     * {@code user.country} (with DISPLAY fallback when the language is blank),
     * normalized for zh scripts (zh-CN/zh-SG → zh-Hans, zh-TW/zh-HK/zh-MO →
     * zh-Hant, other script-less zh such as Wine-rewritten zh-US → zh-Hans).</p>
 */
public final class HostLocaleHostValidationPlugin implements TurboismPlugin {

    private static final String RESULT_FILE = "host-locale-result.properties";
    private static final long READY_TIMEOUT_MILLIS = 180_000L;
    private static final long SETTLE_STEP_MILLIS = 2_000L;
    private static final long PASS_SETTLE_MILLIS = 3_000L;

    private PluginContext context;
    private Path stateDir;

    @Override
    public void init(final PluginContext context) {
        this.context = context;
        this.stateDir = context.paths().stateDir();
        final Thread exerciser = new Thread(this::runWhenHostReady, "host-locale-exerciser");
        exerciser.setDaemon(true);
        exerciser.start();
    }

    @Override
    public void enable() {
        context.logger().info("HOST_LOCALE_PROBE_ENABLED");
    }

    @Override
    public void disable() {
        context.logger().info("HOST_LOCALE_PROBE_DISABLED");
    }

    @Override
    public void shutdown() {
        context.logger().info("HOST_LOCALE_PROBE_SHUTDOWN");
    }

    private void runWhenHostReady() {
        if (!awaitHostReady()) {
            writeResult("readiness-timeout", "FAIL", "MATCHED-READY-RUNNING", "missing", List.of(
                "preview-runtime-report did not reach identityState=MATCHED adapterState=READY runtimeState=RUNNING"
            ));
            context.logger().warn("HOST_LOCALE_MATRIX_RESULT status=FAIL phase=readiness");
            Runtime.getRuntime().halt(2);
            return;
        }
        context.logger().info("HOST_LOCALE_PROBE_READY hostState=ACTIVE");
        runAssertion();
    }

    /** READY is emitted only from a non-stopped HostSession.State.ACTIVE snapshot. */
    private boolean awaitHostReady() {
        final long deadline = System.currentTimeMillis() + READY_TIMEOUT_MILLIS;
        final Path report = stateDir.getParent().resolve("runtime/preview-runtime-report.json");
        while (System.currentTimeMillis() < deadline) {
            try {
                final String json = Files.readString(report);
                if (json.contains("\"identityState\":\"MATCHED\"")
                    && json.contains("\"adapterState\":\"READY\"")
                    && json.contains("\"runtimeState\":\"RUNNING\"")) {
                    return true;
                }
            } catch (java.io.IOException unavailable) {
                // report not yet written; keep polling
            }
            try {
                Thread.sleep(SETTLE_STEP_MILLIS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return false;
    }

    private void runAssertion() {
        final List<String> failures = new ArrayList<>();
        final Locale actual;
        try {
            actual = context.uiHost().hostLocale();
        } catch (RuntimeException failure) {
            writeResult("hostLocale-call", "FAIL", "non-throwing", failure.getClass().getSimpleName(), List.of(
                "hostLocale threw " + failure.getClass().getSimpleName()
            ));
            context.logger().warn("HOST_LOCALE_MATRIX_RESULT status=FAIL phase=api-call");
            Runtime.getRuntime().halt(2);
            return;
        }

        // Expected value follows the documented contract: the effective UI
        // language — host JVM user.language/user.country with DISPLAY fallback
        // when the language is blank — then the same zh script normalization the
        // runtime applies. The probe must not import runtime classes, so the
        // algorithm is inlined: zh with blank script → CN/SG → Hans, TW/HK/MO →
        // Hant, everything else (incl. blank/US) → Hans; non-zh unchanged.
        final String language = System.getProperty("user.language", "");
        final String country = System.getProperty("user.country", "");
        final Locale raw = language.isBlank()
            ? Locale.getDefault(Locale.Category.DISPLAY)
            : new Locale(language, country);
        final Locale expected;
        if ("zh".equals(raw.getLanguage()) && raw.getScript().isBlank()) {
            final String script = switch (raw.getCountry()) {
                case "CN", "SG" -> "Hans";
                case "TW", "HK", "MO" -> "Hant";
                default -> "Hans";
            };
            expected = new Locale.Builder()
                .setLanguage("zh")
                .setScript(script)
                .build();
        } else {
            expected = raw;
        }

        final String actualTag = actual == null ? "null" : actual.toLanguageTag();
        if (actual == null) {
            failures.add("hostLocale returned null");
        } else if (!expected.equals(actual)) {
            failures.add("locale mismatch expected=" + expected.toLanguageTag() + " actual=" + actualTag);
        }
        final String status = failures.isEmpty() ? "PASS" : "FAIL";
        if (!writeResult(
            "hostLocale-equality",
            status,
            expected.toLanguageTag(),
            actualTag,
            failures
        )) {
            failures.add("result file could not be written");
        }
        context.logger().info("HOST_LOCALE_MATRIX_RESULT status=" + status
            + " expected=" + expected.toLanguageTag()
            + " actual=" + actualTag
            + " userLanguage=" + System.getProperty("user.language", "<unset>")
            + " display=" + Locale.getDefault(Locale.Category.DISPLAY).toLanguageTag());
        try {
            Thread.sleep(PASS_SETTLE_MILLIS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
        Runtime.getRuntime().exit(failures.isEmpty() ? 0 : 2);
    }

    private boolean writeResult(
        final String assertion,
        final String status,
        final String expected,
        final String actual,
        final List<String> failures
    ) {
        final StringBuilder result = new StringBuilder()
            .append("schemaVersion=1\n")
            .append("runId=").append(System.getProperty("turboism.validation.runId", "unspecified")).append('\n')
            .append("assertion=").append(assertion).append('\n')
            .append("expected=").append(expected).append('\n')
            .append("actual=").append(actual).append('\n')
            .append("status=").append(status).append('\n')
            .append("failures=").append(failures.size()).append('\n');
        for (int index = 0; index < failures.size(); index++) {
            result.append("failure.").append(index).append('=')
                .append(failures.get(index).replace('\n', ' ')).append('\n');
        }
        result.append("terminal=").append("FAIL".equals(status) ? "FAIL" : "PASS").append('\n');
        try {
            Files.writeString(stateDir.resolve(RESULT_FILE), result);
            return true;
        } catch (java.io.IOException failure) {
            return false;
        }
    }
}
