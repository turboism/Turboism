package dev.example.previewcontextservices;

import dev.turboism.sdk.config.ConfigCodecs;
import dev.turboism.sdk.config.ConfigKey;
import dev.turboism.sdk.config.ConfigReadResult;
import dev.turboism.sdk.config.ConfigSchema;
import dev.turboism.sdk.config.ConfigValueSource;
import dev.turboism.sdk.config.ConfigWriteResult;
import dev.turboism.sdk.hostread.AsyncHostReadIntent;
import dev.turboism.sdk.hostread.AsyncHostReadRequest;
import dev.turboism.sdk.hostread.AsyncHostReadSubmissionStatus;
import dev.turboism.sdk.plugin.PluginContext;
import dev.turboism.sdk.plugin.TurboismPlugin;
import dev.turboism.sdk.storage.StoragePath;
import dev.turboism.sdk.storage.StorageRoot;
import dev.turboism.sdk.task.PluginTaskKind;
import dev.turboism.sdk.task.PluginTaskPriority;
import dev.turboism.sdk.task.PluginTaskRequest;
import dev.turboism.sdk.task.TaskId;
import dev.turboism.sdk.task.TaskOutcomeStatus;
import dev.turboism.sdk.task.TaskSubmissionStatus;
import dev.turboism.sdk.ui.UserFileLifetime;
import dev.turboism.sdk.ui.UserFileMode;
import dev.turboism.sdk.ui.UserFileRequest;
import dev.turboism.sdk.ui.UserFileRequestStatus;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

public final class PreviewContextServicesPlugin implements TurboismPlugin {
    private static final String MARKER_DIRECTORY_PROPERTY = "__MARKER_DIRECTORY_PROPERTY__";

    @Override
    public void init(PluginContext context) {
        try {
            Map<String, String> marker = new LinkedHashMap<>();
            recordLocalization(context, marker);
            recordTask(context, marker);
            recordStorage(context, marker);
            recordConfig(context, marker);
            recordUserFiles(context, marker);
            recordHostReads(context, marker);
            writeReady(marker);
        } catch (Exception failure) {
            throw new IllegalStateException("Context services characterization failed", failure);
        }
    }

    private static void recordLocalization(PluginContext context, Map<String, String> marker) {
        String localized = context.localization().text("fixture.missing");
        require("⟦fixture.missing⟧".equals(localized), "localization marker");
        marker.put("localization", localized);
    }

    private static void recordTask(PluginContext context, Map<String, String> marker) throws Exception {
        var submission = context.tasks().submit(new PluginTaskRequest(
            new TaskId("context-fixture-task"), PluginTaskKind.COMPUTE, PluginTaskPriority.NORMAL,
            token -> { }
        ));
        require(submission.status() == TaskSubmissionStatus.ACCEPTED, "task status");
        require(submission.accepted(), "task accepted");
        var outcome = submission.handle().completion().toCompletableFuture().get(5, TimeUnit.SECONDS);
        require(outcome.status() == TaskOutcomeStatus.SUCCEEDED, "task outcome");
        marker.put("task.status", submission.status().name());
        marker.put("task.accepted", Boolean.toString(submission.accepted()));
        marker.put("task.outcome", outcome.status().name());
    }

    private static void recordStorage(PluginContext context, Map<String, String> marker) throws Exception {
        StoragePath path = new StoragePath(StorageRoot.DATA, "characterization/value.txt");
        var written = context.storage().writeUtf8Atomic(path, "characterization-value")
            .toCompletableFuture().get(5, TimeUnit.SECONDS);
        var read = context.storage().readUtf8(path, 1024).toCompletableFuture().get(5, TimeUnit.SECONDS);
        require(written.written(), "storage write");
        require(written.error().isEmpty() && read.error().isEmpty(), "storage error");
        require(!read.truncated(), "storage truncation");
        require("characterization-value".equals(read.value().orElseThrow()), "storage value");
        marker.put("storage.written", Boolean.toString(written.written()));
        marker.put("storage.value", read.value().orElseThrow());
        marker.put("storage.truncated", Boolean.toString(read.truncated()));
        marker.put("storage.error", "empty");
    }

    private static void recordConfig(PluginContext context, Map<String, String> marker) throws Exception {
        ConfigKey<Boolean> key = new ConfigKey<>("context-fixture", "enabled", true, ConfigCodecs.booleanValue());
        context.config().registerSchema(new ConfigSchema(
            "context-fixture", "context-fixture/config.cfg", 1, List.of(key)
        ), List.of()).toCompletableFuture().get(5, TimeUnit.SECONDS);
        var defaultRead = context.config().read(key).toCompletableFuture().get(5, TimeUnit.SECONDS);
        var configWrite = context.config().write(key, false, 0).toCompletableFuture().get(5, TimeUnit.SECONDS);
        var storedRead = context.config().read(key).toCompletableFuture().get(5, TimeUnit.SECONDS);
        require(defaultRead.error().isEmpty(), "default config error");
        require(defaultRead.value().source() == ConfigValueSource.STORED, "default config source");
        require(defaultRead.value().revision() == 0, "default config revision");
        require(Boolean.TRUE.equals(defaultRead.value().value()), "default config value");
        require(configWrite.written() && configWrite.revision() == 1, "config write");
        require(configWrite.error().isEmpty(), "config write error");
        require(storedRead.error().isEmpty(), "stored config error");
        require(storedRead.value().source() == ConfigValueSource.STORED, "stored config source");
        require(storedRead.value().revision() == 1, "stored config revision");
        require(Boolean.FALSE.equals(storedRead.value().value()), "stored config value");
        recordConfigValues(marker, defaultRead, configWrite, storedRead);
    }

    private static void recordConfigValues(
        Map<String, String> marker,
        ConfigReadResult<Boolean> defaults,
        ConfigWriteResult write,
        ConfigReadResult<Boolean> stored
    ) {
        marker.put("config.default.source", defaults.value().source().name());
        marker.put("config.default.revision", Long.toString(defaults.value().revision()));
        marker.put("config.default.value", Boolean.toString(defaults.value().value()));
        marker.put("config.write.written", Boolean.toString(write.written()));
        marker.put("config.write.revision", Long.toString(write.revision()));
        marker.put("config.write.error", "empty");
        marker.put("config.stored.source", stored.value().source().name());
        marker.put("config.stored.revision", Long.toString(stored.value().revision()));
        marker.put("config.stored.value", Boolean.toString(stored.value().value()));
    }

    private static void recordUserFiles(PluginContext context, Map<String, String> marker) throws Exception {
        var userFile = context.userFiles().request(new UserFileRequest(
            "context-fixture-file", "Select fixture file", List.of("txt"), UserFileMode.READ,
            UserFileLifetime.ONE_OPERATION
        )).toCompletableFuture().get(5, TimeUnit.SECONDS);
        require(userFile.status() == UserFileRequestStatus.UNAVAILABLE, "user file status");
        require(userFile.handle().isEmpty(), "user file handle");
        require(userFile.error().orElseThrow().code().name().equals("RUNTIME_UNAVAILABLE"), "user file error");
        marker.put("userFiles.status", userFile.status().name());
        marker.put("userFiles.error", userFile.error().orElseThrow().code().name());
        marker.put("userFiles.handle", Boolean.toString(userFile.handle().isPresent()));
    }

    private static void recordHostReads(PluginContext context, Map<String, String> marker) {
        var hostRead = context.hostReads().submit(new AsyncHostReadRequest(
            AsyncHostReadIntent.PROJECT_WORKSPACE_SNAPSHOT, Duration.ofSeconds(1)
        ));
        require(hostRead.status() == AsyncHostReadSubmissionStatus.REJECTED, "host read status");
        require(hostRead.handle().isEmpty(), "host read handle");
        require(hostRead.error().orElseThrow().code().name().equals("PERMISSION_DENIED"), "host read error");
        marker.put("hostReads.status", hostRead.status().name());
        marker.put("hostReads.error", hostRead.error().orElseThrow().code().name());
        marker.put("hostReads.handle", Boolean.toString(hostRead.handle().isPresent()));
    }

    private static void writeReady(Map<String, String> marker) throws Exception {
        String configuredDirectory = System.getProperty(MARKER_DIRECTORY_PROPERTY);
        require(configuredDirectory != null && !configuredDirectory.isBlank(), "marker directory");
        Path directory = Path.of(configuredDirectory);
        Files.createDirectories(directory);
        Path temporary = directory.resolve("preview-context-services.tmp");
        Path ready = directory.resolve("preview-context-services.ready");
        Files.writeString(temporary, markerContent(marker), StandardCharsets.UTF_8);
        Files.move(temporary, ready, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    }

    private static String markerContent(Map<String, String> marker) {
        StringBuilder content = new StringBuilder();
        for (Map.Entry<String, String> entry : marker.entrySet()) {
            content.append(entry.getKey()).append('=').append(entry.getValue()).append('\n');
        }
        return content.toString();
    }

    private static void require(boolean condition, String subject) {
        if (!condition) {
            throw new IllegalStateException("Unexpected " + subject);
        }
    }
}
