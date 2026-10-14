package dev.turboism.validation.modelimport3d.host;

import dev.turboism.sdk.i18n.PluginLocalization;
import dev.turboism.sdk.ui.*;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.BooleanSupplier;

/** Resolves declared model resources using only separately granted SDK reads. */
final class ModelInputFiles {
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
                    if (input.length >= 4 && input[0] == 'P' && input[1] == 'K')
                        return CompletableFuture.<Optional<byte[]>>failedFuture(missing);
                    if (!active.getAsBoolean()) return CompletableFuture.completedFuture(Optional.<byte[]>empty());
                    if (resources.size() >= 64 || resources.containsKey(missing.key))
                        return CompletableFuture.<Optional<byte[]>>failedFuture(
                                new ModelImportPlugin.LocalizedIllegalArgumentException(
                                        "error.resource-limit", "模型资源数量超限或无法关联。"));
                    String name = missing.reference;
                    String title = format("resource.chooser.title", "选择模型引用的资源：{0}", name);
                    if (title.length() > 256) title = title.substring(0, 253) + "…";
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
                                                    "error.resource-select", "无法选择模型资源：{0}", selected.status()));
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
                                                                "error.resource-too-large", "模型资源超过 16 MiB 限制。"));
                                            byte[] bytes = value.value()
                                                    .orElseThrow(
                                                            () -> new ModelImportPlugin.LocalizedIllegalStateException(
                                                                    "error.resource-read", "无法读取模型资源。"));
                                            total += bytes.length;
                                            if (total > GlbSurfaceReader.MAX_BYTES)
                                                throw new CompletionException(
                                                        new ModelImportPlugin.LocalizedIllegalArgumentException(
                                                                "error.total-too-large", "模型及外部资源合计超过 16 MiB 限制。"));
                                            resources.put(missing.key, bytes);
                                            return resolve();
                                        });
                            });
                })
                .thenCompose(stage -> stage);
    }
}
