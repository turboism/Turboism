package dev.turboism.plugin.turboismwithfx;

import dev.turboism.sdk.plugin.PluginLogger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class FxSecretStoreTest {

    @TempDir
    Path root;

    @Test
    void plainFallbackKeepsCredentialsAcrossRestartsAndDeletesOnClear() throws Exception {
        final FxSecretStore store = new FxSecretStore(root, null, logger(new ArrayList<>()));

        assertTrue(store.persistent());
        assertFalse(store.protectedPersistencePreferred());
        assertEquals(Optional.empty(), store.read("profile-a"));

        store.write("profile-a", "sk-plain-value");
        store.write("profile-b", "sk-other-value");

        final Path authFile = root.resolve("auth.json");
        assertTrue(Files.isRegularFile(authFile));
        final String contents = Files.readString(authFile, StandardCharsets.UTF_8);
        assertTrue(contents.contains("sk-plain-value"));

        final FxSecretStore reopened = new FxSecretStore(root, null, logger(new ArrayList<>()));
        assertEquals(Optional.of("sk-plain-value"), reopened.read("profile-a"));
        assertEquals(Optional.of("sk-other-value"), reopened.read("profile-b"));

        reopened.write("profile-a", "");
        assertEquals(Optional.empty(), reopened.read("profile-a"));
        assertEquals(Optional.of("sk-other-value"), reopened.read("profile-b"));

        reopened.write("profile-b", "");
        assertFalse(Files.exists(authFile));
    }

    @Test
    void protectedStoreIsPreferredAndRemovesAnyEarlierPlainCopy() throws Exception {
        final List<String> warnings = new ArrayList<>();
        final FxSecretStore plain = new FxSecretStore(root, null, logger(warnings));
        plain.write("profile-a", "sk-plain-value");
        assertTrue(Files.readString(root.resolve("auth.json")).contains("sk-plain-value"));

        final FxSecretStore protectedStore = new FxSecretStore(root, reversing(), logger(warnings));
        protectedStore.write("profile-a", "sk-protected-value");

        assertTrue(protectedStore.protectedPersistencePreferred());
        assertFalse(Files.exists(root.resolve("auth.json")));
        assertEquals(Optional.of("sk-protected-value"), protectedStore.read("profile-a"));
        assertEquals(List.of(), warnings);

        final Path stored = Files.list(root.resolve("protected")).findFirst().orElseThrow();
        assertFalse(Files.readString(stored, StandardCharsets.UTF_8).contains("sk-protected-value"));
    }

    @Test
    void unavailableProtectionFallsBackToPlainFileAndReportsOnce() throws Exception {
        final List<String> warnings = new ArrayList<>();
        final FxSecretStore store = new FxSecretStore(root, failing(), logger(warnings));

        store.write("profile-a", "sk-value-one");
        store.write("profile-b", "sk-value-two");

        assertEquals(Optional.of("sk-value-one"), store.read("profile-a"));
        assertTrue(Files.isRegularFile(root.resolve("auth.json")));
        assertEquals(1, warnings.size());
        assertTrue(warnings.get(0).contains("auth.json"));
    }

    @Test
    void disabledStoreNeitherReadsNorWrites() throws Exception {
        final FxSecretStore store = FxSecretStore.unavailable();

        assertFalse(store.persistent());
        store.write("profile-a", "sk-ignored");
        assertEquals(Optional.empty(), store.read("profile-a"));
    }

    private static FxSecretStore.Protector reversing() {
        return new FxSecretStore.Protector() {
            @Override public byte[] protect(final byte[] plain) {
                return reverse(plain);
            }

            @Override public byte[] unprotect(final byte[] protectedValue) {
                return reverse(protectedValue);
            }

            private byte[] reverse(final byte[] value) {
                final byte[] result = new byte[value.length];
                for (int index = 0; index < value.length; index++) {
                    result[index] = value[value.length - 1 - index];
                }
                return result;
            }
        };
    }

    private static FxSecretStore.Protector failing() {
        return new FxSecretStore.Protector() {
            @Override public byte[] protect(final byte[] plain) throws IOException {
                throw new IOException("credential helper failed");
            }

            @Override public byte[] unprotect(final byte[] protectedValue) throws IOException {
                throw new IOException("credential helper failed");
            }
        };
    }

    private static PluginLogger logger(final List<String> warnings) {
        return new PluginLogger() {
            @Override public void debug(final String message) { }
            @Override public void info(final String message) { }
            @Override public void warn(final String message) { warnings.add(message); }
            @Override public void error(final String message) { }
            @Override public void error(final String message, final Throwable throwable) { }
        };
    }
}
