package dev.turboism.sdk.hostread;

import dev.turboism.sdk.cubism.ProjectSnapshot;
import dev.turboism.sdk.cubism.WorkspaceSnapshot;
import dev.turboism.sdk.plugin.PluginContext;
import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletionStage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AsyncHostReadContractTest {

    @Test
    void pluginContextAddsCompatibleHostReadAccessorWithStableUnavailableMessage() {
        final Method accessor = Arrays.stream(PluginContext.class.getMethods())
            .filter(method -> method.getName().equals("hostReads"))
            .findFirst()
            .orElseThrow();
        assertTrue(accessor.isDefault());
        assertEquals(AsyncHostReadService.class, accessor.getReturnType());
        assertEquals(0, accessor.getParameterCount());

        final PluginContext context = (PluginContext) java.lang.reflect.Proxy.newProxyInstance(
            PluginContext.class.getClassLoader(),
            new Class<?>[]{PluginContext.class},
            AsyncHostReadContractTest::invokeDefault
        );
        final UnsupportedOperationException error = assertThrows(
            UnsupportedOperationException.class,
            context::hostReads
        );
        assertEquals("async host read service is not available", error.getMessage());
    }

    @Test
    void publicApiIsClosedAndExposesNoExecutionOrHostEscapeType() {
        assertEquals(
            List.of("submit"),
            Arrays.stream(AsyncHostReadService.class.getDeclaredMethods())
                .filter(method -> Modifier.isPublic(method.getModifiers()))
                .map(Method::getName)
                .sorted()
                .toList()
        );
        assertEquals(AsyncHostReadSubmission.class,
            AsyncHostReadService.class.getDeclaredMethods()[0].getReturnType());
        assertEquals(List.of(AsyncHostReadRequest.class),
            List.of(AsyncHostReadService.class.getDeclaredMethods()[0].getParameterTypes()));

        final Set<String> forbiddenPrefixes = Set.of(
            "java.lang.Thread",
            "java.util.concurrent.Executor",
            "java.util.concurrent.Callable",
            "java.lang.Runnable",
            "java.util.function.",
            "java.lang.reflect.",
            "com.live2d.",
            "dev.turboism.adapter.",
            "dev.turboism.core."
        );
        for (Class<?> type : sdkTypes()) {
            for (Method method : type.getMethods()) {
                assertAllowed(method.getReturnType(), type.getName() + "." + method.getName());
                for (Class<?> parameter : method.getParameterTypes()) {
                    assertAllowed(parameter, type.getName() + "." + method.getName());
                }
                final String genericSignature = method.toGenericString();
                forbiddenPrefixes.forEach(prefix -> assertFalse(
                    genericSignature.contains(prefix),
                    () -> genericSignature + " exposes forbidden type " + prefix
                ));
            }
        }
    }

    @Test
    void enumsAndRecordsFormTheFrozenClosedAlgebra() {
        assertEquals("PROJECT_WORKSPACE_SNAPSHOT", names(AsyncHostReadIntent.values()));
        assertEquals("ACCEPTED,COALESCED,REJECTED", names(AsyncHostReadSubmissionStatus.values()));
        assertEquals("QUEUED,RUNNING,SUCCEEDED,FAILED,CANCELED", names(AsyncHostReadStatus.values()));
        assertEquals(
            "CAPABILITY_UNAVAILABLE,PERMISSION_DENIED,HOST_VERSION_UNSUPPORTED,MAPPING_NOT_VERIFIED,"
                + "VALIDATION_FAILURE,TIMEOUT,CANCELED,BACKPRESSURE,RUNTIME_UNAVAILABLE,RUNTIME_FAILURE",
            names(AsyncHostReadErrorCode.values())
        );

        assertTrue(AsyncHostReadRequest.class.isRecord());
        assertTrue(AsyncHostReadSubmission.class.isRecord());
        assertTrue(AsyncHostReadResult.class.isRecord());
        assertTrue(AsyncHostReadError.class.isRecord());
        assertTrue(ProjectWorkspaceSnapshot.class.isRecord());
        assertTrue(AsyncHostReadValue.class.isSealed());
        assertEquals(
            Set.of(ProjectWorkspaceSnapshot.class),
            Set.of(AsyncHostReadValue.class.getPermittedSubclasses())
        );

        assertEquals(
            List.of("cancel", "close", "completion", "intent", "status"),
            Arrays.stream(AsyncHostReadHandle.class.getDeclaredMethods())
                .map(Method::getName)
                .sorted()
                .toList()
        );
        assertEquals(CompletionStage.class,
            Arrays.stream(AsyncHostReadHandle.class.getDeclaredMethods())
                .filter(method -> method.getName().equals("completion"))
                .findFirst().orElseThrow().getReturnType());
    }

    @Test
    void constructorsEnforceRequestSubmissionResultAndErrorInvariants() {
        assertThrows(IllegalArgumentException.class,
            () -> new AsyncHostReadRequest(AsyncHostReadIntent.PROJECT_WORKSPACE_SNAPSHOT, Duration.ofMillis(99)));
        assertThrows(IllegalArgumentException.class,
            () -> new AsyncHostReadRequest(AsyncHostReadIntent.PROJECT_WORKSPACE_SNAPSHOT, Duration.ofSeconds(11)));

        final AsyncHostReadError denied = new AsyncHostReadError(
            AsyncHostReadErrorCode.PERMISSION_DENIED,
            "Permission denied."
        );
        final AsyncHostReadHandle handle = new TestHandle();
        assertThrows(IllegalArgumentException.class, () -> new AsyncHostReadSubmission(
            AsyncHostReadSubmissionStatus.ACCEPTED,
            Optional.empty(),
            Optional.empty()
        ));
        assertThrows(IllegalArgumentException.class, () -> new AsyncHostReadSubmission(
            AsyncHostReadSubmissionStatus.REJECTED,
            Optional.of(handle),
            Optional.of(denied)
        ));

        final ProjectWorkspaceSnapshot snapshot = snapshot();
        assertThrows(IllegalArgumentException.class, () -> new AsyncHostReadResult(
            AsyncHostReadIntent.PROJECT_WORKSPACE_SNAPSHOT,
            AsyncHostReadStatus.RUNNING,
            Optional.empty(),
            Optional.empty()
        ));
        assertThrows(IllegalArgumentException.class, () -> new AsyncHostReadResult(
            AsyncHostReadIntent.PROJECT_WORKSPACE_SNAPSHOT,
            AsyncHostReadStatus.SUCCEEDED,
            Optional.empty(),
            Optional.empty()
        ));
        assertThrows(IllegalArgumentException.class, () -> new AsyncHostReadResult(
            AsyncHostReadIntent.PROJECT_WORKSPACE_SNAPSHOT,
            AsyncHostReadStatus.CANCELED,
            Optional.empty(),
            Optional.of(denied)
        ));
        assertEquals(snapshot, new AsyncHostReadResult(
            AsyncHostReadIntent.PROJECT_WORKSPACE_SNAPSHOT,
            AsyncHostReadStatus.SUCCEEDED,
            Optional.of(snapshot),
            Optional.empty()
        ).value().orElseThrow());

        assertThrows(IllegalArgumentException.class,
            () -> new AsyncHostReadError(AsyncHostReadErrorCode.RUNTIME_FAILURE, " "));
        assertThrows(IllegalArgumentException.class,
            () -> new AsyncHostReadError(AsyncHostReadErrorCode.RUNTIME_FAILURE, "x".repeat(257)));
    }

    @Test
    void projectWorkspaceIntentAcceptsOnlyItsDedicatedResultType() {
        final ProjectWorkspaceSnapshot snapshot = snapshot();
        final AsyncHostReadResult result = AsyncHostReadResult.success(
            AsyncHostReadIntent.PROJECT_WORKSPACE_SNAPSHOT,
            snapshot
        );
        assertEquals(snapshot, result.value().orElseThrow());
        assertEquals(AsyncHostReadStatus.SUCCEEDED, result.status());

        assertThrows(NullPointerException.class, () -> AsyncHostReadResult.success(
            AsyncHostReadIntent.PROJECT_WORKSPACE_SNAPSHOT,
            null
        ));
    }

    private static List<Class<?>> sdkTypes() {
        return List.of(
            AsyncHostReadService.class,
            AsyncHostReadIntent.class,
            AsyncHostReadRequest.class,
            AsyncHostReadSubmissionStatus.class,
            AsyncHostReadSubmission.class,
            AsyncHostReadHandle.class,
            AsyncHostReadStatus.class,
            AsyncHostReadResult.class,
            AsyncHostReadError.class,
            AsyncHostReadErrorCode.class,
            AsyncHostReadValue.class,
            ProjectWorkspaceSnapshot.class
        );
    }

    private static void assertAllowed(final Class<?> type, final String source) {
        final String name = type.getName();
        assertFalse(name.startsWith("java.lang.Thread"), source);
        assertFalse(name.startsWith("java.util.concurrent.Executor"), source);
        assertFalse(name.startsWith("java.util.concurrent.Callable"), source);
        assertFalse(name.equals(Runnable.class.getName()), source);
        assertFalse(name.startsWith("java.util.function."), source);
        assertFalse(name.startsWith("java.lang.reflect."), source);
        assertFalse(name.startsWith("com.live2d."), source);
        assertFalse(name.startsWith("dev.turboism.adapter."), source);
        assertFalse(name.startsWith("dev.turboism.core."), source);
    }

    private static ProjectWorkspaceSnapshot snapshot() {
        return new ProjectWorkspaceSnapshot(
            Optional.of(new ProjectSnapshot("project", "Project", Optional.empty(), List.of())),
            Optional.of(new WorkspaceSnapshot("workspace", "Workspace", List.of("project")))
        );
    }

    private static String names(final Enum<?>[] values) {
        return Arrays.stream(values).map(Enum::name).reduce((left, right) -> left + "," + right).orElse("");
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

    private static final class TestHandle implements AsyncHostReadHandle {
        @Override public AsyncHostReadIntent intent() { return AsyncHostReadIntent.PROJECT_WORKSPACE_SNAPSHOT; }
        @Override public AsyncHostReadStatus status() { return AsyncHostReadStatus.QUEUED; }
        @Override public boolean cancel() { return false; }
        @Override public CompletionStage<AsyncHostReadResult> completion() { throw new UnsupportedOperationException(); }
        @Override public void close() {}
    }
}
