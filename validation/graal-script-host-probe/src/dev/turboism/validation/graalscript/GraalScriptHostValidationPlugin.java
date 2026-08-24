package dev.turboism.validation.graalscript;

import dev.turboism.sdk.plugin.PluginContext;
import dev.turboism.sdk.plugin.PluginLogger;
import dev.turboism.sdk.plugin.TurboismPlugin;
import dev.turboism.sdk.script.ScriptId;
import dev.turboism.sdk.script.ScriptRunHandle;
import dev.turboism.sdk.script.ScriptRunRequest;
import dev.turboism.sdk.script.ScriptRunResult;
import dev.turboism.sdk.script.ScriptRunStatus;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/** Task-local public-SDK probe for the isolated Graal script runtime. */
public final class GraalScriptHostValidationPlugin implements TurboismPlugin {

    private static final long COMPLETION_TIMEOUT_SECONDS = 45L;
    private static final long HOST_READY_TIMEOUT_MILLIS = 180_000L;
    private static final long HOST_READY_POLL_MILLIS = 2_000L;

    private PluginContext context;
    private PluginLogger logger;
    private Path stateDir;

    @Override
    public void init(final PluginContext context) {
        this.context = context;
        this.logger = context.logger();
        this.stateDir = context.paths().stateDir();
        final Thread probe = new Thread(this::run, "graal-script-host-validation");
        probe.setDaemon(true);
        probe.start();
    }

    @Override
    public void enable() {
        logger.info("GRAAL_SCRIPT_HOST_VALIDATION_ENABLED");
    }

    private void run() {
        final List<String> failures = new ArrayList<>();
        final StringBuilder evidence = new StringBuilder();
        try {
            evidence.append("cubismJvm.javaVersion=").append(safe(System.getProperty("java.version"))).append('\n');
            evidence.append("cubismJvm.vmName=").append(safe(System.getProperty("java.vm.name"))).append('\n');
            evidence.append("cubismJvm.vmVendor=").append(safe(System.getProperty("java.vm.vendor"))).append('\n');
            evidence.append("hostReady=").append(awaitHostReady()).append('\n');
            evidence.append("available=").append(context.scripts().available()).append('\n');
            if (!context.scripts().available()) {
                failures.add("script runtime is unavailable");
            }
            evidence.append("scripts=")
                .append(context.scripts().list().stream().map(script -> script.id().value()).sorted().toList())
                .append('\n');

            verifySuccess(evidence, failures);
            verifySandbox(evidence, failures);
            verifyHostCall(evidence, failures);
            verifyBulkApi(evidence, failures);
            verifyPermissionDenial(evidence, failures);
            verifyCancellation(evidence, failures);
        } catch (Throwable failure) {
            failures.add("probe exception: " + failure.getClass().getName() + ": " + safe(failure.getMessage()));
        }
        finish(evidence, failures);
    }

    private boolean awaitHostReady() throws InterruptedException {
        final long deadline = System.currentTimeMillis() + HOST_READY_TIMEOUT_MILLIS;
        while (System.currentTimeMillis() < deadline) {
            try {
                if (context.cubism().isHostPresent()) {
                    context.cubism().model().active().id();
                    return true;
                }
            } catch (RuntimeException unavailable) {
                // The official launcher may load the fixture before the adapter becomes ready.
            }
            Thread.sleep(HOST_READY_POLL_MILLIS);
        }
        return false;
    }

    private void verifySuccess(final StringBuilder evidence, final List<String> failures) throws Exception {
        final ScriptRunResult result = run("validation.success", Map.of("name", "proton"));
        record(evidence, "success", result);
        if (result.status() != ScriptRunStatus.SUCCEEDED
            || !result.output().contains("hello proton")) {
            failures.add("success script did not print expected output");
        }
    }

    private void verifySandbox(final StringBuilder evidence, final List<String> failures) throws Exception {
        final ScriptRunResult result = run("validation.sandbox", Map.of());
        record(evidence, "sandbox", result);
        if (result.status() != ScriptRunStatus.SUCCEEDED
            || !result.output().contains("java=blocked")
            || !result.output().contains("load=blocked")
            || !result.output().contains("process=blocked")
            || !result.output().contains("thread=blocked")
            || !result.output().contains("fetch=blocked")) {
            failures.add("sandbox script exposed Java host access");
        }
    }

    private void verifyHostCall(final StringBuilder evidence, final List<String> failures) throws Exception {
        final ScriptRunResult result = run("validation.host-call", Map.of());
        record(evidence, "hostCall", result);
        if (result.status() != ScriptRunStatus.SUCCEEDED
            || !result.output().contains("hostPresent=true")) {
            failures.add("host-call script did not observe Cubism");
        }
    }

    private void verifyBulkApi(final StringBuilder evidence, final List<String> failures) throws Exception {
        final ScriptRunResult result = run("validation.bulk-api", Map.of());
        record(evidence, "bulkApi", result);
        if (result.status() != ScriptRunStatus.SUCCEEDED
            || !result.output().contains("modelIdPresent=true")
            || !result.output().contains("parameterCountPresent=true")
            || !result.output().contains("getManyCount=1")) {
            failures.add("bulk snapshot API did not return the expected active-model data");
        }
    }

    private void verifyPermissionDenial(
        final StringBuilder evidence,
        final List<String> failures
    ) throws Exception {
        final ScriptRunResult result = run("validation.permission-denied", Map.of());
        record(evidence, "permissionDenied", result);
        final String code = result.failure().map(failure -> failure.code()).orElse("");
        if (result.status() != ScriptRunStatus.FAILED
            || !"SCRIPT_PERMISSION_DENIED".equals(code)) {
            failures.add("undeclared host permission did not preserve SCRIPT_PERMISSION_DENIED");
        }
    }

    private void verifyCancellation(final StringBuilder evidence, final List<String> failures) throws Exception {
        final ScriptRunHandle handle = context.scripts().run(
            new ScriptRunRequest(new ScriptId("validation.cancel"))
        );
        Thread.sleep(1_000L);
        evidence.append("cancel.requested=").append(handle.cancel()).append('\n');
        final ScriptRunResult result = handle.completion().toCompletableFuture()
            .get(COMPLETION_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        record(evidence, "cancel", result);
        if (result.status() != ScriptRunStatus.CANCELLED
            && result.status() != ScriptRunStatus.TIMED_OUT) {
            failures.add("infinite script was neither cancelled nor resource-limited");
        }
    }

    private ScriptRunResult run(final String id, final Map<String, String> arguments) throws Exception {
        return context.scripts().run(
            new ScriptRunRequest(new ScriptId(id), arguments)
        ).completion().toCompletableFuture().get(COMPLETION_TIMEOUT_SECONDS, TimeUnit.SECONDS);
    }

    private static void record(
        final StringBuilder evidence,
        final String label,
        final ScriptRunResult result
    ) {
        evidence.append(label).append(".status=").append(result.status()).append('\n')
            .append(label).append(".output=").append(oneLine(result.output())).append('\n');
        result.failure().ifPresent(failure -> evidence
            .append(label).append(".failure.code=").append(failure.code()).append('\n')
            .append(label).append(".failure.message=").append(oneLine(failure.message())).append('\n'));
    }

    private void finish(final StringBuilder evidence, final List<String> failures) {
        final boolean pass = failures.isEmpty();
        final StringBuilder result = new StringBuilder()
            .append("status=").append(pass ? "PASS" : "FAIL").append('\n')
            .append(evidence)
            .append("failures=").append(failures.size()).append('\n');
        for (int index = 0; index < failures.size(); index++) {
            result.append("failure.").append(index).append('=').append(oneLine(failures.get(index))).append('\n');
        }
        try {
            Files.writeString(stateDir.resolve("graal-script-result.txt"), result);
        } catch (Exception writeFailure) {
            logger.error("GRAAL_SCRIPT_HOST_VALIDATION_RESULT_WRITE_FAILED", writeFailure);
            Runtime.getRuntime().halt(3);
            return;
        }
        logger.info("GRAAL_SCRIPT_HOST_VALIDATION_RESULT status=" + (pass ? "PASS" : "FAIL")
            + " failures=" + failures.size());
        try {
            Thread.sleep(2_000L);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
        Runtime.getRuntime().exit(pass ? 0 : 2);
    }

    private static String safe(final String value) {
        return value == null || value.isBlank() ? "missing" : oneLine(value);
    }

    private static String oneLine(final String value) {
        return value.replace('\r', ' ').replace('\n', '|');
    }
}
