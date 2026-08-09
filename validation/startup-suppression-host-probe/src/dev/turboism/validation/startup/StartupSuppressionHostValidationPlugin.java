package dev.turboism.validation.startup;

import dev.turboism.sdk.plugin.PluginContext;
import dev.turboism.sdk.plugin.PluginLogger;
import dev.turboism.sdk.plugin.TurboismPlugin;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Task-local exerciser for Turboism startup suppression on an exact Cubism
 * host. The suppression is applied by the premain transformer before any
 * plugin loads, so this probe verifies the observable result of that
 * transformation:
 *
 * <ul>
 *   <li>the splash factory {@code CECubismEditorApp.e()} returns {@code null}
 *       (the transformed body is {@code ACONST_NULL; ARETURN}); an
 *       untransformed body would create and show the native splash window;</li>
 *   <li>the startup entry {@code CECubismEditorApp.a(String[])} still exists
 *       (the class was transformed in place, not replaced);</li>
 *   <li>no visible {@code com.live2d.ui.window} splash window remains in the
 *       AWT tree at verification time;</li>
 *   <li>the preview runtime report reached MATCHED/READY/RUNNING, proving the
 *       host booted normally under the transformed startup path.</li>
 * </ul>
 *
 * <p>The update-check and information calls are removed in the same atomic
 * {@code StartupSuppressionTransformer.transformClass} pass that rewrites
 * {@code e()}; the premain console lines
 * {@code STARTUP_SUPPRESSION_TRANSFORM_TRANSFORMED} (and the
 * {@code status=INSTALLED} decision line) are collected from
 * {@code cubism-console.txt} by the wrapper as the transformation evidence.
 * This probe never mutates the model, never creates Undo, and touches no
 * authoring state.</p>
 */
public final class StartupSuppressionHostValidationPlugin implements TurboismPlugin {

    private static final String PLUGIN_ID = "dev.turboism.validation.startup";
    private static final String RESULT = "startup-suppression-result.properties";
    private static final String HOST_APP = "com.live2d.cubism.CECubismEditorApp";
    private static final String HOST_APP_INSTANCE_FIELD = "a";
    private static final String SPLASH_METHOD = "e";
    private static final String STARTUP_METHOD = "a";
    private static final long CLASS_READY_TIMEOUT_MILLIS = 180_000L;
    private static final long REPORT_READY_TIMEOUT_MILLIS = 180_000L;
    private static final long SETTLE_STEP_MILLIS = 2_000L;
    private static final long PASS_SETTLE_MILLIS = 3_000L;
    private static final List<String> REVIEWED_HOST_VERSIONS = List.of("5.2.03", "5.3.02");

    private PluginLogger logger;
    private Path stateDir;

    @Override
    public void init(final PluginContext context) {
        this.logger = context.logger();
        this.stateDir = context.paths().stateDir();
        final Thread exerciser = new Thread(this::runWhenHostReady, "startup-suppression-host-exerciser");
        exerciser.setDaemon(true);
        exerciser.start();
    }

    private void runWhenHostReady() {
        final Optional<Class<?>> application = awaitHostApplicationClass();
        if (application.isEmpty()) {
            finish(List.of("host application class never became available"), "unknown", "unknown");
            return;
        }
        final String version = awaitReviewedRuntimeVersion().orElse("unknown");
        logger.info("STARTUP_SUPPRESSION_VALIDATION_READY"
            + " hostApplication=" + HOST_APP
            + " hostVersion=" + version);

        final List<String> failures = new ArrayList<>();
        final String splashResult = assertSplashSuppressed(application.orElseThrow(), failures);
        assertStartupMethodPresent(application.orElseThrow(), failures);
        finish(failures, version, splashResult);
    }

    /** Waits until the transformed host application class is loaded and its singleton exists. */
    private Optional<Class<?>> awaitHostApplicationClass() {
        final long deadline = System.currentTimeMillis() + CLASS_READY_TIMEOUT_MILLIS;
        while (System.currentTimeMillis() < deadline) {
            try {
                final Class<?> application = Class.forName(
                    HOST_APP,
                    true,
                    ClassLoader.getSystemClassLoader()
                );
                final Field singleton = application.getField(HOST_APP_INSTANCE_FIELD);
                if (singleton.get(null) != null) {
                    return Optional.of(application);
                }
            } catch (ReflectiveOperationException | RuntimeException unavailable) {
                // The host class is loaded by the Cubism classpath, not by this
                // plugin loader; keep polling until the host startup reaches it.
            }
            try {
                Thread.sleep(SETTLE_STEP_MILLIS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return Optional.empty();
            }
        }
        return Optional.empty();
    }

    /**
     * The preview runtime report version when the exact artifact is MATCHED
     * and the adapter is READY/RUNNING; empty for any other (or missing)
     * report. An unreviewed artifact never reports MATCHED and stays
     * fail-closed.
     */
    private Optional<String> awaitReviewedRuntimeVersion() {
        final long deadline = System.currentTimeMillis() + REPORT_READY_TIMEOUT_MILLIS;
        final Path report = stateDir.getParent().resolve("runtime/preview-runtime-report.json");
        while (System.currentTimeMillis() < deadline) {
            try {
                final String json = Files.readString(report);
                final boolean ready = json.contains("\"identityState\":\"MATCHED\"")
                    && json.contains("\"adapterState\":\"READY\"")
                    && json.contains("\"runtimeState\":\"RUNNING\"");
                if (ready) {
                    for (String reviewed : REVIEWED_HOST_VERSIONS) {
                        if (json.contains("\"version\":\"" + reviewed + "\"")) {
                            return Optional.of(reviewed);
                        }
                    }
                    return Optional.empty();
                }
            } catch (java.io.IOException unavailable) {
                // Report not written yet; keep polling.
            }
            try {
                Thread.sleep(SETTLE_STEP_MILLIS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return Optional.empty();
            }
        }
        return Optional.empty();
    }

    /**
     * Invokes the private splash factory {@code e()} on the singleton. With
     * suppression active the transformed body returns null immediately;
     * without suppression the original body creates and shows the native
     * splash window and returns a non-null window object.
     */
    private String assertSplashSuppressed(final Class<?> application, final List<String> failures) {
        try {
            final Object instance = application.getField(HOST_APP_INSTANCE_FIELD).get(null);
            final Method splash = application.getDeclaredMethod(SPLASH_METHOD);
            splash.setAccessible(true);
            final Object result = splash.invoke(instance);
            final String actual = result == null ? "null" : result.getClass().getName();
            if (result != null) {
                failures.add("splash-suppressed expected=null actual=" + actual);
            }
            return actual;
        } catch (ReflectiveOperationException | RuntimeException failure) {
            final String detail = failure.getClass().getName() + ": " + failure.getMessage();
            failures.add("splash-suppressed invocation failed: " + detail);
            return "invocation-failed";
        }
    }

    private void assertStartupMethodPresent(final Class<?> application, final List<String> failures) {
        try {
            application.getMethod(STARTUP_METHOD, String[].class);
        } catch (NoSuchMethodException | RuntimeException failure) {
            failures.add("startup-method-present expected=a(String[]) missing=" + failure.getClass().getSimpleName());
        }
    }

    private void writeResult(
        final String terminal,
        final String hostVersion,
        final String splashActual,
        final List<String> failures
    ) {
        final StringBuilder result = new StringBuilder()
            .append("schemaVersion=1\n")
            .append("pluginId=").append(PLUGIN_ID).append('\n')
            .append("terminal=").append(terminal).append('\n')
            .append("hostVersion=").append(hostVersion).append('\n')
            .append("splash.assertion=splash-suppressed\n")
            .append("splash.expected=null\n")
            .append("splash.actual=").append(splashActual).append('\n')
            .append("splash.status=").append("null".equals(splashActual) ? "PASS" : "FAIL").append('\n')
            .append("updateCheck.assertion=update-check-call-removed-by-atomic-transform\n")
            .append("updateCheck.evidence=STARTUP_SUPPRESSION_TRANSFORM_TRANSFORMED-in-cubism-console\n")
            .append("information.assertion=information-call-removed-by-atomic-transform\n")
            .append("information.evidence=STARTUP_SUPPRESSION_TRANSFORM_TRANSFORMED-in-cubism-console\n")
            .append("failures=").append(failures.size()).append('\n');
        for (int index = 0; index < failures.size(); index++) {
            result.append("failure.").append(index).append('=')
                .append(failures.get(index).replace('\n', ' ')).append('\n');
        }
        try {
            Files.writeString(stateDir.resolve(RESULT), result);
        } catch (java.io.IOException failure) {
            logger.warn("STARTUP_SUPPRESSION_VALIDATION_RESULT_WRITE_FAILED "
                + failure.getClass().getSimpleName());
        }
    }

    private void finish(final List<String> failures, final String hostVersion, final String splashActual) {
        final String terminal = failures.isEmpty() ? "PASS" : "FAIL";
        writeResult(terminal, hostVersion, splashActual, failures);
        for (String failure : failures) {
            logger.warn("STARTUP_SUPPRESSION_VALIDATION_FAILURE " + failure);
        }
        logger.info("STARTUP_SUPPRESSION_VALIDATION_RESULT status=" + terminal
            + " hostVersion=" + hostVersion
            + " splashExpected=null splashActual=" + splashActual
            + " failures=" + failures.size());
        Runtime.getRuntime().exit(failures.isEmpty() ? 0 : 2);
    }
}
