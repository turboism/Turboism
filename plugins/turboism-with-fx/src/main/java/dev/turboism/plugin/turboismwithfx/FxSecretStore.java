package dev.turboism.plugin.turboismwithfx;

import dev.turboism.protocol.json.StrictJson;
import dev.turboism.sdk.plugin.PluginLogger;
import dev.turboism.sdk.plugin.PluginPaths;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** Persistent custom-provider API keys with Windows DPAPI and an auth.json fallback. */
final class FxSecretStore {

    private static final int MAX_SECRET_BYTES = 4096;
    private static final int MAX_PROTECTED_BYTES = 16 * 1024;
    private static final int MAX_AUTH_FILE_BYTES = 64 * 1024;
    private static final Duration PROCESS_TIMEOUT = Duration.ofSeconds(20);
    private static final String PROTECT_SCRIPT =
        "$ErrorActionPreference='Stop';"
            + "$p=[Console]::In.ReadToEnd();"
            + "$b=[Text.Encoding]::UTF8.GetBytes($p);"
            + "$e=[Security.Cryptography.ProtectedData]::Protect($b,$null,"
            + "[Security.Cryptography.DataProtectionScope]::CurrentUser);"
            + "[Console]::Out.Write([Convert]::ToBase64String($e))";
    private static final String UNPROTECT_SCRIPT =
        "$ErrorActionPreference='Stop';"
            + "$p=[Console]::In.ReadToEnd();"
            + "$b=[Convert]::FromBase64String($p);"
            + "$e=[Security.Cryptography.ProtectedData]::Unprotect($b,$null,"
            + "[Security.Cryptography.DataProtectionScope]::CurrentUser);"
            + "[Console]::Out.Write([Text.Encoding]::UTF8.GetString($e))";

    private final Path protectedRoot;
    private final Path authFile;
    private final Protector protector;
    private final PluginLogger logger;
    private final AtomicBoolean fallbackReported = new AtomicBoolean();

    private FxSecretStore(
        final Path protectedRoot,
        final Path authFile,
        final Protector protector,
        final PluginLogger logger
    ) {
        this.protectedRoot = protectedRoot;
        this.authFile = authFile;
        this.protector = protector;
        this.logger = logger;
    }

    static FxSecretStore create(final PluginPaths paths, final PluginLogger logger) {
        Objects.requireNonNull(paths, "paths");
        final PluginLogger checkedLogger = Objects.requireNonNull(logger, "logger");
        final Path config = paths.configDir();
        final Protector protector = System.getProperty("os.name", "").startsWith("Windows")
            ? new WindowsDpapiProtector()
            : null;
        return new FxSecretStore(
            config.resolve("provider-credentials"),
            config.resolve("auth.json"),
            protector,
            checkedLogger
        );
    }

    static FxSecretStore unavailable() {
        return new FxSecretStore(null, null, null, null);
    }

    FxSecretStore(
        final Path root,
        final Protector protector,
        final PluginLogger logger
    ) {
        this(
            Objects.requireNonNull(root, "root").resolve("protected"),
            root.resolve("auth.json"),
            protector,
            logger
        );
    }

    boolean persistent() {
        return authFile != null;
    }

    boolean protectedPersistencePreferred() {
        return protector != null;
    }

    synchronized Optional<String> read(final String profileId) throws IOException {
        if (!persistent()) return Optional.empty();
        if (protector != null) {
            final Path path = protectedPath(profileId);
            if (Files.isRegularFile(path)) {
                try {
                    final byte[] protectedValue = Files.readAllBytes(path);
                    if (protectedValue.length == 0 || protectedValue.length > MAX_PROTECTED_BYTES) {
                        throw new IOException("protected provider credential is invalid");
                    }
                    final byte[] plain = protector.unprotect(protectedValue);
                    return Optional.of(validSecret(plain));
                } catch (IOException failure) {
                    reportFallback();
                }
            }
        }
        return Optional.ofNullable(readPlain().get(profileId));
    }

    synchronized void write(final String profileId, final String secret) throws IOException {
        if (!persistent()) return;
        final String id = checkedProfileId(profileId);
        final String value = Objects.requireNonNullElse(secret, "");
        if (value.isEmpty()) {
            if (protectedRoot != null) Files.deleteIfExists(protectedPath(id));
            final LinkedHashMap<String, String> plain = readPlain();
            if (plain.remove(id) != null) writePlain(plain);
            return;
        }
        final byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        validSecret(bytes);
        if (protector != null) {
            try {
                writeProtected(id, protector.protect(bytes));
                final LinkedHashMap<String, String> plain = readPlain();
                if (plain.remove(id) != null) writePlain(plain);
                return;
            } catch (IOException failure) {
                reportFallback();
            }
        }
        final LinkedHashMap<String, String> plain = readPlain();
        plain.put(id, value);
        writePlain(plain);
    }

    private void writeProtected(final String profileId, final byte[] protectedValue)
        throws IOException {
        if (protectedValue.length == 0 || protectedValue.length > MAX_PROTECTED_BYTES) {
            throw new IOException("protected provider credential is invalid");
        }
        Files.createDirectories(protectedRoot);
        writeAtomic(protectedPath(profileId), protectedValue);
    }

    private LinkedHashMap<String, String> readPlain() throws IOException {
        final LinkedHashMap<String, String> values = new LinkedHashMap<>();
        if (!Files.isRegularFile(authFile)) return values;
        final byte[] bytes = Files.readAllBytes(authFile);
        if (bytes.length == 0 || bytes.length > MAX_AUTH_FILE_BYTES) {
            throw new IOException("auth.json is invalid");
        }
        final Object parsed;
        try {
            parsed = StrictJson.parse(bytes);
        } catch (IllegalArgumentException failure) {
            throw new IOException("auth.json is invalid", failure);
        }
        if (!(parsed instanceof Map<?, ?> raw)) throw new IOException("auth.json is invalid");
        for (Map.Entry<?, ?> entry : raw.entrySet()) {
            if (!(entry.getKey() instanceof String id)
                || !(entry.getValue() instanceof String secret)) {
                throw new IOException("auth.json is invalid");
            }
            checkedProfileId(id);
            validSecret(secret.getBytes(StandardCharsets.UTF_8));
            values.put(id, secret);
        }
        return values;
    }

    private void writePlain(final Map<String, String> values) throws IOException {
        if (values.isEmpty()) {
            Files.deleteIfExists(authFile);
            return;
        }
        final byte[] bytes = StrictJson.bytes(values);
        if (bytes.length > MAX_AUTH_FILE_BYTES) throw new IOException("auth.json is too large");
        Files.createDirectories(authFile.getParent());
        writeAtomic(authFile, bytes);
    }

    private static void writeAtomic(final Path target, final byte[] bytes) throws IOException {
        final Path temporary = Files.createTempFile(
            target.getParent(), target.getFileName().toString(), ".tmp"
        );
        try {
            Files.write(
                temporary,
                bytes,
                StandardOpenOption.TRUNCATE_EXISTING,
                StandardOpenOption.WRITE
            );
            try {
                Files.move(
                    temporary,
                    target,
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING
                );
            } catch (java.nio.file.AtomicMoveNotSupportedException ignored) {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private Path protectedPath(final String profileId) {
        return protectedRoot.resolve(digest(checkedProfileId(profileId)) + ".dpapi");
    }

    private static String checkedProfileId(final String value) {
        final String id = Objects.requireNonNull(value, "profileId");
        if (id.isBlank() || id.length() > 128 || id.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("profile id is invalid");
        }
        return id;
    }

    private static String validSecret(final byte[] value) throws IOException {
        if (value.length == 0 || value.length > MAX_SECRET_BYTES) {
            throw new IOException("provider credential is invalid");
        }
        return new String(value, StandardCharsets.UTF_8);
    }

    private void reportFallback() {
        if (logger != null && fallbackReported.compareAndSet(false, true)) {
            logger.warn("Provider credential protection is unavailable; using local auth.json");
        }
    }

    private static String digest(final String value) {
        try {
            return HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))
            );
        } catch (NoSuchAlgorithmException failure) {
            throw new IllegalStateException("SHA-256 is unavailable", failure);
        }
    }

    interface Protector {
        byte[] protect(byte[] plain) throws IOException;
        byte[] unprotect(byte[] protectedValue) throws IOException;
    }

    private static final class WindowsDpapiProtector implements Protector {
        @Override public byte[] protect(final byte[] plain) throws IOException {
            return powershell(PROTECT_SCRIPT, plain, MAX_PROTECTED_BYTES);
        }

        @Override public byte[] unprotect(final byte[] protectedValue) throws IOException {
            return powershell(UNPROTECT_SCRIPT, protectedValue, MAX_SECRET_BYTES);
        }

        private static byte[] powershell(
            final String script,
            final byte[] input,
            final int maximumOutput
        ) throws IOException {
            final Process process = new ProcessBuilder(
                "powershell.exe",
                "-NoLogo",
                "-NoProfile",
                "-NonInteractive",
                "-Command",
                script
            ).redirectError(ProcessBuilder.Redirect.DISCARD).start();
            try (OutputStream output = process.getOutputStream()) {
                output.write(input);
            }
            final CompletableFuture<byte[]> result = CompletableFuture.supplyAsync(() -> {
                try (InputStream output = process.getInputStream()) {
                    final byte[] bytes = output.readNBytes(maximumOutput + 1);
                    if (bytes.length > maximumOutput) {
                        throw new IOException("credential helper output is too large");
                    }
                    return bytes;
                } catch (IOException failure) {
                    throw new java.util.concurrent.CompletionException(failure);
                }
            });
            try {
                if (!process.waitFor(PROCESS_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)) {
                    process.destroyForcibly();
                    throw new IOException("credential helper timed out");
                }
                if (process.exitValue() != 0) throw new IOException("credential helper failed");
                return result.join();
            } catch (InterruptedException failure) {
                Thread.currentThread().interrupt();
                process.destroyForcibly();
                throw new IOException("credential helper was interrupted", failure);
            } catch (java.util.concurrent.CompletionException failure) {
                final Throwable cause = failure.getCause();
                if (cause instanceof IOException io) throw io;
                throw new IOException("credential helper failed", cause);
            }
        }
    }
}
