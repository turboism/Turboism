package dev.turboism.plugin.modelimport3d;

import dev.turboism.sdk.i18n.PluginLocalization;
import dev.turboism.sdk.ui.*;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.BooleanSupplier;

/** Resolves declared model resources using only separately granted SDK reads. */
final class ModelInputFiles {
    private static final int ZIP_SIGNATURE_MIN_BYTES = 4;
    private static final int MAX_EXTERNAL_RESOURCES = 64;
    private static final int MAX_CHOOSER_TITLE_LENGTH = 256;
    private static final int TRUNCATED_TITLE_PREFIX_LENGTH = MAX_CHOOSER_TITLE_LENGTH - 3;

    private final byte[] input;
    private final UserFileAccessService files;
    private final String requestId;
    private final BooleanSupplier active;
    private final PluginLocalization i18n;
    private final Map<String, byte[]> resources = new LinkedHashMap<>();
    private long total;

    private ModelInputFiles(
            byte[] input,
            UserFileAccessService files,
            String requestId,
            BooleanSupplier active,
            PluginLocalization i18n) {
        this.input = input;
        this.files = files;
        this.requestId = requestId;
        this.active = active;
        this.i18n = i18n;
        total = input.length;
    }

    private String text(String key, String fallback) {
        if (i18n == null) return fallback;
        try {
            String val = i18n.text(key);
            return key.equals(val) ? fallback : val;
        } catch (Exception ignored) {
            return fallback;
        }
    }

    private String format(String key, String fallback, Object... args) {
        if (i18n == null) return java.text.MessageFormat.format(fallback, args);
        try {
            String pattern = i18n.text(key);
            if (key.equals(pattern)) return java.text.MessageFormat.format(fallback, args);
            return i18n.format(key, args);
        } catch (Exception ignored) {
            return java.text.MessageFormat.format(fallback, args);
        }
    }

    static CompletionStage<Optional<byte[]>> normalize(
            byte[] input, UserFileAccessService files, String requestId, BooleanSupplier active) {
        return normalize(input, files, requestId, active, null);
    }

    static CompletionStage<Optional<byte[]>> normalize(
            byte[] input,
            UserFileAccessService files,
            String requestId,
            BooleanSupplier active,
            PluginLocalization i18n) {
        return new ModelInputFiles(input, files, requestId, active, i18n).resolve();
    }

    private CompletionStage<Optional<byte[]>> resolve() {
        return CompletableFuture.supplyAsync(() -> {
                    if (!active.getAsBoolean()) return Optional.<byte[]>empty();
                    try {
                        return Optional.of(ModelInputNormalizer.normalizeSelected(input, resources));
                    } catch (IOException failure) {
                        throw new CompletionException(failure);
                    }
                })
                .handle((normalized, failure) -> {
                    if (failure == null) return CompletableFuture.completedFuture(normalized);
                    while (failure instanceof CompletionException && failure.getCause() != null)
                        failure = failure.getCause();
                    if (!(failure instanceof ModelInputNormalizer.MissingResource missing))
                        return CompletableFuture.<Optional<byte[]>>failedFuture(failure);
                    // ZIP packages retain their existing requirement to contain all referenced resources.
                    if (input.length >= ZIP_SIGNATURE_MIN_BYTES && input[0] == 'P' && input[1] == 'K')
                        return CompletableFuture.<Optional<byte[]>>failedFuture(missing);
                    if (!active.getAsBoolean()) return CompletableFuture.completedFuture(Optional.<byte[]>empty());
                    if (resources.size() >= MAX_EXTERNAL_RESOURCES || resources.containsKey(missing.key))
                        return CompletableFuture.<Optional<byte[]>>failedFuture(
                                new ModelImportPlugin.LocalizedIllegalArgumentException(
                                        "error.resource-limit",
                                        "Model resource count exceeded or cannot be resolved."));
                    String name = missing.reference;
                    String title = format("resource.chooser.title", "Select referenced model resource: {0}", name);
                    if (title.length() > MAX_CHOOSER_TITLE_LENGTH)
                        title = title.substring(0, TRUNCATED_TITLE_PREFIX_LENGTH) + "…";
                    String suffix = name.substring(name.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT);
                    List<String> extensions = Set.of("png", "jpg", "jpeg").contains(suffix)
                            ? List.of("png", "jpg", "jpeg")
                            : suffix.matches("[a-z0-9][a-z0-9+_-]{0,31}") ? List.of(suffix) : List.of();
                    return files.request(new UserFileRequest(
                                    requestId + "-resource-" + resources.size(),
                                    title,
                                    extensions,
                                    UserFileMode.READ,
                                    UserFileLifetime.ONE_OPERATION))
                            .thenCompose(selected -> {
                                if (selected.status() == UserFileRequestStatus.CANCELED)
                                    return CompletableFuture.completedFuture(Optional.<byte[]>empty());
                                if (selected.status() != UserFileRequestStatus.GRANTED)
                                    return CompletableFuture.<Optional<byte[]>>failedFuture(
                                            new ModelImportPlugin.LocalizedIllegalStateException(
                                                    "error.resource-select",
                                                    "Could not select model resource: {0}",
                                                    selected.status()));
                                var handle = selected.handle().orElseThrow();
                                if (!active.getAsBoolean()) {
                                    handle.close();
                                    return CompletableFuture.completedFuture(Optional.<byte[]>empty());
                                }
                                CompletionStage<UserFileReadResult<byte[]>> read;
                                try {
                                    read = files.readBytes(handle, GlbSurfaceReader.MAX_BYTES);
                                } catch (RuntimeException failureRead) {
                                    handle.close();
                                    throw failureRead;
                                }
                                return read.whenComplete((value, error) -> handle.close())
                                        .thenCompose(value -> {
                                            if (!active.getAsBoolean())
                                                return CompletableFuture.completedFuture(Optional.<byte[]>empty());
                                            if (value.truncated())
                                                throw new CompletionException(
                                                        new ModelImportPlugin.LocalizedIllegalArgumentException(
                                                                "error.resource-too-large",
                                                                "Model resource exceeds the 16 MiB limit."));
                                            byte[] bytes = value.value()
                                                    .orElseThrow(
                                                            () -> new ModelImportPlugin.LocalizedIllegalStateException(
                                                                    "error.resource-read",
                                                                    "Could not read model resource."));
                                            total += bytes.length;
                                            if (total > GlbSurfaceReader.MAX_BYTES)
                                                throw new CompletionException(
                                                        new ModelImportPlugin.LocalizedIllegalArgumentException(
                                                                "error.total-too-large",
                                                                "Total size of model and external resources exceeds the 16 MiB limit."));
                                            resources.put(missing.key, bytes);
                                            return resolve();
                                        });
                            });
                })
                .thenCompose(stage -> stage);
    }
}
