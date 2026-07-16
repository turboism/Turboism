package dev.turboism.sdk.ui;

import dev.turboism.sdk.plugin.PluginContext;
import org.junit.jupiter.api.Test;

import java.io.Serializable;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletionStage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UserFileAccessContractTest {

    @Test
    void exposesFrozenClosedEnumsAndExactServiceMethods() throws Exception {
        assertEquals("READ,WRITE", names(UserFileMode.values()));
        assertEquals("ONE_OPERATION,UNTIL_DISABLE", names(UserFileLifetime.values()));
        assertEquals("ACTIVE,CLOSED,REVOKED", names(UserFileHandleState.values()));
        assertEquals("GRANTED,CANCELED,DENIED,UNAVAILABLE", names(UserFileRequestStatus.values()));
        assertEquals(
            "PERMISSION_DENIED,INVALID_GRANT,MODE_MISMATCH,GRANT_EXPIRED,GRANT_REVOKED," +
                "FOREIGN_GRANT,SIZE_LIMIT_EXCEEDED,ATOMIC_REPLACE_UNAVAILABLE,CANCELED," +
                "RUNTIME_UNAVAILABLE,IO_FAILURE",
            names(UserFileErrorCode.values())
        );

        assertEquals(5, UserFileAccessService.class.getDeclaredMethods().length);
        assertEquals(
            CompletionStage.class,
            UserFileAccessService.class.getMethod("request", UserFileRequest.class)
                .getReturnType()
        );
        assertEquals(
            CompletionStage.class,
            UserFileAccessService.class.getMethod(
                "writeBytesAtomic",
                UserFileHandle.class,
                byte[].class
            ).getReturnType()
        );
    }

    @Test
    void requestIsImmutableAndRejectsMalformedMetadata() {
        final UserFileRequest request = new UserFileRequest(
            "parameter-export",
            "Export parameters",
            List.of("csv", "txt"),
            UserFileMode.WRITE,
            UserFileLifetime.ONE_OPERATION
        );
        assertEquals(List.of("csv", "txt"), request.allowedExtensions());
        assertThrows(UnsupportedOperationException.class, () ->
            request.allowedExtensions().add("json")
        );

        assertThrows(IllegalArgumentException.class, () -> new UserFileRequest(
            " ", "Title", List.of("csv"), UserFileMode.READ, UserFileLifetime.ONE_OPERATION
        ));
        assertThrows(IllegalArgumentException.class, () -> new UserFileRequest(
            "id", " ", List.of("csv"), UserFileMode.READ, UserFileLifetime.ONE_OPERATION
        ));
        for (String extension : List.of("", ".csv", "a/b", "a\\b", "*", " CSV")) {
            assertThrows(IllegalArgumentException.class, () -> new UserFileRequest(
                "id",
                "Title",
                List.of(extension),
                UserFileMode.READ,
                UserFileLifetime.ONE_OPERATION
            ), extension);
        }
    }

    @Test
    void handleIsOpaqueAndDoesNotExposePathOrSerialization() {
        assertFalse(Serializable.class.isAssignableFrom(UserFileHandle.class));
        final String methodNames = Arrays.stream(UserFileHandle.class.getDeclaredMethods())
            .map(Method::getName)
            .sorted()
            .collect(java.util.stream.Collectors.joining(","));
        assertEquals("close,displayName,id,lifetime,mode,revoke,state", methodNames);
        final String signatures = Arrays.stream(UserFileHandle.class.getDeclaredMethods())
            .map(Method::toGenericString)
            .collect(java.util.stream.Collectors.joining("\n"));
        assertFalse(signatures.contains("java.nio.file.Path"));
        assertFalse(signatures.contains("java.io.File"));
        assertFalse(signatures.contains("java.net.URI"));
    }

    @Test
    void enforcesRequestReadAndWriteResultAlgebra() {
        final UserFileHandle handle = new StubHandle();
        final UserFileError denied = new UserFileError(
            UserFileErrorCode.PERMISSION_DENIED,
            "User-file permission was denied."
        );
        final UserFileError unavailable = new UserFileError(
            UserFileErrorCode.RUNTIME_UNAVAILABLE,
            "User-file runtime is unavailable."
        );

        assertEquals(
            UserFileRequestStatus.GRANTED,
            new UserFileRequestResult(
                UserFileRequestStatus.GRANTED,
                Optional.of(handle),
                Optional.empty()
            ).status()
        );
        assertEquals(
            UserFileRequestStatus.CANCELED,
            new UserFileRequestResult(
                UserFileRequestStatus.CANCELED,
                Optional.empty(),
                Optional.empty()
            ).status()
        );
        assertTrue(new UserFileRequestResult(
            UserFileRequestStatus.DENIED,
            Optional.empty(),
            Optional.of(denied)
        ).error().isPresent());
        assertTrue(new UserFileRequestResult(
            UserFileRequestStatus.UNAVAILABLE,
            Optional.empty(),
            Optional.of(unavailable)
        ).error().isPresent());
        assertThrows(IllegalArgumentException.class, () -> new UserFileRequestResult(
            UserFileRequestStatus.GRANTED,
            Optional.empty(),
            Optional.empty()
        ));
        assertThrows(IllegalArgumentException.class, () -> new UserFileRequestResult(
            UserFileRequestStatus.CANCELED,
            Optional.empty(),
            Optional.of(denied)
        ));
        assertThrows(IllegalArgumentException.class, () -> new UserFileRequestResult(
            UserFileRequestStatus.DENIED,
            Optional.empty(),
            Optional.of(unavailable)
        ));

        assertEquals(
            "value",
            new UserFileReadResult<>(
                Optional.of("value"),
                Optional.empty(),
                true
            ).value().orElseThrow()
        );
        assertThrows(IllegalArgumentException.class, () -> new UserFileReadResult<>(
            Optional.of("value"),
            Optional.of(denied),
            false
        ));
        assertThrows(IllegalArgumentException.class, () -> new UserFileReadResult<String>(
            Optional.empty(),
            Optional.empty(),
            false
        ));
        assertThrows(IllegalArgumentException.class, () -> new UserFileReadResult<String>(
            Optional.empty(),
            Optional.of(denied),
            true
        ));

        assertTrue(new UserFileWriteResult(true, Optional.empty()).written());
        assertFalse(new UserFileWriteResult(false, Optional.of(denied)).written());
        assertThrows(IllegalArgumentException.class, () ->
            new UserFileWriteResult(true, Optional.of(denied))
        );
        assertThrows(IllegalArgumentException.class, () ->
            new UserFileWriteResult(false, Optional.empty())
        );
    }

    @Test
    void pluginContextDefaultAccessorFailsWithFrozenMessage() {
        final PluginContext context = (PluginContext) Proxy.newProxyInstance(
            PluginContext.class.getClassLoader(),
            new Class<?>[] {PluginContext.class},
            UserFileAccessContractTest::invokeDefault
        );
        final UnsupportedOperationException failure = assertThrows(
            UnsupportedOperationException.class,
            context::userFiles
        );
        assertEquals("user file access service is not available", failure.getMessage());
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

    private static final class StubHandle implements UserFileHandle {
        @Override public String id() { return "grant"; }
        @Override public String displayName() { return "file.csv"; }
        @Override public UserFileMode mode() { return UserFileMode.READ; }
        @Override public UserFileLifetime lifetime() { return UserFileLifetime.ONE_OPERATION; }
        @Override public UserFileHandleState state() { return UserFileHandleState.ACTIVE; }
        @Override public void revoke() { }
        @Override public void close() { }
    }
}
