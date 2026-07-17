package dev.turboism.sdk.storage;

import dev.turboism.sdk.plugin.PluginContext;
import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PluginStorageContractTest {

    @Test
    void exposesFrozenEnumsAndExactPluginStorageMethods() throws Exception {
        assertEquals("DATA,STATE,CACHE", names(StorageRoot.values()));
        assertEquals(
            "INVALID_PATH,PERMISSION_DENIED,NOT_FOUND,ALREADY_EXISTS,TYPE_MISMATCH," +
                "SIZE_LIMIT_EXCEEDED,QUOTA_EXCEEDED,LINK_ESCAPE,ATOMIC_REPLACE_UNAVAILABLE," +
                "CROSS_ROOT_ATOMIC_MOVE_UNSUPPORTED,PARTIAL_DELETE,CONFLICT,CANCELED," +
                "RUNTIME_UNAVAILABLE,IO_FAILURE",
            names(StorageErrorCode.values())
        );
        assertEquals("FILE,DIRECTORY", names(StorageEntryType.values()));
        assertEquals(8, PluginStorage.class.getDeclaredMethods().length);
        assertEquals(
            "java.util.concurrent.CompletionStage",
            PluginStorage.class.getMethod("readUtf8", StoragePath.class, int.class)
                .getReturnType().getTypeName()
        );
        assertEquals(
            "java.util.concurrent.CompletionStage",
            PluginStorage.class.getMethod(
                "moveAtomic",
                StoragePath.class,
                StoragePath.class,
                boolean.class
            ).getReturnType().getTypeName()
        );
    }

    @Test
    void storagePathAcceptsOnlyNormalizedPortableRelativeText() {
        final StoragePath path = new StoragePath(StorageRoot.DATA, "themes/custom/theme.properties");
        assertEquals(StorageRoot.DATA, path.root());
        assertEquals("themes/custom/theme.properties", path.relativePath());

        for (String invalid : List.of(
            "", " ", "/absolute", "C:/drive", "file:/uri", "~/home",
            "a\\b", "a//b", "a/./b", "a/../b", "a/", "a\u0000b", "a\u001Fb"
        )) {
            assertThrows(
                IllegalArgumentException.class,
                () -> new StoragePath(StorageRoot.DATA, invalid),
                invalid
            );
        }
        assertThrows(NullPointerException.class, () -> new StoragePath(null, "a"));
        assertThrows(NullPointerException.class, () -> new StoragePath(StorageRoot.DATA, null));
    }

    @Test
    void readAndListResultsEnforceSuccessFailureAlgebra() {
        final StoragePath path = new StoragePath(StorageRoot.STATE, "state.json");
        final StorageError error = new StorageError(
            StorageErrorCode.NOT_FOUND,
            "Storage entry was not found.",
            path
        );

        assertTrue(new StorageReadResult<>(Optional.of("value"), Optional.empty(), true).truncated());
        assertEquals(
            Optional.of(error),
            new StorageReadResult<String>(Optional.empty(), Optional.of(error), false).error()
        );
        assertThrows(IllegalArgumentException.class, () ->
            new StorageReadResult<>(Optional.of("value"), Optional.of(error), false)
        );
        assertThrows(IllegalArgumentException.class, () ->
            new StorageReadResult<String>(Optional.empty(), Optional.empty(), false)
        );
        assertThrows(IllegalArgumentException.class, () ->
            new StorageReadResult<String>(Optional.empty(), Optional.of(error), true)
        );

        final StorageEntry entry = new StorageEntry(path, StorageEntryType.FILE, 12L);
        final StorageListResult success = new StorageListResult(
            List.of(entry),
            Optional.empty(),
            true
        );
        assertTrue(success.truncated());
        assertThrows(UnsupportedOperationException.class, () -> success.entries().add(entry));
        assertThrows(IllegalArgumentException.class, () ->
            new StorageListResult(List.of(entry), Optional.of(error), false)
        );
        assertTrue(
            new StorageListResult(List.of(), Optional.empty(), false).entries().isEmpty()
        );
        assertThrows(IllegalArgumentException.class, () ->
            new StorageListResult(List.of(), Optional.of(error), true)
        );
    }

    @Test
    void writeAndMutationResultsEnforceClosedLegalForms() {
        final StoragePath path = new StoragePath(StorageRoot.CACHE, "cache.bin");
        final StorageError failure = new StorageError(
            StorageErrorCode.IO_FAILURE,
            "Storage operation failed.",
            path
        );
        final StorageError partial = new StorageError(
            StorageErrorCode.PARTIAL_DELETE,
            "Recursive delete stopped after a partial change.",
            path
        );

        assertTrue(new StorageWriteResult(true, Optional.empty()).written());
        assertFalse(new StorageWriteResult(false, Optional.of(failure)).written());
        assertThrows(IllegalArgumentException.class, () ->
            new StorageWriteResult(false, Optional.empty())
        );
        assertThrows(IllegalArgumentException.class, () ->
            new StorageWriteResult(true, Optional.of(failure))
        );

        assertTrue(new StorageMutationResult(true, Optional.empty()).changed());
        assertTrue(new StorageMutationResult(true, Optional.of(partial)).changed());
        assertFalse(new StorageMutationResult(false, Optional.of(failure)).changed());
        assertThrows(IllegalArgumentException.class, () ->
            new StorageMutationResult(false, Optional.empty())
        );
        assertThrows(IllegalArgumentException.class, () ->
            new StorageMutationResult(true, Optional.of(failure))
        );
    }

    @Test
    void entryAndErrorValidationRemainSdkSafe() {
        final StoragePath path = new StoragePath(StorageRoot.DATA, "file.txt");
        assertThrows(IllegalArgumentException.class, () ->
            new StorageEntry(path, StorageEntryType.FILE, -1)
        );
        assertThrows(IllegalArgumentException.class, () ->
            new StorageError(StorageErrorCode.IO_FAILURE, " ", path)
        );
        assertThrows(NullPointerException.class, () ->
            new StorageError(null, "Failure.", path)
        );
    }

    @Test
    void pluginContextDefaultStorageAccessorFailsWithFrozenMessage() {
        final PluginContext context = (PluginContext) Proxy.newProxyInstance(
            PluginContext.class.getClassLoader(),
            new Class<?>[] {PluginContext.class},
            PluginStorageContractTest::invokeDefault
        );

        final UnsupportedOperationException error = assertThrows(
            UnsupportedOperationException.class,
            context::storage
        );
        assertEquals("storage service is not available", error.getMessage());
    }

    private static Object invokeDefault(
        final Object proxy,
        final Method method,
        final Object[] arguments
    ) throws Throwable {
        if (!method.isDefault()) {
            throw new AssertionError("Unexpected abstract method invocation: " + method);
        }
        return InvocationHandler.invokeDefault(proxy, method, arguments);
    }

    private static String names(final Enum<?>[] values) {
        return Arrays.stream(values)
            .map(Enum::name)
            .collect(java.util.stream.Collectors.joining(","));
    }
}
